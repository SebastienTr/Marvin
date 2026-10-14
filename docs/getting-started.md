<!-- SPDX-License-Identifier: CC-BY-4.0 -->
# Tutorial 1: run Marvin on your Mac

**Marvin's brain runs on your computer, not in the robot. This tutorial installs it and runs it, first with a
simulated robot (no hardware at all), then for real with a local language model.** About 30 minutes of your
time, plus downloads (about 25 GB in all, most of it the language model).

Nothing here needs the robot. When you are done, the [XIAO bench](xiao-bench.md) (tutorial 2) connects the real
electronics to what you installed here. The full list of tutorials is in [docs/README.md](README.md).

## What you need

- **A Mac with Apple Silicon** (M1 or later). Marvin is developed on an M3 Max with 64 GB. The language model
  takes about 18 GB of memory and the voice's models a few more, so 32 GB is the practical minimum for the model
  used here. (Linux on x64 or arm64 also works for everything but Marvin's own voice; this tutorial is written for
  macOS.)
- **About 30 GB of free disk space.**
- **An internet connection** for the downloads. Afterwards Marvin runs without one: no cloud, no account.

You type commands in the **Terminal** app (Applications > Utilities > Terminal). Each grey block below is one
command: copy it, paste it into the Terminal, press Enter, and wait for the prompt to come back before the next one.

## 1. Install the tools (once)

