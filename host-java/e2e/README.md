<!-- SPDX-License-Identifier: CC-BY-4.0 -->
# End-to-end check of the whole host

Three small scripts that drive a running host (Java or Python) the way a person would, without
hardware, Ollama or a microphone. They were used for the final verification of the Java host
(see [NOTES.md](../NOTES.md#final-verification)).

| Script | What it is |
|---|---|
| `stub_ollama.py [PORT] [LOG]` | A stand-in for Ollama on `PORT` (default 11434): `/api/tags`, `/api/show`, `/api/chat` streamed. A question with "weather" gets a `get_weather` tool call, then an answer that leaks reasoning before a lone `</think>`; "think" gets a `<think>…</think>` block; anything else a short greeting. |
| `second_device.py [SECONDS]` | A simulated ESP32-S3 DevKitC (board 2, has the face screen) that says `HELLO` on 127.0.0.1:47100 and counts what the host sends back (`HOST_ACK`, `FACE_STATE` at 10 Hz, `FACE_EVENT`). |
| `e2e.py [TAG]` | Playwright (Python) walk through every section of the app at 390x844 DPR 2 and 1440x900: Home, Robot (live redraw), History (conversation search, previous day), Settings, then Talk (voice on, typed question, weather tool, both think cases, inspector, Talk now, mute, stop) and a voice setting applied. Screenshots go to `$OUT` (default `final-shots/`); it fails on any console error. |

```bash
python3 host-java/e2e/stub_ollama.py &                    # instead of Ollama
MARVIN_VOICE_ARGS=--fake ./marvin up                      # the voice sidecar in its test mode
(cd host && .venv/bin/python -m marvin_host.cli sim --host 127.0.0.1) &
python3 host-java/e2e/second_device.py 600 &
pip install playwright && python3 -m playwright install chromium   # once
python3 host-java/e2e/e2e.py live
```

The checks expect the stub's answers, so run them against `stub_ollama.py`, not a real model.
