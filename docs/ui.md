<!-- SPDX-License-Identifier: MIT -->
# Marvin's app

Marvin comes with a small web app that runs on your computer, next to its host. You open it in a browser, on the
computer or on your phone. It is the owner's view and Marvin's control center: how Marvin is doing, how long you have
been sitting, your day, the conversation with Marvin (the voice is turned on, set up and used from here), what Marvin
remembers and where each memory comes from, the robot's devices and sensors, and a log of what happened.

There are two apps, one per host, on the same API:

- **The Java host's app** (`./marvin up`, `./marvin demo`), described below: the current design, with a Day and a
  Night appearance and six destinations. Its files are in
  [`host-java/marvin-adapter-web/src/main/resources/app/`](../host-java/marvin-adapter-web/src/main/resources/app).
- **The Python host's app** (`marvin-host run`, `marvin-host ui`), unchanged: the Python host stays a tool (the
  simulator, the viewer, replays) and keeps its older app, see [The Python host's app](#the-python-hosts-app).

<p align="center">
  <img src="images/app_home_day.png" alt="The app on a computer, Day appearance: Marvin's face in a pale green orb with 'You're here, up and about', your decision with nothing pending, your day, a small thing remembered with its source" width="800">
</p>

<p align="center">
  <img src="images/app_memory_night.png" alt="Memory on a computer, Night appearance: the Suggested filter with two facts Marvin learned on its own, each with its source, Source &amp; edit, Pin and Keep; Keep all shown; the profile with the owner's lines; the days in Marvin's words" width="800">
</p>

<p align="center">
  <img src="images/app_senses_night.png" alt="Senses on a computer, Night appearance: the Thinks strip (voice idle, the model, reasoning, no task, the speech engines, the last answer's stages), then Sees: the lidar's top view of a cluttered desk at 2 m with the nearest return circled at 13 cm, the range choice, no radar connected, no camera" width="800">
</p>

<p align="center">
  <img src="images/app_talk_night_phone.png" alt="Talk on a phone, Night appearance: Marvin's small face, the conversation with an answer and its 'Why this answer' link, the listening strip, Talk now, Mute and Stop, and the text box" width="300">
</p>

## Try it

```bash
./marvin demo          # a simulated robot and a simulated past week
```

Then open http://localhost:8765/. The demo keeps its own copy of the voice's settings and does not touch yours. With
Ollama running, the voice can be turned on from Talk.

## Layout and appearance

Six destinations: **Home**, **Talk**, **Memory**, **Activity**, **Senses**, **Marvin**. On a phone they are a bar at the bottom,
on a computer a rail on the left; a small count on a destination says something waits for you (on Home: a decision),
a dot on Talk that Marvin is listening or speaking. The top bar has the wordmark with Marvin's eyes, the connection
("Live · on this computer", "Reconnecting", "Robot offline"), a **Simulated** badge when the robot says its sensor
data is simulated (hover, focus or tap it to read what that means), and the appearance.

**Appearance**: **Day** (ivory, olive, matte), **Night** (indigo, lavender) or **Auto**, which follows the device's
light or dark setting, also when it changes while the page is open. Both appearances share the same markup, geometry,
type and behaviour: only colours change. The choice is kept in this browser only (`localStorage`), like the Home
layout; nothing on the host changes.

Every control is reachable with the keyboard (a "Skip to content" link, visible focus rings) and is at least 44 pixels
tall; with reduced motion the eyes only change expression and nothing animates.

## What it shows

| Part | Content |
|---|---|
| **Home** | |
| Here with you | Marvin's eyes, live, in an orb: the robot's own eye geometry (the same expressions as its screen, face.py's table) drawn in the appearance's colours, driven by the robot's expression and, while the voice runs, by the voice (attentive while it listens, glancing up while it thinks, moving with its voice while it speaks). Under them the status sentence ("You've been at your desk for 42 min", "Marvin is offline"), **Talk to Marvin** and **Mute**. |
| Your decision | What waits for your yes, always on Home, whatever you hide. Today: forgetting a memory you asked to forget (by voice or in the app), with **Keep** or **Forget**. When nothing waits: "Nothing needs you." Background tasks and their approvals come later and will wait here too. |
| Your day | Time seated, breaks taken, first hello, and the day's timeline: when you were around, when you were seated, break reminders. |
| A small thing remembered | The latest fact Marvin keeps, with where it comes from and when ("You told Marvin · Today, 11:16"), and the next two; **Read the source & correct** opens it with its sources quoted. |
| Breaks and breathing | The time left before the next break (the bar turns warm when it is time), and breathing per minute with its last ten minutes, marked **Simulated reading** when the sensors are simulated and "no longer live" when the stream stopped. |
| Recent moments | What happened, in plain words. |
| The room | Once a robot has room sensors (the bench board's PIR and DHT11, [wiring-bench.md](wiring-bench.md)): the temperature, the humidity, the light (bright, dim or dark) and the last movement, a dot that lights up while something moves. Marked **Simulated** in the demo, and "no longer live" when the readings are over 2 minutes old. Hidden while no robot sends any. |
| Customize | Show, hide and reorder the modules (buttons, no dragging); kept in this browser; **Reset** restores the suggested layout. It changes only the screen: Marvin keeps sensing and remembering. |
| **Talk** | |
| Conversation | Live: while you speak, your bubble forms with a small wave and the words understood so far, then shimmers while they are understood; Marvin's shows three dots while he thinks, then writes itself word by word as he says it. Typed questions say so; what Marvin said on his own is marked; what it heard but did not answer folds into one discreet line ("3 sounds ignored") that opens to show each one and why, with its loudness. |
| Memory chips | When an answer used a memory tool, a chip under it: "Remembered: …", "Looked in memory", "Waiting for your yes to forget" (which waits on Home). |
| Why this answer | Under each answer, with the time to its first word: the inspector, in a dialog. What Marvin heard (and the raw transcript), what it answered, where the time went (end of speech, recognition, model, tools, first sentence, synthesis), the tools with their arguments and results, the memory that went into the question (the profile's version, each section with its budget and tokens, each candidate with its score, kept or left out, the timings, Ollama's counts), the model and language, and the exact message sent to the model. |
| Showing an image | The image button beside the text box (on a phone: take a photo or choose one), dropping a picture on the conversation or pasting one: made small in the browser, it waits above the text box ("Goes with your next question, typed or spoken", its size, the model that will look at it, remove) and goes with the next question, typed or spoken. The question's bubble shows it; the inspector says what was sent and which model saw it. A model that cannot see images says so, with the way to Marvin > Voice. See [voice.md](voice.md#showing-marvin-an-image). |
| Controls | **Voice on/off** (remembered for the next start), **Talk now** (a listening window without saying "Marvin"; a line shows the time left), **Mute**, **Stop**, and the text box. Above them the listening strip (the microphone's or Marvin's voice as a scrolling wave, with a word on what is going on) and a line with what Marvin knows right now, which opens the robot's sensors. |
| Here, now | Beside the conversation on a computer: presence, the facts the last answer used, the last answer's timing and model. |
| Problems | If the voice cannot start, the panel says why and how to fix it, with **Try again**. |
| Sounds | Short soft notes played by the browser when listening opens and closes, when Marvin did not catch you, when an answer starts; off in Marvin > Preferences. |
| **Memory** | See also [memory.md](memory.md). |
| Your memories | **All memories**, **Pinned**, **Suggested** (learned by Marvin on its own, not reviewed yet) and **Past** (no longer true, or archived), with their counts and a search. Each row: the fact, its status (Yours, Reviewed, Suggested, Pinned...), its sensitivity, where it comes from and when; **Source & edit**, **Pin** / **Unpin**, **Keep** (a suggestion becomes reviewed; **Keep all shown** for the whole list). **Add** stores your own memory at once. |
| Source & edit | The sources quoted and dated (what you said first), each with **Open that conversation**, which opens History at that line; the correction ("What Marvin should remember", and its sensitivity: a new version, the earlier one kept in the fact's history); **Pin**, **Keep as reviewed**, **Archive** / **Bring back**; how often it was used; its other versions. **Forget this fact** is a second, explicit choice: it says what goes (the fact, its versions, its profile lines, for good) and what stays (the conversation, in History). |
| Your profile | What Marvin reads at the start of every conversation, your own lines marked. **Read profile & versions**: every version with its differences (added, removed), **Use this version again**, and **Edit**: the lines you write or change are yours, and every nightly rewrite keeps them. |
| Days, in Marvin's words | The summaries Marvin writes the night after: days, weeks, months, with how many moments they come from; one being rewritten says so. |
| Memory at work | What waits to be read, the last passes and what they did ("3 new facts, 5 days written, in 1.3 s with ..."), the next night, the models, **Consolidate now** and **Run the nightly pass** (both give way to the voice). When the embedding model is missing, a notice at the top of the screen says so, with the command that fixes it and **Check again**. |
| See your memory | Four pictures of what Marvin keeps, as tabs (the last one chosen is remembered in this browser). **Graph**: you at the centre, the people, places and things your facts are about around you (a node's size is its number of facts; a ring: pinned; a dot: sensitive; faded: only past facts), a line per pair linked by facts; select a node for its facts in a side sheet, each with **Source & edit**; zoom and drag when zoomed. **Meaning map**: each current fact placed by its meaning (the host projects the embeddings to two directions), coloured and shaped by kind or by subject, short labels where they fit; facts not embedded yet are listed apart with the reason. **Timeline**: each fact as a bar from when it was true (or learned) to when it ended (or now), a fact and what followed it (a new wording, or the fact that replaced it: "lives in Nice", then "lives in Lille") in one lane, the days and weeks in Marvin's words above; **Week**, **Month**, **Year**, **All**. **Flow**: moments in the log per source, what waits to be read and what was read, the facts (true now, earlier wordings, no longer true, archived, forgotten), what was written from them (profile versions, days, weeks), what the worker is doing now (live), and the last idle and nightly passes with each step's duration. Each picture has one tab stop (arrow keys move inside it, Enter opens), and **Read it as a list** says the same in text. |
| Memory, on your terms | What Marvin may learn from: **Your conversations**, **What Marvin notices** (switched off, nothing from it reaches memory from that moment); **Settings** (consolidating on its own, after how long away, the nightly hour, how long what Marvin noticed is kept); **The raw log** (everything memory was told, searchable, older pages); **Export** (JSON or Markdown); **Forget everything** (what goes and what stays, then the phrase "forget everything" typed; the request expires after five minutes). |
| **Activity** | |
| Today | **Tasks** ([intelligence.md](intelligence.md#thinking-and-background-tasks)): what Marvin works on in the background, live (what it is doing, "Waiting: you are talking with Marvin", the steps of its budget it used, **Cancel**), then what it found: the sentence it says, **Read the result** (its Markdown: headings, lists, tables, links that open outside), and **Steps & reasoning**, an inspector with every request to the model (its reasoning, folded; what it wrote; the tools it called; Ollama's counts), every tool run with its result, and the notes (paused for the conversation, asked to finish); the model and the level it was sent (*High*, sent as `xhigh`). A form gives Marvin a task; a note says when one is done. **Approvals**: today only a request to forget waits for you (decided on Home); anything a task would send outside this computer will wait here. Memory at work, today's moments (the latest twelve, **Show all**). |
| History | Any day's timeline and numbers with arrows back in time, and that day **in Marvin's words** (its summary, or when it will be written); the last seven days with the weeks in Marvin's words; the conversations: that day's (read-only, answers still open their inspector) and a search over everything said. |
| **Senses** | Everything Marvin sees, hears and feels, live and dense, for the curious and the engineer. It has its own stream, open only while the screen is on screen (and the page visible); nothing on it is kept. Each panel says what is missing rather than staying blank. |
| Thinks | A strip at the top: the voice's state (idle, listening, thinking, speaking, or why it is off), the language model and whether it was already loaded at the last answer, how hard it thinks (talk and tasks), the background task running, the speech recognition and speech engines in use, and the last answer's stages from the end of the question to the speaker (end of speech, recognition, model, tools, first sentence, synthesis, speaker), as in the reply inspector. |
| Sees | **Lidar**: the top view (each return a dot sized to the screen, drawn over the robot's marker, the nearest return circled), a range that follows the scan (**Auto**: the smallest of 0.5, 1, 2, 4 or 8 m that holds most returns; a lidar on a cluttered desk is shown at 1 or 2 m, a room at 4) or one chosen by hand (kept in this browser), returns beyond the range as ticks on the edge; the nearest obstacle (cm and bearing), scans per second, points, bearings that returned something, the scan's age. **Radar**: the LD2450's field of view, the people it follows and their last five seconds, each with its distance, bearing and speed. **Camera**: which camera Marvin would use and why, **Take a picture** (one, shown here only), **Live preview** (a picture every 2 seconds while ticked, with a visible "Live preview" line; stopped when you leave the screen), and what the vision model said the last time Marvin looked. |
| Hears | **Microphone**: the level in dBFS (about 16 times a second, with a peak hold and the last ten seconds as a trace, speech shaded), the voice activity (speech, silence, deaf while Marvin speaks, muted), the speech recognition and its last time, the voice activity detector, the last end-of-turn decision (Smart Turn: complete or not, its probability and time; or the silence timers), the last "Marvin" heard, the language and its probability, whose voice (name and score), the device. When the voice is off, it says why. **What it heard**: the last 20 utterances, newest first, with the transcript, what became of each (answered, the name alone, ignored, not judged) and why, its length, loudness, engine and time, language, voice, and the end-of-turn and voice-activity engines. |
| Feels | **Presence**: here or not, seated, the distance; where it comes from (a radar, a motion sensor alone, or no presence sensor: a lidar alone sees shapes, not people) and the evidence, one line each (what the radar sees, how long still or seated, the vital-sign radar, that the camera is never used for presence, simulated data); which sensors are live. **Room**: temperature, humidity, light and motion when a board has them. **Vital signs**: breathing and heart rate when an MR60BHA2 is connected. |
| Devices | Every linked device (robot boards, the USB sensor bridge) with its link (rates, loss, CRC errors, Wi-Fi, uptime, firmware), and where the voice hears and speaks (the computer or a robot) and where its earcons play. |
| **Marvin** | |
| Overview | Robot and voice (connected, simulated or missing; the voice's state and model; which camera Marvin looks through, or why none), Soul and Connections as honest "coming later" entries (no action), and preferences. |
| Robot | A notice when the robot is offline (the views keep its last data), the host does not answer, or the sensors are simulated; each device (board, firmware, address, uptime, Wi-Fi, link, rates, loss), the robot's screen as the host draws it, the lidar's top view (the same view as Senses, its range automatic, the nearest return circled) with the person the radar follows, the LD2450's field of view (or "no radar connected"), breathing and heart rate over five minutes with their waves. Its stream runs only while this page is open. |
| Voice & models | The voice: language model, the model for images (empty: the language model; says whether it can see images), speech recognition (Auto, Parakeet v3, Whisper on the Apple GPU or the CPU), **Names and words** speech recognition must spell right, **Only answer voices Marvin knows**, speech and voice, language, waiting for "Marvin", the follow-up window, spoken reminders, tools and **Internet**, the home location (**Apply** restarts the voice). **Your voice**: the voices Marvin knows (when, on which device; **Delete**), each device's last calibration (noise and voice levels), whether the ears' models are here (Silero VAD, Smart Turn, speaker recognition, Parakeet), and **Calibrate a voice**: a dialog asks for the name, then a quiet moment (with the microphone's level), then three sentences to read one at a time (French and English), showing what was heard of each ("I heard: ...", how close, how loud); at the end the levels and thresholds measured and any warning (a noisy room, a voice too quiet or clipping, a misheard sentence), **Calibrate again**, **Done**. Offered once on the first run, when the voice is on and nothing is calibrated ([voice.md](voice.md#your-voice)). Ollama: reachable or not (with its fix), its models and what each is used for. Memory's models: during the day (empty: the voice's), at night, and the embedding model, with its state. **Camera**: which camera Marvin looks through when asked (Auto, This computer, Robot, Off; **Apply** restarts the voice), what each camera can do now and how to fix it, and **Test camera**, which shows one picture in the panel only ([vision.md](vision.md)). Each picture Marvin takes is said in a short note at the bottom of the screen. |
| Voice & models | The voice: language model, the model for images (empty: the language model; says whether it can see images), speech recognition, speech and voice, language, waiting for "Marvin", the follow-up window, spoken reminders, tools and **Internet**, the home location (**Apply** restarts the voice). Ollama: reachable or not (with its fix), its models and what each is used for. Memory's models: during the day (empty: the voice's), at night, and the embedding model, with its state. **Camera**: which camera Marvin looks through when asked (Auto, This computer, Robot, Off; **Apply** restarts the voice), what each camera can do now and how to fix it, and **Test camera**, which shows one picture in the panel only ([vision.md](vision.md)). Each picture Marvin takes is said in a short note at the bottom of the screen. **Thinking & tasks**: how hard the model thinks when you talk with Marvin (Off by default) and on background tasks (High), and whether Marvin may start tasks; the model's size, whether it can think and its levels, what *High* is sent as; the recommended model for tasks (`qwen3.8:27b-mxfp8`, said, never switched to); whether Ollama answers one request at a time. Saving restarts nothing. |
| System | Each service with its state and, when it is down, why and how to fix it: the database, the robot link, memory, the voice sidecar (**Restart the voice**, the one sidecar the host restarts), Ollama; this computer (version, mode, data folder); the log, filtered by Marvin, Devices, Voice or Warnings and searchable, live. |
| Preferences | Break reminder, quiet hours, 12- or 24-hour clock, sounds, appearance, resetting the Home layout; language, tools and internet, the home place (the voice's settings: saving restarts the voice); where your data is. |

When the host stops answering, every screen says so after a few seconds ("What you see may be out of date") until the
stream is back; lists show a short placeholder while they load, and a panel that cannot be read says why, with **Try
again** where it helps. The old addresses (`#robot`, `#history`, `#settings`, `#activity/log`) still open the right
place.

## Open it on your phone

When it starts, the host (`./marvin up`, or `marvin-host` for the Python host) prints two addresses:

```
Marvin's app: http://localhost:8765/
  on a phone on the same Wi-Fi: http://192.168.1.23:8765/?token=q1w2e3r4t5y6u7i8
```

Open the second one on a phone connected to the same Wi-Fi. The browser remembers the access
key (a cookie), so after the first visit `http://192.168.1.23:8765/` is enough. You can add it to
the home screen; it opens full screen like an app.

If the phone cannot reach it, check that both are on the same network (not a guest Wi-Fi) and
that the computer's firewall lets the host accept incoming connections (macOS asks the first time).

## The terminal

The Java host writes its log to a file (`./marvin logs`). The Python host's `marvin-host run` prints only what you need to know; the app shows the rest:

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

These are the Python host's options; the Java host reads `MARVIN_UI_TOKEN` (`auto`, `off` or your own key),
`MARVIN_BIND` and `MARVIN_PORT` (see the top of [`marvin`](../marvin)).

The app also ignores requests addressed to unknown host names (a protection against web pages
that try to reach services on your computer), and only accepts changes sent by the app itself
(JSON from the same origin): settings, the voice's settings, turning the voice on and off,
questions, Talk now, Mute and Stop. All of these need the access key from another device, like
everything else. Anyone with the key can make Marvin listen and speak: keep it to yourself.

The app has no HTTPS: the key and the data travel unencrypted on your local network, like most
home devices. Do not expose the port to the internet.

## Privacy: your data stays on your computer

- The Java host keeps everything in PostgreSQL on this computer (Docker, or inside the host), memory included:
  what Marvin remembers, with the source of every fact. Marvin > Preferences says where; Memory exports it and
  can forget any of it. The app's appearance and Home layout stay in the browser.
- The Python host keeps everything in one SQLite file on the computer running `marvin-host`:
  `~/.local/share/marvin/marvin.db` (or `$XDG_DATA_HOME/marvin/`, or the folder in
  `MARVIN_DATA_DIR` if you set it). The settings dialog shows where.
- It holds Marvin's events (arrived, sat down, stood up...), one summary per minute (whether
  someone was there, and the average breathing and heart rate when they were measured), the
  conversation with Marvin (what it heard, what it answered, with the context it was given and
  the timings, what it did not answer and why), and your settings. No images, no sound, no raw
  radar data. The log is kept in memory only, for as long as `marvin-host` runs.
- Nothing is sent anywhere. The app loads nothing from the internet (no fonts, no scripts, no
  analytics) and works without an internet connection. An image shown to Marvin in Talk goes to the local model
  with its question and is not kept (only its size and a hash are recorded). A picture Marvin takes when you ask what
  it sees (Java host, [vision.md](vision.md)) goes to the local model only, is not kept, and is said in the app and the log. The one exception is Marvin's online tools:
  asking about the weather sends the place name to Open-Meteo. Turn **Internet** off in
  Marvin > Voice (Settings > Voice in the Python host's app) to keep Marvin fully offline ([voice.md](voice.md#tools)).
- To erase the history, stop `marvin-host` and delete the folder:
  `rm -r ~/.local/share/marvin`.

## The Python host's app

The Python host (`marvin-host run`, `marvin-host ui`) keeps the older app, on the same API. It has five tabs
(Home, Talk, Robot, History, Settings) and one dark appearance.

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

### Try it

```bash
marvin-host ui --demo --open        # a simulated robot and a simulated past week, time 10x faster
marvin-host ui --demo --speed 60    # faster: a minute of sitting per second
```

The demo keeps nothing: its history lives in a temporary folder that is deleted when you stop it
(Ctrl-C).

The demo also plays the robot and the vital signs radar for the Robot panel, and, when there is
no language model to talk to, a scripted conversation (typed questions get canned answers from
the simulated brain; ask about the weather to see a tool call, with made-up demo data, in the inspector), with a few past days of conversation to browse and search in History.
With Ollama and the `voice` extra installed, the demo offers the real voice instead, off until you
turn it on.

With the real robot, the app starts with `marvin-host run` (turn it off with `--no-ui`).
`marvin-host ui` runs the brain and the app without the Rerun viewer, which is lighter for
everyday use.

### Layout

On a phone, a tab bar at the bottom: **Home**, **Talk**, **Robot**, **History**, **Settings**. On a
computer the tabs move to the top; on a wide screen (1200 px and more) Home shows the conversation
beside the face and the day, so the Talk tab disappears.

### What it shows

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
| Why Marvin said that | Tap an answer, or the time under it (like `1.2 s`), to open its inspector: what Marvin heard (and the raw transcript when it differs, e.g. with "Marvin," in front), where the time went as a small bar (end of speech, recognition, model, and after a tool call **Tools** (orange stripes) and **Model (again)**, first sentence, synthesis, with the numbers), the tools Marvin used (each call with its arguments, its result or error, and how long it took; see [voice.md](voice.md#tools)), what Marvin knew when it answered (the facts of the context block sent with the question: the time, presence, how long you have been seated, breathing and heart rate, simulated sensors), the model and the language, and the exact message sent to the model. |
| Now | One discreet line above the text box: what Marvin knows right now (you are here or seated for how long, how far, breathing and heart rate when the radar reads them, "simulated" when it is). On a narrow screen the least useful parts go first. Tap it to open the Robot panel. |
| Sounds | Short soft notes played by the browser (synthesised, no audio files): two rising notes when listening opens, two falling ones when it closes without a question, a low blip when Marvin did not catch what you said while listening, a faint tick when an answer starts. Opening and closing a listening window are the voice's own earcons, played on one device (the computer's speaker, the robot's, or a bench board's buzzer: [voice.md](voice.md#earcons)), so the browser plays its notes for them only when no device does (the voice's `earcons` is `none`: the voice's own chime turned off, `"chime": false` in `voice.json`); a moment never chimes twice. The follow-up window after each answer makes no sound. Browsers allow sound only after a gesture: nothing plays before your first click or key press on the page. On by default; **Settings** turns them off. |
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
| Voice | Language model (the models installed in Ollama), speech recognition (MLX or faster-whisper, and the Whisper model), speech (Marvin's voice with Qwen3-TTS, say, Piper, espeak-ng, and the voice: `marvin` for Qwen3-TTS, installed Piper voices, a few to download, and the macOS voices), language (auto, French, English), waiting for "Marvin", the follow-up window, spoken break reminders, **Tools** (let Marvin use tools at all, and **Internet**: the online ones; off, Marvin is fully offline), the list of tools and whether each is on, and the **Home location** (at most 80 characters) the weather uses when you don't say where. Saved to `voice.json`; **Apply** restarts the voice with them. See [voice.md](voice.md). |

A few definitions:

- **Time seated** runs from the moment Marvin decides you sat down to the moment you stand up or
  leave (the `sat_down`, `stood_up` and `left` events, see
  [`brain.py`](../host/marvin_host/brain.py)).
- **Sessions** join sittings separated by less than a minute: getting up to grab something does
  not start a new session. **Breaks** are the gaps between sessions; **longest streak** is the
  longest session.
- When Marvin loses contact with the robot, or `marvin-host` stops, the timeline has a hole
  rather than a made-up block. After a crash, the time is counted up to the last sign of life.

## For developers

### The Java host's app

Plain HTML, CSS and JavaScript, no framework, no build step, nothing loaded from elsewhere (no fonts, no CDN): ES
modules served by `AppController` from
[`resources/app/`](../host-java/marvin-adapter-web/src/main/resources/app), under the page's Content Security Policy
(no inline script or style). `AppFilesTest` keeps it that way, and checks that Day and Night define the same
variables; `host-java/e2e/e2e.py` walks it in a browser.

| File | Role |
|---|---|
| `index.html` | The shell and every screen's markup, shared by both appearances |
| `style.css` | The tokens (the Day block, the Night block, nothing else differs), components, screens, the phone and computer layouts, reduced motion |
| `theme.js` | Applies Day, Night or Auto before the page is drawn; Auto follows `prefers-color-scheme` live |
| `app.js` | The shell: navigation (`#view/sub`), the appearance switch, the connection, the one event stream, badges |
| `core.js` | Helpers (DOM, API, formatting, dialog, `localStorage` that may be missing) and the small event bus between modules |
| `face.js` | Marvin's eyes: face.py's expressions, lids and blinks, the spring, in the appearance's colours; one renderer for every face |
| `home.js`, `daycard.js` | Home and its customization; one day's timeline (Home and History) |
| `talk.js`, `convo.js`, `inspector.js` | The live conversation and the voice's controls; conversation entries (Talk and History); the reply inspector |
| `memory.js`, `memdata.js`, `memtools.js` | The Memory screen; a fact's source line and its "Source & edit" dialog (edit, pin, keep, archive, forget); the profile and its versions, export, "forget everything", the raw log, memory's settings |
| `memviews.js` | "See your memory": the graph (a seeded force layout, capped, not animated), the meaning map, the timeline and the flow, in plain SVG from `/api/memory/graph`, `map`, `timeline`, `flow` |
| `worker.js` | Memory's worker, shown on Memory and Activity |
| `activity.js` | Approvals (today: a request to forget), today's moments, History with the days and weeks in Marvin's words |
| `tasks.js`, `markdown.js` | Background tasks in Activity (live, results, the steps' inspector, the form) and Thinking & tasks in Voice & models; a small Markdown reader built with DOM nodes only (a task's result) |
| `senses.js`, `sensorviews.js` | Senses and its stream; the sensor views Senses and Marvin > Robot share (the lidar with its range and nearest return, the radar, the vital signs' sparklines, a device and its link) |
| `marvin.js`, `system.js` | The overview, the robot's page, voice and models, preferences; System (services, restart, the log) |

Appearance and layout are presentation only: they never switch a sensor or a service. The Home layout lives in
`localStorage` under `marvin.home`, the appearance under `marvin.appearance`.

### The Python host's app

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
| `GET /` | the app (each host serves its own) |
| `GET /api/state` | `{state, today, settings}`: presence, status sentence, break progress (`state.simulated`: the robot's sensor data is simulated), today's stats |
| `GET /api/day?date=YYYY-MM-DD` | one day's stats and timeline (default: today) |
| `GET /api/history?days=7` | time seated per day, oldest first |
| `GET /api/events?since=ID&limit=50&quiet=1` | recent events, newest first, with a sentence each (`quiet` hides vital-sign events) |
| `GET /api/stream` | Server-Sent Events: `state` (2 per second), `event` (as they happen), `today`, `settings`, `voice` (state and status), `transcript` (one conversation entry), `log` (one log line), `devices` (once a second); while the voice runs, the live signals `level` (microphone loudness 0..1 and, from the Java host, `dbfs`, ~16 per second), `utterance` (`start`, `end`, `done`), `partial` (the words understood so far) and `say` (a piece of the reply with its duration and loudness envelope) |
| `GET /face.png` | the face right now (240 × 280 PNG); `503` if it cannot be drawn |
| `GET`, `POST /api/settings` | settings (`break_interval_min`, `quiet_hours`, `clock`, `voice`, `ui_sounds`); POST a JSON object with the fields to change |
| `GET /api/robot` | `{devices, scene}`: the devices, and the sensor mini-views (`lidar.ranges_cm`: 360 ranges, one per degree clockwise from the front; `targets` and `trail` in cm, right and forward; `vitals` with `rates` and `waves`) |
| `GET /api/robot/stream` | Server-Sent Events for the Robot panel, opened only while it is on screen: `scene` 4 times a second (vital sign history once a second), `devices` once a second |
| `GET /api/senses` | The Java host only. `{t, devices, scene, feels, hears, thinks, sees}`: the devices and the scene as `/api/robot` gives them; `feels` (`sensors` live, `presence` with its `source` (`radar`, `pir`, `none`) and `reasoning` lines, `vitals`, `room`); `hears` (`state`, `status`, `muted`, `why` it hears nothing or empty, `wake`, `language`, the engines in use `stt` and `tts` with their settings, `device`, `earcons`, the last `level`, and `recent`: the ears' last 20 diagnostics, oldest first: `{kind: "turn", uid, t, vad, turn, complete, probability, turn_s}` or `{kind: "utterance", uid, t, vad, turn, duration_s, dbfs, stt, stt_s, speculative, text, language, language_prob, wake, speaker, speaker_score, outcome, reason}`); `thinks` (`model`, `status`, `last_answer` with its `latency` and `load_s`, `reasoning`, the open `task`); `sees` (`camera` as `/api/camera` gives it, `look`: what the vision model said last) |
| `GET /api/senses/stream` | Server-Sent Events for Senses, opened only while it is on screen: `scene` 5 times a second, `devices` and `senses` (`feels`, `hears`, `thinks`, `look`) once a second, and as they come `level` (at most 20 a second, with `dbfs`), `hearing` (the ears' diagnostics, only on this stream), `utterance`, `voice`, `camera`, `task` |
| `GET /api/log?source=brain,device&limit=200&since=ID` | the log, newest first; sources `brain`, `device`, `host`, `voice` |
| `GET /api/voice` | `{voice, settings, transcript}`: state (`off`, `starting`, `on`, `stopping`, `error` with `error` and `fix`, or `unavailable`), status, muted; the voice settings; the conversation (today's last 200 entries: `heard` with `raw`, `reply` with `latency`, `context`, `prompt`, `model` and, after tool calls, `tools`: `[{name, arguments, ok, seconds, result | error}]`, `ignored` with `reason` and `dbfs`, `note`) |
| `GET /api/conversation?day=YYYY-MM-DD` | `{day, entries}`: that day's conversation, oldest first (default: today) |
| `GET /api/conversation?q=text` | `{q, results}`: what was heard or answered containing `text` (case-insensitive, plain text), newest first, at most 100 (`limit`) |
| `GET /api/voice/options` | what the voice settings offer: Ollama's models (or why Ollama cannot be reached), speech recognition backends and models, speech backends (installed or not), Piper and macOS voices, and `tools` (`[{name, description, online}]`) |
| `/api/memory/...` | the Java host's memory: facts, sources, profile, episodes, export, forgetting, the worker ([memory.md](memory.md#the-memory-api)); changes arrive as `memory` messages on the stream |
| `POST /api/voice/on`, `/off` | start or stop the voice (in the background: follow `voice` on the stream) |
| `POST /api/voice/ask` | `{"text": "..."}`: a typed question (at most 500 characters), answered aloud; `409` while the voice is off |
| `POST /api/voice/listen`, `/stop-speaking` | Talk now; Stop |
| `POST /api/voice/mute` | `{"muted": true}` |
| `GET /api/tasks?limit=20` | `{tasks, open}`: background tasks, newest first, without their results (`id`, `title`, `goal`, `status`: `queued`, `running`, `done`, `failed`, `cancelled`, `interrupted`; `origin`, `language`, times, `model`, `reasoning` and `think` (what Ollama was sent), `budget`, `spent`, `activity`, `summary`, `error`, `announced`, `has_result`) |
| `GET /api/tasks/{id}` | `{task, steps}`: the task with its `result` (Markdown), and every step (`model`: `thinking`, `text`, `tool_calls`, `usage`; `tool`: `name`, `arguments`, `ok`, `result` or `error`; `note`: `text`) |
| `POST /api/tasks/start`, `/cancel` | `{"goal": "...", "language": "fr"}` (language optional): the task, queued (`409` when tasks are off or four wait); `{"id": "..."}`: cancelled (a running one stops within a moment) |
| `GET`, `POST /api/tasks/settings` | `reasoning` (when talking), `task_reasoning` (off, low, medium, high), `tasks` (on or off), saved to `voice.json` without a restart; the model (size, capabilities, thinking levels, what each setting is sent as), the recommendation, the budget, `server.parallel`. Changes to tasks arrive on the stream as `task` (a task as listed) and `task_step` (`{id, step}`) |
| `POST /api/voice/settings` | the voice's settings to change (`llm_model`, `stt`, `stt_model`, `tts`, `tts_voice`, `language`, `wake`, `follow_up_s`, `reminders`, `welcome_back`, `tools`, `internet`, `home_place`); saved to `voice.json`, the voice restarts |

Times are Unix seconds from the computer's clock; days are local calendar days. The break
reminder setting also sets the brain's `still_long_s`, so the robot's own reminder follows it.
Tests: `pytest tests/test_ui.py tests/test_ui_control.py tests/test_ui_conversation.py`.
