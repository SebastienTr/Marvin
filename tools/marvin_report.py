#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""`./marvin report`: one paste with everything needed to help with a Marvin install, and nothing personal.

Sections: versions, the host (health, mode), doctor, the setup steps (when the host has them), devices, the
robot's self-test lines, the last 20 latency lines (contract C6, with p50/p90 of the first word), the last
errors and warnings. Left out on purpose: conversations, memory, the owner's name, Wi-Fi names, MAC
addresses, full IP addresses, home folders, e-mail addresses (and anything matching them in log lines).

Runs with the Python standard library only. The launcher passes MARVIN_REPORT_ROOT, MARVIN_PORT,
MARVIN_DATA_DIR and MARVIN_REPORT_LOG; `--offline` skips the host's API (for tests).
"""

from __future__ import annotations

import argparse
import getpass
import json
import os
import platform
import re
import shutil
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.request
from collections import deque
from pathlib import Path

SELF_TEST = re.compile(r"^\s*(screen|speaker|mic|radar|wifi|camera|host|self-test|selftest):", re.I)
LATENCY_FIELDS = ("lang", "stt_ms", "llm_first_token_ms", "tts_first_audio_ms", "robot_out_ms", "total_ms")
ERROR_LINE = re.compile(r"\b(ERROR|SEVERE|FATAL|WARN|WARNING)\b|Exception\b|^\s*Caused by:")
MAX_LINE = 300


# ------------------------------------------------------------------------------------------- redaction

class Redactor:
    """Removes what could identify the owner or their home from a line of text."""

    def __init__(self, extra_words: list[str] | None = None):
        self.home = str(Path.home())
        try:
            self.user = getpass.getuser()
        except Exception:  # noqa: BLE001 - no user name: nothing to hide
            self.user = ""
        self.words = [w for w in (extra_words or []) if w and len(w) >= 2]

    def __call__(self, text: str) -> str:
        if not text:
            return text
        if self.home and self.home != "/":
            text = text.replace(self.home, "~")
        if self.user and len(self.user) >= 3 and self.user not in ("root", "runner"):
            text = re.sub(rf"(?<![\w-]){re.escape(self.user)}(?![\w-])", "<user>", text)
        text = re.sub(r"[\w.+-]+@[\w-]+(\.[\w-]+)+", "<email>", text)
        text = re.sub(r"\b([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\b", "<mac>", text)
        text = re.sub(r"\b(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\b", self._address, text)
        # Wi-Fi names: `joining "MyWifi"`, `SSID MyWifi`, `ssid=MyWifi`
        text = re.sub(r"(joining|ssid[=:]?|SSID[=:]?|network)\s+\"[^\"]*\"", r'\1 "<wifi>"', text)
        text = re.sub(r"(joining|ssid[=:]|SSID[=:])\s*(?!\"<wifi>\")([^\s,.\"]+)", r"\1 <wifi>", text)
        text = re.sub(r"(access key|key=|token=|password=|secret=)\s*\S+", r"\1<hidden>", text, flags=re.I)
        for w in self.words:
            text = re.sub(rf"(?<!\w){re.escape(w)}(?!\w)", "<owner>", text, flags=re.I)
        return text


    @staticmethod
    def _address(m: re.Match) -> str:
        """An IPv4 address keeps its last byte (which device), not a version number (Java 25.0.4.1)."""
        before = m.string[max(0, m.start() - 12):m.start()].lower()
        if m.group(1) == "127" or m.group(0) == "0.0.0.0" or "version" in before or before.endswith(("v", "-")) \
                or any(int(g) > 255 for g in m.groups()):
            return m.group(0)
        return f"x.x.x.{m.group(4)}"


def show(v) -> str:
    """A value as the report prints it: null, true/false, or the value."""
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "true" if v else "false"
    return str(v)


def clip(line: str) -> str:
    line = line.rstrip()
    return line if len(line) <= MAX_LINE else line[:MAX_LINE] + " ..."


# ------------------------------------------------------------------------------------------- collecting

def get_json(base: str, path: str, timeout: float = 3.0):
    try:
        with urllib.request.urlopen(base + path, timeout=timeout) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        try:
            return json.load(e)   # /api/health answers 503 with a body when degraded
        except Exception:  # noqa: BLE001
            return None
    except Exception:  # noqa: BLE001 - not running, not there yet: the section says so
        return None


def run(cmd: list[str], timeout: float = 20.0, cwd: str | None = None) -> str:
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, cwd=cwd,
                           env={**os.environ, "NO_COLOR": "1", "TERM": "dumb"})
        return (p.stdout + p.stderr).strip()
    except (OSError, subprocess.SubprocessError):
        return ""


def first_line(text: str) -> str:
    for line in text.splitlines():
        if line.strip() and not line.startswith("Picked up"):
            return line.strip()
    return ""


def versions(root: Path, health: dict | None, ollama: str) -> list[str]:
    out = []
    commit = run(["git", "-C", str(root), "describe", "--always", "--dirty", "--tags"])
    branch = run(["git", "-C", str(root), "rev-parse", "--abbrev-ref", "HEAD"])
    if commit and "fatal" not in commit:
        out.append(f"marvin:   {first_line(commit)} ({first_line(branch) or '?'})")
    else:
        out.append("marvin:   not a git checkout")
    out.append(f"host:     {health.get('version', '?') if health else 'not running'}")
    out.append(f"system:   {platform.system()} {platform.release()} ({platform.machine()})"
               + (f", macOS {platform.mac_ver()[0]}" if platform.system() == "Darwin" and platform.mac_ver()[0] else ""))
    java = os.environ.get("MARVIN_REPORT_JAVA") or shutil.which("java")
    if java:
        out.append(f"java:     {first_line(run([java, '-version']))}")
    out.append(f"python:   {platform.python_version()} ({sys.executable.replace(str(Path.home()), '~')})")
    venv = root / "host/.venv/bin/python"
    if venv.exists():
        out.append(f"venv:     {first_line(run([str(venv), '--version']))}")
    if shutil.which("docker"):
        v = first_line(run(["docker", "version", "--format", "{{.Server.Version}}"], timeout=8))
        out.append(f"docker:   {v if v and 'error' not in v.lower() and 'cannot' not in v.lower() else 'not running'}")
    else:
        out.append("docker:   not installed")
    out.append(f"ollama:   {ollama}")
    if shutil.which("pio"):
        out.append(f"pio:      {first_line(run(['pio', '--version'], timeout=10))}")
    return out


def ollama_info() -> tuple[str, list[str]]:
    url = os.environ.get("OLLAMA_HOST", "http://localhost:11434")
    if not url.startswith("http"):
        url = "http://" + url
    v = get_json(url, "/api/version", 2)
    if not v:
        return "not answering on " + url, []
    ps = get_json(url, "/api/ps", 2) or {}
    loaded = [f"{m.get('name')} ({m.get('size_vram', m.get('size', 0)) / 1e9:.1f} GB, until {m.get('expires_at', '?')[:19]})"
              for m in ps.get("models", [])]
    return v.get("version", "?"), loaded


def latency_lines(path: Path, n: int = 20) -> list[dict]:
    rows: deque = deque(maxlen=n)
    if not path.is_file():
        return []
    with path.open(encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line.startswith("{"):
                continue
            try:
                d = json.loads(line)
            except ValueError:
                continue
            if isinstance(d, dict) and "total_ms" in d:
                rows.append(d)
    return list(rows)


def percentile(values: list[float], p: float) -> float:
    s = sorted(values)
    if not s:
        return float("nan")
    k = (len(s) - 1) * p
    lo, hi = int(k), min(int(k) + 1, len(s) - 1)
    return s[lo] + (s[hi] - s[lo]) * (k - lo)


def tail_lines(path: Path, n: int = 4000) -> list[str]:
    if not path.is_file():
        return []
    with path.open(encoding="utf-8", errors="replace") as f:
        return list(deque(f, maxlen=n))


SPRING_LINE = re.compile(r"^(\d{4}-\d\d-\d\d)T(\d\d:\d\d:\d\d)\S*\s+(\w+)\s+\d+\s+---\s+(?:\[[^\]]*\]\s*)*(\S+)\s*:\s(.*)$")


def compact(line: str) -> str:
    """A Spring Boot log line without its pid, thread and trace columns."""
    m = SPRING_LINE.match(line.strip())
    if m:
        return f"{m.group(1)} {m.group(2)} {m.group(3)} {m.group(4)}: {m.group(5)}"
    return " ".join(line.split())


def last_errors(lines: list[str], n: int = 20) -> list[str]:
    picked = [compact(l) for l in lines if ERROR_LINE.search(l)]
    return picked[-n:]


# ------------------------------------------------------------------------------------------- the report

def report(args) -> str:
    root = Path(args.root).resolve()
    data = Path(args.data_dir).expanduser()
    base = f"http://127.0.0.1:{args.port}"
    online = not args.offline
    health = get_json(base, "/api/health") if online else None
    settings = get_json(base, "/api/settings") if health else None
    owner = []
    if isinstance(settings, dict):
        o = settings.get("owner") or (settings.get("settings") or {}).get("owner")
        if isinstance(o, dict) and o.get("name"):
            owner = [o["name"]] + o["name"].split()
    red = Redactor(owner)
    ollama, loaded = ollama_info() if online else ("not checked (--offline)", [])
    out = [f"# Marvin report, {time.strftime('%Y-%m-%d %H:%M %Z')}", "",
           "(Made by ./marvin report: no conversations, memory, names, Wi-Fi names or full addresses. "
           "Read it before you paste it.)", ""]

    def section(title: str, lines: list[str], raw: bool = False) -> None:
        out.append(f"## {title}")
        out.append("")
        out.extend((clip(l) if raw else red(clip(l))) for l in (lines or ["(nothing)"]))
        out.append("")

    section("Versions", versions(root, health, ollama), raw=True)  # only version strings, home already hidden

    host = []
    if health:
        host.append(f"status: {health.get('status')}, mode: {health.get('mode')}")
        for name, c in (health.get("components") or {}).items():
            host.append(f"  {name}: {c.get('state')}" + (f" ({c.get('detail')})" if c.get("detail") else ""))
        voice = get_json(base, "/api/voice")
        if isinstance(voice, dict):
            v, s = voice.get("voice") or {}, voice.get("settings") or {}
            host.append(f"voice: {v.get('state')}/{v.get('status')}, model {v.get('model') or s.get('llm_model')}, "
                        f"stt {s.get('stt')}{' ' + s['stt_model'] if s.get('stt_model') else ''}, tts {s.get('tts')}, "
                        f"wake {show(s.get('wake'))}"
                        + (f", error: {v.get('error')}" if v.get("error") else ""))
    else:
        host.append(f"not answering on {base} (./marvin status, ./marvin logs)")
    if loaded:
        host.append("ollama loaded: " + "; ".join(loaded))
    section("Host", host)

    if args.doctor and (root / "marvin").exists():
        section("Doctor", run(["sh", str(root / "marvin"), "doctor"], timeout=90, cwd=str(root)).splitlines())

    setup = get_json(base, "/api/setup") if health else None
    if isinstance(setup, dict) and isinstance(setup.get("steps"), list):
        section("Setup", [f"{s.get('id')}: {s.get('state')}" + (f" - {s.get('detail')}" if s.get("detail") else "")
                          + (f" (fix: {s.get('fix')})" if s.get("fix") else "") for s in setup["steps"]]
                + [f"done: {setup.get('done')}"])

    robot = get_json(base, "/api/robot") if health else None
    devices = []
    for d in (robot or {}).get("devices", []) if isinstance(robot, dict) else []:
        rates = d.get("rates") or {}
        devices.append(
            f"{d.get('name')} [{d.get('role')}] {d.get('board')}, firmware {d.get('firmware')}, "
            f"{'online' if d.get('online') else 'offline'} (age {d.get('age_s')} s), uptime {d.get('uptime_s')} s, "
            f"RSSI {show(d.get('rssi'))} dBm, {'simulated' if d.get('simulated') else 'real'} sensors, "
            f"loss {d.get('loss_pct')} %, bad {d.get('bad')}, crc {d.get('crc_errors')}, "
            f"rates {', '.join(f'{k} {v}' for k, v in rates.items())}")
    section("Devices", devices if health else ["(host not running)"])

    device_log = get_json(base, "/api/log?source=device&limit=500") if health else None
    entries = (device_log or {}).get("entries", []) if isinstance(device_log, dict) else []
    entries = sorted(entries, key=lambda e: e.get("id", 0))
    self_test = [f"{e.get('device', '?')}: {e.get('text', '')}" for e in entries if SELF_TEST.match(e.get("text", ""))]
    section("Self-test", self_test or ["(no self-test lines from a robot in the host's log: on the bench build "
                                       "they are on the serial console, `pio device monitor -e xiao_bench`)"])
    section("Device log (last 15)", [f"{e.get('device', '?')}: [{e.get('level')}] {e.get('text', '')}"
                                     for e in entries[-15:]])

    lat_path = Path(args.latency) if args.latency else data / "logs" / "latency.jsonl"
    lat = latency_lines(lat_path)
    lines = []
    for d in lat:
        t = time.strftime("%m-%d %H:%M:%S", time.localtime(d["t"])) if isinstance(d.get("t"), (int, float)) else "?"
        extra = {k: v for k, v in d.items() if k.endswith("_ms") and k not in LATENCY_FIELDS}
        lines.append(f"{t} " + " ".join(f"{k}={show(d.get(k))}" for k in LATENCY_FIELDS)
                     + ("".join(f" {k}={show(v)}" for k, v in extra.items())))
    totals = [d["total_ms"] for d in lat if isinstance(d.get("total_ms"), (int, float))]
    if totals:
        lines.append(f"first word over {len(totals)} turns: p50 {percentile(totals, .5):.0f} ms, "
                     f"p90 {percentile(totals, .9):.0f} ms (v1 target: p50 <= 2500, p90 <= 4000)")
        if len(totals) >= 2:
            lines.append(f"mean {statistics.mean(totals):.0f} ms, worst {max(totals):.0f} ms")
    section("Latency (last 20 turns)", lines or [f"(no latency lines in {lat_path})"])

    errors = [l.rstrip("\n") for l in last_errors(tail_lines(Path(args.log)))] if args.log else []
    host_log = get_json(base, "/api/log?limit=500") if health else None
    api_errors = [f"[{e.get('source')}] {e.get('level')}: {e.get('text')}"
                  for e in sorted((host_log or {}).get("entries", []), key=lambda e: e.get("id", 0))
                  if e.get("level") in ("warn", "warning", "error") and e.get("source") != "brain"][-10:] \
        if isinstance(host_log, dict) else []
    section("Last errors and warnings", (errors[-20:] + api_errors) or ["(none)"])
    return "\n".join(out).rstrip() + "\n"


def main(argv: list[str] | None = None) -> int:
    here = Path(__file__).resolve().parent.parent
    ap = argparse.ArgumentParser(description="Marvin's report: one paste, nothing personal.")
    ap.add_argument("--root", default=os.environ.get("MARVIN_REPORT_ROOT", str(here)))
    ap.add_argument("--port", default=os.environ.get("MARVIN_PORT", "8765"))
    ap.add_argument("--data-dir", default=os.environ.get("MARVIN_DATA_DIR", "~/.local/share/marvin"))
    ap.add_argument("--log", default=os.environ.get("MARVIN_REPORT_LOG", ""), help="the host's log file")
    ap.add_argument("--latency", default=os.environ.get("MARVIN_LATENCY_LOG", ""),
                    help="the latency lines (default DATA_DIR/logs/latency.jsonl)")
    ap.add_argument("--no-doctor", dest="doctor", action="store_false", help="skip ./marvin doctor")
    ap.add_argument("--offline", action="store_true", help="do not ask the host or Ollama")
    ap.add_argument("--out", help="also write the report to this file")
    args = ap.parse_args(argv)
    text = report(args)
    sys.stdout.write(text)
    if args.out:
        Path(args.out).write_text(text, encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
