# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Each hardware revision is a git tag (`rev-B` to `rev-E`; rev A predates the repository). Rev F will be tagged once the head is designed and the first unit is built.

## [Unreleased]

### Marvin v1, wave 1 (2026-10-14)
- Robot knob: the orange knob is a 6 × 6 mm tact switch on D7 (`MARVIN_KNOB_PIN=44`): a tap is *Talk now*, a 1.5 s hold mutes or unmutes the microphone (`BUTTON` action 2, protocol v1 without a version bump); the robot announces `HELLO` bit 3 and plays the error sound when there is no host.
- Firmware: the robot no longer waits for the Wi-Fi at boot and reconnects from its loop with a backoff that never gives up; a task watchdog on the loop (`MARVIN_WDT_S`, 10 s, paused during OTA), a health line every minute and the last reset's reason as `LOG`; losing the host puts the face on its own at once; the voice's moods on the face (listening, thinking, speaking, concerned, happy); the self-test on demand (`t` in the serial monitor); the screen's orientation as build flags (`MARVIN_TFT_ROTATE_180`, `_MIRROR_X`, `_MIRROR_Y`, `_BGR`); `xiao_esp32s3` is the default env.
- MR60BHA2 bridge 0.2.0: updates over Wi-Fi (`xiao_esp32c6_ota`), rejoins Wi-Fi with a backoff, reopens the radar's UART when it goes silent, and flags a steady signal in `VITALS` (flags bits 1-2).
- Vital signs you can trust, or that say they cannot yet: a quality gate gives `good`, `unstable` or `none`, one MR60BHA2 per seat; heart and breathing rates kept per minute (90 days, `marvin.vitals.keep-days`); `GET /api/vitals?hours=24` and a `vitals` event on the app stream once a second. Measurements, not a diagnosis.
- Voice: Marvin greets its owner by name when they sit down after 30 minutes away ("Bonjour Sébastien !", "Hi Sébastien!"), in the last language spoken, at most once every 10 minutes, neutral with a guest; voice setting `greet_on_sit` (default on); nothing proactive in quiet hours.
- Voice: one latency line per answered turn (contract C6) in `<data-dir>/latency.jsonl` (last 500 kept); the voice's model stays loaded in Ollama (`keep_alive: -1`) and is warmed up again if pushed out. `VoiceService` split into collaborators, no behaviour change.
- Presence: posture hysteresis against a real LD2450's jitter (no more `sat_down`/`stood_up` flapping); every brain threshold tunable from a `brain` block in `calibration.json`; `BrainSpeedSignCheck` finds the LD2450's speed sign from a recording.
- Approvals: a background task that wants to write outside the computer (a Gmail draft) waits in `needs_approval` with a preview of exactly what it will write; approve or reject it in the app (`/api/approvals`, stream event `approval`); rejected or unanswered within an hour, nothing is written.
- Marvin never sends an email: a tool filter refuses any tool that would send, delete, forward, reply, pay or share; a tool that writes outside the computer is offered only to a task with approvals.
- Google Calendar and Gmail connectors with the owner's own OAuth desktop client (PKCE, loopback): calendar events (read only), Gmail search and drafts after approval; scopes `calendar.readonly`, `gmail.readonly`, `gmail.compose` only; tokens in the macOS Keychain (or a 0600 file), revoked on Disconnect (`/api/connectors`).
- The soul: the owner's core and a learned part about how to be with them; a weekly reflection proposes changes as a diff, nothing changes until approved, every version kept, guardrails on every proposal and edit (`/api/soul`). The app's Soul, connectors and approvals screens are written; wave 2 wires them into the navigation.
- Memory: what a background task found goes to the event log (and the day's summary); conversation text and task results leave the log after a year once summarised.
- App: a first-run setup (`/setup`): nine short steps (this computer, the language model, ears and voice, the robot, the MR60BHA2 kit, the speaker, your voice, about you, quiet hours), each saying what it is for, what the host found and how to fix it; **Finish later** goes Home, which says what is left (`GET /api/setup`, `POST /api/setup/{step}`).
- App: **Heart and breathing** on Home (now, how far to trust it, the distance, the last 24 hours), hidden without a vital-signs radar; Senses follow the rev F robot (radars and camera first, the lidar panel only when a lidar is linked); Marvin > Preferences > **You** (your name, your languages, the greeting, "welcome back", run the setup again).
- Hardware: the head CAD: the shell printed face down with the smoked acrylic window behind a 0.8 mm lip, a back cap (4 × M3 × 10), a face frame holding the screen and the XIAO in a 20° pocket, the orange knob on a tact-switch bracket, a window DXF and drilling template; module sizes in one `MEASURED (H0)` block, provisional until measured; `hardware/scripts/check_head.py` checks the fits; the Bambu project on four plates (`build_3mf.py --no-studio`, `--check`); the plinth prints upside down; renders from the CAD head.
- Docs: a reading order (`docs/README.md`) and tutorial 1, *Run Marvin on your Mac* (`docs/getting-started.md`); `docs/test-plan.md` is the v1 acceptance checklist (about 30 minutes on the real robot); the knob in the wiring, BOM and XIAO bench; architecture roadmap, design status and the README (EN and FR, section for section) up to date.
- CI: the firmware and the MR60BHA2 bridge build and test in a workflow of their own; the Testcontainers integration tests must really run; the app is walked through end to end in demo mode; the docs are linted (`python3 tools/doclint.py`: no lidar claimed for Marvin, pin tables, env table, protocol tables, links, README EN/FR in step).
- `./marvin report`: one paste for a bug report (versions, health, doctor, setup steps, devices, the robot's self-test, the last 20 latency lines with p50/p90, the last errors; nothing personal).
- The API specification catches up with the Java host (voice options' ears and vision, the senses stream, events, the host log): the contract test passes with Docker.

### The XIAO bench, with a boot self-test (2026-10-14)
- A new build, `xiao_bench` (USB, native USB console) and `xiao_bench_ota` (Wi-Fi, `OTA_PASSWORD` from `secrets.h` as for the other `*_ota` envs): the robot's XIAO ESP32S3 Sense and parts on a breadboard, on the pins of docs/wiring.md: the real LD2450 (no lidar), the face on the ST7789, the speaker on the MAX98357A, the Sense board's mic and camera, OTA. Docs: `docs/xiao-bench.md` (pins, flashing, download mode, the expected console per part, troubleshooting).
- Boot self-test (`MARVIN_SELF_TEST`, `firmware/src/self_test.h`): one console line per part as it starts, `screen:`, `speaker:`, `mic:`, `radar:`, `wifi:`, `camera:`, `host:`, each saying which wires to check when its part does not answer; the radar and mic lines every 5 s; Enter in the monitor prints them again; it waits up to 3 s for the monitor at boot. Every part may be missing: nothing waits on it. The wording is tested natively (`test_self_test`).
- The screen shows red, green and blue (`MARVIN_SCREEN_TEST`), then the eyes wake up and look at you for 8 s (`face_app::greet`); the speaker plays a two-note chime (the *wake* earcon) at the default volume; the screen's ST7789 ID is read back over its data line before the SPI takes it (`St7789::id`).
- The microphone can run without a host for a level reading (`audio_app::set_mic_monitor`, `audio_app::mic_level`); `AUDIO_IN` still only goes out on the host's request. The camera grabs one frame on demand (`camera_app::test_frame`). The LD2450's configuration can be quiet (`real::begin(model, false)`).
- Every build with a screen and real sensors (the robot too): the face follows the board's own LD2450 while the host sends no `FACE_STATE`: someone in front wakes it up and the eyes follow them; the host's `FACE_STATE` still wins.

### The lidar leaves Marvin (2026-10-14)
- Rev F has no lidar: the D800 moves to a separate project, a 3D room scanner. Marvin senses with its two radars, its camera and its microphone. BOM about €115 (was €230), 25 connections (was 29), WAGO 5V 4/5 and GND-A 3/5, D7 spare, about 0.7 A average and 1.5 A peak, about 12.5 cm tall.
- CAD: the lidar template and the lidar's hole pattern are gone from `marvin.scad` (rev E's stays in the archive); `0_lidar_template.stl` removed; the Bambu project has three plates (body + sled, plinth, TPU); the Blender model, its renders and the section diagram without the lidar ring and turret.
- Firmware: the robot build (`xiao_esp32s3`) reads no lidar (`MARVIN_LIDAR_MODEL=0`); a D500 or D800 on D7 is still read with 1 or 2. All lidar software stays: the firmware framer and UART reader, the USB sensor bridge, the host's parsers, calibration and views, for the bench and the USB tooling.
- Docs: bom, wiring (diagram, table, WAGO counts, power budget), assembly, printing, architecture (the decision), README (EN and FR), the presentation page, intelligence ("the lidar question", decided), firmware README.

### The radar in 3D (2026-10-06)
- Senses > Radar shows the LD2450 in 3D: a desk with the radar on it, the floor with a ring every metre, its field of view (±60° side to side, ±35° up and down, 6 m) cut by the floor, the plane it measures in, each person as a silhouette labelled with distance, angle (left or right as you see it, facing the radar) and speed, their last five seconds as a fading line, and where Marvin's head looks. Views from your seat, behind the radar, above and the side; drag and zoom. A **mounting** choice (upright, flat face up, upright turned 90°) turns the board and its field and moves the people, each with a line that says what it means. Under the view, each target's raw numbers, frames per second and the last frame's age; the old 2D fan stays one click away. three.js r170 (MIT) is vendored in the app and loaded only when the view is first shown (`docs/ui.md`, "The radar in 3D").
- API: `scene.head` (where the servo head looks, from the last `HEAD`; `null` without one in the last 2 s) and `scene.radar.age_s` (the last LD2450 frame's age) in `/api/robot`, `/api/senses` and their streams. The host now uses `HEAD`.

### The "looks" bench: a head that turns to look at you (2026-10-05)
- A new bench build, `esp32dev_looks` (and `esp32dev_looks_ota`), on the classic ESP32 DevKit: a real HLK-LD2450 (UART1, RX GPIO26, TX GPIO27) and no lidar (`MARVIN_LIDAR_MODEL=0`), a 9 g servo on GPIO13, the passive buzzer on GPIO25, the Talk button on GPIO32, the optional OLED on GPIO21/22, an optional WS2812 strip on GPIO18 (off by default). `docs/looks-bench.md`: parts, wiring, mounting, flashing, what it does, troubleshooting.
- The servo head (`MARVIN_HAS_HEAD`, `firmware/src/head/`): turns towards the nearest radar target, smoothed, with a deadband and a speed limit; nobody for 3 s, a slow return to the centre; nobody for 20 s, an occasional small glance around. Silent for its first second and started at the centre, resting once still, against USB brownouts. Works before Wi-Fi and without a host.
- Presence chirps: *hello* on the buzzer when someone arrives after nobody for 10 s, a softer *bye* when they have been gone for 10 s (`MARVIN_PRESENCE_CHIRPS=0` mutes them).
- The bench's OLED eyes follow the board's own radar while the host sends no `FACE_STATE`; the host's still wins once linked.
- Protocol v1, without a version bump: `HEAD` (0x09, robot → host, where the head looks, 5 Hz) and `HEAD_CTRL` (0x87, host → robot: auto, manual at a yaw, centre), in both hosts (`protocol.Head`, `protocol.head_ctrl`, `Sink.on_head`; `Head`, `SensorFrame.HeadPose`, `HostMessages.headCtrl`), `docs/protocol.md` and the protocol vectors. No `HELLO` flag: bit 7 stays kept for a second flags byte. Nothing on the host uses the head yet.

### Senses, and the lidar you could not see (2026-10-04)
- Fixed: a lidar lying on a desk drew almost nothing in Marvin > Robot. The view's scale never went below 2 m and followed the 95th percentile of every return (a few far ones through a gap set it to 4 or 5 m), the dots were 2.4 px of a pale tone, and the robot's marker was drawn over the nearest ones: a scan within 10 to 100 cm collapsed into a smudge under the marker. The view now picks the smallest of 0.5, 1, 2, 4 and 8 m that holds most returns, with rings to match, dots sized to the canvas in a stronger tone and drawn over the marker, far returns as ticks on the edge, the nearest return circled and measured; the radar's field of view is shaded only when a radar is connected. A Robot stream refused for good (an error page while the host restarts) is opened again.
- **Senses**, a sixth destination: everything Marvin sees, hears and feels, live and technical. Sees: the lidar (automatic or chosen range, nearest obstacle, scans/s, points, age), the radar and its people, the camera on demand (**Take a picture**, a **Live preview** at one picture every 2 s with a visible indicator, nothing kept) and what the vision model said last. Hears: the microphone's level in dBFS with its last ten seconds, the voice activity, the end-of-turn decision, the last "Marvin", the engines, the language, whose voice, and the last 20 utterances with what became of each and why. Feels: presence with its source and evidence, the room, the vital signs. Thinks: the voice's state, the model and reasoning levels, the running task, the speech engines, the last answer's stages to the speaker. Devices: every link and the audio route. Every panel says what is missing. Day and Night, phone and computer.
- Voice contract, additions to version 2: `Hearing` (the ears' diagnostics: each end-of-turn decision, and each utterance's loudness, transcript, engine and time, language, name, voice and outcome; no audio) and `Level.dbfs`. The Java host keeps the last 20 in memory only.
- API: `GET /api/senses`, `GET /api/senses/stream` (scene 5/s, devices and a summary 1/s, levels at most 20/s, `hearing` only there; open only while the screen is); `level` carries `dbfs`. The robot's views are shared by Senses and Robot (`sensorviews.js`).
- `e2e/lidar_pty.py` plays an STL-27L on a cluttered desk (or a room) over a pseudo-terminal for the USB bridge; `e2e/senses.py` checks that the points are really drawn (pixels), the level moves, the empty states, Day and Night at both widths.

### New ears and the voice calibration (2026-10-04)
- The end of a question: Silero VAD v6.2 (MIT) hears speech, and after 0.2 s of silence Smart Turn v3.2 (pipecat-ai, BSD-2-Clause) judges from the voice itself whether the turn is complete: a finished question ends then, an unfinished one waits up to 2 s. The decision's time is in each question's latency (`turn`). The silence timers and word rules stay as the fallback; joining a question that goes on stays.
- Speech recognition: Parakeet TDT 0.6B v3 (NVIDIA, CC-BY-4.0) by default, on the Mac's GPU through parakeet-mlx, on the CPU elsewhere through onnx-asr (int8); Whisper stays selectable and the fallback. The turn's language is read from Parakeet's words. **Names and words** (`vocabulary`): Whisper is biased with them, every engine's transcript is corrected with them (a French/English phonetic match: "Marvine", "Ashma", "Lorine" become "Marvin", "Aashma", "Laurine").
- The wake word stays a fuzzy match on the transcript (with Parakeet's spellings of a French "Marvine"): openWakeWord's pre-trained models are CC BY-NC-SA, and with Silero and Parakeet the transcript costs little and gives the question too (docs/voice.md, "The wake word").
- Your voice: a calibration in Marvin > Voice & models (offered once on the first run) measures the room's noise and your voice through the voice's device, writes that device's VAD floor, "too quiet" level and Silero threshold to `voice.json`, shows what speech recognition heard of each sentence, and keeps your voiceprint (WeSpeaker ECAPA-TDNN, Apache-2.0) in `voiceprints.json`, on this computer only, deletable in the app. Each question then says whose voice asked (`Heard.speaker`), in the conversation and in Marvin's context; **Only answer voices Marvin knows** ignores the others.
- Contract version 2, fields only added: the ears' settings and the voiceprints in `Configure`, Silero's threshold in the device profile, `Heard.speaker`, `Enroll` and `EnrollProgress`, the ears' models in `Options`. API: `GET`/`POST /api/voice/enroll`, `/api/voice/enroll/cancel`, `/api/voice/people/forget`; `vocabulary` and `known_voices_only` in the voice's settings.
- `./marvin up` installs Parakeet and onnxruntime with the voice and downloads the ears' models (36 MB, pinned and checked) and Parakeet's weights once, with their progress; `./marvin doctor` checks them (`python -m marvin_host.voice.models --check`, `python -m marvin_host.voice.stt --check`).
### Thinking and background tasks (2026-10-04)
- One model, two regimes (`docs/intelligence.md`, "Thinking and background tasks"): the conversation answers at once (no reasoning by default), background tasks think hard. Marvin > Voice & models > **Thinking & tasks**: the level when you talk and on tasks (off, low, medium, high; saved to `voice.json` as `reasoning`, `task_reasoning`, `tasks`, nothing restarts), the model's size and thinking levels, the recommended model for tasks (`qwen3.8:27b-mxfp8`).
- Reasoning levels sent as the model lists them: *high* is `xhigh` for Qwen3.8 (Ollama would turn `"high"` or `true` into its default, medium); a refused level falls back to thinking on, then off. Requests that think go to Ollama's API directly (Spring AI cannot send `xhigh`), their thinking apart from the answer and never spoken; a turn's thinking is in the reply inspector.
- Background tasks: the model's `start_task` (the turn ends with "Je m'en occupe, je te dis quand c'est prêt"), "fais-le en tâche de fond" without the model, a hint for words that announce several steps, `task_result` for "c'est quoi le résultat ?", and Activity's form. One task at a time with the conversation's tools that stay on this computer, `final_answer` (what Marvin says, the result in Markdown), budgets (12 requests, 20 minutes, 40 000 tokens), cancel, every step kept in PostgreSQL (`conversation.task`, `task_step`), interrupted after a restart.
- The voice first: a task's request is cut the moment someone talks with Marvin and made again after (Ollama runs Qwen3.8 one request at a time whatever `OLLAMA_NUM_PARALLEL` says); `MARVIN_LLM_PARALLEL` for a model it runs in parallel.
- Telling you: said on its own when you are there and not talking with Marvin, otherwise mentioned at the start of the next answer; quiet hours respected; a line in the Log panel.
- The tool registry says what each tool does to the world (reads, writes here, writes outside) and where it runs (conversation, tasks); tools from outside the conversation come through a `ToolSource` port, where connectors will plug in. The persona's honesty rules cover tasks: a task has only Marvin's tools and nothing is said about one that a tool did not say.
- The app: **Tasks** in Activity (live progress, Cancel, the result in Markdown, the steps with the model's reasoning). API: `/api/tasks`, `/api/tasks/{id}`, `/api/tasks/start`, `/api/tasks/cancel`, `/api/tasks/settings`; stream messages `task` and `task_step`. `./marvin doctor` shows the model's size and whether tasks can run beside the conversation. `e2e/tasks.py`.

### USB sensors: the lidar on the computer, no ESP32 (2026-10-04)
- The USB sensor bridge (`host/marvin_host/usb_sensors.py`, `marvin-host usb-sensors`, `docs/usb-sensors.md`): a lidar (D800 / STL-27L at 921600 baud, D500 / STL-19P at 230400) or an HLK-LD2450 (256000) on a USB-serial adapter is found by probing the adapters for valid frames, and sent to the host exactly as a robot board sends it: `HELLO`, `HOST_ACK` and the link timeout as the firmware's `link_step`, 10 lidar packets per datagram with a 20 ms flush, the counters in a `LOG` every 5 s. Unplugged and plugged back in, it comes back as the same device (a MAC made from the adapter's serial number, a fixed source port).
- `host/marvin_host/sensor_framing.py`: the firmware's resynchronising framers (LDROBOT packets, LD2450 frames and ACKs) and LD2450 commands in Python, checked against the firmware's own native tests. pyserial joins the Python host's dependencies.
- Protocol v1, without a version bump: board id 6, *USB sensor bridge (computer)*, in both hosts, `docs/protocol.md`, `protocol.h`'s comment and the protocol vectors (`hello_usb_bridge`).
- Real sensors beat simulated ones: a simulated device's lidar scans are not drawn while a real lidar's are fresh (`SensorScene`), and its radar targets and vital signs do not reach the brain while a real radar is live (`PresenceFeed`).
- `./marvin up` and `restart` start the bridge when an adapter is plugged in (it probes in the background while the host starts, and says what it found), `down` stops it, `status`, `logs` and `doctor` show it, `./marvin usb-sensors` runs it in the foreground (`--detect`: what is plugged in). `MARVIN_USB_SENSORS=off`, `MARVIN_USB_PORTS`.
- The app: the device *USB sensor bridge* ("Real sensors over USB", on this computer, the lidar's rate) in Marvin > Robot; with only sensors that see no one (a lidar), the top bar says *Sensors live · no presence sensor* and the Overview *Sensors only*, instead of *Robot offline*.
- `docs/usb-sensors.md`: the STL-27L on a CP2102 adapter (ZH1.5 pin order; this kit's cable colours, black = TX, green = GND, red = P5V, white = PWM to ground), use, behaviour and troubleshooting.

### Marvin's own voice, and the voice's plumbing (2026-10-01)
- Marvin speaks with its own voice: Qwen3-TTS (0.6B Base) cloning "Malicieux", the voice chosen for it (a French and an English sentence in `host/marvin_host/voice/voices/marvin/`, made once with Qwen3-TTS VoiceDesign), on the Mac's GPU through mlx-audio, streamed, one voice for both languages. Each clause is read in its own language: the Java host cuts answers into clauses and tags them (`SpeechLanguage`: a run of three words of the other language is cut out, isolated anglicisms stay). Piper and `say` stay as fallbacks (one voice per reply, "Marvine" for a French voice). `./marvin up` installs it and downloads the model once; `./marvin doctor` checks it; Marvin > Voice & models offers **Marvin's voice (Qwen3-TTS)**.
- Talk now and barge-in are clean: the voice keeps one lock around its state, so a cut answer can never play a sentence or say "speaking" after the cut; the microphone reopens as soon as the speaker is really silent, keeping what was said meanwhile as pre-roll (the start of the question is never clipped); Talk now while Marvin thinks leaves no empty "interrupted" answer, and what follows replaces it. The longer pause after "et", "parce que"... also works with speculative recognition off.
- Audio devices: the Java host decides once where the voice hears and speaks (the computer, or a robot) and sends the device with its profile (half or full duplex, echo tail, output latency, VAD floor, "too quiet", earcons); a robot linking or leaving swaps the device under the running voice, without restarting it. `devices` in `voice.json` tunes each profile.
- One place for the voice's settings and defaults (`VoiceSettings`, contract version 2: every value sent, `continue_join_s` and `speculate_after_s` exposed; `golden/voice/configure_defaults.json` keeps the sidecar's copies equal).
- One earcon decider (`VoiceChimes`) and one device per earcon: the voice's own (the computer's speaker, the robot's built-in sounds), else a bench board's buzzer; the app chimes only when neither does. No more double chimes.
- Where the time went, to the speaker: the first sound synthesised and when the speaker played it (`first_audio`, `playback_start`), one line per answer in both logs, the **Speaker** stage in the reply inspector; "first word" is when it was heard.
- Retired: the Python host's own conversation (`marvin-host talk`, `run --voice`, its assistant, Ollama client, tools, persona and voice controller, the Python app's voice panel, `buttons.py`). The Python side keeps the voice's audio and models (the sidecar) and its tools (simulator, viewer, recordings, calibration, the demo's week). The API and conversation goldens are now hand-kept specifications.

### Marvin looks when asked (2026-10-01)
- The `look` tool (Java host, `docs/vision.md`): asked what it sees, what you hold or to read your screen, Marvin takes one picture, the model for images describes it in a request of its own, and the answer comes from that description. Offered only while a camera can take a picture, with a persona that says so; without one, Marvin still says it cannot look. The picture is never kept and never leaves the computer.
- Two interchangeable cameras behind one port (`FrameSource`, new `vision` context and `marvin-adapter-camera`): the computer's camera through an `ffmpeg` process started for one picture (AVFoundation on macOS, V4L2 on Linux; the first frames dropped while the exposure settles), and the robot's MJPEG camera (`/capture`, or the first picture of `/stream`). Both give a JPEG of at most 1280 px, as an image shown in Talk.
- The voice setting `camera` (`auto`: the robot's when one with a camera is connected, else the computer's; `computer`; `robot`; `off`), in both hosts and the conversation vectors (the Python host keeps it and ignores it).
- The app: Marvin > Voice & models > **Camera** (which camera, what each can do now and how to fix it, **Test camera** with its picture in the panel), the camera on the Overview, a note each time Marvin takes a picture, and the line in the Log panel. API: `GET /api/camera`, `POST /api/camera/snapshot`, a `camera` event.
- `./marvin doctor` checks for ffmpeg (`brew install ffmpeg`). `e2e/camera.py` walks it in the app with ffmpeg's test pattern as the camera.

### Room sensors on the bench board (2026-09-30)
- `firmware/src/room/` (`MARVIN_HAS_ROOM_SENSORS`, envs `esp32dev` and `esp32s3`): an HC-SR501 PIR (a minute's warm-up, debounced, motion start and end sent at once), a DHT11 read every 5 s in its own task (its own small driver: checksum and range checked, retried, invalid within 12 s when unplugged) and a light sensor (`MARVIN_HAS_LDR`, off until the module is wired). Unity tests in `test/test_room`.
- Protocol v1, without a version bump: `ENVIRONMENT` (0x08, robot → host, 12 bytes: which sensors, motion, warming up, temperature, humidity, light, seconds since the last motion) and `HELLO` flag bit 6 (room sensors). Python and Java hosts and the protocol vectors updated together.
- Presence: on a board whose radar data is simulated, a real, warm PIR is the presence (the simulated person no longer drives the brain): motion is `arrived` at once, 2 minutes without movement is `left`. A live radar always wins. Both hosts (`brain.py`; `Brain.onMotion` and `PresenceFeed`), `motion_presence` in the presence state, the golden recordings' states regenerated.
- The room in Marvin's context ("The room, from your own sensors: 23.4 °C, 48 % humidity, movement just now.", only while the readings are under 2 minutes old; the presence line says a motion sensor noticed movement, not that someone is in front of Marvin), and a Room card on the app's Home. The simulated robot sends a simulated room (marked as such). Both hosts (`room.py`, `persona.py`; `Room`, `RoomState`, `Persona.roomFact`, `LiveState`), the conversation vectors and the API snapshots' `state` updated.
- `docs/wiring-bench.md`: the PIR, the DHT11 and the light sensor on both DevKits, their settings and troubleshooting.

### Bench board (2026-09-30)
- `firmware/src/bench/` (`MARVIN_HAS_BENCH_IO`, envs `esp32dev` and `esp32s3`): Marvin's eyes on a 0.96" SSD1306 OLED (the robot's face at half scale, fed by `FACE_STATE` and `FACE_EVENT`, with moods from the voice's sounds; found again when a loose screen comes back), a Talk and a Mute button, a passive buzzer playing the earcons, and a status LED for Wi-Fi, host search and link (the S3 DevKitC-1's RGB LED on v1.0 and v1.1 boards). U8g2 pinned, built only by these envs. Unity tests in `test/test_bench`.
- Protocol v1, without a version bump: `BUTTON` (0x07, robot → host: button, action) and `HELLO` flags bit 3 (buttons), 4 (buzzer) and 5 (the face on a screen of its own). Python and Java hosts and the protocol vectors updated together.
- Hosts: a Talk press is the app's Talk now, a Mute press mutes or unmutes; the voice's moments (listening, done, error, muted) play as chimes on robots with a buzzer; the face messages go to any device announcing a screen (`host/marvin_host/buttons.py`; `ButtonFeed`, `VoiceChimes` and `BuzzerChimeService` in the Java host).
- `docs/wiring-bench.md`: the pin maps of both DevKits, no soldering, how to flash and what to expect.

### Java host (2026-09-28)
- `host-java/`: the host core in Java 25 and Spring Boot 4.1 (hexagonal, bounded contexts, ArchUnit), built phase by phase from `docs/design.md` and checked against the Python host (`marvin-contracts`: protocol vectors, recordings, API snapshots, face and conversation vectors). It reaches parity with `marvin-host run`: the robot link (UDP protocol v1), the brain, the face, the history in PostgreSQL (the Python host's `marvin.db` imported once), the app unchanged, and the voice: the conversation in Java (Ollama through Spring AI, tools, memory, reminders), the audio loop in the Python voice sidecar it supervises (`host/marvin_host/sidecar/voice`, gRPC `marvin.voice.v1`).
- `./marvin up` is now the way to run Marvin (PostgreSQL, the Java host and its voice); `./marvin demo`, `status`, `logs`, `doctor`, `down`. The `marvin-host` commands stay as tools: simulator, viewer, recording, replay, calibration.

### Software prototype (2026-09-27)
- `docs/protocol.md`: robot → host UDP protocol v1. Zero-configuration discovery (`HELLO` broadcast, `HOST_ACK`), raw lidar and LD2450 frames in a 16-byte envelope.
- `host/`: the `marvin-host` Python package. Parsers for LDROBOT and HLK-LD2450 frames, sensor extrinsics, UDP receiver with loss counting, live Rerun view and recording, and a simulated robot (room, walking person, D500 or D800 lidar). `marvin-host demo` runs everything on one computer. Tests with `pytest`.
- Simulated home office (`host/marvin_host/scene.py`, shared with the firmware through a generated header): walls, furniture at their real heights, and a person who comes in, sits at the desk and leaves. Simulated camera rendering the same room, and MR60BHA2 vital signs (`VITALS` message).
- Viewer layout: 3D, camera with lidar and radar projected onto the image, lidar top view, radar-style mmWave view, presence and vital-sign time series.
- The brain (`host/marvin_host/brain.py`): presence, seating, stillness and vital-sign reliability as debounced events (`events.py`), shown in the viewer. The simulated MR60BHA2 now drops out while the person fidgets, as the real one does.
- The face (`host/marvin_host/face.py`, `docs/face.md`): the robot's eyes as a reference renderer for the 240 × 280 screen, following the person, blinking, reacting to events and falling asleep; shown live in the viewer.
- Real sensors (`firmware/src/sensor_framing.*`, `sensors_real.*`): resynchronising framers for the LDROBOT lidar and the HLK-LD2450 (with its multi-target init sequence), UART drivers for the XIAO ESP32-S3; `xiao_esp32s3` builds the real robot, `xiao_esp32s3_sim` keeps simulated sensors. Unity tests run on the PC (`pio test -e native`).
- The face on the robot (`firmware/src/face/`): C++ port of the face, identical to the Python reference frame for frame, a small ST7789 driver sending only what changed, and host → robot messages `FACE_STATE` and `FACE_EVENT` (`host/marvin_host/link.py`).
- `firmware/mr60_bridge/`: firmware for the MR60BHA2 kit's ESP32-C6 that sends its vital signs to the host (`VITALS`, board 4).
- The app (`host/marvin_host/ui/`, `docs/ui.md`): a local web page for computer and phone with the live face, today's timeline, breaks, 7-day history and settings; events and daily stats kept in SQLite on the computer. `marvin-host ui --demo` shows it with a simulated robot.
- The voice (`host/marvin_host/voice/`, `docs/voice.md`): "Marvin" wake word, Whisper speech-to-text, a local LLM through Ollama and macOS/Piper speech, with the brain's context and break reminders; all local. `marvin-host talk`, `marvin-host run --voice`.
- Audio and camera on the robot (`firmware/src/audio/`, `firmware/src/camera/`, `docs/audio.md`): I2S speaker with earcons and streamed speech, PDM microphone streamed to the host, MJPEG camera on port 81 shown in the viewer; protocol messages `AUDIO_IN`, `AUDIO_OUT`, `AUDIO_CTRL`, `SOUND` and HELLO capability flags.
- Tools (`docs/tools.md`): wireless firmware updates (OTA, `xiao_esp32s3_ota`), raw session recording and replay (`.mvrec`), automatic or manual lidar yaw calibration.
- PlatformIO platforms are pinned (espressif32 7.1.3, espressif8266 4.2.1).
- `firmware/`: first PlatformIO firmware. Wi-Fi, host discovery and streaming with simulated sensors, on the Wemos D1 mini (ESP8266), the ESP32-S3 DevKitC and the XIAO ESP32S3.

### Rev F CAD, body half (2026-09-27)
- `hardware/cad/marvin.scad`: body shell (printed upside down, 1.6 mm radome wall, anthracite band by filament change), plinth, sensor sled, TPU neck gasket, grommets and feet, lidar template. Print-ready STLs in `hardware/stl/`.
- The 24 GHz radar now sits **below** the 60 GHz radar (face centres at 16 mm and 42 mm): above it, it would have shadowed the upper part of the 60 GHz beam.
- The head sits on the body through a TPU gasket and four TPU grommets; no screw links them rigidly.
- `hardware/scripts/section_diagram.py` draws the section diagram from the CAD values.
- Rev E (Fanou) sources, STLs and Blender file moved to `hardware/archive/rev-e/`.
- `hardware/blender/marvin.blend`: the body half from the CAD (band painted), the modules, a concept stand-in for the head, and the print plates. Built by `hardware/scripts/build_blend.sh`.
- `hardware/bambu/marvin_A1.3mf`: Bambu Studio project for the A1 + AMS lite (4 plates, colours, per-part settings, band colour change). Built by `hardware/scripts/build_3mf.py` with the Bambu Studio command line.

### Renamed (2026-09-27): SuperLens is now Marvin
- The project and the robot are now called Marvin. "marvin" is also its wake word, a keyword of Google's open Speech Commands dataset.
- Older entries below keep the file names they had at the time (`superlens.scad`, `superlens.blend`).

### Rev F design (2026-09-25): the upright robot
- New shape: a compact upright robot (about 88 × 80 mm, 15 cm tall with the lidar) replaces the lighthouse. Design studies in `docs/concepts/` (terminal, robot v1–v3, sight lines).
- Sensors are tilted inside an upright shell: 60 GHz radar at 20° (aimed at a seated chest, 0.6–1 m), 24 GHz radar at 10°, camera at 20°, screen vertical.
- Head and body joined through a TPU damper so the lidar motor does not disturb the heart-rate radar.
- Smoked acrylic face window; anthracite band hiding both radars, printed as a filament change.
- Grown-up design language: warm grey, anthracite, one orange knob.
- BOM: MR60BHA2 now bought from Seeed Studio; adds smoked acrylic and TPU. Electronics and wiring unchanged.
- Docs, READMEs and presentation page describe rev F. The rev F CAD is not written yet; `hardware/cad/fanou.scad` still holds rev E.

## [rev E] - 2026-09-25 · Fanou

### Changed
- The handheld sensor head becomes **Fanou**, a desk companion shaped like a little lighthouse. Same sensors, same wiring and pin map as rev D.
- New parametric source `hardware/cad/fanou.scad`: rocky island base, bottom plate, striped tower (1.6 mm PETG wall, half a wavelength at 60 GHz), spine carrying every module, gallery cap with railing and lidar plinth.
- The radars now look through the painted wall: the 60 GHz radar behind an arched door, the 24 GHz radar behind the middle red band.
- The screen is Fanou's face, in portrait behind a framed window; the camera looks through a brass porthole above it.
- The speaker moves to the bottom plate under a grille; the WAGOs and the amp are stuck on the back of the spine.
- Power arrives through a USB-C cable with a right-angle plug, entering at the back of the island.
- The lidar is held by 3 thread-forming M2.5 screws (no nuts); the cap by 3 radial M3 screws.
- New Blender file `hardware/blender/fanou.blend` and Cycles renders `docs/images/fanou_*.png`. Presentation page and READMEs rewritten.

### Added
- Character studies in `docs/concepts/` (Fanou the lighthouse, Hulotte the owl, Tito the robot).

### Removed
- `hardware/cad/superlens.scad` and its parts (head, back cover, grip, stand), `superlens.blend` and the rev D renders.

## [rev D] - 2026-09-24

### Added
- Audio: the XIAO Sense's built-in PDM microphone, plus a MAX98357A I2S amp driving a 2030 speaker held in a cradle on the left wall. The side vents act as its grille, with extra holes.
- Presentation page `docs/index.html` (EN/FR) with a live simulation of the back screen.
- Cycles renders `docs/images/hero_front.png` and `hero_back.png`.

### Changed
- The D800 lidar (STL-27L, 921 600 baud) is now the reference; the D500 remains a cheaper option.
- To free the I2S pins, the lidar PWM goes back to GND and the screen backlight to 3V3. There are now four WAGO buses (5 V, GND-A, GND-B, 3V3) and 29 connections.
- The standard XIAO (loose headers) is the default: two header strips to solder.
- README and README.fr open with the project vision.
- Blender file: flat CAD shading, glowing screen, speaker volume.

## [rev C] - 2026-09-24

### Added
- 1.69" ST7789 colour screen (Waveshare, 240×280) in the back cover, held by two clamp brackets pressing its standoffs through foam.
- Blender file `hardware/blender/superlens.blend` (assembly + print layout) and its build script.

### Changed
- Recommended lidar: LDROBOT D500 (STL-19P), a drop-in replacement for the LD19. The D800 (STL-27L) is listed as an option.
- The power bank now stays in your pocket on a 1 m USB-C cable. The strap slots are gone.
- The lidar PWM pin now goes to D9 instead of GND, so the firmware can control the motor speed.
- Pressing columns moved around the screen: MR60 columns lowered, LD2450 columns moved to the board ends.

## [rev B] - 2026-09-24

### Changed
- Solderless build: a pre-soldered XIAO ESP32S3 Sense, Dupont plugs and WAGO 221 lever connectors replace the soldered distribution board.
- The user's power bank now plugs straight into the XIAO's USB-C port, and the 5V pin feeds the sensors.
- M3 screws thread-form into PETG, so heat-set inserts are no longer needed.
- The tripod mount uses a captive 1/4"-20 nut instead of a heat-set insert.

### Added
- Desk stand that holds the grip so the head stays level.
- Print-bed orientations in the CAD source (`PART="print_*"`) and export scripts.

### Removed
- Trigger button, USB-C input breakout, switch cable and bulk capacitor.

## [rev A] - 2026-09-24

### Added
- First enclosure: head, back cover with pressing columns, raked pistol grip, lidar check template.
