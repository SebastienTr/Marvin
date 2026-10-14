# Firmware

Firmware for the Marvin robot's XIAO ESP32S3 Sense, built with PlatformIO.

**Status: prototype, real sensors ready for the first power-on.** The Wi-Fi link and the [UDP protocol](../docs/protocol.md) work. The robot build reads the **real HLK-LD2450** over its UART (rev F has no lidar; a lidar on D7 is still read when built with `MARVIN_LIDAR_MODEL=1|2`, for the bench and USB tooling); the other builds run **simulated sensors** (a D500 or D800 lidar, an LD2450 and the MR60BHA2 vital signs, seeing a small home office with a person walking around and sitting down, byte-exact to the real frames), on a Wemos D1 mini (ESP8266) as well as on the ESP32-S3 boards, so the whole chain can be tested without the robot. On the XIAO builds, the face runs on the screen, the speaker and microphone stream over UDP and the camera streams MJPEG over HTTP (untested on hardware so far).

## Build environments

| Env | Board | Sensors |
|---|---|---|
| `xiao_esp32s3` | XIAO ESP32S3 Sense, **the robot** | **Real**: LD2450 on UART1 (`MARVIN_SENSORS=REAL`, `MARVIN_LIDAR_MODEL=0`: no lidar); screen, audio, camera |
| `xiao_esp32s3_sim` | XIAO ESP32S3 Sense | Simulated; screen, audio, camera |
| `xiao_bench` | XIAO ESP32S3 Sense on a breadboard, the robot's parts and pins | **Real** LD2450; screen, audio, camera, and the boot self-test ([xiao-bench.md](../docs/xiao-bench.md)) |
| `esp32s3` | ESP32-S3 DevKitC-1 (bench) | Simulated; screen, the bench parts and the room sensors ([wiring-bench.md](../docs/wiring-bench.md)) |
| `esp32dev` | Classic ESP32 DevKit (ESP32-WROOM-32, 30 pins; bench, a second device) | Simulated; the bench parts: OLED eyes, Talk and Mute buttons, buzzer; real room sensors: PIR, DHT11 ([wiring-bench.md](../docs/wiring-bench.md)) |
| `esp32dev_looks` | Classic ESP32 DevKit, the "looks" bench | **Real** LD2450 only (`MARVIN_LIDAR_MODEL=0`); a servo head that turns to the nearest person, presence chirps, OLED eyes, Talk button, buzzer ([looks-bench.md](../docs/looks-bench.md)) |
| `d1_mini` | Wemos D1 mini (ESP8266), default | Simulated (real sensors need an ESP32: the build stops with an error) |
| `native` | Your computer | Unit tests of the portable code: `pio test -e native` |

