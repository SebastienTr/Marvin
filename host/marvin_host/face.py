"""The robot's eyes: a reference renderer and behaviour, to be ported 1:1 to the ESP32-S3.

The face is two rounded-rectangle eyes on a very dark warm-grey background, seen through the
smoked acrylic window. Expressions come from a few numbers per expression (``FaceParams``):
eye size, how far the upper and lower lids close, the tilt of the upper lid, brightness and
colour warmth. Lids are drawn as occluding shapes in the background colour, so the whole face is
made of fillRoundRect, fillTriangle and fillEllipse calls (see ``raster.py``).

``Face`` turns the brain's events and presence state (``events.py``) into motion: gaze that
follows the person's head through a critically damped spring, micro-saccades, seeded random
blinks, eased transitions between expressions, and falling asleep when nobody is there.
Time is supplied by the caller and all randomness comes from a seeded xorshift32 generator, so
the same seed and the same calls always give the same frames.

Screen: ST7789, 240 x 280 pixels, portrait. Screen x goes to the right as seen from the front of
the robot, which is device +X. See docs/face.md for the port notes.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import math
from dataclasses import astuple, dataclass, fields, replace

import numpy as np

from .events import Event, EventKind, PresenceState
from .raster import Canvas, Color, rgb565

# ---------------------------------------------------------------------------------------------
# Constants: everything the C++ port needs, in one place
# ---------------------------------------------------------------------------------------------

SCREEN_W = 240                          # px
SCREEN_H = 280                          # px
SCREEN_POS_MM = (0.0, -38.0, 92.0)      # screen centre, device frame (planned, docs/architecture.md)

BACKGROUND: Color = (20, 18, 17)        # very dark warm grey; pure black vanishes behind the glass
EYE_WHITE: Color = (236, 230, 218)      # soft warm white
EYE_AMBER: Color = (255, 190, 120)      # pale amber, used as expressions get sleepy (warmth = 1)
ACCENT: Color = (238, 118, 38)          # the orange accent, same hue as the knob

EYE_CX = 120.0                          # midpoint between the eyes, px
EYE_CY = 146.0                          # eye centre line, px
CLOSED_H = 5.0                          # height of a closed eye (a thin line), px
CLOSE_DROP = 0.35                       # a closing eye moves down by this fraction of lost height
PERSPECTIVE = 0.06                      # eye on the gaze side grows by this much at full gaze
LID_MARGIN = 3.0                        # lid occluders overhang the eye by this much, px
ACCENT_POS = (120.0, 228.0)             # vitals cue dot, px
ACCENT_R = 4.5                          # px

GAZE_X_PX = 28.0                        # eye shift at full horizontal gaze, px
GAZE_Y_PX = 22.0                        # eye shift at full vertical gaze, px
GAZE_YAW_FULL = math.radians(50.0)      # yaw giving full horizontal gaze
GAZE_PITCH_FULL = math.radians(60.0)    # pitch giving full vertical gaze
HEAD_Z_SEATED = 400.0                   # assumed head height when only the body position is known
HEAD_Z_STANDING = 950.0                 # (mm above the desk)
FAR_M = 2.2                             # beyond this, the robot is present but not engaged

GAZE_OMEGA = 16.0                       # gaze spring stiffness, rad/s (critically damped)
SACCADE_OMEGA = 24.0                    # used for quick glances and micro-saccades
EXPR_OMEGA = 7.0                        # expression spring stiffness, rad/s
SLEEP_OMEGA = 1.6                       # slow lid fall when going to sleep
WAKE_OMEGA = 9.0                        # quick eye opening on wake-up

BLINK_EVERY_S = (2.0, 6.0)              # uniform random interval between spontaneous blinks
BLINK_DOUBLE_P = 0.15                   # chance that a blink is followed by a second one
BLINK = (0.07, 0.04, 0.13)              # close, hold, open durations, s
SLOW_BLINK = (0.35, 0.30, 0.55)         # a deliberate blink ("take a break", "I see you")
MICRO_EVERY_S = (0.8, 2.8)              # micro-saccade interval when engaged
MICRO_PX = 3.0                          # micro-saccade amplitude
WANDER_EVERY_S = (1.2, 3.5)             # idle glance interval when present but far
WANDER_HOLD_S = (0.5, 1.4)              # how long an idle glance lasts

LOOK_AROUND_S = 4.0                     # after LEFT: look around this long...
SLEEPY_AFTER_S = 4.0                    # ...then sleepy...
ASLEEP_AFTER_S = 20.0                   # ...then asleep, counted from when nobody is present
BREATH_PERIOD_S = 5.5                   # asleep: slow "breathing" of the closed-eye line
BREATH_DEPTH = 0.25                     # brightness modulation depth while asleep

ACCENT_SHOW_S = 5.0                     # vitals cue duration after VITALS_ACQUIRED
ACCENT_FADE_S = 0.4                     # vitals cue fade time constant


# ---------------------------------------------------------------------------------------------
# Expressions
# ---------------------------------------------------------------------------------------------

@dataclass(frozen=True)
class FaceParams:
    """Eye geometry for one expression. This table is what the firmware copies.

    Sizes are in pixels. Lid values are fractions of the visible eye height. ``lid_tilt`` > 0
    lowers the upper lid on the outer side of each eye (a soft, concerned look).
    """

    width: float = 64.0         # eye width
    height: float = 82.0        # eye height when fully open
    radius: float = 22.0        # corner radius (clamped to half the smaller side)
    spacing: float = 104.0      # distance between the eye centres
    dy: float = 0.0             # vertical offset of both eyes (+ = down)
    open: float = 1.0           # 1 = open, 0 = closed (a thin line, CLOSED_H)
    lid_top: float = 0.0        # upper lid coverage at the eye centre
    lid_tilt: float = 0.0       # upper lid slope, outer side lower when > 0
    lid_bottom: float = 0.0     # lower lid coverage at the centre (curved: a gentle smile)
    brightness: float = 1.0     # 0 = background colour, 1 = full eye colour
    warmth: float = 0.1         # 0 = warm white, 1 = pale amber

    def to_array(self) -> np.ndarray:
        return np.array(astuple(self), np.float64)

    @classmethod
    def from_array(cls, a: np.ndarray) -> FaceParams:
        return cls(*(float(v) for v in a))


EXPRESSIONS: dict[str, FaceParams] = {
    # engaged, standing or moving around
    "neutral":   FaceParams(),
    # someone just arrived: fully awake, a little bigger
    "awake":     FaceParams(width=66, height=88, radius=24, spacing=106),
    # came within arm's reach: big round eyes, briefly
    "surprised": FaceParams(width=72, height=94, radius=32, spacing=110, dy=-4),
    # stood up: alert, slightly taller and narrower
    "attentive": FaceParams(width=60, height=88, radius=20),
    # sat down: relaxed smile from the lower lids
    "content":   FaceParams(width=68, height=76, radius=26, spacing=106, lid_bottom=0.36, warmth=0.2),
    # seated, the resting state while working together
    "calm":      FaceParams(width=68, height=68, radius=22, dy=2, warmth=0.15),
    # seated and still for too long: soft, concerned, "time for a break"
    "concerned": FaceParams(width=64, height=80, radius=16, lid_top=0.40, lid_tilt=0.40, warmth=0.2),
    # nobody around for a bit: heavy lids
    "sleepy":    FaceParams(width=66, height=74, radius=22, dy=4, lid_top=0.55, brightness=0.9,
                            warmth=0.45),
    # nobody around: closed, dim amber lines
    "asleep":    FaceParams(width=60, height=74, radius=22, dy=14, open=0.0, brightness=0.7,
                            warmth=0.9),
}

_OPEN = [f.name for f in fields(FaceParams)].index("open")

TRANSIENTS: dict[EventKind, tuple[str, float]] = {
    # event -> expression held for this many seconds before returning to the base expression
    EventKind.ARRIVED: ("awake", 1.6),
    EventKind.APPROACHED: ("surprised", 1.0),
    EventKind.SAT_DOWN: ("content", 3.5),
    EventKind.STOOD_UP: ("attentive", 2.0),
    EventKind.STILL_LONG: ("concerned", 7.0),
}


# ---------------------------------------------------------------------------------------------
# Pure helpers (each one ports to a small C++ function)
# ---------------------------------------------------------------------------------------------

class XorShift32:
    """The tiny deterministic PRNG shared with the firmware (Marsaglia xorshift32)."""

    def __init__(self, seed: int):
        self.state = (seed * 2654435761 + 0x9E3779B9) & 0xFFFFFFFF or 0x6D2B79F5

    def next_u32(self) -> int:
        x = self.state
        x ^= (x << 13) & 0xFFFFFFFF
        x ^= x >> 17
        x ^= (x << 5) & 0xFFFFFFFF
        self.state = x
        return x

    def uniform(self, lo: float, hi: float) -> float:
        return lo + (hi - lo) * (self.next_u32() / 4294967296.0)


def spring_step(x: np.ndarray, v: np.ndarray, target: np.ndarray, omega: float | np.ndarray,
                dt: float) -> tuple[np.ndarray, np.ndarray]:
    """Exact step of a critically damped spring (x'' = w^2 (target - x) - 2 w x').

    Stable for any dt, so the result does not depend on the frame rate beyond rounding.
    """
    e = np.exp(-omega * dt)
    x0 = x - target
    c = v + omega * x0
    return target + (x0 + c * dt) * e, (v - omega * c * dt) * e


def smoothstep(u: float) -> float:
    u = min(1.0, max(0.0, u))
    return u * u * (3.0 - 2.0 * u)


def blink_closure(t: float, start: float, timing: tuple[float, float, float]) -> float:
    """How closed a blink is at time t (0 = open, 1 = closed), eased in and out."""
    close, hold, open_ = timing
    u = t - start
    if u < 0 or u > close + hold + open_:
        return 0.0
    if u < close:
        return smoothstep(u / close)
    if u < close + hold:
        return 1.0
    return 1.0 - smoothstep((u - close - hold) / open_)


def gaze_from_point(p: tuple[float, float, float]) -> tuple[float, float]:
    """Eye offset in pixels (screen x right, screen y down) to look at a device-frame point.

    Screen x matches device +X as seen from the front, so a person at +X makes the eyes move
    to the right of the screen. yaw = atan2(dx, -dy), pitch = atan2(dz, hypot(dx, dy)), with
    d = p - screen centre; each is divided by its full-scale angle and clamped to [-1, 1].
    """
    dx = p[0] - SCREEN_POS_MM[0]
    dy = p[1] - SCREEN_POS_MM[1]
    dz = p[2] - SCREEN_POS_MM[2]
    yaw = math.atan2(dx, max(-dy, 1.0))
    pitch = math.atan2(dz, math.hypot(dx, dy))
    gx = max(-1.0, min(1.0, yaw / GAZE_YAW_FULL))
    gy = max(-1.0, min(1.0, pitch / GAZE_PITCH_FULL))
    return GAZE_X_PX * gx, -GAZE_Y_PX * gy


def eye_color(p: FaceParams) -> Color:
    w = min(1.0, max(0.0, p.warmth))
    b = min(1.0, max(0.0, p.brightness))
    return tuple(bg + b * ((1 - w) * c0 + w * c1 - bg)
                 for bg, c0, c1 in zip(BACKGROUND, EYE_WHITE, EYE_AMBER))


def render(params: FaceParams, gaze: tuple[float, float] = (0.0, 0.0), blink: float = 0.0,
           accent: float = 0.0, canvas: Canvas | None = None) -> np.ndarray:
    """Draw one frame from explicit parameters; returns (280, 240, 3) uint8 RGB.

    ``gaze`` is the eye offset in pixels, ``blink`` the blink closure (0..1) and ``accent`` the
    opacity of the orange vitals dot (0..1). Per eye: one rounded rectangle, then the upper lid
    (two triangles) and the lower lid (one ellipse) in the background colour.
    """
    cv = canvas or Canvas(SCREEN_W, SCREEN_H, BACKGROUND)
    cv.fill(BACKGROUND)
    color = eye_color(params)
    gx, gy = gaze
    lean = max(-1.0, min(1.0, gx / GAZE_X_PX))
    openness = max(0.0, min(1.0, params.open * (1.0 - blink)))
    for side in (-1.0, 1.0):                    # -1: left of the screen, +1: right
        k = 1.0 + side * PERSPECTIVE * lean
        w, h = params.width * k, params.height * k
        cx = EYE_CX + side * params.spacing / 2 + gx
        h_eff = max(CLOSED_H, h * openness)
        cy = EYE_CY + params.dy + gy + (h - h_eff) * CLOSE_DROP
        top, bottom = cy - h_eff / 2, cy + h_eff / 2
        cv.fill_round_rect(cx, cy, w, h_eff, params.radius * k, color)

        if params.lid_top > 1e-3 and h_eff > CLOSED_H:
            y_mid = top + params.lid_top * h_eff
            slope = params.lid_tilt * h_eff / 2
            x_in = cx - side * (w / 2 + LID_MARGIN)
            x_out = cx + side * (w / 2 + LID_MARGIN)
            y_cap = top - LID_MARGIN
            y_in = max(y_mid - slope, y_cap + 1)
            y_out = max(y_mid + slope, y_cap + 1)
            cv.fill_quad([(x_in, y_cap), (x_out, y_cap), (x_out, y_out), (x_in, y_in)], BACKGROUND)

        if params.lid_bottom > 1e-3 and h_eff > CLOSED_H:
            rx, ry = w * 0.56, h_eff * 0.5
            cv.fill_ellipse(cx, bottom - params.lid_bottom * h_eff + ry, rx, ry, BACKGROUND)

    if accent > 1e-3:
        cv.fill_circle(ACCENT_POS[0], ACCENT_POS[1], ACCENT_R, ACCENT, min(1.0, accent))
    return cv.to_uint8()


# ---------------------------------------------------------------------------------------------
# Behaviour
# ---------------------------------------------------------------------------------------------

class Face:
    """The animated face. Feed it events with ``on_event`` and call ``update`` every frame."""

    def __init__(self, seed: int = 0):
        self._rng = XorShift32(seed)
        self._canvas = Canvas(SCREEN_W, SCREEN_H, BACKGROUND)
        self._pending: list[Event] = []
        self._t: float | None = None

        start = EXPRESSIONS["asleep"]
        self._p = start.to_array()
        self._pv = np.zeros_like(self._p)
        self._gaze = np.zeros(2)
        self._gaze_v = np.zeros(2)

        self._expression = "asleep"
        self._transient: tuple[str, float] | None = None     # (expression, until t)
        self._absent_since: float | None = -math.inf          # asleep until someone shows up
        self._awake = False
        self._last_side = 0.0                                 # where the person was last seen

        self._blinks: list[tuple[float, tuple[float, float, float]]] = []
        self._next_blink: float | None = None
        self._micro = np.zeros(2)
        self._next_micro = 0.0
        self._wander = np.zeros(2)
        self._wander_until = 0.0
        self._next_wander = 0.0
        self._accent_until = -math.inf
        self._accent_level = 0.0
        self._accent_t0 = 0.0

    # -- public API ------------------------------------------------------------------------

    @property
    def expression(self) -> str:
        """The expression the face is currently heading to (a key of ``EXPRESSIONS``)."""
        return self._expression

    @property
    def params(self) -> FaceParams:
        """The current, smoothed eye geometry."""
        return FaceParams.from_array(self._p)

    def on_event(self, event: Event) -> None:
        """Queue an event; it takes effect at the next ``update`` (its time base is the caller's)."""
        self._pending.append(event)

    def update(self, state: PresenceState, t: float) -> np.ndarray:
        """Advance the animation to time t (seconds, monotonic) and return the frame."""
        dt = 0.0 if self._t is None else min(0.25, max(0.0, t - self._t))
        self._t = t
        if self._next_blink is None:
            self._next_blink = t + self._rng.uniform(*BLINK_EVERY_S)

        self._track_presence(state, t)
        for ev in self._pending:
            self._handle(ev, state, t)
        self._pending.clear()

        name = self._choose_expression(state, t)
        self._expression = name
        target = EXPRESSIONS[name].to_array()
        omega = EXPR_OMEGA
        if name in ("asleep", "sleepy"):
            omega = SLEEP_OMEGA
        elif self._p[_OPEN] < 0.5:          # eyes (nearly) closed and opening: wake up briskly
            omega = WAKE_OMEGA
        self._p, self._pv = spring_step(self._p, self._pv, target, omega, dt)

        gaze_target, gaze_omega = self._gaze_target(state, t, name)
        self._gaze, self._gaze_v = spring_step(self._gaze, self._gaze_v, gaze_target, gaze_omega, dt)

        blink = self._blink(t, name)
        params = FaceParams.from_array(self._p)
        if name == "asleep":
            breath = 0.5 - 0.5 * math.cos(2 * math.pi * t / BREATH_PERIOD_S)
            params = replace(params, brightness=params.brightness * (1 - BREATH_DEPTH * breath))
        return render(params, (float(self._gaze[0]), float(self._gaze[1])), blink,
                      self._accent(t, dt, state), self._canvas)

    # -- events and state ------------------------------------------------------------------

    def _track_presence(self, state: PresenceState, t: float) -> None:
        if state.present:
            self._absent_since = None
            arriving = any(ev.kind == EventKind.ARRIVED for ev in self._pending)
            if not self._awake and not arriving:        # present without ARRIVED: wake anyway
                self._wake(t)
            p = state.head or state.position
            if p is not None:
                self._last_side = math.copysign(1.0, p[0]) if abs(p[0]) > 50 else 0.0
        elif self._absent_since is None:
            self._absent_since = t

    def _wake(self, t: float) -> None:
        self._awake = True
        self._transient = ("awake", t + TRANSIENTS[EventKind.ARRIVED][1])
        self._blinks.append((t + 0.7, BLINK))

    def _handle(self, ev: Event, state: PresenceState, t: float) -> None:
        kind = ev.kind
        if kind == EventKind.ARRIVED:
            self._wake(t)
        elif kind == EventKind.LEFT:
            if self._absent_since is None and not state.present:
                self._absent_since = t
            self._transient = None
        elif kind in TRANSIENTS:
            name, hold = TRANSIENTS[kind]
            self._transient = (name, t + hold)
            if kind == EventKind.STILL_LONG:
                self._blinks += [(t + 0.4, SLOW_BLINK), (t + 1.9, SLOW_BLINK)]
            elif kind == EventKind.APPROACHED:
                self._blinks.append((t + 0.9, BLINK))
        elif kind == EventKind.VITALS_ACQUIRED:
            self._accent_until = t + ACCENT_SHOW_S
            self._accent_t0 = t
            self._blinks.append((t + 0.2, SLOW_BLINK))
        elif kind == EventKind.VITALS_LOST:
            self._accent_until = min(self._accent_until, t)

    def _choose_expression(self, state: PresenceState, t: float) -> str:
        if self._absent_since is not None:
            self._transient = None
            gone = t - self._absent_since
            if gone >= ASLEEP_AFTER_S:
                self._awake = False
                return "asleep"
            if gone >= SLEEPY_AFTER_S:
                return "sleepy"
            return "neutral"
        if self._transient is not None:
            name, until = self._transient
            if t < until:
                return name
            self._transient = None
        return "calm" if state.seated else "neutral"

    # -- gaze ------------------------------------------------------------------------------

    def _gaze_target(self, state: PresenceState, t: float, name: str) -> tuple[np.ndarray, float]:
        if name == "asleep":
            return np.array([0.0, 4.0]), SLEEP_OMEGA
        if self._absent_since is not None:
            return self._search(t)

        p = state.head
        if p is None and state.position is not None:
            z = HEAD_Z_SEATED if state.seated else HEAD_Z_STANDING
            p = (state.position[0], state.position[1], z)
        far = state.distance_m is not None and state.distance_m > FAR_M
        if p is None or far:
            base = np.array(gaze_from_point(p)) * 0.6 if p is not None else np.zeros(2)
            return base + self._idle_glance(t), SACCADE_OMEGA
        return np.array(gaze_from_point(p)) + self._micro_saccade(t), GAZE_OMEGA

    def _micro_saccade(self, t: float) -> np.ndarray:
        if t >= self._next_micro:
            self._micro = np.array([self._rng.uniform(-MICRO_PX, MICRO_PX),
                                    self._rng.uniform(-MICRO_PX, MICRO_PX) * 0.6])
            self._next_micro = t + self._rng.uniform(*MICRO_EVERY_S)
        return self._micro

    def _idle_glance(self, t: float) -> np.ndarray:
        if t >= self._next_wander:
            self._wander = np.array([self._rng.uniform(-0.8, 0.8) * GAZE_X_PX,
                                     self._rng.uniform(-0.4, 0.3) * GAZE_Y_PX])
            self._wander_until = t + self._rng.uniform(*WANDER_HOLD_S)
            self._next_wander = t + self._rng.uniform(*WANDER_EVERY_S)
        return self._wander if t < self._wander_until else np.zeros(2)

    def _search(self, t: float) -> tuple[np.ndarray, float]:
        """After someone left: glance where they went, the other way, then settle down."""
        gone = t - self._absent_since
        if gone >= LOOK_AROUND_S:
            return np.array([0.0, 2.0]), EXPR_OMEGA
        side = self._last_side or 1.0
        steps = (side * 0.9, -side * 0.8, side * 0.3)
        i = min(len(steps) - 1, int(gone / (LOOK_AROUND_S / len(steps))))
        return np.array([steps[i] * GAZE_X_PX, -0.1 * GAZE_Y_PX]), SACCADE_OMEGA

    # -- blinks and accent -----------------------------------------------------------------

    def _blink(self, t: float, name: str) -> float:
        if name == "asleep":
            self._blinks.clear()
            self._next_blink = t + self._rng.uniform(*BLINK_EVERY_S)
            return 0.0
        if t >= self._next_blink:
            timing = SLOW_BLINK if name == "sleepy" else BLINK
            self._blinks.append((t, timing))
            if self._rng.uniform(0.0, 1.0) < BLINK_DOUBLE_P:
                self._blinks.append((t + sum(timing) + 0.08, timing))
            self._next_blink = t + self._rng.uniform(*BLINK_EVERY_S)
        self._blinks = [(s, tm) for s, tm in self._blinks if t <= s + sum(tm)]
        return max((blink_closure(t, s, tm) for s, tm in self._blinks), default=0.0)

    def _accent(self, t: float, dt: float, state: PresenceState) -> float:
        target = 1.0 if t < self._accent_until and state.present else 0.0
        # First-order fade (time constant ACCENT_FADE_S), then a gentle pulse at the heart rate.
        self._accent_level += (target - self._accent_level) * (1.0 - math.exp(-dt / ACCENT_FADE_S))
        if self._accent_level < 1e-3:
            return 0.0
        bpm = state.heart_rate or 60.0
        pulse = 0.5 + 0.5 * math.cos(2 * math.pi * (t - self._accent_t0) * bpm / 60.0)
        return self._accent_level * (0.45 + 0.55 * pulse)


def palette() -> dict[str, tuple[Color, int]]:
    """The face colours as (RGB, RGB565), for docs and the firmware port."""
    return {name: (color, rgb565(color)) for name, color in
            (("background", BACKGROUND), ("eye_white", EYE_WHITE), ("eye_amber", EYE_AMBER),
             ("accent", ACCENT))}


__all__ = ["Face", "FaceParams", "EXPRESSIONS", "TRANSIENTS", "render", "gaze_from_point",
           "palette", "spring_step", "blink_closure", "XorShift32"]
