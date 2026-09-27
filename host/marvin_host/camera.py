"""Camera model and a simulated camera that renders scene.py from the robot's eye.

The real camera (XIAO ESP32S3 Sense) will stream MJPEG over HTTP; until then, the simulated
camera renders the same apartment the simulated lidar and radars see, at the device's clock,
so lidar points and radar targets line up with the image in the viewer.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

from . import scene


@dataclass
class CameraModel:
    position: tuple[float, float, float] = (0.0, -38.0, 113.0)   # planned, see docs/architecture.md
    pitch_deg: float = 20.0                                     # tilted up
    width: int = 320
    height: int = 240
    hfov_deg: float = 66.0

    @property
    def focal(self) -> float:
        return (self.width / 2) / math.tan(math.radians(self.hfov_deg / 2))

    @property
    def rotation(self) -> np.ndarray:
        """Columns: the camera's right, down and forward axes (RDF) in the device frame."""
        p = math.radians(self.pitch_deg)
        fwd = np.array([0.0, -math.cos(p), math.sin(p)])
        right = np.array([-1.0, 0.0, 0.0])        # looking at -Y, the image right is -X
        down = np.cross(fwd, right)
        return np.column_stack([right, down, fwd])


CAMERA = CameraModel()

_LIGHT = np.array([0.35, -0.45, 0.82])
_LIGHT /= np.linalg.norm(_LIGHT)


class SimCamera:
    def __init__(self, model: CameraModel = CAMERA, seed: int = 0):
        self.m = model
        h, w, f = model.height, model.width, model.focal
        v, u = np.mgrid[0:h, 0:w].astype(np.float32)
        d = np.stack([(u + 0.5 - w / 2) / f, (v + 0.5 - h / 2) / f, np.ones_like(u)], axis=-1).reshape(-1, 3)
        d /= np.linalg.norm(d, axis=1, keepdims=True)
        self.dirs = (d @ model.rotation.T).astype(np.float32)          # world directions, (N, 3)
        self.origin = np.array(model.position, dtype=np.float32)
        with np.errstate(divide="ignore"):
            self.inv = 1.0 / np.where(np.abs(self.dirs) < 1e-9, 1e-9, self.dirs)
        self.inv_axes = [np.ascontiguousarray(self.inv[:, k]) for k in range(3)]
        self.rng = np.random.default_rng(seed)
        self.boxes = [(np.array(b.lo, np.float32), np.array(b.hi, np.float32), np.array(b.color, np.float32))
                      for b in scene.BOXES]

    def render(self, t: float) -> np.ndarray:
        """(H, W, 3) uint8 image of the scene at time t (seconds)."""
        o, d, inv = self.origin, self.dirs, self.inv
        n = len(d)
        depth = np.full(n, np.inf, np.float32)
        color = np.zeros((n, 3), np.float32)
        normal = np.zeros((n, 3), np.float32)

        def take(t_hit, col, nrm):
            closer = t_hit < depth
            depth[closer] = t_hit[closer]
            color[closer] = col[closer] if col.ndim == 2 else col
            normal[closer] = nrm[closer] if nrm.ndim == 2 else nrm

        # floor (wood planks) and ceiling
        with np.errstate(divide="ignore", invalid="ignore"):
            tf = np.where(d[:, 2] < 0, (scene.FLOOR - o[2]) / d[:, 2], np.inf)
            tc = np.where(d[:, 2] > 0, (scene.CEILING - o[2]) / d[:, 2], np.inf)
        px = o[0] + d[:, 0] * np.where(np.isfinite(tf), tf, 0)
        plank = (np.floor(px / 180.0) % 2)[:, None]
        take(tf, np.array([205, 195, 178], np.float32) * (0.92 + 0.08 * plank), np.array([0, 0, 1], np.float32))
        take(tc, np.array([245, 244, 240], np.float32), np.array([0, 0, -1], np.float32))

        # furniture and walls (axis-aligned boxes, slab test, one array per axis for speed)
        for lo, hi, col in self.boxes:
            tn, tf, ax = None, None, None
            for k in range(3):
                t1 = (lo[k] - o[k]) * self.inv_axes[k]
                t2 = (hi[k] - o[k]) * self.inv_axes[k]
                near, far = np.minimum(t1, t2), np.maximum(t1, t2)
                if tn is None:
                    tn, tf, ax = near, far, np.zeros(n, np.int8)
                else:
                    ax = np.where(near > tn, k, ax).astype(np.int8)
                    tn, tf = np.maximum(tn, near), np.minimum(tf, far)
            idx = np.nonzero((tf >= tn) & (tn > 0) & (tn < depth))[0]
            if not len(idx):
                continue
            depth[idx] = tn[idx]
            color[idx] = col
            axis = ax[idx]
            nrm = np.zeros((len(idx), 3), np.float32)
            nrm[np.arange(len(idx)), axis] = -np.sign(d[idx, axis])
            normal[idx] = nrm

        # the person: a cylinder body and a sphere head
        p = scene.person_at(t)
        head_r = 110.0
        body_top = p.top - 2 * head_r
        ox, oy = o[0] - p.x, o[1] - p.y
        a = d[:, 0] ** 2 + d[:, 1] ** 2
        b = 2 * (ox * d[:, 0] + oy * d[:, 1])
        c = ox * ox + oy * oy - scene.PERSON_RADIUS ** 2
        disc = b * b - 4 * a * c
        with np.errstate(invalid="ignore", divide="ignore"):
            tcyl = (-b - np.sqrt(np.maximum(disc, 0))) / (2 * a)
        z = o[2] + d[:, 2] * tcyl
        ok = (disc >= 0) & (tcyl > 0) & (z >= scene.FLOOR) & (z <= body_top)
        tcyl = np.where(ok, tcyl, np.inf)
        hx, hy = o[0] + d[:, 0] * tcyl - p.x, o[1] + d[:, 1] * tcyl - p.y
        nrm = np.stack([hx, hy, np.zeros_like(hx)], axis=1) / scene.PERSON_RADIUS
        shirt = np.where((z < body_top - 650)[:, None], [[50, 55, 70]], [[70, 110, 160]]).astype(np.float32)
        take(tcyl, shirt, np.nan_to_num(nrm))
        hc = np.array([p.x, p.y, p.top - head_r], np.float32)
        oc = o - hc
        bb = d @ oc
        cc = oc @ oc - head_r ** 2
        disc = bb * bb - cc
        ts = np.where(disc >= 0, -bb - np.sqrt(np.maximum(disc, 0)), np.inf)
        ts = np.where(ts > 0, ts, np.inf)
        hn = (o + d * np.where(np.isfinite(ts), ts, 0)[:, None] - hc) / head_r
        take(ts, np.array([224, 184, 150], np.float32), hn)

        shade = 0.72 + 0.28 * np.clip(normal @ _LIGHT, 0, 1) + 0.08 * np.clip(-normal[:, 1], 0, 1)
        fog = np.exp(-np.minimum(depth, 20000) / 20000.0)
        img = color * (shade * (0.8 + 0.2 * fog))[:, None]
        img += self.rng.normal(0, 2.5, img.shape)
        return np.clip(img, 0, 255).astype(np.uint8).reshape(self.m.height, self.m.width, 3)
