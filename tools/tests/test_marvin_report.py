# SPDX-License-Identifier: MIT
"""Tests of tools/marvin_report.py: nothing personal gets out, the latency lines (contract C6) are read."""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import marvin_report as mr  # noqa: E402


def test_redaction() -> None:
    red = mr.Redactor(["Sébastien"])
    line = (f"{Path.home()}/marvin: joining \"MyWifi\" from 192.168.1.42 (aa:bb:cc:dd:ee:ff), "
            "mail seb@example.com, Bonjour Sébastien, localhost 127.0.0.1, key=abc123")
    out = red(line)
    for secret in ("MyWifi", "192.168.1.42", "aa:bb:cc:dd:ee:ff", "seb@example.com", "Sébastien", "abc123",
                   str(Path.home()) + "/"):
        assert secret not in out, secret
    assert "x.x.x.42" in out and "127.0.0.1" in out and "~/marvin" in out


def test_latency_lines_and_percentiles(tmp_path: Path) -> None:
    p = tmp_path / "latency.jsonl"
    rows = [{"t": 1789983000.0 + i, "lang": "fr", "stt_ms": 100, "llm_first_token_ms": 1000,
             "tts_first_audio_ms": 1500, "robot_out_ms": None, "total_ms": 1000 + 100 * i} for i in range(25)]
    p.write_text("not json\n" + "\n".join(json.dumps(r) for r in rows) + "\n{\"other\": 1}\n")
    got = mr.latency_lines(p)
    assert len(got) == 20 and got[0]["total_ms"] == 1500 and got[-1]["total_ms"] == 3400
    assert mr.percentile([1, 2, 3, 4], 0.5) == 2.5


def test_compact_spring_line() -> None:
    line = ("2026-09-28T21:39:00.910+02:00 ERROR 14174 --- [marvin-host] [-voice-answer-1] [f7cf2340-0b4d] "
            "marvin.voice                             : cannot reach Ollama")
    assert mr.compact(line) == "2026-09-28 21:39:00 ERROR marvin.voice: cannot reach Ollama"


def test_offline_report_has_every_section(tmp_path: Path, capsys) -> None:
    log = tmp_path / "host.log"
    log.write_text("2026-10-14T10:00:00.000Z  INFO 1 --- [m] [main] [] a.B : fine\n"
                   "2026-10-14T10:00:01.000Z  WARN 1 --- [m] [main] [] a.B : something odd at 10.0.0.7\n")
    lat = tmp_path / "latency.jsonl"
    lat.write_text(json.dumps({"t": 1789983000.0, "lang": "en", "stt_ms": 120, "llm_first_token_ms": 1400,
                               "tts_first_audio_ms": 1700, "robot_out_ms": 1850, "total_ms": 1850,
                               "speaker_ms": 30}) + "\n")
    assert mr.main(["--offline", "--no-doctor", "--log", str(log), "--latency", str(lat),
                    "--data-dir", str(tmp_path), "--port", "1"]) == 0
    out = capsys.readouterr().out
    for title in ("## Versions", "## Host", "## Devices", "## Self-test", "## Latency (last 20 turns)",
                  "## Last errors and warnings"):
        assert title in out
    assert "total_ms=1850" in out and "speaker_ms=30" in out and "p50 1850 ms" in out
    assert "something odd at x.x.x.7" in out and "fine" not in out
