<!-- SPDX-License-Identifier: MIT -->
# Marvin's app

Marvin comes with a small web app that runs on your computer, next to `marvin-host`. You open
it in a browser, on the computer or on your phone. The Rerun viewer is the developer's view of
the sensors. The app is the owner's view: how Marvin is doing, how long you have been sitting,
and what your day and week look like.

<p align="center">
  <img src="images/ui_phone.png" alt="Marvin's app on a phone: the live face, 'You've been at your desk for 38 min', breathing and heart rate, the break timer at 38 of 50 minutes, and the start of today's timeline" width="300">
</p>

<p align="center">
  <img src="images/ui_desktop.png" alt="Marvin's app on a desktop browser: the face and break timer on the left; today's timeline, key numbers, the last 7 days and recent events on the right" width="800">
</p>

## Try it

```bash
marvin-host ui --demo --open        # a simulated robot and a simulated past week, time 10x faster
marvin-host ui --demo --speed 60    # faster: a minute of sitting per second
```

The demo keeps nothing: its history lives in a temporary folder that is deleted when you stop it
(Ctrl-C).

With the real robot, the app starts with `marvin-host run` (turn it off with `--no-ui`).
`marvin-host ui` runs the brain and the app without the Rerun viewer, which is lighter for
everyday use.

## What it shows

| Part | Content |
|---|---|
| Face | Marvin's eyes, live, drawn by the same code and from the same brain state as the robot's screen. |
| Status | One sentence: "You've been at your desk for 42 min", "You're here, up and about", "Marvin is asleep. Nobody around.", "Marvin is offline". Breathing and heart rate appear under it only while the radar reads them reliably (you are seated and still). |
| Break | How long you have been sitting, against the break reminder (50 minutes by default). The bar turns orange when it is time to stand up. Standing up resets it. |
| Today | A timeline of the day: when you were around (dark), when you were seated (light), and break reminders (orange dots). Below it: time seated, sitting sessions, breaks, longest streak, when you arrived and last left, and your average breathing and heart rate. The arrows show earlier days. |
| Last 7 days | Time seated per day. Tap a day to see its timeline. |
| Recent | What happened, in plain words: you came in, sat down, stood up after 47 minutes, break reminders. |
| Settings | Break reminder interval, quiet hours, voice (for later), 12- or 24-hour clock. |

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
that try to reach services on your computer), and only accepts settings changes sent by the app
itself.

The app has no HTTPS: the key and the data travel unencrypted on your local network, like most
home devices. Do not expose the port to the internet.

## Privacy: your data stays on your computer

- Everything is kept in one SQLite file on the computer running `marvin-host`:
  `~/.local/share/marvin/marvin.db` (or `$XDG_DATA_HOME/marvin/`, or the folder in
  `MARVIN_DATA_DIR` if you set it). The settings dialog shows where.
- It holds Marvin's events (arrived, sat down, stood up...), one summary per minute (whether
  someone was there, and the average breathing and heart rate when they were measured), and
  your settings. No images, no sound, no raw radar data.
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
| `server.py` | `UIServer(brain, host, port, store, token)`: HTTP API, live stream, face, settings |
| `store.py` | `EventStore`: the SQLite file (events, per-minute samples, settings) |
| `stats.py` | Pure functions: intervals and daily statistics from events, the sentences |
| `demo.py` | The simulated robot and past week for `--demo` |
| `static/` | The page |

```python
from marvin_host import ui
server = ui.UIServer(brain).start()      # brain: marvin_host.brain.Brain
...
server.stop()
```

| Endpoint | |
|---|---|
| `GET /` | the app |
| `GET /api/state` | `{state, today, settings}`: presence, status sentence, break progress, today's stats |
| `GET /api/day?date=YYYY-MM-DD` | one day's stats and timeline (default: today) |
| `GET /api/history?days=7` | time seated per day, oldest first |
| `GET /api/events?since=ID&limit=50&quiet=1` | recent events, newest first, with a sentence each (`quiet` hides vital-sign events) |
| `GET /api/stream` | Server-Sent Events: `state` (2 per second), `event` (as they happen), `today`, `settings` |
| `GET /face.png` | the face right now (240 × 280 PNG); `503` if it cannot be drawn |
| `GET`, `POST /api/settings` | settings; POST a JSON object with the fields to change |

Times are Unix seconds from the computer's clock; days are local calendar days. The break
reminder setting also sets the brain's `still_long_s`, so the robot's own reminder follows it.
Tests: `pytest tests/test_ui.py`.
