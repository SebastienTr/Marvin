<!-- SPDX-License-Identifier: MIT -->
# Marvin's app

Marvin comes with a small web app that runs on your computer, next to `marvin-host`. You open
it in a browser, on the computer or on your phone. The Rerun viewer is the developer's view of
the sensors. The app is the owner's view and Marvin's control center: how Marvin is doing, how
long you have been sitting, your day and week, the conversation with Marvin (the voice is turned
on, set up and used from here), the robot's devices and sensors, and a log of what happened.
The terminal stays quiet: it only prints the app's address, devices connecting and disconnecting,
the voice turning on and off, and warnings.

<p align="center">
  <img src="images/ui_phone.png" alt="Marvin's app on a phone: the live face, 'You've been at your desk for 38 min', breathing and heart rate, the break timer at 38 of 50 minutes, and the start of today's timeline" width="300">
</p>

<p align="center">
  <img src="images/ui_desktop.png" alt="Marvin's app on a desktop browser: the face and break timer on the left, today's timeline and recent events in the middle, and the conversation with Marvin on the right, Marvin listening" width="800">
</p>

<p align="center">
  <img src="images/ui_conversation.png" alt="The Talk panel on a phone: Marvin's small live eyes next to the title, a Simulated sensors badge in the header, the conversation with a sentence Marvin did not answer and why, Marvin writing his answer word by word as he says it, the listening strip showing his voice, the Talk now, Mute and Stop buttons, and a line with what Marvin knows right now above the text box" width="300">
</p>

<p align="center">
  <img src="images/ui_robot.png" alt="The Robot panel: the robot and the vital signs radar with board, firmware, Wi-Fi signal, link and rates; a lidar top view of the room; the LD2450 radar's field of view with the person; breathing and heart rate charts" width="800">
</p>

## Try it

```bash
marvin-host ui --demo --open        # a simulated robot and a simulated past week, time 10x faster
marvin-host ui --demo --speed 60    # faster: a minute of sitting per second
```

The demo keeps nothing: its history lives in a temporary folder that is deleted when you stop it
(Ctrl-C).

The demo also plays the robot and the vital signs radar for the Robot panel, and, when there is
no language model to talk to, a scripted conversation (typed questions get canned answers from
the simulated brain), with a few past days of conversation to browse and search in History.
With Ollama and the `voice` extra installed, the demo offers the real voice instead, off until you
turn it on.

With the real robot, the app starts with `marvin-host run` (turn it off with `--no-ui`).
`marvin-host ui` runs the brain and the app without the Rerun viewer, which is lighter for
everyday use.

## Layout

On a phone, a tab bar at the bottom: **Home**, **Talk**, **Robot**, **History**, **Settings**. On a
computer the tabs move to the top; on a wide screen (1200 px and more) Home shows the conversation
beside the face and the day, so the Talk tab disappears.

## What it shows

