<!-- SPDX-License-Identifier: CC-BY-4.0 -->
# Marvin v1: the acceptance checklist

**One pass through everything Marvin v1 promises, on the real robot, in about 30 minutes.** Each line says what
to do and what you should see; tick it or write down what you saw instead. When every line is ticked, v1 is done.
At the end, paste the output of `./marvin report` with the ticked list.

The numbers in the last column are the v1 criteria they prove (the *Definition of "Marvin v1 complete"* of the v1
plan). What agents and CI prove on their own (the builds, the tests, the doc lint, the end-to-end runs in demo
mode) is not repeated here: the integrator confirms CI is green before you start.

## Before you start (not counted in the 30 minutes)

- The robot is assembled ([tutorial 3](assembly.md)), flashed with the `xiao_esp32s3` firmware, and on the desk
  0.6 to 1.1 m in front of your chair. The MR60BHA2 kit is flashed with `mr60_bridge`.
- On the Mac: Docker Desktop and Ollama running, `qwen3.8:27b-mlx` and `qwen3-embedding:8b` pulled
  ([tutorial 1](getting-started.md)), `git pull` done.
- The first-run setup in the app is finished (your name, your languages, your voice calibrated, quiet hours), and
  Google is connected in the app (Calendar and Gmail, with your own OAuth client: once, about 10 minutes).
- Something on tomorrow's calendar, your watch on your wrist, the phone with the app open on the same Wi-Fi.
- **Start the checklist after at least 30 minutes away from the desk** (after lunch, for instance): the first
  check is the greeting, and Marvin greets only after an absence.

## The checklist

### 1. Start (3 min)

| ✓ | # | Do | Expected | v1 |
|---|---|---|---|---|
| ☐ | 1.1 | Unplug the robot's USB-C, plug it back in | The face appears within 5 s. | 4 |
| ☐ | 1.2 | `./marvin up` | The prompt comes back with `Marvin's app: http://localhost:8765/`; within 15 s, **Senses** > Devices lists the robot (XIAO ESP32S3 Sense), not simulated. | 4 |
| ☐ | 1.3 | Senses > Devices | The MR60BHA2 kit is listed as its own device, connected. | 5 |
| ☐ | 1.4 | `./marvin doctor` | Only `ok` lines (a `note` is acceptable only if it names something you chose not to use), and `Ready` at the end. | 6 |
| ☐ | 1.5 | The app's setup page (`/setup`) | Every step `ok` (or skipped on purpose): host, model, voice, robot, MR60, speaker, voiceprint, owner, quiet hours. | 6 |

### 2. Coming back to the desk (5 min)

| ✓ | # | Do | Expected | v1 |
|---|---|---|---|---|
| ☐ | 2.1 | Walk in and sit down in front of Marvin (after 30 min or more away) | Within 5 s, from the robot's speaker: "Bonjour Sébastien" (or "Hi Sébastien", in the language you last spoke with Marvin). The greeting is in Talk's conversation. | 7 |
| ☐ | 2.2 | Stand up, walk out of the room for a minute, come back and sit down | No second greeting (at most one per 10 minutes). | 7 |
| ☐ | 2.3 | Sit still, breathing normally, for 45 s; read Home's vitals card | Heart rate and breathing appear within 45 s of sitting still, marked as measured (not simulated). | 8 |
| ☐ | 2.4 | Compare the heart rate with your watch, three times a minute apart | Within ±8 bpm of the watch each time. Write down the three pairs. | 8 |

### 3. Talking (8 min)

