"""Shared test setup.

The ears' models (Silero VAD, Smart Turn, the speaker model: voice/models.py) are looked for in
an empty folder, so the engine tests run with the silence timers and no speaker recognition
whatever this computer has downloaded; the tests of the real models find them with
`real_models_dir()` (MARVIN_TEST_MODELS_DIR, else the usual folder) and skip when they are not there.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import os
from pathlib import Path

import pytest

from marvin_host.voice import models

_REAL = Path(os.environ.get("MARVIN_TEST_MODELS_DIR") or models.models_dir())


def real_models_dir() -> Path:
    return _REAL


@pytest.fixture(autouse=True)
def _no_downloaded_models(tmp_path_factory, monkeypatch):
    monkeypatch.setenv("MARVIN_MODELS_DIR", str(tmp_path_factory.mktemp("no-models")))
