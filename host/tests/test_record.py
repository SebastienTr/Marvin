"""Recording and replaying sensor sessions (.mvrec).

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import argparse
import gzip
import struct
import threading
import time

import numpy as np
import pytest

from marvin_host import protocol, record, sim
from marvin_host.brain import Brain
from marvin_host.receiver import Sink


class Collect(Sink):
    """Remembers every sink call, in order, in a comparable form."""

    def __init__(self):
        self.calls = []

    def on_hello(self, dev):
        self.calls.append(("hello", dev.hello.device_name))

    def on_scan(self, dev, t_us, points, intensities, speed_dps):
        self.calls.append(("scan", t_us, points.tobytes(), intensities.tobytes(), speed_dps))

    def on_targets(self, dev, t_us, targets, points):
        self.calls.append(("targets", t_us, tuple((t.x_mm, t.y_mm, t.speed_cms) for t in targets), tuple(points)))

    def on_vitals(self, dev, t_us, vitals):
        self.calls.append(("vitals", t_us, vitals))

    def on_log(self, dev, t_us, text):
        self.calls.append(("log", t_us, text))


class Tee(Sink):
    def __init__(self, *sinks):
        self.sinks = sinks

    def on_hello(self, *a):
        for s in self.sinks:
            s.on_hello(*a)

    def on_scan(self, *a):
        for s in self.sinks:
            s.on_scan(*a)

    def on_targets(self, *a):
        for s in self.sinks:
            s.on_targets(*a)

    def on_vitals(self, *a):
        for s in self.sinks:
            s.on_vitals(*a)

    def on_log(self, *a):
        for s in self.sinks:
            s.on_log(*a)


def events(brain: Brain):
    return [(e.kind, e.t_us, e.detail, e.data) for e in brain.events]


def test_record_live_udp_then_replay_gives_the_same_calls_and_events(tmp_path):
    path = tmp_path / "live.mvrec"
    live_brain, live = Brain(), Collect()
    rx = record.RecordingReceiver(Tee(live_brain, live), path, port=0, bind="127.0.0.1")
    port = rx.sock.getsockname()[1]
    stop = threading.Event()
    th = threading.Thread(target=rx.serve, kwargs={"stop": stop}, daemon=True)
    th.start()
    dev = sim.SimDevice(host="127.0.0.1", port=port)
    dev.t0 -= 6.0                     # start the scene at 6 s: the person walks into view, ARRIVED follows
    dev.run(duration=2.5)
    time.sleep(0.2)
    stop.set()
    th.join()
    rx.close()

    assert len([c for c in live.calls if c[0] == "scan"]) >= 5
    assert any(k == "arrived" for k, *_ in events(live_brain)), events(live_brain)

    replay_brain, replayed = Brain(), Collect()
    rrx = record.replay(path, Tee(replay_brain, replayed), speed=None)
    assert replayed.calls == live.calls
    assert events(replay_brain) == events(live_brain)
    assert replay_brain.state == live_brain.state
    (d_live,), (d_rep,) = rx.devices.values(), rrx.devices.values()
    assert d_rep.stats == d_live.stats
    info = record.summarize(path)
    assert info["devices"] == [{"address": f"{d_live.addr[0]}:{d_live.addr[1]}", "device_name": "marvin-53494d",
                                "board": 255, "firmware": "sim-0.1.0", "simulated": True}]
    assert info["datagrams"] >= d_live.stats.datagrams      # the recording also has what came before the HELLO
    assert not info["truncated"] and 2.0 < info["duration_s"] < 3.5


@pytest.fixture(scope="module")
def simulated(tmp_path_factory):
    p = tmp_path_factory.mktemp("rec") / "sim.mvrec"
    return record.write_simulated(p, 12.0, start=5.0)


def replay_calls(path, **kw):
    brain, c = Brain(), Collect()
    rx = record.replay(path, Tee(brain, c), speed=None, **kw)
    return brain, c, rx


def test_simulated_recording_replays_deterministically(simulated):
    b1, c1, _ = replay_calls(simulated)
    b2, c2, _ = replay_calls(simulated)
    assert c1.calls == c2.calls and events(b1) == events(b2)
    assert [e.kind.value for e in b1.events] == ["arrived"]


def test_gzip_recording_matches_plain(tmp_path, simulated):
    gz = tmp_path / "sim.mvrec.gz"
    with record.RecordingReader(simulated) as r, record.RecordingWriter(gz, meta={"note": "copy"}) as w:
        for rec in r:
            w.write_datagram(rec.data, rec.addr, w._t0 + rec.t)
    assert gz.read_bytes()[:2] == b"\x1f\x8b"
    assert gz.stat().st_size < simulated.stat().st_size * 0.8
    assert replay_calls(gz)[1].calls == replay_calls(simulated)[1].calls
    with record.RecordingReader(gz) as r:
        assert r.meta["note"] == "copy" and r.meta["protocol"] == protocol.VERSION


@pytest.mark.parametrize("compress", [False, True])
def test_truncated_recording_reads_up_to_the_last_complete_record(tmp_path, simulated, compress):
    data = simulated.read_bytes()
    full = list(record.RecordingReader(simulated))
    if compress:
        data = gzip.compress(data)
    for cut in (0.3, 0.77, 0.999):
        p = tmp_path / f"cut{cut}.mvrec"
        p.write_bytes(data[:int(len(data) * cut)])
        r = record.RecordingReader(p)
        got = list(r)
        assert 0 < len(got) < len(full) and r.truncated
        assert [g.data for g in got] == [f.data for f in full[:len(got)]]
        replay_calls(p)                      # and a replay just stops there


def test_not_a_recording(tmp_path):
    p = tmp_path / "x.mvrec"
    for blob in (b"", b"MVR", b"hello world, not a recording", struct.pack("<5sBI", b"MVREC", 9, 2) + b"{}",
                 struct.pack("<5sBI", b"MVREC", 1, 5) + b"{oops"):
        p.write_bytes(blob)
        with pytest.raises(record.RecordingError):
            record.RecordingReader(p)


def test_corrupt_records(tmp_path):
    p = tmp_path / "x.mvrec"
    with record.RecordingWriter(p) as w:
        w.write_datagram(b"garbage, not a protocol datagram", ("10.0.0.2", 47101))
        w.write_datagram(protocol.pack(protocol.LD2450, 0, 0, b"short"), ("10.0.0.2", 47101))
    # garbage datagrams replay like live ones: dropped by the receiver
    brain, c, rx = replay_calls(p)
    assert c.calls == [] and rx.devices == {}
    # unknown record kinds (a later version) are skipped
    blob = p.read_bytes() + struct.pack("<BQH", 99, 5, 3) + b"xyz"
    p.write_bytes(blob)
    assert len(list(record.RecordingReader(p))) == 2
    # a datagram from a sender that was never declared
    p.write_bytes(blob + struct.pack("<BQH", record.DATAGRAM, 6, 3) + struct.pack("<H", 7) + b"x")
    with pytest.raises(record.RecordingError):
        list(record.RecordingReader(p))


def test_replay_speed_loop_and_stop(tmp_path):
    p = tmp_path / "short.mvrec"
    record.write_simulated(p, 0.6)
    t = time.monotonic()
    record.replay(p, Sink(), speed=3.0)
    assert time.monotonic() - t >= 0.6 / 3 * 0.8

    hellos = []
    stop = threading.Event()

    class S(Sink):
        def on_hello(self, dev):
            hellos.append(dev)

        def on_targets(self, dev, t_us, targets, points):
            if dev.stats.datagrams > 3 * 60:      # well into the third pass
                stop.set()

    rx = record.replay(p, S(), speed=None, loop=True, stop=stop)
    assert stop.is_set() and len(hellos) == 1 and rx.devices    # same robot, same address: one HELLO
    assert len(record.replay(p, Sink(), speed=None, stop=stop).devices) == 0     # already stopped


def test_replay_never_sends(tmp_path, simulated, monkeypatch):
    rx = record.ReplayReceiver(Sink())
    sent = []
    monkeypatch.setattr(rx.sock, "sendto", lambda data, addr: sent.append(addr) or len(data))
    record.replay(simulated, Sink(), speed=None, receiver=rx)
    rx.send(next(iter(rx.devices.values())), protocol.FACE_EVENT, b"\x01")
    assert rx.devices and all(a[0] == record.SIM_ADDR[0] for a in sent)      # into the null socket only


def test_cli_helpers(tmp_path, simulated, capsys):
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd")
    run = sub.add_parser("run")
    record.add_run_arguments(run)
    record.add_cli(sub)

    args = ap.parse_args(["run", "--record", str(tmp_path / "r.mvrec")])
    rx = record.make_receiver(args, Sink(), port=0, bind="127.0.0.1")
    assert isinstance(rx, record.RecordingReceiver)
    record.close_receiver(rx)
    assert record.RecordingReader(tmp_path / "r.mvrec").meta["marvin_host"]
    plain = record.make_receiver(ap.parse_args(["run"]), Sink(), port=0, bind="127.0.0.1")
    assert not isinstance(plain, record.RecordingReceiver)
    plain.sock.close()

    record.run_replay(ap.parse_args(["replay", str(simulated), "--info"]), lambda: pytest.fail("no sink for --info"))
    assert "12.0 s" in capsys.readouterr().out
    brains = []
    record.run_replay(ap.parse_args(["replay", str(simulated), "--fast", "--no-viewer"]),
                      lambda: brains.append(Brain()) or brains[-1])
    assert [e.kind.value for e in brains[0].events] == ["arrived"]
    assert "marvin-53494d" in capsys.readouterr().out


def test_simulated_datagrams_follow_the_simulator_rates():
    dgrams = list(record.simulated_datagrams(1.0))
    types = [protocol.unpack(d)[0].type for _, d in dgrams]
    assert types[0] == protocol.HELLO
    assert types.count(protocol.LD2450) == 10 and types.count(protocol.VITALS) == 10
    pkts = sum(len(d) - protocol.HEADER.size - 1 for (_, d), ty in zip(dgrams, types) if ty == protocol.LIDAR) // 47
    assert abs(pkts - sim.LIDAR_RATE[1] / 12) <= 10
    assert np.all(np.diff([t for t, _ in dgrams]) >= 0)