| Part | Content |
|---|---|
| **Home** | |
| Face | Marvin's eyes, live, drawn by the same code and from the same brain state as the robot's screen. |
| Status | One sentence: "You've been at your desk for 42 min", "You're here, up and about", "Marvin is asleep. Nobody around.", "Marvin is offline". Breathing and heart rate appear under it only while the radar reads them reliably (you are seated and still). |
| Break | How long you have been sitting, against the break reminder (50 minutes by default). The bar turns orange when it is time to stand up. Standing up resets it. |
| Today | A timeline of the day: when you were around (dark), when you were seated (light), and break reminders (orange dots). Below it: time seated, sitting sessions, breaks, longest streak, when you arrived and last left, and your average breathing and heart rate. |
| Recent | What happened, in plain words: you came in, sat down, stood up after 47 minutes, break reminders. |
| Header | The connection ("Live", "Reconnecting", "Robot offline") and, when the robot says its sensor data is simulated (a D1 mini, `marvin-host sim`, the demo), a calm **Simulated sensors** badge: hover, focus or tap it to read that the person, the room and the vital signs come from a simulated scene, not from real sensors. |
| **Talk** | |
| Marvin's eyes | A small live rendering of Marvin's face, drawn in the browser with the same shapes and colours as the robot's screen ([`face.py`](../host/marvin_host/face.py)), at the screen's refresh rate: sleepy while the voice is off, calm and blinking while it waits, attentive and a little wider with your voice while it listens (with a thin orange ring), glancing up and aside while it thinks, moving with its own voice while it speaks. With reduced motion, the eyes only change expression. |
| Listening strip | Above the buttons, a wave of what the microphone hears (orange while Marvin listens, grey while it waits for its name) or of Marvin's own voice while he speaks, with a word on what is going on: "Say “Marvin, …”", "Listening…", "Understanding…", "Thinking…", "Stopped listening" when the listening window closes. |
| Conversation | Live: while you speak, your bubble forms with a small wave and the words understood so far (dashed until it knows you talk to Marvin), then shimmers while the words are understood; Marvin's bubble shows three dots while he thinks, then writes itself word by word as he says it. What Marvin heard (typed questions say so), what it answered, what it said on its own (a break reminder, dashed), and, discreetly, what it heard but did not answer. Consecutive ignored sounds and sentences fold into one line ("3 sounds ignored", "2 sentences not answered", which grows as more come); open it to see each one, why it was not answered ("known hallucination", "own voice", "too quiet", "conversation closed") and how loud it was (dBFS). The conversation is kept with the history: it is still there after a restart. |
| Why Marvin said that | Tap an answer, or the time under it (like `1.2 s`), to open its inspector: what Marvin heard (and the raw transcript when it differs, e.g. with "Marvin," in front), where the time went as a small bar (end of speech, recognition, model, first sentence, synthesis, with the numbers), what Marvin knew when it answered (the facts of the context block sent with the question: the time, presence, how long you have been seated, breathing and heart rate, simulated sensors), the model and the language, and the exact message sent to the model. |
| Now | One discreet line above the text box: what Marvin knows right now (you are here or seated for how long, how far, breathing and heart rate when the radar reads them, "simulated" when it is). On a narrow screen the least useful parts go first. Tap it to open the Robot panel. |
| Sounds | Short soft notes played by the browser (synthesised, no audio files): two rising notes when listening opens, two falling ones when it closes without a question, a low blip when Marvin did not catch what you said while listening, a faint tick when an answer starts. Marvin already chimes on the computer's speaker when it starts listening, so the browser's "listening" note only plays when this page pressed **Talk now** (you hear at once that it worked), or when the voice's own chime is turned off (`"chime": false` in `voice.json`); the follow-up window after each answer makes no sound. Browsers allow sound only after a gesture: nothing plays before your first click or key press on the page. On by default; **Settings** turns them off. |
| Controls | **Voice** on and off without restarting `marvin-host` (it is remembered: the voice starts with `marvin-host` next time). **Talk now**: a listening window without saying "Marvin", as if you had just said the name: whatever you say next is the question, even a single word. The button turns orange while Marvin listens, a thin line under the wave shows the time left (it waits while you talk), and pressing it again stops listening. **Mute**: the microphone is ignored (reminders and typed questions still work). **Stop**: Marvin stops talking. The text box asks a question in writing; Marvin answers aloud and in the conversation. |
| Problems | If the voice cannot start, the panel says why and how to fix it: the `voice` extra is not installed (`pip install -e ".[voice]"`), no microphone, Ollama not running (`ollama serve`), the model not pulled (`ollama pull qwen3:4b-instruct`). **Try again** after fixing it. |
| **Robot** | |
| Devices | Each device that said hello: the robot, the MR60BHA2 vital signs radar, the simulator. Board, firmware, address, uptime, Wi-Fi signal (bars and dBm), link (live, or how long since the last packet: a device that sends nothing for 6 s is offline), datagrams per second, loss, CRC errors, lidar scans per second and points, radar frames per second, and what it has (camera, speaker and microphone, simulated data). |
| Lidar | A top view of the last scan (one range per degree), rings every metre, the robot in the middle facing up, and the person the LD2450 follows (orange) with their path over the last five seconds. |
| Radar | The LD2450's field of view (±60°) with the people it tracks and their paths. |
| Vital signs | Breathing and heart rate over the last five minutes, and their waves over the last fifteen seconds, while the MR60BHA2 can read them. |
| Log | The brain's events, the devices' own messages (`LOG`, e.g. "firmware up"), devices connecting and disconnecting, the voice turning on and off, and the host's warnings and errors, newest first. Filter by Marvin, Devices, Voice or Warnings. |
| **History** | |
| Day | Any day's timeline and numbers; the arrows go back in time. |
| Last 7 days | Time seated per day. Tap a day to see its timeline. |
| Conversations | The conversation of the day shown above, with the same bubbles as Talk (read-only; answers still open their inspector), and a search over everything said on any day: each matching line with its date and time; tap one to jump to that day and to the line. |
| **Settings** | |
| Breaks, clock and sounds | Break reminder interval, quiet hours, 12- or 24-hour clock, and the app's sounds (applied at once). |
| Voice | Language model (the models installed in Ollama), speech recognition (MLX or faster-whisper, and the Whisper model), speech (say, Piper, espeak-ng, and the voice: installed Piper voices, a few to download, and the macOS voices), language (auto, French, English), waiting for "Marvin", the follow-up window, and spoken break reminders. Saved to `voice.json`, the file `marvin-host talk` reads too; **Apply** restarts the voice with them. See [voice.md](voice.md). |

