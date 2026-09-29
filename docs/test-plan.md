<!-- SPDX-License-Identifier: CC-BY-4.0 -->
# Test plan: the Java host on a Mac

A manual check of the Java host (`./marvin`) with the real boards, the real voice and a real model,
before it replaces `marvin-host run` for good. About an hour. Each check says what to do and what
you should see; write down any difference (and the last lines of `./marvin logs -n 100`).

The setup this plan assumes: an Apple Silicon Mac (M3 Max) on the same 2.4 GHz Wi-Fi as the boards,
Ollama with the model `qwen3.8:27b-mlx`, a Wemos D1 mini and an ESP32-S3 DevKitC running the firmware
with simulated sensors, and `host/.venv` already set up for the Python host.

## 0. Before you start (once)

1. Update the repository: `git pull`.
2. Keep a copy of the Python host's history, in case: `cp ~/.local/share/marvin/marvin.db ~/marvin-before-java.db`.
3. Stop the Python host if it runs (`Ctrl+C` in its terminal): only one host can own UDP port 47100.
4. Start Docker Desktop (optional: without it the host runs its own PostgreSQL; the first choice is kept).
5. Start Ollama (the app, or `ollama serve`) and check the model: `ollama list` shows `qwen3.8:27b-mlx`.
6. `./marvin doctor`. Expected: `ok` lines, possibly `note` lines, and `Ready: ./marvin up` at the end.
   A missing JDK is not an error: `./marvin up` downloads one into `~/.local/share/marvin/jdk`.

## 1. Start and stop

| # | Do | Expected |
|---|---|---|
| 1.1 | `./marvin up` | The first time: JDK download, Java build (a few minutes), `Starting PostgreSQL ...`, `Starting Marvin's host (live) ...`, then `Marvin's app: http://localhost:8765/` and a phone address with `?token=`. The prompt comes back. |
| 1.2 | `./marvin status` | `host: running (pid ..., live)`, a `health:` line where `database`, `robot` and `voice` are `up` (`robot` says `no robot yet` until a board links), `postgres: running`. |
| 1.3 | Open http://localhost:8765/ | The app, header `Marvin`, a connection dot. No error banner. |
| 1.4 | `./marvin up` again | `Marvin's host is already running (pid ...)`; nothing restarts. |
| 1.5 | `./marvin restart` | The host stops and starts again in a few seconds; the app reconnects by itself (header dot back to Live). |
| 1.6 | `./marvin down`, then `./marvin status` | `Stopped.`; status says `host: not running`. The app shows it is disconnected. |
| 1.7 | `./marvin up`, then `./marvin logs -n 30` | The last log lines, no `ERROR`. |

## 2. Robot link

| # | Do | Expected |
|---|---|---|
| 2.1 | Power the Wemos D1 mini | Within 2 s its LED stops blinking fast and flashes every 2 s. App, **Robot** tab: the device list shows `marvin-xxxxxx`, `Wemos D1 mini (ESP8266)`, simulated. Home: an event `Connected to the robot`. |
| 2.2 | Robot tab, lidar and radar views | The lidar top view is redrawn several times a second, the radar shows a moving target, the vital signs sparklines move. The header shows the `Simulated sensors` badge. |
| 2.3 | Power the ESP32-S3 DevKitC | A second device `ESP32-S3 DevKitC` in the list (`2 of 2 connected`). Its screen shows Marvin's face, which follows the presence state (eyes open when someone is there, reacts to events). |
| 2.4 | `./marvin status` | `robot` is `up` and names a linked device. |

## 3. Presence (with the simulated sensors)

The simulated person comes in, sits down, stands up and leaves in a loop of about a minute and a half.

| # | Do | Expected |
|---|---|---|
| 3.1 | Watch **Home** for two minutes | The status line changes (`You came in`, `You just sat down`, `You're here, up and about`...), the **Recent** list gets `You came in`, `You sat down`, `You stood up after ...`, `You left`, with the time. Vital signs (breathing, heart) appear while seated. |
| 3.2 | Home, **Break** card | While seated, `N min of 50 min` counts up (the break interval from Settings). |
| 3.3 | Home, **Today** card | Seated time and sessions grow; the day's bar gets the new sessions. |

## 4. The app, section by section (on the Mac and on the phone)

