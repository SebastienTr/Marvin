<!-- SPDX-License-Identifier: CC-BY-4.0 -->
# End-to-end check of the whole host

Three small scripts that drive a running host (Java or Python) the way a person would, without
hardware, Ollama or a microphone. They were used for the final verification of the Java host
(see [NOTES.md](../NOTES.md#final-verification)).

| Script | What it is |
|---|---|
| `stub_ollama.py [PORT] [LOG]` | A stand-in for Ollama on `PORT` (default 11434): `/api/tags`, `/api/show`, `/api/chat` streamed. "Remember that ..." gets a `remember` tool call; a question with "weather" gets a `get_weather` tool call, then an answer that leaks reasoning before a lone `</think>`; "think" gets a `<think>…</think>` block; anything else a short greeting. |
| `second_device.py [SECONDS]` | A simulated ESP32-S3 DevKitC (board 2, has the face screen) that says `HELLO` on 127.0.0.1:47100 and counts what the host sends back (`HOST_ACK`, `FACE_STATE` at 10 Hz, `FACE_EVENT`). |
| `e2e.py [TAG]` | Playwright (Python) walk through the Java host's app: the robot page live, History (search, previous day), Marvin > System (services, the log and its filter), Memory (add, correct, pin, filters, search, forget and its second choice, the profile edited and an older version restored, export, the raw log, settings, a source switched off and on, "forget everything" up to its typed phrase), the lost-connection banner, 320 px without sideways scrolling, the voice (typed question, the weather tool, the remember tool and its chip, the inspector with its memory report, Talk now, mute, stop, a voice setting applied), Home (Auto following the system, the same geometry in Day and Night, the layout kept and reset, a decision kept), then every screen in both appearances at 390x844 DPR 2 and 1440x900. Screenshots go to `$OUT` (default `final-shots/`); it fails on any console error. What it teaches Marvin, it forgets again at the end. |

```bash
python3 host-java/e2e/stub_ollama.py &                    # instead of Ollama
MARVIN_VOICE_ARGS=--fake ./marvin up                      # the voice sidecar in its test mode
(cd host && .venv/bin/python -m marvin_host.cli sim --host 127.0.0.1) &
python3 host-java/e2e/second_device.py 600 &
pip install playwright && python3 -m playwright install chromium   # once
python3 host-java/e2e/e2e.py live
```

The checks expect the stub's answers, so run them against `stub_ollama.py`, not a real model.