| ✓ | # | Do | Expected | v1 |
|---|---|---|---|---|
| ☐ | 3.1 | Say "Marvin, quel temps fait-il demain ?" | A spoken answer in French, from the robot's speaker (not the Mac's). | 9 |
| ☐ | 3.2 | Say "Marvin, what time is it in Tokyo?" | A spoken answer in English, from the robot's speaker. | 9 |
| ☐ | 3.3 | Ask eight more short questions, mixing French and English | Each one answered. Then `./marvin report`: over these 10 questions, end of speech → first audio is **p50 ≤ 2.5 s and p90 ≤ 4 s** (the latency lines, `total_ms`). Write down both numbers. | 9 |
| ☐ | 3.4 | Tap the orange knob, then ask a question without saying "Marvin" | A click, Marvin listens (Talk shows *Listening*), then answers. | 10 |
| ☐ | 3.5 | Hold the knob for 2 s | A click after about 1.5 s; Talk's **Mute** button is pressed; "Marvin, ..." is not heard. Hold it again: unmuted. | 10 |

### 4. Working in the background (6 min)

| ✓ | # | Do | Expected | v1 |
|---|---|---|---|---|
| ☐ | 4.1 | Say "Marvin, in the background, summarise my calendar for tomorrow." | Marvin says it is on it and stays free to talk. **Activity** shows the task running, then done; Marvin says when it is done; the result in Activity lists tomorrow's events. | 11 |
| ☐ | 4.2 | Say "Marvin, prépare un brouillon d'e-mail à moi-même pour dire que le test v1 est passé." | The task stops at an approval: Home's *Your decision* (and Activity) shows the draft (to, subject, body) with **Approve** and **Reject**. Nothing is in Gmail yet. | 12 |
| ☐ | 4.3 | **Approve** | The draft appears in Gmail's **Drafts**. Gmail's **Sent** has nothing new: Marvin never sends. | 12 |

### 5. Memory and Soul (5 min)

| ✓ | # | Do | Expected | v1 |
|---|---|---|---|---|
| ☐ | 5.1 | Say "Marvin, remember that I play football on Wednesdays." | **Memory** lists it at once, marked as yours, with its source (that sentence, dated). | 13 |
| ☐ | 5.2 | Say "Marvin, what do I do on Wednesdays?" | Football. | 13 |
| ☐ | 5.3 | Tomorrow, ask again (tick it then) | Football, after a night (and a nightly memory pass) in between. | 13 |
| ☐ | 5.4 | Marvin > **Soul**: start a reflection | A proposal for the learned part appears as a diff (kept, added, removed lines) with its guardrail checks. The Soul does not change. | 13 |
| ☐ | 5.5 | **Reject** the proposal (or approve it, if you like it) | Rejected: the Soul is unchanged. Approved: a new version, listed in the Soul's versions, which you can revert. | 13 |

### 6. Breaks (3 min)

| ✓ | # | Do | Expected | v1 |
|---|---|---|---|---|
| ☐ | 6.1 | Marvin > Preferences: break reminder **2 min**. Stay seated | After 2 min seated: one spoken break reminder. Only one. | 14 |
| ☐ | 6.2 | Stand up and sit down again, and start talking to Marvin just before the 2 min are up | The reminder waits until the conversation is over: never in the middle of it. | 14 |
| ☐ | 6.3 | Preferences: quiet hours covering now; stand, sit again, wait 2 min | No reminder. | 14 |
| ☐ | 6.4 | Put the break reminder back to **50 min** and the quiet hours back to yours | Saved. | — |

### 7. Hand back

`./marvin report`, pasted with this list (ticked, and what you saw instead where a line failed). The report has the
versions, the doctor's lines, the devices, the robot's self-test, the last latency lines and the last errors, and
nothing personal.

## Without the hardware

The same behaviour is checked without a robot, a model or a microphone by the end-to-end scripts in
[host-java/e2e](../host-java/e2e/README.md) (a stand-in Ollama, a simulated robot, the app driven by Playwright),
and `./marvin demo` runs a simulated robot and a simulated past week on its own database.

The longer manual plan written for the move from the Python host to the Java host (start and stop, robot link,
the app section by section, failure cases, performance, memory step by step) is in the repository's history:
`git show b5450fa:docs/test-plan.md`.
