# SPDX-License-Identifier: MIT
import time

import numpy as np
import pytest

from marvin_host import face as F
from marvin_host.events import Event, EventKind, PresenceState
from marvin_host.raster import Canvas, rgb565

SEATED = (0.0, -800.0, 400.0)


def person(head=SEATED, seated=True):
    return PresenceState(present=True, seated=seated, head=head, position=(head[0], head[1], 16.0),
                         distance_m=abs(head[1]) / 1000.0)


def play(face, state, t0, t1, fps=30):
    frame = None
    for i in range(int(round((t1 - t0) * fps)) + 1):
        frame = face.update(state, t0 + i / fps)
    return frame


def centroid_x(frame):
    lum = frame.astype(np.float64).sum(axis=2)
    lum -= lum.min()
    xs = np.arange(frame.shape[1])[None, :]
    return float((lum * xs).sum() / lum.sum())


def bright_pixels(frame):
    return int((frame.max(axis=2) > 100).sum())


def test_frame_shape_and_dtype():
    frame = F.Face().update(PresenceState(), 0.0)
    assert frame.shape == (F.SCREEN_H, F.SCREEN_W, 3) == (280, 240, 3)
    assert frame.dtype == np.uint8


def test_deterministic():
    def record(seed):
        face = F.Face(seed)
        face.on_event(Event(EventKind.ARRIVED, 0))
        frames = []
        for i in range(240):
            t = i / 30
            if i == 90:
                face.on_event(Event(EventKind.SAT_DOWN, 0))
            frames.append(face.update(person(), t))
        return np.stack(frames)

    a, b = record(5), record(5)
    assert np.array_equal(a, b)
    assert not np.array_equal(a, record(6))       # the seed drives blinks and saccades


def test_gaze_follows_the_person():
    def look(x):
        face = F.Face(1)
        face.on_event(Event(EventKind.ARRIVED, 0))
        return play(face, person((x, -900.0, 500.0), seated=False), 0.0, 3.0)

    right, left = centroid_x(look(700.0)), centroid_x(look(-700.0))
    assert right > F.EYE_CX + 10 > F.EYE_CX - 10 > left


def test_gaze_mapping_signs():
    gx, gy = F.gaze_from_point((500.0, -1000.0, 92.0))
    assert gx > 0 and gy == pytest.approx(0.0, abs=1e-6)
    assert F.gaze_from_point((0.0, -1000.0, 600.0))[1] < 0          # head above: eyes up


def test_falls_asleep_after_leaving():
    face = F.Face(2)
    face.on_event(Event(EventKind.ARRIVED, 0))
    awake = play(face, person(), 0.0, 4.0)
    face.on_event(Event(EventKind.LEFT, 0))
    asleep = play(face, PresenceState(), 4.0, 4.0 + F.ASLEEP_AFTER_S + 6.0)
    assert face.expression == "asleep"
    assert bright_pixels(asleep) < 0.1 * bright_pixels(awake)


def test_wakes_up_when_someone_arrives():
    face = F.Face()
    play(face, PresenceState(), 0.0, 1.0)
    assert face.expression == "asleep"
    face.on_event(Event(EventKind.ARRIVED, 0))
    face.update(person(seated=False), 1.1)
    assert face.expression == "awake"


@pytest.mark.parametrize("kind", list(EventKind))
def test_every_event_is_accepted(kind):
    face = F.Face()
    play(face, person(), 0.0, 1.0)
    face.on_event(Event(kind, 0, "test", {}))
    frame = play(face, person(), 1.0, 2.0)
    assert frame.shape == (280, 240, 3)
    assert face.expression in F.EXPRESSIONS


def test_event_expressions():
    expected = {EventKind.APPROACHED: "surprised", EventKind.SAT_DOWN: "content",
                EventKind.STOOD_UP: "attentive", EventKind.STILL_LONG: "concerned"}
    for kind, name in expected.items():
        face = F.Face()
        play(face, person(), 0.0, 3.0)
        face.on_event(Event(kind, 0))
        face.update(person(), 3.1)
        assert face.expression == name


def test_transitions_are_eased():
    face = F.Face()
    play(face, person(), 0.0, 5.0)
    before = face.params.lid_bottom
    face.on_event(Event(EventKind.SAT_DOWN, 0))
    face.update(person(), 5.0 + 1 / 30)
    step = face.params.lid_bottom - before
    assert 0 < step < 0.5 * F.EXPRESSIONS["content"].lid_bottom


def test_blinks_happen():
    face = F.Face(3)
    face.on_event(Event(EventKind.ARRIVED, 0))
    counts = [bright_pixels(face.update(person(), i / 60)) for i in range(60 * 15)]
    assert min(counts[120:]) < 0.5 * np.median(counts[120:])


def test_rgb565():
    assert rgb565((255, 255, 255)) == 0xFFFF
    assert rgb565((0, 0, 0)) == 0
    assert rgb565((255, 0, 0)) == 0xF800


def test_raster_primitives_cover_expected_area():
    cv = Canvas(100, 100)
    cv.fill_circle(50, 50, 20, (255, 255, 255))
    assert cv.buf[..., 0].sum() / 255 == pytest.approx(np.pi * 400, rel=0.01)
    cv.fill(0)
    cv.fill_round_rect(50, 50, 40, 30, 0, (255, 255, 255))
    assert cv.buf[..., 0].sum() / 255 == pytest.approx(1200, rel=0.01)
    cv.fill(0)
    cv.fill_triangle(10, 10, 90, 10, 10, 90, (255, 255, 255))
    assert cv.buf[..., 0].sum() / 255 == pytest.approx(3200, rel=0.01)
    cv.fill(0)
    cv.fill_ellipse(50, 50, 30, 10, (255, 255, 255))
    assert cv.buf[..., 0].sum() / 255 == pytest.approx(np.pi * 300, rel=0.02)


def test_render_speed():
    face = F.Face(4)
    face.on_event(Event(EventKind.SAT_DOWN, 0))
    face.update(person(), 0.0)
    n = 120
    start = time.perf_counter()
    for i in range(1, n + 1):
        face.update(person(), i / 30)
    ms = (time.perf_counter() - start) / n * 1000
    print(f"face: {ms:.2f} ms per frame")
    assert ms < 15