A few definitions:

- **Time seated** runs from the moment Marvin decides you sat down to the moment you stand up or
  leave (the `sat_down`, `stood_up` and `left` events, see
  [`brain.py`](../host/marvin_host/brain.py)).
- **Sessions** join sittings separated by less than a minute: getting up to grab something does
  not start a new session. **Breaks** are the gaps between sessions; **longest streak** is the
  longest session.
- When Marvin loses contact with the robot, or `marvin-host` stops, the timeline has a hole
  rather than a made-up block. After a crash, the time is counted up to the last sign of life.

## Open it on your phone

When it starts, `marvin-host` prints two addresses:

```
Marvin's app: http://localhost:8765/
  on a phone on the same Wi-Fi: http://192.168.1.23:8765/?token=q1w2e3r4t5y6u7i8
```

Open the second one on a phone connected to the same Wi-Fi. The browser remembers the access
key (a cookie), so after the first visit `http://192.168.1.23:8765/` is enough. You can add it to
the home screen; it opens full screen like an app.

If the phone cannot reach it, check that both are on the same network (not a guest Wi-Fi) and
that the computer's firewall lets Python accept incoming connections (macOS asks the first time).

## The terminal

`marvin-host run` prints only what you need to know; the app shows the rest:

```
Marvin's app: http://localhost:8765/
  on a phone on the same Wi-Fi: http://192.168.1.23:8765/?token=q1w2e3r4t5y6u7i8
listening for the robot on UDP 47100
+ marvin-a1b2c3 connected (Wemos D1 mini (ESP8266), firmware 0.4.1, simulated sensors) at 192.168.1.40
voice: starting (qwen3:4b-instruct)...
voice: on, say “Marvin, …”
- marvin-a1b2c3 disconnected (nothing received for 6 s)
```

Warnings and errors are printed too (`warning: ...`). `-v` adds the brain's events, the devices'
messages, the conversation (`you: ...`, `marvin: ...` with the time to the first word) and a
sensor summary every second (`--stats`: the summary alone); `-vv` adds debug logs. With `--no-ui`
the terminal is all there is, so it shows the events, the conversation and a summary every 5 s.

## Access key

The app listens on your local network so your phone can reach it. Anyone on the same network
could reach it too, so other devices need the **access key**:

- It is a random key, created on first start and kept in `ui_token` next to the database. Delete
  that file to get a new one (phones will need the new link).
- Your own computer (`localhost`) never needs it.
- `--ui-token off` turns the key off: anyone on the network can then open the app. Only do this
  on a network you trust.
- `--ui-token my-own-key` sets your own.
- `--ui-host 127.0.0.1` makes the app reachable from this computer only.
- `--ui-port 8765` changes the port.

The app also ignores requests addressed to unknown host names (a protection against web pages
that try to reach services on your computer), and only accepts changes sent by the app itself
(JSON from the same origin): settings, the voice's settings, turning the voice on and off,
questions, Talk now, Mute and Stop. All of these need the access key from another device, like
everything else. Anyone with the key can make Marvin listen and speak: keep it to yourself.

The app has no HTTPS: the key and the data travel unencrypted on your local network, like most
home devices. Do not expose the port to the internet.

## Privacy: your data stays on your computer

- Everything is kept in one SQLite file on the computer running `marvin-host`:
  `~/.local/share/marvin/marvin.db` (or `$XDG_DATA_HOME/marvin/`, or the folder in
  `MARVIN_DATA_DIR` if you set it). The settings dialog shows where.
- It holds Marvin's events (arrived, sat down, stood up...), one summary per minute (whether
  someone was there, and the average breathing and heart rate when they were measured), the
  conversation with Marvin (what it heard, what it answered, with the context it was given and
  the timings, what it did not answer and why), and your settings. No images, no sound, no raw
  radar data. The log is kept in memory only, for as long as `marvin-host` runs.
- Nothing is sent anywhere. The app loads nothing from the internet (no fonts, no scripts, no
  analytics) and works without an internet connection.
- To erase the history, stop `marvin-host` and delete the folder:
  `rm -r ~/.local/share/marvin`.

## For developers

The app is in [`host/marvin_host/ui/`](../host/marvin_host/ui): Python standard library only
(`http.server`, Server-Sent Events, `sqlite3`), and plain HTML, CSS and JavaScript with no build
step. The face is drawn with [`face.py`](../host/marvin_host/face.py) and encoded as PNG without an
imaging library.

