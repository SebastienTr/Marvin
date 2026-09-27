"""Calibration file and lidar yaw estimation, checked against the simulator.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import argparse
import json
import math

import numpy as np
import pytest

from marvin_host import calibration as cal
from marvin_host import frames, record, scene, sim


@pytest.fixture(autouse=True)
def isolated(tmp_path, monkeypatch):
    """A private config directory, and frames restored after each test."""
    monkeypatch.setenv(cal.ENV_DIR, str(tmp_path / "config"))
    saved = frames.LIDAR.yaw_deg, frames.LD2450.x_sign
    yield
    frames.LIDAR.yaw_deg, frames.LD2450.x_sign = saved


def angle_err(a: float, b: float) -> float:
    return abs(float(cal.wrap180(a - b)))


# ------------------------------------------------------------------ file

def test_config_dir_from_env(tmp_path, monkeypatch):
    assert cal.default_path() == tmp_path / "config" / "calibration.json"
    monkeypatch.delenv(cal.ENV_DIR)
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "xdg"))
    assert cal.default_path() == tmp_path / "xdg" / "marvin" / "calibration.json"


def test_no_file_means_defaults_and_nothing_applied():
    frames.LIDAR.yaw_deg = 12.0
    c = cal.load()
    assert c == cal.Calibration() and frames.LIDAR.yaw_deg == 12.0


def test_save_load_apply_keeps_unknown_keys():
    p = cal.default_path()
    p.parent.mkdir(parents=True)
    p.write_text(json.dumps({"version": 1, "lidar": {"yaw_deg": 5, "mystery": 1}, "future": {"x": [1, 2]}}))
    c = cal.load(apply=False)
    assert c.lidar_yaw_deg == 5.0 and frames.LIDAR.yaw_deg == 0.0
    c.lidar_yaw_deg, c.ld2450_x_sign, c.radar_speed_sign = -30.0, -1, 1
    cal.save(c)
    d = json.loads(p.read_text())
    assert d["future"] == {"x": [1, 2]} and d["lidar"] == {"yaw_deg": 330.0, "mystery": 1} and d["updated"]
    c2 = cal.load()
    assert (c2.lidar_yaw_deg, c2.ld2450_x_sign, c2.radar_speed_sign) == (330.0, -1, 1)
    assert frames.LIDAR.yaw_deg == 330.0 and frames.LD2450.x_sign == -1
    assert c2.brain_config().radar_speed_sign == 1
    assert not list(p.parent.glob("*.tmp"))


@pytest.mark.parametrize("text", ["{not json", "[]", '{"lidar": {"yaw_deg": "north"}}', '{"ld2450": {"x_sign": 3}}',
                                  '{"lidar": {"yaw_deg": NaN}}'])
def test_bad_file_is_ignored(text, caplog):
    p = cal.default_path()
    p.parent.mkdir(parents=True)
    p.write_text(text)
    assert cal.load() == cal.Calibration()
    assert frames.LIDAR.yaw_deg == 0.0 and "ignoring calibration file" in caplog.text


def test_calibrated_yaw_is_used_by_the_conversions():
    cal.Calibration(lidar_yaw_deg=90.0).apply()
    # the lidar's 90 deg mark now points to the front (-Y)
    np.testing.assert_allclose(frames.lidar_to_device(np.array([90.0]), np.array([1000.0]))[0, :2], [0, -1000], atol=1e-3)
    ang, d = frames.device_to_lidar(0.0, -1000.0)
    assert ang == pytest.approx(90.0) and d == pytest.approx(1000.0)


# ------------------------------------------------------------------ geometry

def test_robust_circular_mean_rejects_outliers_and_wraps():
    rng = np.random.default_rng(1)
    good = 359.0 + rng.normal(0, 1.0, 200)          # around the 0/360 wrap
    bad = rng.uniform(0, 360, 150)
    e = cal.robust_circular_mean(np.concatenate([good, bad]))
    assert angle_err(e.angle_deg, 359.0) < 0.5 and e.inliers >= 190 and e.spread_deg < 3
    assert cal.robust_circular_mean([]) is None


def test_clusters():
    def arc(b0, b1, r, n):
        th = np.radians(np.linspace(b0, b1, n))
        return np.stack([-r * np.sin(th), -r * np.cos(th), np.zeros(n)], axis=1)

    person = arc(-10, 10, 1000, 20)                 # ~350 mm wide at 1 m, straight ahead
    wall = arc(60, 300, 3000, 600)                  # a long wall behind, wrapping through 180 deg
    cs = cal.clusters(np.concatenate([wall, person]))
    assert len(cs) == 2
    p = next(c for c in cs if cal.person_sized(c))
    assert abs(p.bearing_deg) < 0.5 and p.range_mm == pytest.approx(1000, abs=1) and p.n == 20
    w = next(c for c in cs if not cal.person_sized(c))
    assert angle_err(w.bearing_deg, 180) < 1 and w.n == 600


# ------------------------------------------------------------------ estimation, against the simulator

@pytest.mark.parametrize("yaw", [37.0, 200.0, -75.0])
def test_auto_estimate_recovers_the_simulated_mounting(tmp_path, yaw):
    p = record.write_simulated(tmp_path / "walk.mvrec", 26.0, yaw_offset_deg=yaw)   # walking in, before sitting
    frames.LIDAR.yaw_deg = 10.0                     # a wrong calibration in place: the estimate is absolute
    est = cal.LidarYawEstimator()
    record.replay(p, est, speed=None)
    e = est.estimate()
    assert e is not None and e.inliers >= cal.MIN_INLIERS
    assert angle_err(e.angle_deg, yaw) < 2.0, e
    assert e.spread_deg < 3.0


def test_auto_estimate_needs_enough_data(tmp_path):
    est = cal.LidarYawEstimator()
    p = record.write_simulated(tmp_path / "short.mvrec", 1.5, start=66.0)         # leaving, far away, briefly
    record.replay(p, est, speed=None)
    e = est.estimate()
    assert e is None or e.inliers < cal.MIN_INLIERS


def test_manual_estimate_puts_the_person_at_zero(tmp_path):
    yaw = 123.0
    p = record.write_simulated(tmp_path / "seated.mvrec", 8.0, start=28.0, yaw_offset_deg=yaw)
    est = cal.ManualLidarYaw()
    record.replay(p, est, speed=None)
    e = est.estimate()
    person = scene.person_at(31.0)                  # seated, swaying a little
    expected = yaw + float(cal.bearing(person.x, person.y))
    assert angle_err(e.angle_deg, expected) < 2.0, (e, expected)


def test_sim_lidar_follows_the_host_calibration_by_default():
    frames.LIDAR.yaw_deg = 45.0
    a = np.array([45.0, 100.0])
    np.testing.assert_allclose(sim.scan_distances(a, 3.0), sim.scan_distances(a - 45.0, 3.0, yaw_deg=0.0))


def test_cli_from_recording_saves(tmp_path, capsys):
    p = record.write_simulated(tmp_path / "walk.mvrec", 26.0, yaw_offset_deg=-20.0)
    ap = argparse.ArgumentParser()
    cal.add_cli(ap.add_subparsers(dest="cmd"))
    e = cal.run_cli(ap.parse_args(["calibrate", "lidar", "--from", str(p), "--save"]))
    assert angle_err(e.angle_deg, -20.0) < 2.0
    assert "saved to" in capsys.readouterr().out
    assert angle_err(cal.load().lidar_yaw_deg, -20.0) < 2.0 and frames.LIDAR.yaw_deg == cal.load().lidar_yaw_deg

    # not enough data: nothing saved
    empty = record.write_simulated(tmp_path / "short.mvrec", 2.0, start=66.0)
    assert cal.run_cli(ap.parse_args(["calibrate", "lidar", "--from", str(empty), "--save", "--seconds", "1"])) is None
    assert "nothing saved" in capsys.readouterr().out
    assert math.isclose(cal.load(apply=False).lidar_yaw_deg, e.angle_deg % 360, abs_tol=0.01)