| # | Do | Expected |
|---|---|---|
| 4.1 | On the phone, open the address `./marvin up` printed (`http://<Mac address>:8765/?token=...`) | The same app, bottom tab bar. It keeps working after reloading the page without `?token=` (the key is kept in a cookie). |
| 4.2 | On the phone, the same address without `?token=` in a private tab | Refused (the access key is needed from other devices). |
| 4.3 | **Home** | Face, status, break, today, recent events; on the Mac the conversation panel on the right. |
| 4.4 | **Robot** | Devices, lidar, radar, vital signs, and the host log at the bottom (new lines appear by themselves). |
| 4.5 | **History** | The day (bar, seated time, sessions, breaks), `<` goes to the previous day, the week chart below, and the day's conversation. |
| 4.6 | History, search box: type a word you said to Marvin before | The matching sentences from all days, with their date; clearing the box shows the day again. |
| 4.7 | **Settings** | General (break interval, quiet hours, sounds), Voice (model, speech recognition, voice, wake word, listening time, tools...), Data. |

## 5. Voice

Turn the voice on in the **Talk** panel (the switch); the first time it takes a few seconds (models load).

| # | Do | Expected |
|---|---|---|
| 5.1 | Settings > Voice, model: pick `qwen3.8:27b-mlx`, **Save** | `Saved`, the voice restarts with it; the Talk panel shows the model's name. |
| 5.2 | Wake word: say "Marvin, bonjour" | Your sentence appears (`bonjour`), Marvin answers aloud in French, the answer appears as it is spoken. |
| 5.3 | Follow-up: right after the answer, ask another question without "Marvin" | Marvin answers (the listening window stays open `follow_up_s` seconds, shown by the listening bar). |
| 5.4 | Press **Talk now**, ask a question without "Marvin" | The button shows `Listening`, a countdown bar; Marvin answers. |
| 5.5 | Type a question in `Ask Marvin something`, press the arrow | It appears marked `typed`; Marvin answers aloud and in text. |
| 5.6 | Tools: "Marvin, quel temps fait-il à Nice demain ?" | A short `Je regarde…`, then the real forecast for Nice (compare with a weather site). |
| 5.7 | Think leak: ask two or three weather questions in a row, and one that needs reasoning ("Marvin, combien font 17 fois 23 ?") | Marvin never says his reasoning aloud ("Okay, the user asks..."), never says `think`. If a model leaks, only the answer is spoken. |
| 5.8 | Inspector: click the time under one of Marvin's answers (e.g. `2.9 s`) | It opens: what you said, **Where the time went** (end of speech, recognition, model, synthesis), **What Marvin knew** (time, presence, vital signs), the tool calls with their results, the prompt. |
| 5.9 | **Mute** | The button is pressed, "Marvin, ..." is no longer heard; press again to unmute. |
| 5.10 | Ask a long question ("Marvin, raconte-moi une histoire"), press **Stop** while he speaks | He stops within a moment; the answer is marked `interrupted`. |
| 5.11 | Interrupt by voice: while he speaks, say "Marvin, stop" | Same as 5.10 (barge-in). |
| 5.12 | Turn the voice off with the switch | Status `off`; turning it back on works. |

## 6. Settings

| # | Do | Expected |
|---|---|---|
| 6.1 | General: set the break interval to 45, **Save** | `Saved`; Home's break card says `of 45 min`. |
| 6.2 | Voice: change the listening time (`follow_up_s`) to 8, **Save** | `Saved`; after an answer the listening bar lasts about 8 s. `cat ~/.config/marvin/voice.json` shows `"follow_up_s": 8.0`. |
| 6.3 | Voice: turn tools off, **Save**, ask the weather | No tool call in the answer's inspector and no `Je regarde…` (the model answers on its own, possibly saying it cannot check). Turn tools back on. |
| 6.4 | Voice: type a model name with a space (`bad model`), **Save** | `llm_model must be an Ollama model name, e.g. qwen3:4b-instruct` under the form, nothing saved. |

## 7. Persistence and import

| # | Do | Expected |
|---|---|---|
| 7.1 | First `./marvin up` only: `./marvin logs -n 500 \| grep imported` | `imported .../marvin.db: N events, N samples, N conversation`: the Python host's history is in PostgreSQL. |
| 7.2 | History: go back to days before the switch | The same days, bars and conversations as the Python host showed. |
| 7.3 | `./marvin down`, `./marvin up`, open History and search a sentence from section 5 | Still there; settings from section 6 kept; the voice comes back in the state you left it (on or off). The log does not import again. |
| 7.4 | Reboot the Mac (with Docker Desktop), `./marvin up` | Same data (Docker starts if it is not running; the host never opens an empty second database). |

