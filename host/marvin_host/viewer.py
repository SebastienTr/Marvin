"""Live view in Rerun.

Layout (sent as a blueprint):
    3D scene | camera (with lidar points and radar targets projected onto it) | the robot's face
    time series (presence, vital signs, link, log, events) | lidar top view | radar view

Entity layout: world/... in the device frame (mm, 3D); lidar_map/... and radar/... are 2D views
seen from above with the robot's front pointing up (screen x = -X, screen y = Y).

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import math
import threading
import time
from collections import deque

import numpy as np
import rerun as rr
import rerun.blueprint as rrb

from . import frames, protocol, scene
from .brain import Brain
from .camera import CAMERA, SimCamera
from .events import Event
from .face import Face
from .receiver import Device, Sink

GREY = (190, 185, 175)
ANTHRACITE = (60, 62, 66)
BLUE = (80, 160, 255)
CORAL = (255, 120, 80)
RING = (90, 90, 90)
TRAIL_S = 4.0
MR60_RANGE_MM = 1500.0     # vital signs range of the MR60BHA2


def _turbo(x: np.ndarray) -> np.ndarray:
    """Cheap turbo-like colormap, x in 0..1 -> uint8 RGB."""
    x = np.clip(x, 0, 1)[:, None]
    r = np.clip(0.14 + 4.4 * x - 7.0 * x ** 2 + 3.3 * x ** 3, 0, 1)
    g = np.clip(0.09 + 2.9 * x - 2.8 * x ** 2, 0, 1)
    b = np.clip(0.6 + 1.9 * x - 6.0 * x ** 2 + 3.6 * x ** 3, 0, 1)
    return (np.hstack([r, g, b]) * 255).astype(np.uint8)


def _top(x, y, oy: float = 0.0):
    """Device X, Y (mm) -> top-view screen coordinates, front up."""
    return np.stack([-np.asarray(x, dtype=np.float32), np.asarray(y, dtype=np.float32) - oy], axis=-1)


def _arc(r: float, a0: float, a1: float, n: int = 64) -> np.ndarray:
    """Arc in the top view around the origin, angles in degrees from straight ahead, clockwise positive."""
    a = np.radians(np.linspace(a0, a1, n))
    return np.stack([r * np.sin(a), -r * np.cos(a)], axis=1)


def blueprint() -> rrb.Blueprint:
    recent = rrb.VisibleTimeRange("device_time", start=rrb.TimeRangeBoundary.cursor_relative(seconds=-30.0),
                                  end=rrb.TimeRangeBoundary.cursor_relative())
    return rrb.Blueprint(
        rrb.Vertical(
            rrb.Horizontal(
                rrb.Spatial3DView(origin="/world", name="3D"),
                rrb.Spatial2DView(origin="/world/camera", contents=["$origin/**", "/world/lidar/**", "/world/ld2450/**"], name="Camera"),
                rrb.Spatial2DView(origin="/face", name="Face"),
                column_shares=[4, 4, 2],
            ),
            rrb.Horizontal(
                rrb.Vertical(
                    rrb.TimeSeriesView(origin="/presence", name="Presence (LD2450)", time_ranges=recent),
                    rrb.TimeSeriesView(origin="/vitals/rates", name="Vital signs (MR60BHA2), per minute", time_ranges=recent),
                    rrb.TimeSeriesView(origin="/vitals/waves", name="Breathing and heartbeat",
                                       time_ranges=rrb.VisibleTimeRange(
                                           "device_time", start=rrb.TimeRangeBoundary.cursor_relative(seconds=-10.0),
                                           end=rrb.TimeRangeBoundary.cursor_relative())),
                    rrb.Tabs(rrb.TextLogView(origin="/events", name="Events"),
                             rrb.TimeSeriesView(origin="/stats", name="Link", time_ranges=recent),
                             rrb.TextLogView(origin="/log", name="Log")),
                ),
                rrb.Spatial2DView(origin="/lidar_map", name="Lidar, top view",
                                  visual_bounds=rrb.VisualBounds2D(x_range=[-4500, 4500], y_range=[-4500, 4500])),
                rrb.Spatial2DView(origin="/radar", name="mmWave radars",
                                  visual_bounds=rrb.VisualBounds2D(x_range=[-4200, 4200], y_range=[-4600, 600])),
                column_shares=[1, 1, 1],
            ),
            row_shares=[5, 4],
        ),
        collapse_panels=True,
    )


def log_static(simulated_scene: bool = False) -> None:
    rr.log("world", rr.ViewCoordinates.RIGHT_HAND_Z_UP, static=True)
    # the robot
    rr.log("world/robot/body", rr.Boxes3D(centers=[(0, 0, 35)], half_sizes=[(40, 37, 29)], colors=[GREY]), static=True)
    rr.log("world/robot/head", rr.Boxes3D(centers=[(0, 0, 96)], half_sizes=[(44, 40, 28)], colors=[GREY]), static=True)
    rr.log("world/robot/lidar", rr.Boxes3D(centers=[(0, 0, frames.LIDAR.z_mm)], half_sizes=[(19, 19, 9)],
                                           colors=[ANTHRACITE]), static=True)
    r = frames.LD2450
    t = math.radians(r.tilt_deg)
    rr.log("world/robot/ld2450", rr.Arrows3D(origins=[(0, r.y_mm, r.z_mm)], vectors=[(0, -300 * math.cos(t), 300 * math.sin(t))],
                                             colors=[BLUE]), static=True)
    t = math.radians(20)
    rr.log("world/robot/mr60bha2", rr.Arrows3D(origins=[scene.MR60_POS], vectors=[(0, -300 * math.cos(t), 300 * math.sin(t))],
                                               colors=[CORAL]), static=True)
    # the camera: pinhole + pose, so 3D points are projected onto its image
    m = CAMERA
    rr.log("world/camera", rr.Transform3D(translation=m.position, mat3x3=m.rotation), static=True)
    rr.log("world/camera", rr.Pinhole(focal_length=m.focal, width=m.width, height=m.height,
                                      camera_xyz=rr.ViewCoordinates.RDF, image_plane_distance=250), static=True)

    # lidar top view: range rings every metre, 30-degree spokes, the robot
    rings = [_arc(k * 1000, -180, 180, 128) for k in range(1, 6)]
    spokes = [np.array([[0, 0], 5000 * np.array([math.sin(math.radians(a)), -math.cos(math.radians(a))])]) for a in range(0, 360, 30)]
    rr.log("lidar_map/grid", rr.LineStrips2D(rings + spokes, colors=[RING], radii=4), static=True)
    rr.log("lidar_map/grid/labels", rr.Points2D([(0, -k * 1000) for k in range(1, 6)], radii=0,
                                                labels=[f"{k} m" for k in range(1, 6)], colors=[RING]), static=True)
    rr.log("lidar_map/robot", rr.Boxes2D(centers=[(0, 0)], half_sizes=[(44, 40)], colors=[GREY],
                                         labels=["Marvin"]), static=True)
    rr.log("lidar_map/robot/heading", rr.Arrows2D(origins=[(0, 0)], vectors=[(0, -400)], colors=[GREY], radii=12), static=True)

    # radar view: LD2450 sector (+-60 deg, 6 m) and MR60BHA2 vital-signs range, origin at the radar
    arcs = [_arc(k * 1000, -60, 60) for k in range(1, 7)]
    spokes = [np.array([[0, 0], 6000 * np.array([math.sin(math.radians(a)), -math.cos(math.radians(a))])]) for a in (-60, -30, 0, 30, 60)]
    rr.log("radar/grid", rr.LineStrips2D(arcs + spokes, colors=[(40, 70, 110)], radii=6), static=True)
    rr.log("radar/grid/labels", rr.Points2D([(60, -k * 1000) for k in range(1, 7)], radii=0,
                                            labels=[f"{k} m" for k in range(1, 7)], colors=[(90, 130, 180)]), static=True)
    mr = np.vstack([[0, 0], _arc(MR60_RANGE_MM, -45, 45), [0, 0]])
    rr.log("radar/mr60bha2_range", rr.LineStrips2D([mr], colors=[CORAL], radii=6,
                                                    labels=["MR60BHA2 vital signs"]), static=True)
    rr.log("radar/robot", rr.Boxes2D(centers=[(0, 40)], half_sizes=[(40, 37)], colors=[GREY]), static=True)

    rr.log("vitals/rates/breath", rr.SeriesLines(colors=[(120, 200, 255)], names=["breath"]), static=True)
    rr.log("vitals/rates/heart", rr.SeriesLines(colors=[CORAL], names=["heart"]), static=True)
    rr.log("vitals/waves/breath", rr.SeriesLines(colors=[(120, 200, 255)], names=["breathing"]), static=True)
    rr.log("vitals/waves/heart", rr.SeriesLines(colors=[CORAL], names=["heartbeat"]), static=True)

    if simulated_scene:
        log_scene()


def log_scene() -> None:
    """The simulated apartment as faint wireframes (ground truth, only for simulated devices)."""
    lo = np.array([b.lo for b in scene.BOXES])
    hi = np.array([b.hi for b in scene.BOXES])
    cols = [(*b.color, 90) for b in scene.BOXES]
    rr.log("world/sim_scene", rr.Boxes3D(centers=(lo + hi) / 2, half_sizes=(hi - lo) / 2, colors=cols, radii=3), static=True)
    rr.log("lidar_map/sim_scene", rr.LineStrips2D([_top(s[[0, 2]], s[[1, 3]]) for s in scene.SEGMENTS],
                                                   colors=[(70, 70, 70)], radii=3), static=True)


class RerunSink(Sink):
    """Logs everything to Rerun. With a brain, also logs its events and state and renders the face.

    Put the brain before this sink in the chain, so its state is current when a frame is logged.
    """

    def __init__(self, brain: Brain | None = None, camera_fps: float = 5.0, face_fps: float = 20.0):
        self.trail: deque = deque()          # (t_s, screen x, screen y)
        self.sim_camera: SimCamera | None = None
        self.camera_fps = camera_fps
        self.latest_t_us: int | None = None
        self._latest_mono = time.monotonic()
        self.scene_logged = False
        self._stop = threading.Event()
        self.brain = brain
        self.face: Face | None = None
        if brain is not None:
            self.face = Face()
            brain.add_listener(self._on_event)
            threading.Thread(target=self._face_loop, args=(face_fps,), daemon=True).start()

    def _device_time_now(self) -> int | None:
        """Device clock estimated from the last frame, for streams rendered between frames."""
        if self.latest_t_us is None:
            return None
        return self.latest_t_us + int(min(time.monotonic() - self._latest_mono, 1.0) * 1e6)

    def _seen(self, t_us: int) -> None:
        self.latest_t_us = t_us
        self._latest_mono = time.monotonic()

    def _on_event(self, event: Event) -> None:
        self._time(event.t_us)
        rr.log("events", rr.TextLog(f"{event.kind.value}: {event.detail}" if event.detail else event.kind.value))
        if self.face is not None:
            self.face.on_event(event)

    def _face_loop(self, fps: float) -> None:
        """Animates the face on the host clock (it must never jump back); logs it on the device clock."""
        period = 1.0 / fps
        t0 = time.monotonic()
        while not self._stop.is_set():
            start = time.monotonic()
            frame = self.face.update(self.brain.state, start - t0)
            t_us = self._device_time_now()
            if t_us is not None:
                self._time(t_us)
                rr.log("face", rr.Image(frame))
            time.sleep(max(0.0, period - (time.monotonic() - start)))

    def _log_state(self) -> None:
        if self.brain is None:
            return
        st = self.brain.state
        rr.log("presence/seated", rr.Scalars(1.0 if st.seated else 0.0))
        rr.log("presence/still_s", rr.Scalars(st.still_s))

    @staticmethod
    def _time(t_us: int) -> None:
        rr.set_time("device_time", duration=t_us / 1e6)

    def on_hello(self, dev: Device) -> None:
        h = dev.hello
        rr.log("log", rr.TextLog(f"{h.device_name}: {protocol.BOARDS.get(h.board, h.board)}, firmware {h.firmware}"
                                 f"{' (simulated sensors)' if h.simulated else ''}, RSSI {h.rssi} dBm"))
        if h.simulated and not self.scene_logged:
            log_scene()
            self.scene_logged = True
            self.sim_camera = SimCamera()
            threading.Thread(target=self._camera_loop, daemon=True).start()

    def _camera_loop(self) -> None:
        """Renders the simulated camera at the device's clock, as fast as camera_fps allows."""
        period = 1.0 / self.camera_fps
        while not self._stop.is_set():
            start = time.monotonic()
            t_us = self._device_time_now()
            if t_us is not None and self.sim_camera is not None:
                img = self.sim_camera.render(t_us / 1e6)
                self._time(t_us)
                rr.log("world/camera", rr.Image(img).compress(jpeg_quality=80))
            time.sleep(max(0.0, period - (time.monotonic() - start)))

    def stop(self) -> None:
        self._stop.set()

    def on_scan(self, dev, t_us, points, intensities, speed_dps) -> None:
        self._seen(t_us)
        self._time(t_us)
        if len(points):
            dist = np.hypot(points[:, 0], points[:, 1])
            colors = _turbo(dist / 6000.0)
            rr.log("world/lidar/scan", rr.Points3D(points, colors=colors, radii=10))
            rr.log("lidar_map/scan", rr.Points2D(_top(points[:, 0], points[:, 1]), colors=colors, radii=22))
        rr.log("stats/lidar_points", rr.Scalars(len(points)))
        rr.log("stats/lidar_hz", rr.Scalars(speed_dps / 360.0))
        s = dev.stats
        rr.log("stats/lost_datagrams", rr.Scalars(s.lost))
        rr.log("stats/crc_errors", rr.Scalars(s.crc_errors))

    def on_targets(self, dev, t_us, targets, points) -> None:
        self._seen(t_us)
        self._time(t_us)
        self._log_state()
        now = t_us / 1e6
        while self.trail and now - self.trail[0][0] > TRAIL_S or (self.trail and self.trail[0][0] > now):
            self.trail.popleft()
        if not points:
            for e in ("world/ld2450/targets", "lidar_map/ld2450", "radar/ld2450/targets"):
                rr.log(e, rr.Clear(recursive=False))
            rr.log("presence/targets", rr.Scalars(0))
        else:
            pts = np.array(points)
            dist = [math.hypot(t.x_mm, t.y_mm) / 1000 for t in targets]
            labels = [f"{d:.2f} m, {t.speed_cms:+d} cm/s" for d, t in zip(dist, targets)]
            rr.log("world/ld2450/targets", rr.Points3D(pts, radii=60, colors=[BLUE] * len(pts), labels=labels))
            top = _top(pts[:, 0], pts[:, 1])
            rr.log("lidar_map/ld2450", rr.Points2D(top, radii=120, colors=[(*BLUE, 110)] * len(pts)))
            radar = _top(pts[:, 0], pts[:, 1], frames.LD2450.y_mm)
            cols = [CORAL if t.speed_cms < -3 else (90, 220, 160) if t.speed_cms > 3 else BLUE for t in targets]
            rr.log("radar/ld2450/targets", rr.Points2D(radar, radii=110, colors=cols, labels=labels))
            for p in radar:
                self.trail.append((now, float(p[0]), float(p[1])))
            rr.log("presence/targets", rr.Scalars(len(points)))
            rr.log("presence/nearest_m", rr.Scalars(min(dist)))
            near = targets[int(np.argmin(dist))]
            rr.log("presence/speed_cms", rr.Scalars(near.speed_cms))
        if len(self.trail) >= 2:
            rr.log("radar/ld2450/trail", rr.LineStrips2D([[(x, y) for _, x, y in self.trail]], colors=[(80, 160, 255, 120)], radii=18))

    def on_vitals(self, dev, t_us, v: protocol.Vitals) -> None:
        self._time(t_us)
        if v.valid:
            rr.log("vitals/rates/breath", rr.Scalars(v.breath_rate))
            rr.log("vitals/rates/heart", rr.Scalars(v.heart_rate))
            rr.log("vitals/waves/breath", rr.Scalars(v.breath_wave))
            rr.log("vitals/waves/heart", rr.Scalars(v.heart_wave))
            y = v.distance_mm - (scene.MR60_POS[1] - frames.LD2450.y_mm)
            rr.log("radar/mr60bha2", rr.Points2D([(0, -y)], radii=70, colors=[CORAL],
                                                 labels=[f"{v.heart_rate:.0f} bpm heart, {v.breath_rate:.0f}/min breath"]))
        else:
            # a Clear ends the series, so no line is drawn across the time without vital signs
            for e in ("radar/mr60bha2", "vitals/rates/breath", "vitals/rates/heart",
                      "vitals/waves/breath", "vitals/waves/heart"):
                rr.log(e, rr.Clear(recursive=False))

    def on_log(self, dev, t_us, text) -> None:
        self._time(t_us)
        rr.log("log", rr.TextLog(f"{dev.hello.device_name}: {text}"))


def start(save: str | None = None, spawn: bool = True, brain: Brain | None = None) -> RerunSink:
    rr.init("marvin", spawn=spawn and not save)
    if save:
        rr.save(save)
    rr.send_blueprint(blueprint())
    log_static()
    return RerunSink(brain=brain)
