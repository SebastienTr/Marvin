<!-- SPDX-License-Identifier: CC-BY-4.0 -->
# End-to-end check of the whole host

Small scripts that drive a running host the way a person would, without hardware, Ollama or a
microphone. They were used for the final verification of the Java host and of memory v1 (see
[NOTES.md](../NOTES.md), "Final verification" and "Memory v1, Stage: final verification").

| Script | What it is |
|---|---|
| `stub_ollama.py [PORT] [LOG]` | A stand-in for Ollama on `PORT` (default 11434): `/api/tags`, `/api/show`, `/api/chat` streamed, `/api/embed` (deterministic: texts that share words are close). "Remember that ..." gets a `remember` tool call, "forget that ..." a `forget` one, "what do you remember about ..." a `recall` one; a question with "weather" gets a `get_weather` tool call, then an answer that leaks reasoning before a lone `</think>`; "think" gets a `<think>…</think>` block; "where do I live?" is answered from what memory put in the prompt; anything else a short greeting. Memory's jobs (a JSON schema in `format`) get extraction by patterns (a home, a move, a pet, a name, a sister, a preference, a job, an allergy, in French and English), reconciliation (the same statement: NOOP; a new home: INVALIDATE the old one), summaries quoting what the owner said, and a profile rewrite without what ended or was forgotten. With `LOG`, each distinct system prompt is saved next to it (`LOG.system-<hash>.txt`), and each request's hash is logged. `/api/show` lists `vision` for the models in `STUB_VISION` (default `qwen3.8:27b-mlx`); every image a chat request carries is recorded (which message, its size and SHA-256) and listed at `GET /stub/images`, and a question with an image gets an answer about it. |
| `vision.py [BASE_URL]` | Showing Marvin an image, in the app (Playwright), for Day and Night at 1440x900 and 390x844: the image picked (on a phone through **Take a photo** / **Choose a photo**), made small in the browser (1280 px), its preview, the typed question and its bubble with the thumbnail, the inspector's "Image sent", a follow-up that does not send the image again (checked on `stub_ollama.py`'s `/stub/images`), a model that cannot see (refused with the way to Marvin > Voice, and the setting's hint), dropping and pasting (desktop). Needs the voice on with a model the stub says can see (`STUB_VISION`, default `qwen3.8:27b-mlx`) as `llm_model`. Screenshots go to `$OUT` (default `vision-shots/`); it fails on any console error. |
| `second_device.py [SECONDS]` | A simulated ESP32-S3 DevKitC (board 2, has the face screen) that says `HELLO` on 127.0.0.1:47100 and counts what the host sends back (`HOST_ACK`, `FACE_STATE` at 10 Hz, `FACE_EVENT`). |
| `week_fixture.py OUT.db [NOW]` | A Python-host history (`marvin.db`) with a scripted past week: the owner talks with Marvin on six days, in French and English, moves from Nice to Lille, has a cat, a sister, an allergy. Run it with `host/` on the Python path. |
| `week.py learn \| snapshot \| after-restart \| reset` | Memory end to end against `./marvin up` on a fresh database that imported the week (`MARVIN_IMPORT`): facts from both languages, the move invalidating the old home, sources, sensitivity, profile versions, day and week summaries, a second night changing nothing, remember, an idle pass that does not add a known fact twice, retrieval in later answers and the inspector's report (Playwright), recall, the system prompt byte-identical between questions (from the stub's log, `STUB_LOG`), a forget asked by voice and confirmed in the app (its request withheld, not learned again), the next night rewriting the blanked days, export; after a restart, the same memory; then "forget everything". |
| `e2e.py [TAG]` | Playwright (Python) walk through the Java host's app: the robot page live, History (search, previous day), Marvin > System (services, the log and its filter), Memory (add, correct, pin, filters, search, forget and its second choice, the profile edited and an older version restored, export, the raw log, settings, a source switched off and on, "forget everything" up to its typed phrase), the lost-connection banner, 320 px without sideways scrolling, the voice (typed question, the weather tool, the remember tool and its chip, the inspector with its memory report, Talk now, mute, stop, a voice setting applied), Home (Auto following the system, the same geometry in Day and Night, the layout kept and reset, a decision kept), then every screen in both appearances at 390x844 DPR 2 and 1440x900. Screenshots go to `$OUT` (default `final-shots/`); it fails on any console error. What it teaches Marvin, it forgets again at the end. |

```bash
python3 host-java/e2e/stub_ollama.py 11434 /tmp/stub.log &   # instead of Ollama
MARVIN_VOICE_ARGS=--fake ./marvin up                      # the voice sidecar in its test mode
(cd host && .venv/bin/python -m marvin_host.cli sim --host 127.0.0.1) &
python3 host-java/e2e/second_device.py 600 &
pip install playwright && python3 -m playwright install chromium   # once
python3 host-java/e2e/e2e.py live
```

The scripted week, from a fresh database (the variables point the host at a new data folder, which imports the
week once):

```bash
(cd host && .venv/bin/python ../host-java/e2e/week_fixture.py /tmp/week.db)
MARVIN_DATA_DIR=/tmp/marvin-week MARVIN_IMPORT=/tmp/week.db MARVIN_VOICE_ARGS=--fake ./marvin up
STUB_LOG=/tmp/stub.log python3 host-java/e2e/week.py learn
python3 host-java/e2e/week.py snapshot && ./marvin restart && python3 host-java/e2e/week.py after-restart
python3 host-java/e2e/week.py reset
```

The Docker database is the same one for every data folder: use it on a machine whose memory you may lose, or
remove the `marvin-pgdata` volume first.

The checks expect the stub's answers, so run them against `stub_ollama.py`, not a real model.
