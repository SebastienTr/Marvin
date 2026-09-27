# Audio

Marvin hears through the XIAO ESP32S3 Sense's built-in PDM microphone and speaks through a MAX98357A I2S amplifier and a 2030 cavity speaker (8 Ω, 1 W). The robot does no speech processing itself: it streams the microphone to the host and plays what the host sends, plus a few built-in sounds that need no streaming. Messages and bytes: [protocol.md](protocol.md#audio-messages). Firmware: [`firmware/src/audio/`](../firmware/src/audio/) ([README](../firmware/README.md#audio)). Host: [`host/marvin_host/robot_audio.py`](../host/marvin_host/robot_audio.py).

Everything is **16 kHz mono 16-bit PCM**, the format of the host audio contract ([`audio.py`](../host/marvin_host/audio.py)), so the voice pipeline can use the robot or the computer's own microphone and speakers without changes:

```python
from marvin_host.robot_audio import RobotMicSource, RobotSpeakerSink, audio_device

dev = audio_device(receiver, timeout=10)     # a robot whose HELLO has the audio flag
mic = RobotMicSource(receiver, dev)          # AudioSource: 20 ms frames
spk = RobotSpeakerSink(receiver, dev)        # AudioSink: play() / wait() / stop() / busy
spk.play_sound("wake")                       # an earcon, played by the robot itself
```

## Earcons

Short sounds generated on the robot (a few tones with smooth 5 ms edges, no sample tables), started by `SOUND` and mixed over any speech being played. Same ids everywhere: `audio::SoundId` in [`earcons.h`](../firmware/src/audio/earcons.h), `protocol.SOUNDS` on the host.

| Id | Name | Sound | Length | Use |
|---:|---|---|---:|---|
| 1 | `chirp` | Two quick rising sweeps, 1.2→2.4 kHz and 1.6→3.2 kHz | 140 ms | The robot noticed something: "hm?" |
| 2 | `beep` | 1 kHz | 120 ms | Neutral acknowledgement, tests |
| 3 | `wake` | 660 Hz then 990 Hz | 200 ms | Listening starts (wake word heard) |
| 4 | `done` | 990 Hz then 660 Hz | 200 ms | Listening ends |
| 5 | `error` | 330 Hz, pause, 262 Hz | 360 ms | Something went wrong (no host answer, not understood) |
| 6 | `hello` | C–E–G–C arpeggio | 430 ms | First link with the host after boot (`MARVIN_BOOT_SOUND=0` to silence it) |

Earcons peak at 0.8 of full scale before the volume, so they sit a little above normal speech.

## Volume and its cap

The MAX98357A, powered at 5 V with its GAIN pin unconnected (9 dB), can drive about 1.4 W into 8 Ω before clipping: more than the 1 W speaker is rated for, and with a sustained tone, enough to make a small cavity speaker rattle and heat. So the firmware caps the digital level instead of trusting every host to behave:

- Volume 0–100 (`AUDIO_CTRL` *volume*, default 60) maps to a gain of **cap × (volume / 100)²**; the square gives roughly even loudness steps.
- The **cap** is 0.5 of full scale (−6 dBFS, a quarter of the power) at volume 100, whatever the host sends: a full-scale sine then gives at most about 0.35 W, an earcon about 0.2 W, and speech (peaks 12–15 dB above its average) a few tens of milliwatts on average, which is plenty on a desk.
- A different speaker (4 Ω, or bigger) takes `-DMARVIN_AUDIO_GAIN_CAP=<percent>`. Do not raise it for this speaker: loud and distorted is worse than moderate and clear.

Samples are saturated after the gain and the earcon mix, so an overdriven stream clips instead of wrapping around.

## Latency budget

**Speaker, host `play()` to sound: about 50 ms.**

| Step | Time |
|---|---:|
| Host sends the first 150 ms of the utterance at once | ~0 |
| Wi-Fi to the robot | 2–10 ms |
| Jitter buffer: starts as soon as 100 ms are queued (on the first burst) | ~0 |
| Next 10 ms speaker block + I2S DMA queue (4 × 10 ms) | 10–50 ms |

After the start, the host keeps 150 ms ahead of real time: the robot's buffer rides out Wi-Fi hiccups up to about that long, and a longer one costs a gap (the buffer then prebuffers again) rather than a crackle on every datagram. Lost datagrams are replaced by silence of the same length, so the rest stays in time. The robot's buffer holds 750 ms; a host sending faster than that loses the excess. `stop()` (barge-in) takes effect within one Wi-Fi hop plus the DMA queue, about 50 ms.

**Earcon, `SOUND` to sound: about 30–60 ms** (Wi-Fi, then the next speaker block and the DMA queue).

**Microphone, sound to host frame: about 25–50 ms.**

| Step | Time |
|---|---:|
| One 20 ms block captured (plus the PDM decimation filter) | 20 ms |
| DC removal, gain, queue to the main loop | < 2 ms |
| Wi-Fi to the host | 2–10 ms |
| Host re-framing into 20 ms `AudioSource` frames | 0 (same size) |

When the microphone starts, the first 100 ms are dropped while the PDM filter and the DC blocker settle. The host fills lost datagrams with silence (up to 0.5 s) so voice activity detection keeps its timing, and drops late or duplicated ones.

**Bandwidth:** 32 kB/s of PCM each way (33 kB/s with headers), 50 datagrams/s each way, only while listening or speaking.

## Tuning on the hardware

- Microphone level: the `audio:` `LOG` line every 10 s reports the microphone peak. Normal speech at a desk should peak around 3 000–15 000; change the gain with `AUDIO_CTRL` *mic gain* (0–36 dB, default 12) or `MARVIN_MIC_GAIN_DB`. A peak of 0 means the mic is on the other PDM slot: build with `-DMARVIN_MIC_RIGHT_SLOT=1`.
- Speaker: start at the default volume 60 and adjust from the host.