## 8. Failure cases

| # | Do | Expected |
|---|---|---|
| 8.1 | Quit Ollama, ask a typed question | Marvin says `Je n'arrive pas à joindre mon modèle de langage. Ollama est-il lancé ?` (or the English one), with how to start Ollama under the answer; Settings > Voice says Ollama is not running. Start Ollama again: the next question is answered, no restart needed. |
| 8.2 | Unplug the D1 mini (and the S3) | After about 10 s the status says `Marvin is offline`, the devices show as not connected. Plug it back in: `Connected to the robot`, events resume. |
| 8.3 | Kill the voice: `pkill -f marvin_host.sidecar.voice` | The Talk panel shows `starting` for a moment, then the voice is back on by itself within about 2 s. The log says `restarting the voice sidecar`. |
| 8.4 | Kill the host hard: `kill -9 $(cat ~/.local/share/marvin/run/host.pid)` | Within 3 s no `marvin_host` Python process is left (`pgrep -fl marvin_host` is empty). `./marvin up` starts again cleanly. |
| 8.5 | Stop the database: `docker stop marvin-postgres`, wait 20 s, `docker start marvin-postgres` | The robot views keep moving; `curl -s localhost:8765/actuator/health/readiness` says `DOWN` then `UP`; the events of that time appear in Recent afterwards. |

## 9. Performance

| # | Do | Expected |
|---|---|---|
| 9.1 | Ask 5 short spoken questions; read the time under each answer | First word about 2.8-3.2 s with the 27B model and Piper, about 4.4-4.7 s with `say` ([voice.md](voice.md#latency)); the first question after a start can be slower. |
| 9.2 | Same 5 questions on the Python host (section 10) | The Java host is not slower by more than about 0.1 s on average. |
| 9.3 | `ps -o rss=,%cpu= -p $(cat ~/.local/share/marvin/run/host.pid)` at idle, robot linked | About 250 MB (the first number, in KB), CPU a few percent at most. |
| 9.4 | What memory costs the first word. Start with `MARVIN_JAVA_OPTS="-XX:+UseSerialGC -Xmx384m -Dmarvin.memory.read=false" ./marvin up`, ask the same 20 short questions (typed in Talk, so that recognition is out of the measure), note each reply's first-word time; then `./marvin up` (memory read on) and the same 20 questions, with some facts and yesterday's summary in memory. In each reply's inspector read "memory" (tokens sent) and Ollama's `prompt_eval_count`/`prompt_eval_duration`; the host log has one `prompt:` line per question. | The median first word with memory is within 100 ms of the median without it (docs/design.md, phase 3). If not, lower `marvin.memory.volatile-budget` (tokens, default 250) and note the prompt evaluation rate (tokens per second) of the voice model. |
| 9.5 | Ask a question while a memory pass runs: `curl -s -X POST localhost:8765/api/memory/consolidate -H 'Content-Type: application/json' -d '{"pass":"nightly"}'`, then speak at once | The pass yields (Memory > Memory at work: "yielded"), `ollama ps` shows the night model's request gone within a second, and the first word is not more than about 0.3 s later than usual. |

## 10. Going back to the Python host

The Python host is kept and still works on its own SQLite file.

```bash
./marvin down                                   # frees UDP 47100 and port 8765
cd host && source .venv/bin/activate
marvin-host run --voice                         # as before: viewer, app on :8765, voice
```

What it shows: its own history up to the day of the switch (`~/.local/share/marvin/marvin.db`, which
the Java host reads once and never writes). What happened while the Java host ran stays in PostgreSQL
and is not visible there; the voice settings (`~/.config/marvin/voice.json`) and the access key are
shared. To come back: `Ctrl+C`, then `./marvin up` (the import is not repeated: days spent on the
Python host in between are not imported).

To undo everything the Java host added: `./marvin down`, `docker rm marvin-postgres`,
`docker volume rm marvin-pgdata` (or `rm -rf ~/.local/share/marvin/pg` without Docker), and
`rm ~/.local/share/marvin/db_mode`.

## Without the hardware

The same checks run without boards, model or microphone (this is how they were run before release):
`host-java/e2e/README.md` has a stand-in Ollama, a second simulated device and a scripted walk
through the app; `./marvin demo` runs a simulated robot and a simulated past week on its own
database.
