"""The brain, driven by the simulated room frame by frame (no sockets).

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import pytest

from marvin_host import frames, ld2450, protocol, scene, sim
from marvin_host.brain import Brain, BrainConfig
from marvin_host.events import EventKind as K

DEV = object()                  # the brain does not look at the device
STEP = 0.1                      # the LD2450 and MR60BHA2 frame period in the simulator


def feed(brain: Brain, t0: float, t1: float, clock_offset: float = 0.0) -> None:
    """Simulated frames for scene times [t0, t1), stamped with device time t + clock_offset."""
    for i in range(round(t0 / STEP), round(t1 / STEP)):
        t = i * STEP
        t_us = round((t + clock_offset) * 1e6)
        targets = sim.ld2450_targets(t)
        brain.on_targets(DEV, t_us, targets, [frames.ld2450_to_device(g.x_mm, g.y_mm) for g in targets])
        brain.on_vitals(DEV, t_us, sim.vitals(t))


def at(ev) -> float:
    return ev.t_us / 1e6


def kinds(events) -> list[K]:
    return [e.kind for e in events]


def assert_no_flicker(events) -> None:
    """Paired events alternate, and nothing happens to someone who is not there."""
    present = seated = vitals = False
    for e in events:
        if e.kind == K.ARRIVED:
            assert not present, e
            present = True
        elif e.kind == K.LEFT:
            assert present and not vitals, e
            present = seated = False
        elif e.kind == K.SAT_DOWN:
            assert present and not seated, e
            seated = True
        elif e.kind == K.STOOD_UP:
            assert present and seated, e
            seated = False
        elif e.kind == K.VITALS_ACQUIRED:
            assert present and not vitals, e
            vitals = True
        elif e.kind == K.VITALS_LOST:
            assert vitals, e
            vitals = False
        else:
            assert present, e


def test_fidget_windows_drop_the_vital_signs():
    for t0, t1 in scene.FIDGETS:
        assert scene.SIT[0] + 3 < t0 < t1 < scene.SIT[1]
        assert not sim.vitals((t0 + t1) / 2).valid
        assert sim.vitals(t0 - 0.5).valid and sim.vitals(t1 + 0.5).valid
    assert sim.vitals(scene.SIT[0] + 1).valid       # time to acquire them before the first fidget
    t0, t1 = scene.FIDGETS[0]                          # the person moves during a fidget
    a, b = scene.person_at(t0), scene.person_at((t0 + t1) / 2)
    assert abs(scene.person_at(t0 + 0.4).x - a.x) + abs(b.y - a.y) > 20


def test_one_loop_of_the_simulated_room():
    brain = Brain()
    seen = []
    brain.add_listener(seen.append)
    feed(brain, 0, scene.LOOP)
    ev = list(brain.events)
    assert ev == seen
    assert_no_flicker(ev)

    ks = kinds(ev)
    assert ks[0] == K.ARRIVED and ks[-1] == K.LEFT
    assert ks.count(K.ARRIVED) == ks.count(K.LEFT) == 1
    assert ks.count(K.SAT_DOWN) == ks.count(K.STOOD_UP) == 1
    assert K.APPROACHED not in ks and K.STILL_LONG not in ks   # never within 0.6 m, sits < 50 min

    sat = next(e for e in ev if e.kind == K.SAT_DOWN)
    stood = next(e for e in ev if e.kind == K.STOOD_UP)
    assert scene.SIT[0] < at(sat) < scene.SIT[0] + 4
    assert scene.SIT[1] < at(stood) < scene.SIT[1] + 4
    assert sat.detail.endswith(" m away") and 0.8 < sat.data["distance_m"] < 0.95

    # vital signs: acquired soon after sitting down, lost at every fidget, re-acquired after it
    vit = [e for e in ev if e.kind in (K.VITALS_ACQUIRED, K.VITALS_LOST)]
    assert vit[0].kind == K.VITALS_ACQUIRED and at(vit[0]) < scene.FIDGETS[0][0]
    assert all(at(sat) <= at(e) <= at(stood) for e in vit)
    for t0, t1 in scene.FIDGETS:
        lost = [e for e in vit if e.kind == K.VITALS_LOST and t0 <= at(e) <= t0 + 1.5]
        back = [e for e in vit if e.kind == K.VITALS_ACQUIRED and t1 <= at(e) <= t1 + 4]
        assert len(lost) == 1 and len(back) == 1, (t0, t1)
    assert vit[-1].kind == K.VITALS_LOST              # not reliable once they get up


def test_state_while_walking_and_seated():
    brain = Brain()
    feed(brain, 0, 12)
    s = brain.state
    assert s.present and not s.seated and s.targets == 1
    assert s.head[2] == 1000 and s.breath_rate is None and s.heart_rate is None
    assert s.speed_cms != 0 and s.still_s < 1

    feed(brain, 12, 41)                               # seated, between two fidgets
    s = brain.state
    assert s.present and s.seated and s.t_us == 40_900_000
    assert s.head[2] == 550 and s.head[:2] == pytest.approx(s.position[:2])
    assert s.distance_m == pytest.approx(0.86, abs=0.03)
    assert 10 < s.breath_rate < 20 and 55 < s.heart_rate < 85
    assert abs(s.speed_cms) < 1 and s.still_s > 3 and 10 < s.seated_s < 13

    feed(brain, 41, scene.LOOP)
    assert not brain.state.present and brain.state.position is None and brain.state.head is None


def test_several_loops_repeat_the_same_story():
    brain = Brain()
    for k in range(3):
        feed(brain, k * scene.LOOP, (k + 1) * scene.LOOP)
    ev = list(brain.events)
    assert_no_flicker(ev)
    one = kinds(ev)[: len(ev) // 3]
    assert kinds(ev) == one * 3


def test_still_long_once_per_sitting():
    brain = Brain(BrainConfig(still_long_s=10.0))
    feed(brain, 0, 2 * scene.LOOP)
    ev = list(brain.events)
    assert_no_flicker(ev)
    long = [e for e in ev if e.kind == K.STILL_LONG]
    sat = [e for e in ev if e.kind == K.SAT_DOWN]
    stood = [e for e in ev if e.kind == K.STOOD_UP]
    assert len(long) == len(sat) == len(stood) == 2    # re-armed after standing up
    for a, b, c in zip(sat, long, stood):
        assert at(b) - at(a) == pytest.approx(10.0, abs=0.15) and at(b) < at(c)
    assert long[0].detail == "seated for 10 s"


def test_device_restart_resets_the_state():
    brain = Brain()
    feed(brain, 0, 40, clock_offset=100.0)             # the device has been up for a while
    assert brain.state.seated and brain.state.heart_rate is not None
    n = len(brain.events)

    # a frame slightly out of order is dropped
    brain.on_targets(DEV, 139_500_000, [], [])   # last frame: 139.9 s
    assert brain.state.present and len(brain.events) == n

    feed(brain, 0, 1)                                  # rebooted: the clock starts over, nobody in view
    new = list(brain.events)[n:]
    assert kinds(new) == [K.VITALS_LOST, K.LEFT] and new[1].detail == "sensor restarted"
    s = brain.state
    assert not s.present and not s.seated and s.heart_rate is None and s.t_us == 900_000

    feed(brain, 1, scene.LOOP)                         # and the story starts again
    ev = list(brain.events)
    assert_no_flicker(ev)
    assert kinds(ev)[n + 2:].count(K.ARRIVED) == 1


def _walk(brain: Brain, t: float, distance_mm: float) -> None:
    """One LD2450 frame with a person straight ahead, about this far from the robot."""
    target = ld2450.Target(0, int(distance_mm), 0, 320)
    brain.on_targets(DEV, round(t * 1e6), [target], [frames.ld2450_to_device(0, distance_mm)])


def test_approach_with_hysteresis():
    brain = Brain()
    t = 0.0
    for d in [2000] * 10 + list(range(2000, 450, -50)):       # walks up to the robot
        _walk(brain, t, d)
        t += STEP
    for d in [500, 750, 520, 800, 550] * 3:                    # hovers between 0.5 and 0.8 m
        for _ in range(8):
            _walk(brain, t, d)
            t += STEP
    assert kinds(brain.events).count(K.APPROACHED) == 1
    for d in list(range(600, 1300, 50)) + list(range(1300, 400, -50)):   # steps back, comes again
        _walk(brain, t, d)
        t += STEP
    ev = [e for e in brain.events if e.kind == K.APPROACHED]
    assert len(ev) == 2 and all(e.data["distance_m"] < 0.6 for e in ev)


def test_arrival_is_confirmed_and_short_gaps_are_ignored():
    brain = Brain()
    t = 0.0
    for k in range(40):                                # glimpses: seen 3 frames out of 8
        if k % 8 < 3:
            _walk(brain, t, 2000)
        else:
            brain.on_targets(DEV, round(t * 1e6), [], [])
        t += STEP
    assert not brain.state.present and len(brain.events) == 0
    for _ in range(10):
        _walk(brain, t, 2000)
        t += STEP
    assert kinds(brain.events) == [K.ARRIVED]
    for _ in range(25):                                # 2.5 s without a target: still there
        brain.on_targets(DEV, round(t * 1e6), [], [])
        t += STEP
    _walk(brain, t, 2000)
    assert brain.state.present and kinds(brain.events) == [K.ARRIVED]


def test_vitals_need_a_still_person_and_plausible_values():
    brain = Brain()
    t = 0.0
    for _ in range(60):                                # standing still 1.2 m away
        _walk(brain, t, 1200)
        brain.on_vitals(DEV, round(t * 1e6), protocol.Vitals(True, 3.0, 250.0, 0, 0, 1200))   # nonsense
        t += STEP
    assert K.VITALS_ACQUIRED not in kinds(brain.events)
    for _ in range(40):
        _walk(brain, t, 1200)
        brain.on_vitals(DEV, round(t * 1e6), protocol.Vitals(True, 15.0, 70.0, 0, 0, 1200))
        t += STEP
    assert kinds(brain.events)[-1] == K.VITALS_ACQUIRED
    assert brain.state.breath_rate == 15.0 and brain.state.heart_rate == 70.0


def test_a_failing_listener_does_not_stop_the_others():
    brain = Brain()
    got = []

    def broken(_):
        raise RuntimeError("boom")

    brain.add_listener(broken)
    brain.add_listener(got.append)
    feed(brain, 0, 10)
    assert kinds(got) == [K.ARRIVED]
