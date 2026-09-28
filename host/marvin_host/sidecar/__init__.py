"""Sidecars: the Python processes Marvin's Java core starts beside it (docs/design.md, section 4).

- `voice`: the real-time audio loop (listening, wake word, speech recognition, speech synthesis,
  playback), behind the gRPC contract `marvin.voice.v1` (python -m marvin_host.sidecar.voice).

SPDX-License-Identifier: MIT
"""