**Homebrew**, the usual way to install developer tools on a Mac. If `brew --version` already prints a version, skip
this. Otherwise follow the one-line install on [brew.sh](https://brew.sh), and run the two `echo`/`eval` lines it
prints at the end.

**Git, Python and ffmpeg:**

```bash
brew install git python ffmpeg
```

Python 3.10 or newer runs the voice (the `python3` that comes with macOS is too old). ffmpeg lets Marvin look through
the Mac's camera when you ask it to; it is optional.

**Docker Desktop**, which runs Marvin's database: download it from
[docker.com/products/docker-desktop](https://www.docker.com/products/docker-desktop/), open it once, accept its
terms, and leave it running. You do not need a Docker account.

> Start Docker Desktop **before the first** `./marvin demo` or `./marvin up`. The first start decides where your
> data lives and that choice is kept: without Docker, the host runs its own small database instead (fine for a
> demo, but memory searches more slowly without pgvector), and it will not move your data to Docker later.

**Ollama**, which runs the language model on your Mac:

```bash
brew install ollama
```

or the app from [ollama.com/download](https://ollama.com/download). Either way, it must be running: the app runs it
in the menu bar; with Homebrew, `brew services start ollama` starts it now and at every login.

You do **not** need to install Java: `./marvin` downloads its own copy the first time (into
`~/.local/share/marvin/jdk`, nothing system-wide).

## 2. Get the code

```bash
mkdir -p ~/workspace
git clone https://github.com/SebastienTr/Marvin ~/workspace/marvin
cd ~/workspace/marvin
```

Every command from now on is typed in this folder. In a new Terminal window, `cd ~/workspace/marvin` first.

## 3. Check the Mac

```bash
./marvin doctor
```

It checks everything Marvin needs and prints one line each: `ok` is fine, `note` is something that is missing but
not blocking (with a line `->` that says how to fix it), `FAIL` must be fixed first. On a Mac set up as above, the
first time, expect lines like these:

```
Marvin doctor
  ok   system: mac aarch64 (Darwin ...)
  ok   curl
  ok   tar
  note no JDK 25: ./marvin up will download Eclipse Temurin 25 into ~/.local/share/marvin/jdk
  note the Java host will be built by ./marvin up (host-java/mvnw -DskipTests package)
  ok   Docker: ...
  note image pgvector/pgvector:pg18 will be downloaded by ./marvin up (about 160 MB)
  ok   port 5433 free for PostgreSQL
  ok   port 8765 free for the app
  ok   UDP port 47100 for the robot
  ok   Python: Python 3.1x.x
  note no host/.venv: the voice needs it; ./marvin up creates it
  ok   no USB serial adapter plugged in (...)
  ok   Ollama at http://localhost:11434
  note model qwen3:4b-instruct is not pulled
         -> ollama pull qwen3:4b-instruct
  ...
Ready: ./marvin up (or ./marvin demo)
```

The `note` lines about the JDK, the build, the image and `host/.venv` go away by themselves after the first start.
Ignore the line about `qwen3:4b-instruct` for now: that is the host's built-in default, and you will pick a better
model in step 5. If the last line says `problem(s) to fix`, fix each `FAIL` with the `->` line under it and run
`./marvin doctor` again.

## 4. The demo: Marvin with a simulated robot

```bash
./marvin demo
```

The first time it downloads Java (about 200 MB) and builds the host: **a few minutes**, with little on the screen
(it says `Building the Java host (first time: a few minutes) ...`), then makes a small Python environment for the
simulated robot (`Creating the Python environment in host/.venv (a few minutes) ...`). Then you should see:

```
Starting PostgreSQL 18 with pgvector (Docker, first time: downloading the image) ...
Starting Marvin's host (demo) ...
Marvin's app: http://localhost:8765/
  on a phone on the same Wi-Fi: http://192.168.x.x:8765/?token=...
  log: ./marvin logs    stop: ./marvin down
```

and the prompt comes back: the host keeps running in the background. Open **http://localhost:8765/** in your browser.

What you are looking at: a simulated robot and a simulated person who comes in, sits down, stands up and leaves, in
a loop of about a minute and a half, and a simulated past week of history. The top bar shows a **Simulated** badge.
Take a short tour:

| Where | What to look at |
|---|---|
| **Home** | Marvin's eyes follow the simulated person; the sentence under them changes ("You're here, up and about"); *Recent moments* fills in; breathing appears while the person is seated, marked *Simulated reading*. |
| **Activity** > History | Arrows back in time: the simulated week, its timeline and its conversations. |
| **Memory** | What Marvin remembers, each memory with its source. In the demo it may say that the embedding model is missing: normal, Ollama has no models yet. |
| **Senses** | Everything Marvin perceives, live: the radar's view of the simulated person, the devices. |
| **Marvin** > System | Each part of the host with its state, and the host's log. |

The same app opens on your phone: on the same Wi-Fi, open the second address `./marvin demo` printed (with
`?token=`: the key that keeps other people on your network out).

The demo has its own data and its own copy of the voice's settings: nothing you do in it touches the real thing.
Stop it:

```bash
./marvin down
```

It answers `Stopped.`, and `./marvin status` says `host: not running`.

## 5. The real thing: your model, your voice

**Download the models** (about 23 GB; Ollama must be running):

```bash
ollama pull qwen3.8:27b-mlx         # the language model, Qwen3.8 27B (about 18 GB)
ollama pull qwen3-embedding:8b      # what memory searches with (about 4.7 GB)
```

**Start Marvin:**

```bash
./marvin up
```

The first `up` also sets up the voice: a Python environment in `host/.venv`, then Marvin's own voice model (about
1.5 GB), the speech recognition model (about 2.5 GB) and the ears' small models, each with its progress. Allow
**several minutes**. It ends like the demo: `Starting Marvin's host (live) ...`, then `Marvin's app:
http://localhost:8765/`. If the voice could not be installed (offline, for instance), Marvin starts without it and
says so; `./marvin restart` tries again.

**Choose the model.** In the app: **Marvin** > **Voice & models**, *Language model*: type `qwen3.8:27b-mlx`, then
**Apply** (the voice restarts with it). Check from the Terminal:

```bash
./marvin doctor
```

Now expect `ok   model qwen3.8:27b-mlx pulled` and `ok   embedding model qwen3-embedding:8b pulled (memory)`, and the
voice's lines (`the voice's Python packages are installed`, `Marvin's own voice: ...`, `the ears: ...`,
`speech recognition: ...`) all `ok`.

**Talk to Marvin.** Open **Talk** and turn the voice on with the switch at the top (the first time, the models load
for a few seconds). macOS asks once whether the Terminal may use the microphone: allow it.

1. Type `Bonjour Marvin, qui es-tu ?` in the text box and press the arrow. Marvin answers aloud, in French, through
   the Mac's speakers, and the answer appears as it is spoken.
2. Say **"Marvin, what time is it?"** Your sentence appears, then the answer, in English.
3. Under an answer, open **Why this answer**: what Marvin heard, what it answered, and where the time went.

There is no robot yet, so Marvin uses the Mac's microphone and speakers, and Senses says no robot is connected.
When the robot links (tutorial 2), the voice moves to the robot's own microphone and speaker.

## Everyday commands

| Command | What it does |
|---|---|
| `./marvin up` | Start everything (the database, the host, the voice). The app: http://localhost:8765/ |
| `./marvin down` | Stop everything `up` started. Your data is kept. |
| `./marvin restart` | Stop and start again (and rebuild, after a `git pull`) |
| `./marvin status` | What is running |
| `./marvin logs` | Follow the host's log (`Ctrl+C` to stop reading; the host keeps running). `./marvin logs -n 50`: the last 50 lines. |
| `./marvin doctor` | What is missing, and how to fix it |
| `./marvin demo` | The demo (stop the real host first: only one runs at a time) |

To update Marvin later: `./marvin down`, `git pull`, `./marvin up` (it rebuilds what changed).

## When something goes wrong

| What you see | What to do |
|---|---|
| `port 8765 is already in use` | Another host is running: `./marvin down`, then try again. Something else on that port: `MARVIN_PORT=8766 ./marvin up`, and the app is on 8766. |
| `your data is in Docker ... but Docker is not running` | Open Docker Desktop, wait until it says it is running, then `./marvin up` again. |
| `the host stopped while starting` | The last lines of the log are printed above the message; all of it: `./marvin logs -n 200`. |
| `the build failed` | Usually a network problem during the first build: check the connection and run the command again. |
| Talk says Ollama is not running | Start the Ollama app (or `brew services start ollama`), then **Try again** in Talk. |
| Talk says the model is not pulled | `ollama pull` the model it names, or pick the model you pulled in Marvin > Voice & models. |
| Marvin does not hear you | System Settings > Privacy & Security > Microphone: allow the Terminal. Then `./marvin restart`. |
| Anything else | `./marvin doctor`, then the [app's guide](ui.md) and the [voice's guide](voice.md). |

## What you have now

The host is installed and the voice works on your Mac. Next: [tutorial 2, the XIAO bench](xiao-bench.md), the
robot's electronics on a breadboard, linked to this host.
