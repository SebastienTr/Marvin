# Intelligence: what each sensor is for, where machine learning helps

Marvin already has a rule-based brain (`host/marvin_host/brain.py`): it follows the nearest person with the HLK-LD2450, decides whether they are present, seated and still, trusts or distrusts the MR60BHA2 vital signs, and emits events that the voice uses. This page is the plan for what comes next. Which sensor earns its place, how far machine learning can take the robot, and which behaviours we can hope to see emerge.

**In short:** every sensor is useful, but not equally. Most of the value comes from pretrained models and from learning *your* normal. The most original part is letting the sensors teach each other.

## What each sensor is for

Marvin sits on a desk and faces a seated person 0.65–1.1 m away.

| Sensor | What it brings | Limits | Used today | Verdict |
|---|---|---|---|---|
| **Camera** (OV3660) | The richest signal: who you are, where you look (screen, robot, away), posture, facial expression. It is also the teacher that labels data for the other sensors. | Blind in the dark; the most privacy-sensitive | One picture on demand: the `look` tool ([vision.md](vision.md)); the computer's camera stands in until the robot is built | Essential |
| **Microphone** (PDM) | Voice, plus the sound scene: a call in progress, silence, music, a door. | One microphone, so no direction | Voice (computer mic for now) | Essential |
| **60 GHz radar** (MR60BHA2) | Breathing and heart rate at exactly the desk distance. | Ruined by movement (typing hurts heart rate most). The kit reports processed values only, no raw phase signal, so no heart-rate variability. | Yes, with a reliability gate | The core, but imperfect |
| **24 GHz radar** (HLK-LD2450) | Arrivals, departures, someone approaching. Low power, works in the dark, sees no faces. | ±60° in front, 3 targets | Yes, drives the brain | The always-on trigger |
| **Lidar** (D800) | 360° awareness: someone behind you or entering the room. Precise ranges. Notices when the robot is moved. | See below | No | **Removed from the robot** (14 October 2026) |
| **PIR** (HC-SR501, bench board) | Someone moving nearby, for almost nothing, with no radar at all. | Movement only: no position, and someone sitting very still goes unnoticed; a minute to settle after power-on | Yes, as the presence of a board whose radar is simulated ([below](#a-pir-on-the-bench-board)) | A cheap stand-in |
| **DHT11** and **light** (bench board) | The room itself: temperature, humidity, bright or dark. Context for the voice (*it is 27 °C in here*) and for later habits. | The DHT11 is slow and coarse (±2 °C, ±5 %); the light sensor is not wired yet | Shown in the app, given to the language model | Context, not presence |

### The lidar question

> **Decided, 14 October 2026: rev F has no lidar.** The D800 moved to a separate project, a 3D room scanner. The jobs below go to the radars and the camera, or wait; the host still reads a lidar (the USB sensor bridge, a bench board), so this section stays as the record of why it was questioned.

On a fixed desk the lidar is the most expensive part, the only one that makes noise and vibrates (hence the TPU neck), and the largest power draw. Its scan plane sits about 13 cm above the desk, so a monitor and desk clutter hide part of the room, depending on where the robot stands. As `brain.py` notes, a single slice at chest height cannot tell a person from a chair back without a background model of the room.

It is still the only sensor that sees behind the robot. So we keep it, and give it the jobs nothing else can do:

- a **background model** of the room, learned over the first minutes, so anything that moves or appears stands out;
- **tracking outside the radars' field of view**: someone behind you, someone entering;
- **self-awareness**: when the map no longer matches, the robot has been moved or is in another room.

Open question for the first build: its PWM pin is tied to ground (default speed), so software cannot stop the motor. Measure its noise at the desk. If it bothers you, a later revision can switch its power from a free GPIO so it only spins on demand (when the LD2450 loses the person, when someone enters, or a few seconds per minute).

### A PIR on the bench board

The bench board ([wiring-bench.md](wiring-bench.md)) simulates the lidar and both radars, so its "person" walks a loop that has nothing to do with whoever sits at the desk. Its PIR is real. So for a device whose `HELLO` says its sensor data is simulated and that has a PIR (`ENVIRONMENT`, [protocol.md](protocol.md#room-sensors)), the host ignores the simulated radar frames and takes the PIR as the presence (`brain.py`, `Brain.onMotion` and `PresenceFeed` in the Java host):

- nothing during the PIR's first minute, while it settles (the brain's clock still moves: the robot counts as online);
- motion: `arrived` at once ("movement on the motion sensor"), no half-second confirmation: the PIR has already filtered it;
- no motion for 2 minutes (`motion_hold_s`, 120 s): `left` ("no movement for 120 s"). Someone at a desk moves often enough for a PIR at arm's length; someone reading very still may be taken for gone after two minutes, and comes back with the next movement;
- no position, distance or sitting: the eyes wake up and look straight ahead rather than following you, the break timer does not run, and the vital signs (simulated on that board) are ignored. The state says `motion_presence`, and Marvin's context says a motion sensor noticed movement, not that someone is in front of it.

Real sensors also beat simulated ones between devices: while a device with a real radar (a robot, or the [USB sensor bridge](usb-sensors.md) with an LD2450) has sent targets in the last 2 s, a simulated device's targets and vital signs do not reach the brain (`PresenceFeed`), and the app draws a real lidar's scan rather than a simulated one (`SensorScene`). The brain follows one person on one clock.

A real, live radar always wins: a PIR next to it is only noted, since the radar knows where you are and that you are still there when you do not move. Fusing a real radar and a real PIR (the PIR confirming an arrival, or holding a presence the radar lost) waits for a robot that has both.

## How far machine learning goes: three levels

### Level 1: pretrained models (about 80 % of the value, no training)

Assembly work, all local on Apple Silicon:

- **Speech:** VAD, Whisper, the local LLM (already in `voice/`).
- **Vision:** face detection and recognition (who is there), pose estimation (posture, head down, leaning back), gaze direction.
- **Sound:** a sound-event classifier (YAMNet or PANNs): door, voices, music, alarm.

### Level 2: learning your normal (where Marvin becomes yours)

- **Personal baselines**: heart rate, breathing and activity by hour of the day, then anomaly detection. "Faster breathing than usual for 20 minutes" means something; "18 breaths per minute" alone does not.
- **When to talk and when to stay quiet**, learned as a contextual bandit. Each time Marvin speaks up, your reaction is the reward: you answer gladly, you ignore it, or you tell it to be quiet. After a few weeks it stops interrupting at the wrong moment.
- **Habits**: arrival times, usual breaks, the afternoon slump.

### Level 3: sensors teaching each other (the original part)

- **The camera teaches the radars.** From synchronised recordings, a pose model labels every moment automatically (seated, leaning, standing, walking, head down, absent, who). A small model (1D CNN or GRU) then learns the same labels from the radars alone (and a USB lidar's scans, where there is one). The result is a camera-off mode that works in the dark and respects privacy, with most of the same understanding.
- **Two independent heart rates.** Remote photoplethysmography (rPPG) reads the pulse from tiny colour changes in the face. Compared with the 60 GHz radar, each one checks the other, and Marvin knows when its measurements can be trusted. rPPG through a compressed 10–15 fps stream at desk light is an experiment, not a promise.
- **Discovered states.** Clustering the fused features over days (a hidden Markov model or similar) splits your time into recurring modes: focused, restless, tired, away. No labels are needed; you name the clusters afterwards, with the LLM's help.

Level 3 is also a good learning project: real data, a real problem, and everything trains on a laptop GPU.

## Thinking and background tasks

Marvin runs **one language model** for everything (Qwen3.8 27B on the owner's Mac: two 27B models would not fit beside
the voice's models in 64 GB). It works in two regimes:

- **The conversation** answers at once: no reasoning by default (every token of thinking is silence before the first
  word). Marvin > Voice & models > **Thinking & tasks** can turn it on (low, medium, high); the thinking is never
  spoken, and the reply inspector shows it.
- **Background tasks** think hard (high by default): work in several steps ("prépare-moi…", "compare…", "look
  into…", "summarize my week…") that Marvin does with its tools while the conversation goes on, then tells you about.

**How a task starts.** The model decides: the conversation offers it `start_task(goal)`, and the persona says when to
use it (work in several steps, or anything asked "in the background"; never a question it can answer now). The turn
then ends with Marvin's own sentence, "Je m'en occupe, je te dis quand c'est prêt" (no second request to the model). A
light guard sits beside it: "fais-le en tâche de fond" / "in the background" starts a task without asking the model (the
goal is the question before when nothing else is said), and words that usually announce several steps add a one-line
hint to the question, never a decision. The app's Activity has a form for it too.

**How it runs.** One task at a time, with the conversation's model at the task's reasoning level and the conversation's
tools: `recall`, `remember`, `look` (with a camera), the weather (with the internet), and the connectors' when they are
connected ([below](#connecting-google)): `calendar_list_events`, `gmail_search`, and `gmail_create_draft`, the only
one that writes outside this computer. Each tool says what it does to the world (read, write here, write outside); a
call that writes outside waits for your approval ([below](#approvals)). `forget` stays in the conversation: it needs
your yes. The task's prompt names its tools and says it has no other (no web unless a tool does it, never an invented
source or number) and that it can never send, delete or forward an email. It finishes with `final_answer`: a sentence or two Marvin can say, and the
result in Markdown. Budgets: 12 requests to the model, 20 minutes, 40 000 tokens written (thinking included); when one
is nearly spent the model is asked to finish, and a task that spends one fails with what it had. Every request (with
the model's reasoning) and every tool run is a step, kept in PostgreSQL; a task that was running when the host stopped
is *interrupted* after the restart.

**The voice comes first.** Ollama runs Qwen3.8 one request at a time whatever `OLLAMA_NUM_PARALLEL` says (its runner
for this architecture, and its MLX runner, take one request at a time), so a reasoning step could make a spoken question
wait a minute. Instead, as soon as a question is heard (or Marvin listens, thinks or speaks) a task's request is cut,
Ollama drops it when the connection closes, and the task makes it again two seconds after the conversation is over;
the cut request does not count. With a model Ollama does run in parallel, `OLLAMA_NUM_PARALLEL=2` and
`MARVIN_LLM_PARALLEL=2` let tasks run beside the conversation instead (`./marvin doctor` says which case applies).

### Approvals

A task that wants to write outside this computer (in v1: create a Gmail draft) asks first. The call waits as an
approval, with a preview of exactly what will be written (to, subject, body); the task shows *Waiting for your
approval* (`needs_approval`) and lets the next task run meanwhile. Marvin says it once if you are there ("Pour « … »,
j'ai besoin de ton accord dans l'appli."), never again. In the app you **approve** (the draft is created as shown, and
the approval links to it in Gmail) or **reject** (with a reason if you like: the task is told and goes on without it).
Nobody answers within an hour, the task is cancelled, or the host stops: the approval expires and nothing is written.
The wait does not count against the task's 20 minutes.

**Marvin never sends an email.** There is no tool that sends, forwards, replies, deletes, trashes, pays or shares: the
tool filter refuses any such tool from any source, and below it the Gmail client only knows the calls it needs (search,
read headers, create a draft). You send drafts from Gmail yourself. A tool that writes outside is never offered in the
conversation itself: only in a task, where each call can be approved.

When a task ends, what it found also goes to memory's event log, so the day's summary mentions it
([memory.md](memory.md#the-event-log)).

**Thinking levels.** What reaches Ollama is the level the model lists: Qwen3.8 accepts `false`, `low`, `medium` and
`xhigh`, and Ollama turns any other value, `"high"` and `true` included, into its default for it, `medium`. So *high*
is sent as `xhigh` there (and as `high` to a model that lists it, such as gpt-oss). A server that refuses a level gets
thinking simply on, then off. Spring AI's Ollama client cannot send `xhigh`, so requests that think go to Ollama's HTTP
API directly, with the same body.

**Telling you.** When a task is done, Marvin says it on its own ("C'est prêt, pour « … » : …") if you are there and not
talking with it: the presence sensor says so, or, without one, you talked with Marvin in the last 15 minutes. During a
conversation it waits for its end; when you are away, during quiet hours or with the voice off, the next answer
mentions it first (and it is said when the sensor sees you arrive). "C'est quoi le résultat ?" works at any time
(`task_result`). The app shows every task live in Activity, with its result and its steps, and the Log panel a line when
one ends.

## The soul

Marvin's character has two parts ([design.md](design.md#8-soul)). The **core** is yours: its character, what it will
not do, how it speaks to you (Marvin > Soul; it starts as "Calm, grown-up, dry humour. Calls the owner by their first
name. Answers in the language it is spoken to in. Never pretends to sense what it cannot."). The **learned** part is
what Marvin learned about how to be with you: "Prefers short answers in the morning", "Does not want break reminders
during calls". Both are kept in English, about 150 tokens each, and sit in the system prompt right after the rules.

Once a week (Sunday from 21:00, or the first quiet moment after when the Mac slept), the **reflection** reads the week's
day summaries and what you said about how Marvin behaves ("tais-toi", "too long", "don't remind me during calls") and
proposes a new learned part. The app shows it as a diff, with why and from which of your words. Nothing changes until you
approve it; you can also edit the learned part yourself, reject, or go back to any version (each one is kept).

The **guardrails** are rules in the code, not only in a prompt, checked on every proposal and every edit of the learned
part: no manipulation (persuasion, flattery as a strategy, invented feelings), no dependency (more time with Marvin,
discouraging other people or help, speaking up more), honesty (never claiming what it does not sense or know), and
bounds (the size, no tool or permission granted, no rule lifted, nothing that negates the core). A proposal that breaks
one is shown to you as refused, with the reason.

## Connecting Google

Marvin reads your Google Calendar and searches your Gmail, and drafts emails you approve, with **your own** Google OAuth
client: no shared app, no server between you and Google. Tokens stay in the macOS Keychain (on Linux, in
`~/.local/share/marvin/secrets.json`, readable by you only). It asks only for `calendar.readonly`, `gmail.readonly`
and `gmail.compose` (the narrowest permission that can create a draft); never `gmail.send` or a permission to delete.

Once, about ten minutes, in a browser:

1. Open [console.cloud.google.com](https://console.cloud.google.com/), sign in with the Google account Marvin will use,
   and create a project (*Marvin*).
2. *APIs & Services > Library*: enable the **Gmail API** and the **Google Calendar API**.
3. *Google Auth Platform* (the OAuth consent screen): *Branding*: app name *Marvin*, your address as the support and
   contact email. *Audience*: *External*; add your own address as a **test user**. *Data access* (optional, Marvin asks
   for them anyway): `.../auth/calendar.readonly`, `.../auth/gmail.readonly`, `.../auth/gmail.compose`.
4. *Clients > Create client*: application type **Desktop app**, name *Marvin host*. *Download JSON*.
5. Save that file as `~/.local/share/marvin/google-oauth-client.json` (Marvin's data folder; `MARVIN_GOOGLE_CLIENT`
   points elsewhere), then `chmod 600` it.
6. In Marvin's app **on the Mac** (the consent comes back to `127.0.0.1`): Marvin > Connectors > *Connect* Google
   Calendar, then Gmail. Google warns that it has not verified the app: it is yours, choose to continue, tick every box.

While the consent screen's publishing status is *Testing*, Google lets a refresh token live 7 days: Marvin then shows
the connector as needing attention, and *Connect again* fixes it. To avoid it, *Audience > Publish app* (*In
production*): for an app with a few users Google does not require its verification, and keeps showing you the
"unverified app" warning instead. Disconnect revokes Marvin's access at Google; you can also remove it from your Google
account's *Security > Third-party access*.

## Emergent behaviours we can hope for

None of these is programmed directly. They come from combining sensors, a little learning and the LLM's memory:

- **Understanding situations.** It hears two voices but sees one person: you are on a call, so it stays quiet. You are still, head down, breathing slowly: you are dozing. A person appears behind you, outside the camera's view: it tells you.
- **Knowing your rhythm.** "You usually lose focus around 4 pm." "You've been arriving later since Monday."
- **Finding correlations on its own.** Once a week the LLM reads the history and reports what it noticed: "after short nights you breathe faster at the desk", "your heart rate rises during some calls".
- **A personality shaped by use.** It becomes chattier or quieter depending on what you reward, with no rule written for it.
- **Self-awareness.** The camera's view of the room changed: it was moved. Two sensors disagree: one is covered or misaligned.

What not to expect: real emotions, a reliable stress diagnosis, heart-rate variability, or a continuous stream of consciousness. The LLM reacts to events; it does not "live" in between. The illusion can still be very good.

## Principles

- **The LLM never sees raw streams.** Fast models turn sensors into a compact state and discrete events; the LLM reads only those, through tools, and never invents observations (already the rule in `voice/`).
- **Local by default.** Images, audio and vital signs stay on the computer. A cloud model, if ever used, receives the text summary only.
- **Ask, don't diagnose.** Marvin reports deviations from your normal and asks ("Pause?"). Your answer becomes a label.
- **Everything replays.** Every model runs on recordings exactly as it runs live, so each change can be measured on the same data.

## Plan

Data comes first: nothing at level 2 or 3 is possible without weeks of recordings. The parts arrive around 9–10 October 2026.

- [ ] Recordings: add camera keyframes and audio features (not raw audio) to `.mvrec` as new record kinds, plus a label stream (brain events and your feedback). Unknown kinds are already skipped, so the format stays version 1.
- [ ] First build: measure the MR60BHA2's heart-rate quality while typing.
- [x] Look on demand: one picture from the robot's or the computer's camera, described by the local vision model ([vision.md](vision.md)).
- [x] Reasoning levels and background tasks: one model, the conversation at once, tasks thinking hard with Marvin's tools ([above](#thinking-and-background-tasks)).
- [x] Tasks: approvals for tools that write outside this computer (email drafts), task results in memory's log.
- [x] Connectors: Google Calendar (read) and Gmail (search, drafts after an approval), with the owner's OAuth client.
- [x] The soul: the owner's core, the learned part, the weekly reflection as a diff, the guardrails.
- [ ] Connectors' ingestion into memory (calendar as live context, email metadata), MCP as their transport (v1.1).
- [ ] Level 1 vision: face recognition and pose on the MJPEG stream, as new brain inputs.
- [ ] Level 1 sound: sound-event classifier; "on a call" detection (voices heard, one person seen).
- [ ] Background model of the room from the camera, "I was moved" event (the lidar's jobs, without the lidar).
- [ ] Level 2: personal baselines and anomaly events; feedback capture in the app and by voice.
- [ ] Level 2: the "when to talk" bandit behind break reminders and remarks.
- [ ] Level 3: camera-teaches-radar posture model, camera-off mode.
- [ ] Level 3: rPPG experiment against the 60 GHz radar.
- [ ] Weekly LLM review of the history: patterns worth telling you about.
