# Intelligence: what each sensor is for, where machine learning helps

Marvin already has a rule-based brain (`host/marvin_host/brain.py`): it follows the nearest person with the HLK-LD2450, decides whether they are present, seated and still, trusts or distrusts the MR60BHA2 vital signs, and emits events that the voice uses. This page is the plan for what comes next. Which sensor earns its place, how far machine learning can take the robot, and which behaviours we can hope to see emerge.

**In short:** every sensor is useful, but not equally. Most of the value comes from pretrained models and from learning *your* normal. The most original part is letting the sensors teach each other.

## What each sensor is for

Marvin sits on a desk and faces a seated person 0.65–1.1 m away.

| Sensor | What it brings | Limits | Used today | Verdict |
|---|---|---|---|---|
| **Camera** (OV3660) | The richest signal: who you are, where you look (screen, robot, away), posture, facial expression. It is also the teacher that labels data for the other sensors. | Blind in the dark; the most privacy-sensitive | Streamed, not analysed | Essential |
| **Microphone** (PDM) | Voice, plus the sound scene: a call in progress, silence, music, a door. | One microphone, so no direction | Voice (computer mic for now) | Essential |
| **60 GHz radar** (MR60BHA2) | Breathing and heart rate at exactly the desk distance. | Ruined by movement (typing hurts heart rate most). The kit reports processed values only, no raw phase signal, so no heart-rate variability. | Yes, with a reliability gate | The core, but imperfect |
| **24 GHz radar** (HLK-LD2450) | Arrivals, departures, someone approaching. Low power, works in the dark, sees no faces. | ±60° in front, 3 targets | Yes, drives the brain | The always-on trigger |
| **Lidar** (D800) | 360° awareness: someone behind you or entering the room. Precise ranges. Notices when the robot is moved. | See below | No | The most questionable |

### The lidar question

On a fixed desk the lidar is the most expensive part, the only one that makes noise and vibrates (hence the TPU neck), and the largest power draw. Its scan plane sits about 13 cm above the desk, so a monitor and desk clutter hide part of the room, depending on where the robot stands. As `brain.py` notes, a single slice at chest height cannot tell a person from a chair back without a background model of the room.

It is still the only sensor that sees behind the robot. So we keep it, and give it the jobs nothing else can do:

- a **background model** of the room, learned over the first minutes, so anything that moves or appears stands out;
- **tracking outside the radars' field of view**: someone behind you, someone entering;
- **self-awareness**: when the map no longer matches, the robot has been moved or is in another room.

Open question for the first build: its PWM pin is tied to ground (default speed), so software cannot stop the motor. Measure its noise at the desk. If it bothers you, a later revision can switch its power from a free GPIO so it only spins on demand (when the LD2450 loses the person, when someone enters, or a few seconds per minute).

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

- **The camera teaches the radars.** From synchronised recordings, a pose model labels every moment automatically (seated, leaning, standing, walking, head down, absent, who). A small model (1D CNN or GRU) then learns the same labels from radar and lidar alone. The result is a camera-off mode that works in the dark and respects privacy, with most of the same understanding.
- **Two independent heart rates.** Remote photoplethysmography (rPPG) reads the pulse from tiny colour changes in the face. Compared with the 60 GHz radar, each one checks the other, and Marvin knows when its measurements can be trusted. rPPG through a compressed 10–15 fps stream at desk light is an experiment, not a promise.
- **Discovered states.** Clustering the fused features over days (a hidden Markov model or similar) splits your time into recurring modes: focused, restless, tired, away. No labels are needed; you name the clusters afterwards, with the LLM's help.

Level 3 is also a good learning project: real data, a real problem, and everything trains on a laptop GPU.

## Emergent behaviours we can hope for

None of these is programmed directly. They come from combining sensors, a little learning and the LLM's memory:

- **Understanding situations.** It hears two voices but sees one person: you are on a call, so it stays quiet. You are still, head down, breathing slowly: you are dozing. A person appears behind you, outside the camera's view: it tells you.
- **Knowing your rhythm.** "You usually lose focus around 4 pm." "You've been arriving later since Monday."
- **Finding correlations on its own.** Once a week the LLM reads the history and reports what it noticed: "after short nights you breathe faster at the desk", "your heart rate rises during some calls".
- **A personality shaped by use.** It becomes chattier or quieter depending on what you reward, with no rule written for it.
- **Self-awareness.** The lidar map changed: it was moved. Two sensors disagree: one is covered or misaligned.

What not to expect: real emotions, a reliable stress diagnosis, heart-rate variability, or a continuous stream of consciousness. The LLM reacts to events; it does not "live" in between. The illusion can still be very good.

## Principles

- **The LLM never sees raw streams.** Fast models turn sensors into a compact state and discrete events; the LLM reads only those, through tools, and never invents observations (already the rule in `voice/`).
- **Local by default.** Images, audio and vital signs stay on the computer. A cloud model, if ever used, receives the text summary only.
- **Ask, don't diagnose.** Marvin reports deviations from your normal and asks ("Pause?"). Your answer becomes a label.
- **Everything replays.** Every model runs on recordings exactly as it runs live, so each change can be measured on the same data.

## Plan

Data comes first: nothing at level 2 or 3 is possible without weeks of recordings. The parts arrive around 9–10 October 2026.

- [ ] Recordings: add camera keyframes and audio features (not raw audio) to `.mvrec` as new record kinds, plus a label stream (brain events and your feedback). Unknown kinds are already skipped, so the format stays version 1.
- [ ] First build: measure lidar noise and vibration at the desk, and MR60BHA2 heart-rate quality while typing.
- [ ] Level 1 vision: face recognition and pose on the MJPEG stream, as new brain inputs.
- [ ] Level 1 sound: sound-event classifier; "on a call" detection (voices heard, one person seen).
- [ ] Lidar: background model of the room, tracking behind the robot, "I was moved" event.
- [ ] Level 2: personal baselines and anomaly events; feedback capture in the app and by voice.
- [ ] Level 2: the "when to talk" bandit behind break reminders and remarks.
- [ ] Level 3: camera-teaches-radar posture model, camera-off mode.
- [ ] Level 3: rPPG experiment against the 60 GHz radar.
- [ ] Weekly LLM review of the history: patterns worth telling you about.