Build flags: `MARVIN_SENSORS=SIM|REAL`, `MARVIN_LIDAR_MODEL=0|1|2` for the real lidar (0 = none, the LD2450 only; 1 = D500 at 230 400 baud, 2 = D800 at 921 600 baud), `MARVIN_SIM_LIDAR_MODEL=1|2` for the simulated one. `MARVIN_HAS_SCREEN` (the face), `MARVIN_HAS_AUDIO` (speaker and microphone, see [Audio](#audio)) and `MARVIN_HAS_CAMERA` (see [Camera](#camera)) switch the optional parts on; the audio and camera ones are for the XIAO ESP32S3 Sense only (the build stops with an error elsewhere). The `d1_mini`, `esp32s3` and `esp32dev` builds have neither. `MARVIN_HAS_BENCH_IO` (see [Bench parts](#bench-parts)) adds an OLED with the eyes, buttons and a buzzer to the two ESP32 DevKits, and `MARVIN_HAS_ROOM_SENSORS` (see [Room sensors](#room-sensors)) a PIR, a DHT11 and a light sensor (`MARVIN_HAS_LDR`, off until it is wired). `MARVIN_HAS_HEAD` (see [Head](#head)) turns a servo head towards the nearest LD2450 target (`esp32dev_looks`). `MARVIN_SELF_TEST=1` (see [Self-test](#self-test), `xiao_bench`) prints one line per part at boot, and `MARVIN_SCREEN_TEST=1` shows red, green and blue on the ST7789 before the eyes.

Every `*_ota` env (`xiao_esp32s3_ota`, `xiao_bench_ota`, `d1_mini_ota`, `esp32dev_ota`, `esp32dev_looks_ota`) is its USB twin flashed over Wi-Fi with `OTA_PASSWORD` from `include/secrets.h`.

Partitions: the XIAO builds keep the board's `default_8MB.csv`, two 3.2 MB OTA app slots. With screen, audio and camera, the robot firmware is about 0.83 MB (25 % of a slot).

With real sensors, `HELLO` does not carry the *simulated* flag, lidar packets go 10 per `LIDAR` datagram (a partial batch leaves after 20 ms), each LD2450 frame goes in its own datagram, no `VITALS` are sent (the MR60BHA2 kit has its own bridge), and a `LOG` with the sensor counters goes to the host every 5 s.

## Build and flash

1. Install PlatformIO: `pip install platformio` (or the VS Code extension).
2. Copy `include/secrets.example.h` to `include/secrets.h` and put your Wi-Fi name and password in it (2.4 GHz network). `secrets.h` is ignored by git.
3. Plug the board in over USB, then:

```bash
cd firmware
pio run -e d1_mini -t upload       # Wemos D1 mini
pio device monitor                 # serial log (Ctrl+C to quit); the speed comes from platformio.ini
```

The robot: `pio run -e xiao_esp32s3 -t upload && pio device monitor -e xiao_esp32s3`. If the upload does not start, hold **BOOT**, press **RESET**, release **BOOT**, and upload again (the first flash of a new XIAO often needs it; or hold BOOT while plugging the USB cable in). The serial log goes over the USB-C port, which leaves UART0's pins free: GPIO43 (D6) drives the amp, GPIO44 (D7) is spare. The same parts on a breadboard first: `xiao_bench` ([xiao-bench.md](../docs/xiao-bench.md)).

Then run `marvin-host run` on a computer on the same network (see [host/README.md](../host/README.md)). The on-board LED blinks fast while the robot looks for the host, and flashes briefly every 2 s once linked. On the bench builds it blinks slowly while joining Wi-Fi, fast while looking for the host, and stays on once linked (the ESP32-S3 DevKitC-1's RGB LED: blue, amber, green).

## First power-on with the real sensors

Wire as in [docs/wiring.md](../docs/wiring.md) (or on a breadboard with `xiao_bench` and its self-test first: [xiao-bench.md](../docs/xiao-bench.md)), flash `xiao_esp32s3`, open the serial monitor. Expected:

```text
Marvin firmware 0.1.0, board 3, real sensors
no lidar (MARVIN_LIDAR_MODEL=0)
LD2450 on UART1 at 256000 baud: multi-target tracking set
joining MyWifi....
IP 192.168.1.42, RSSI -51 dBm
OTA: ready as marvin-a1b2c3.local (192.168.1.42), port 3232, password protected
audio: speaker on I2S1 (GPIO8/43/3), PDM mic on I2S0 (GPIO42/41)
camera: OV3660, http://192.168.1.42:81/stream
LD2450 10.0 frame/s, 0 bad; 0 UART overflows
linked to host 192.168.1.10
```

The counters line comes every 5 s, host or not; the LD2450 sends 10 frames/s.

A lidar on D7 (a D500 or D800, as the bench and USB tooling still support: [usb-sensors.md](../docs/usb-sensors.md)) needs `MARVIN_LIDAR_MODEL=1` (D500, 230 400 baud) or `2` (D800, 921 600 baud); the counters line then starts with `lidar 1800 pkt/s, 0 CRC err, 0 B dropped` (about 1 800 packets/s for a D800, 375 for a D500; `0 pkt/s`: no data on GPIO44; CRC errors: the wrong model for the baud rate).

| Symptom | Likely cause | Fix |
|---|---|---|
| `UART overflows` keep growing | The loop is too slow to drain the UARTs | Report it: something blocks `loop()` |
| `LD2450 ... no answer` and `0.0 frame/s` | TX and RX swapped, or no power | Swap the wires on **D4 / D5** (LD2450 TX → D4, D5 → LD2450 RX), check 5 V |
| `LD2450 ... no answer` but `10.0 frame/s` | Only the robot → radar wire (D5 → LD2450 RX) is wrong | The radar still streams in its saved mode (multi-target by default); fix the wire for the boot configuration |
| `LD2450 0.0 frame/s`, many bad frames | Wrong baud rate (the radar's was changed from 256 000) | Reset it to factory settings with the HLKRadarTool app |

## Audio

`MARVIN_HAS_AUDIO` ([`src/audio/`](src/audio/)): the host streams speech to the speaker and asks for the microphone over the same UDP link as everything else ([protocol](../docs/protocol.md#audio-messages), [docs/audio.md](../docs/audio.md) for the earcons, the volume cap and the latency budget).

- **Speaker**: MAX98357A on I2S1, BCLK GPIO8 (D9), LRC/WS GPIO43 (D6), DIN GPIO3 (D2), 16 kHz 16-bit, the mono stream on both slots. `AUDIO_OUT` datagrams go through a jitter buffer (100 ms prebuffer, 750 ms capacity; lost datagrams become silence, late ones are dropped); `SOUND` plays a built-in earcon, mixed over the stream; `AUDIO_CTRL` stops playback or sets the volume (0–100, the loudest setting is capped at −6 dBFS for the 1 W speaker). The *hello* earcon plays on the first link after boot (`-DMARVIN_BOOT_SOUND=0` to silence it).
- **Microphone**: the Sense board's PDM mic on I2S0, CLK GPIO42, DATA GPIO41, 16 kHz. Off by default: the PDM clock only runs while the host has asked for it (`AUDIO_CTRL` *mic start*, repeated every second) and the link is up, or, with the self-test, all the time for its level line (`audio_app::set_mic_monitor`; blocks still go out only on the host's request). DC offset removed, +12 dB of gain by default, 20 ms per `AUDIO_IN` datagram.
- **Tasks**: `speaker` (priority 6) and `mic` (priority 5) on core 1, above the Arduino loop (priority 1), so the I2S DMA never starves; each 10–20 ms block costs well under a millisecond. The loop sends the microphone blocks itself, so `AUDIO_IN` leaves from the robot's UDP port like every other message.
- **GPIO43**: it is UART0 TX at boot, where the ROM bootloader and ESP-IDF logs print (the Arduino `Serial` is the USB port, and UART0 is otherwise unused, or only receives an optional lidar on GPIO44). `audio_app::begin()` runs after the sensors, resets GPIO43 (`gpio_reset_pin`) and routes I2S1 WS to it, so no console byte ever reaches the amp's LRC input. The boot ROM messages still toggle it for a moment before the firmware starts: harmless, the amp stays silent without BCLK. Do not call `Serial0.begin()` or `Serial0.setPins()` after `audio_app::begin()`.
- Options: `MARVIN_AUDIO_VOLUME` (default volume, 60), `MARVIN_AUDIO_GAIN_CAP` (percent of full scale at volume 100, 50), `MARVIN_MIC_GAIN_DB` (12), `MARVIN_MIC_RIGHT_SLOT=1` (read the mic on the other PDM slot).

Every 10 s while audio is in use, a `LOG` line: `audio: speaker 500 dgram, 0 lost / 0 late / 0 overflow samples, 0 underruns, 1 sounds; mic 500 blocks, 0 dropped, peak 2100`.

## Camera

`MARVIN_HAS_CAMERA` ([`src/camera/`](src/camera/)): the OV3660 (or OV2640) of the Sense board, VGA JPEG at quality 12, two frame buffers in PSRAM, always the latest frame.

- `http://<robot ip>:81/stream`: MJPEG (`multipart/x-mixed-replace`, boundary `marvinframe`), every part with `Content-Length` and `X-Marvin-Time-Us` (the robot clock of the UDP headers). `http://<robot ip>:81/capture`: one JPEG. `http://<robot ip>:81/`: a page showing the stream. The robot's IP is in the serial log, and the host knows it from `HELLO`.
- A small socket server in one task (`camera`, core 0, priority 1, below Wi-Fi and lwIP): one frame grab is shared by every client (up to 4, e.g. the viewer and the web UI at once), and `/capture` works while a stream is open. Frames go out at most `MARVIN_CAMERA_FPS` times a second (10) and only while someone is connected: VGA is ~25–40 kB a frame, ~3 Mbit/s at 10 fps.
- Options: `MARVIN_CAMERA_FPS` (10), `MARVIN_CAMERA_QUALITY` (12; 0–63, lower is better and larger), `MARVIN_CAMERA_VFLIP` / `MARVIN_CAMERA_HMIRROR` (0/1, for how the module is mounted; the OV3660 is flipped vertically by default, as in Espressif's example).
- `HELLO` flags bit 1 (camera) and bit 2 (audio) are set only when that part started.

Every 10 s while someone watches: `camera OV3660: 1 streaming, 10.0 frames/s, 100 parts, 0 captures, 310 kB/s, 0 connections`.

### Audio and camera troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `audio: speaker I2S init failed` | Another driver owns I2S1 | Check that nothing else installs an I2S driver |
| No sound at all, not even the *hello* earcon | Amp wiring or power | BCLK → D9, LRC → D6, DIN → D2, VIN on 5 V; SD and GAIN unconnected |
| Buzzing or clicks in time with serial output | GPIO43 still driven by UART0 | Something called `Serial0.begin()` after the audio started: move it before `audio_app::begin()` |
| Distorted or too loud | Gain cap too high for the speaker | Lower `MARVIN_AUDIO_GAIN_CAP`, or the volume from the host |
| `underruns` keep growing | The host sends late, or Wi-Fi drops bursts | Check the host pacing (`RobotSpeakerSink`, 150 ms lead) and the RSSI |
| `mic ... peak 0` | The mic is on the other PDM slot | Build with `-DMARVIN_MIC_RIGHT_SLOT=1` |
| Mic very quiet / clipping | Gain | `AUDIO_CTRL` mic gain from the host, or `MARVIN_MIC_GAIN_DB` |
| `camera: no PSRAM` | Wrong board definition | The env must use `board = seeed_xiao_esp32s3` (OPI PSRAM) |
| `camera: init failed (0x20001)` | Camera not detected | Reseat the Sense expansion board and the camera ribbon |
| Stream stutters, sensor datagrams lost | Wi-Fi air time | Lower `MARVIN_CAMERA_FPS` or raise `MARVIN_CAMERA_QUALITY` |
| `503` from the camera | Four clients already connected | Close a viewer tab |

## Self-test

`MARVIN_SELF_TEST=1` ([`src/self_test.h`](src/self_test.h), built by `xiao_bench` and its OTA twin): for someone wiring the robot's parts one at a time ([xiao-bench.md](../docs/xiao-bench.md)). At boot, one console line per part as it starts, each saying what to check when the part does not answer: `screen:` (the ST7789's ID, read back over its data line before the SPI takes it), `speaker:` (after a two-note chime), `mic:` (the level in dBFS over half a second), `radar:` (frames counted for 1.5 s after the LD2450's configuration, its per-command console lines silenced), `wifi:` (with a hint every 20 s while it joins), `camera:` (after grabbing one frame), `host:` (linked, or a hint 10 s after Wi-Fi and every 30 s). The `radar:` and `mic:` lines replace the counters line every 5 s; Enter in the monitor prints the last results again. The lines' wording is portable and tested natively (`test_self_test`); [xiao-bench.md](../docs/xiao-bench.md) quotes them.

With `MARVIN_SCREEN_TEST=1` the face task shows full red, green and blue (0.3 s each) before the eyes; the self-test then tells the face someone stands in front for 8 s (`face_app::greet`), so the eyes wake up and look at you.

On every build with a screen and real sensors, the face follows the board's own LD2450 while the host sends no `FACE_STATE` (`face_app::set_local_presence`, from the nearest target): someone in front wakes it up and the eyes follow them; the host's `FACE_STATE` wins as soon as it comes.

## Bench parts

`MARVIN_HAS_BENCH_IO` ([`src/bench/`](src/bench/), built by `esp32dev` and `esp32s3`): the parts of a bench board, wired as in [docs/wiring-bench.md](../docs/wiring-bench.md). They make a DevKit a second, simpler Marvin on the desk.

- **Eyes on an SSD1306 OLED** (128 × 64, I2C at 400 kHz, address 0x3C or 0x3D): the robot's face (`face/face.h`, fed by `FACE_STATE` and `FACE_EVENT` through `face/face_link.h`) laid out at half scale in white on black (`bench/eyes.h`), drawn with [U8g2](https://github.com/olikraus/u8g2) (pinned in `platformio.ini`, built only by these two envs) in a FreeRTOS task on core 0 at up to 25 frames/s, so the ~25 ms of I2C per frame never block the loop. The voice's sounds set a mood on top: `wake` wide open (listening), `done` a glance aside (thinking), `error` concerned, `hello` happy. Before the host answers, a status line (`joining Wi-Fi`, `looking for the host`) under sleeping eyes. No screen, or one that stops answering: probed again every 5 s, I2C calls time out after 20 ms. `HELLO` flag bit 5 is set while it answers, so the host sends it the face messages.
- **Buttons** (to ground, internal pull-ups, 30 ms debounce): each press clicks the buzzer and sends `BUTTON` (1 = Talk, 2 = Mute); without a host, the *error* sound instead. `HELLO` flag bit 3.
- **Passive buzzer** (LEDC, channel `MARVIN_BUZZER_LEDC_CHANNEL`, 6): `SOUND` ids play as the earcons' notes in square waves, from a non-blocking sequencer (`bench/bench_logic.h`); the *hello* arpeggio on the first link after boot (`-DMARVIN_BOOT_SOUND=0` to silence it). `HELLO` flag bit 4.
- **Status LED**: `LED_BUILTIN` on the classic DevKit; the S3's RGB LED through the RMT on GPIO48 and GPIO38 (its data pin on v1.0 and v1.1 boards).
- Pins are `-D` flags per env: `MARVIN_OLED_SDA`, `MARVIN_OLED_SCL`, `MARVIN_BUTTON_TALK`, `MARVIN_BUTTON_MUTE`, `MARVIN_BUZZER_PIN`, `MARVIN_STATUS_RGB`, `MARVIN_STATUS_RGB_ALT`; `-1` leaves a part out. `MARVIN_OLED_FPS` (25). A 1.3" SH1106 screen would need U8g2's `sh1106` driver instead: not built in yet.

## Head

`MARVIN_HAS_HEAD` ([`src/head/`](src/head/), built by `esp32dev_looks` and its OTA twin): a 9 g servo turning the head towards the nearest LD2450 target, wired as in [docs/looks-bench.md](../docs/looks-bench.md). The logic (`head/head_logic.h`: the nearest target, smoothing, deadband, slew limit, the slow return, the idle glances, the host's modes, the presence events) is portable and tested natively; `head/head_io.h` drives the servo with the LEDC at 50 Hz (channel `MARVIN_HEAD_LEDC_CHANNEL`, 4), silent for its first second, resting once the head has held still for 5 s.

- Every LD2450 frame feeds the head, linked or not (and while joining Wi-Fi), then goes to the host as before.
- `HEAD` to the host 5 times a second and at once on a change; `HEAD_CTRL` from it: auto, manual, centre ([protocol](../docs/protocol.md#head)). No `HELLO` flag.
- Someone arriving after nobody for 10 s plays *hello* on the buzzer, nobody for 10 s a soft *bye* (`MARVIN_PRESENCE_CHIRPS`, 1).
- With `MARVIN_HAS_BENCH_IO`, the OLED eyes follow the target while the host sends no `FACE_STATE` (`bench_io::set_local_presence`).
- Options: the servo's pin, pulse range, centre and limits, mirror, the radar's x sign, a radar or a screen on the head; all in [looks-bench.md](../docs/looks-bench.md#what-it-does).

## Room sensors

`MARVIN_HAS_ROOM_SENSORS` ([`src/room/`](src/room/), built by `esp32dev` and `esp32s3`): the room around the bench board, wired as in [docs/wiring-bench.md](../docs/wiring-bench.md), sent as `ENVIRONMENT` ([protocol](../docs/protocol.md#room-sensors)) every 5 s, at once when motion starts or ends, and on linking. `HELLO` flag bit 6.

- **PIR** (HC-SR501, `MARVIN_PIR_PIN`): its 3.3 V output read from the loop, debounced 100 ms (a Wi-Fi burst coupling into the wire is not motion). For `MARVIN_PIR_WARMUP_S` (60 s) after boot it settles and fires on its own: nothing is reported but *warming up*. Then motion and the seconds since it ended.
- **DHT11** (`MARVIN_DHT11_PIN`, a small implementation of its one-wire protocol, no library): read every 5 s from its own FreeRTOS task on core 1, priority 2, asleep but for a read. The 18 ms start pulse sleeps; the 40 bits (about 5 ms) are timed with the cycle counter inside a critical section, interrupts off on core 1 only, so the loop pauses that long every 5 s and the Wi-Fi (core 0) does not notice. A bit is 1 when its high lasted longer than its low, so it does not depend on exact timings. Checksum and range checked (an all-zero frame is a line held low); a failed read is tried again after 1.2 s, twice; a reading counts for 12 s, so an unplugged sensor is reported invalid within 12 s, and logged.
- **Light** (`MARVIN_HAS_LDR`, `MARVIN_LDR_PIN` on ADC1, 11 dB attenuation): sampled every 50 ms, smoothed over 2 s, as a raw 0–4095 and a level 0–1000 (higher is brighter; `MARVIN_LDR_BRIGHT_HIGH=1` for a sensor whose voltage rises with the light). Off by default, and in both envs until the module is wired: a floating ADC pin reads noise that looks like light, and the classic ESP32's GPIO35 has no pull to tell an empty pin apart.
- The serial log: `pir: warmed up`, `pir: motion`, `pir: still` as they happen; `dht11: 23.4 °C, 48 % humidity` when it first answers (or answers again), `dht11: no reading ...` when it stops; a `room:` line with every reading once a minute.
- Pins are `-D` flags per env; `-1` leaves a sensor out: `MARVIN_PIR_PIN` (34 / 14), `MARVIN_DHT11_PIN` (4 / 15), `MARVIN_LDR_PIN` (35 / 5) on the classic DevKit and the S3.

## Tests

```bash
pio test -e native
```

Runs the Unity tests in `test/` on your computer: the lidar and LD2450 stream framers against a real LD19 packet, the LD2450 datasheet frames, garbage, split and corrupted streams, round trips through the simulator, and the LD2450 command bytes; the face renderer; the self-test's console lines (`test_self_test`); the bench parts (`test_bench`: button debouncing, the buzzer's tone sequencer and its soft *bye*, the status LED patterns, the OLED eyes' layout, moods and gaze, the eyes following the board's own radar); the head (`test_head`: the nearest LD2450 target, tracking with its smoothing, deadband and slew limit, the slow return and the idle glances, the host's modes, a radar on the head, the presence events, the `HEAD` payload); the room sensors (`test_room`: the PIR's warm-up, debouncing and motion age, the DHT11's bits, frames, read schedule and staleness, the light sensor's smoothing, the `ENVIRONMENT` payload); and (`test_audio`) the speaker jitter buffer (prebuffering, lost, late, duplicated and overlapping datagrams, index wrap, overflow, underrun, stop), the earcons, the audio payloads, the DSP helpers and the camera server's HTTP routing.

## Source layout

| File | Role |
|---|---|
| `src/main.cpp` | Wi-Fi, host discovery (`HELLO` / `HOST_ACK`), host message dispatch, streaming loop, status LED, sensor source selection |
| `src/protocol.h` | UDP envelope, mirrors `host/marvin_host/protocol.py` |
| `src/sensor_framing.cpp` | Portable stream framers: LDROBOT lidar packets (CRC-8), LD2450 target and ACK frames, LD2450 command builders |
| `src/sensors_real.cpp` | ESP32 only: the LD2450 UART (and an optional lidar's), LD2450 boot configuration, counters |
| `src/self_test.cpp` | Portable: the boot self-test's console lines (`MARVIN_SELF_TEST`) |
| `src/sensors_sim.cpp` | Simulated lidar packets, LD2450 frames and MR60BHA2 vital signs |
| `src/scene_data.h` | The simulated room and person, generated from `host/marvin_host/scene.py` |
| `src/audio/audio_app.cpp` | ESP32-S3 only: I2S speaker and PDM microphone drivers, their tasks, `AUDIO_*` / `SOUND` handling |
| `src/audio/jitter_buffer.cpp`, `earcons.cpp`, `audio_dsp.cpp`, `audio_packets.h` | Portable: speaker jitter buffer, procedural earcons, DC blocker / gain / volume, audio payloads |
| `src/camera/camera_app.cpp` | ESP32-S3 only: esp32-camera setup and the MJPEG server on port 81 |
| `src/camera/http_request.h` | Portable: the camera server's request routing and multipart framing |
| `src/bench/bench_io.cpp` | ESP32 only: the bench parts: OLED task (U8g2), buttons, buzzer (LEDC), status LED |
| `src/bench/eyes.cpp`, `bench_logic.cpp` | Portable: the eyes' layout on the 128 × 64 OLED and their moods; debounced button, tone sequencer, LED patterns |
| `src/room/room_io.cpp` | ESP32 only: the room sensors: PIR and light from the loop, the DHT11's task and its timed read |
| `src/room/room_logic.cpp` | Portable: the PIR's warm-up and debouncing, DHT11 frames and read schedule, light smoothing, the `ENVIRONMENT` payload |
| `test/` | Unit tests (`pio test -e native`) |

## Responsibilities (full firmware)

1. Optionally (not on rev F, which has no lidar), read a **lidar** on UART0 (RX = GPIO44 / D7): 921 600 baud for the D800 (STL-27L), 230 400 baud for the D500 (STL-19P). Validate each 47-byte LDROBOT packet (header `0x54`, CRC8).
2. Read the **HLK-LD2450** on UART1 (RX = GPIO5 / D4, TX = GPIO6 / D5, 256 000 baud), switch it to multi-target mode at boot and parse its target frames.
3. Stamp every packet with a monotonic microsecond clock, synchronised to the host.
4. Send radar (and lidar) packets to the host as **UDP** datagrams, in a small versioned binary envelope.
5. Serve the camera as an **MJPEG** stream over HTTP (done: [Camera](#camera)).
6. Drive the **1.69" ST7789 screen** (SPI), the robot's face:
   - **eyes** by default: blinking, looking towards the nearest person tracked by the LD2450, sleepy when nobody is around, and any expression the host sends;
   - a top-down **mini-map** with the radar targets, and a status page (Wi-Fi, host link), on request.
7. **Audio in**: read the built-in PDM microphone (I2S0, 16 kHz) and stream it on request (done: [Audio](#audio)), or detect simple sound events locally.
8. **Audio out**: play chirps, beeps and speech streamed from the host through the MAX98357A (I2S1), with a volume cap (done: [Audio](#audio)).
9. Keep the robot **alive without a computer**: eyes, presence reactions and sounds work on the device alone; the host adds understanding and language.
10. Support **OTA** updates after the first USB flash.

The MR60BHA2 kit keeps its own ESP32-C6: [`mr60_bridge/`](mr60_bridge/) replaces Seeed's stock firmware on it and sends the vital signs straight to the host, in the same protocol.

## Pin map

As since rev D, without the lidar (rev F): D7 is spare.

| Signal | XIAO pin | GPIO | Peripheral |
|---|---|---|---|
| Spare (an optional lidar's Tx →) | D7 | 44 | UART0 RX with `MARVIN_LIDAR_MODEL=1|2` |
| LD2450 TX → | D4 | 5 | UART1 RX |
| → LD2450 RX | D5 | 6 | UART1 TX |
| Screen CS | D0 | 1 | GPIO |
| Screen DC | D1 | 2 | GPIO |
| Screen RST | D3 | 4 | GPIO |
| Screen CLK | D8 | 7 | SPI2 SCK |
| Screen DIN | D10 | 9 | SPI2 MOSI |
| Amp BCLK | D9 | 8 | I2S1 |
| Amp LRC / WS | D6 | 43 | I2S1 |
| Amp DIN | D2 | 3 | I2S1 |
| Mic CLK / DATA | internal | 42 / 41 | I2S0 PDM (Sense board) |
| Camera | internal | — | DVP (Sense board) |

The screen shares the SPI pins (GPIO7/8/9) with the Sense board's microSD slot. Leave the slot empty, or give each device its own chip-select.

## Planned toolchain

PlatformIO with the Arduino-ESP32 core (`board = seeed_xiao_esp32s3`), USB CDC on boot enabled so UART0 stays free.
