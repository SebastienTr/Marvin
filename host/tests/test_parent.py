"""Sidecars exit with the host that started them (marvin_host.parent).

SPDX-License-Identifier: MIT
"""
import io
import os
import signal
import subprocess
import sys
import textwrap
import time

import pytest

from marvin_host import parent


def test_off_unless_asked(monkeypatch):
    monkeypatch.delenv(parent.ENV, raising=False)
    assert parent.watch(io.BytesIO(b"")) is None


def test_exits_at_end_of_input(monkeypatch):
    monkeypatch.setenv(parent.ENV, "stdin")
    codes = []
    t = parent.watch(io.BytesIO(b"ignored"), exit=codes.append)
    t.join(2)
    assert codes == [0]


@pytest.mark.skipif(sys.platform == "win32", reason="POSIX signals")
def test_the_child_exits_when_its_host_is_killed(tmp_path):
    # a stand-in host starts a child the way the Java host does (stdin pipe, MARVIN_EXIT_WITH_PARENT),
    # then dies with SIGKILL: the child must go too
    pidfile = tmp_path / "child.pid"
    child = textwrap.dedent(f"""
        import os, time
        from marvin_host import parent
        parent.watch()
        open({str(pidfile)!r}, "w").write(str(os.getpid()))
        time.sleep(60)
    """)
    host = textwrap.dedent(f"""
        import os, subprocess, sys, time
        env = dict(os.environ, MARVIN_EXIT_WITH_PARENT="stdin")
        subprocess.Popen([sys.executable, "-c", {child!r}], stdin=subprocess.PIPE, env=env)
        time.sleep(60)
    """)
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    env = dict(os.environ, PYTHONPATH=root + os.pathsep + os.environ.get("PYTHONPATH", ""))
    h = subprocess.Popen([sys.executable, "-c", host], env=env)
    try:
        for _ in range(100):
            if pidfile.exists() and pidfile.read_text():
                break
            time.sleep(0.05)
        pid = int(pidfile.read_text())
        os.kill(h.pid, signal.SIGKILL)
        h.wait(5)
        deadline = time.monotonic() + 2.0
        while time.monotonic() < deadline:
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                return
            time.sleep(0.05)
        os.kill(pid, signal.SIGKILL)
        pytest.fail("the child outlived its killed parent by more than 2 s")
    finally:
        if h.poll() is None:
            h.kill()