| Module | Role |
|---|---|
| `server.py` | `UIServer(brain, host, port, store, token, sink, voice)`: HTTP API, live streams, face, settings, log, voice control |
| `sink.py` | `UISink`: a receiver sink that keeps the latest sensor data and counters (O(1) per frame); devices, rates, link state, sensor mini-views |
| `store.py` | `EventStore`: the SQLite file (events, per-minute samples, the conversation, settings); older files are upgraded in place |
| `stats.py` | Pure functions: intervals and daily statistics from events, the sentences |
| `demo.py` | The simulated robot, devices, past week, past conversations and scripted voice for `--demo` |
| `static/` | The page |

```python
from marvin_host import ui
sink = ui.UISink().start()               # in the receiver's sink chain, after the brain
voice = VoiceController(brain)           # marvin_host.voice.control, optional
server = ui.UIServer(brain, sink=sink, voice=voice).start()      # brain: marvin_host.brain.Brain
...
server.stop()
```

| Endpoint | |
|---|---|
| `GET /` | the app |
| `GET /api/state` | `{state, today, settings}`: presence, status sentence, break progress (`state.simulated`: the robot's sensor data is simulated), today's stats |
| `GET /api/day?date=YYYY-MM-DD` | one day's stats and timeline (default: today) |
| `GET /api/history?days=7` | time seated per day, oldest first |
| `GET /api/events?since=ID&limit=50&quiet=1` | recent events, newest first, with a sentence each (`quiet` hides vital-sign events) |
| `GET /api/stream` | Server-Sent Events: `state` (2 per second), `event` (as they happen), `today`, `settings`, `voice` (state and status), `transcript` (one conversation entry), `log` (one log line), `devices` (once a second); while the voice runs, the live signals `level` (microphone loudness, ~16 per second), `utterance` (`start`, `end`, `done`), `partial` (the words understood so far) and `say` (a piece of the reply with its duration and loudness envelope) |
| `GET /face.png` | the face right now (240 × 280 PNG); `503` if it cannot be drawn |
| `GET`, `POST /api/settings` | settings (`break_interval_min`, `quiet_hours`, `clock`, `voice`, `ui_sounds`); POST a JSON object with the fields to change |
| `GET /api/robot` | `{devices, scene}`: the devices, and the sensor mini-views (`lidar.ranges_cm`: 360 ranges, one per degree clockwise from the front; `targets` and `trail` in cm, right and forward; `vitals` with `rates` and `waves`) |
| `GET /api/robot/stream` | Server-Sent Events for the Robot panel, opened only while it is on screen: `scene` 4 times a second (vital sign history once a second), `devices` once a second |
| `GET /api/log?source=brain,device&limit=200&since=ID` | the log, newest first; sources `brain`, `device`, `host`, `voice` |
| `GET /api/voice` | `{voice, settings, transcript}`: state (`off`, `starting`, `on`, `stopping`, `error` with `error` and `fix`, or `unavailable`), status, muted; the voice settings; the conversation (today's last 200 entries: `heard` with `raw`, `reply` with `latency`, `context`, `prompt` and `model`, `ignored` with `reason` and `dbfs`, `note`) |
| `GET /api/conversation?day=YYYY-MM-DD` | `{day, entries}`: that day's conversation, oldest first (default: today) |
| `GET /api/conversation?q=text` | `{q, results}`: what was heard or answered containing `text` (case-insensitive, plain text), newest first, at most 100 (`limit`) |
| `GET /api/voice/options` | what the voice settings offer: Ollama's models (or why Ollama cannot be reached), speech recognition backends and models, speech backends (installed or not), Piper and macOS voices |
| `POST /api/voice/on`, `/off` | start or stop the voice (in the background: follow `voice` on the stream) |
| `POST /api/voice/ask` | `{"text": "..."}`: a typed question (at most 500 characters), answered aloud; `409` while the voice is off |
| `POST /api/voice/listen`, `/stop-speaking` | Talk now; Stop |
| `POST /api/voice/mute` | `{"muted": true}` |
| `POST /api/voice/settings` | the voice's settings to change (`llm_model`, `stt`, `stt_model`, `tts`, `tts_voice`, `language`, `wake`, `follow_up_s`, `reminders`, `welcome_back`); saved to `voice.json`, the voice restarts |

Times are Unix seconds from the computer's clock; days are local calendar days. The break
reminder setting also sets the brain's `still_long_s`, so the robot's own reminder follows it.
Tests: `pytest tests/test_ui.py tests/test_ui_control.py tests/test_ui_conversation.py`.
