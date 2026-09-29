# Firmware

Firmware for the Marvin robot's XIAO ESP32S3 Sense, built with PlatformIO.

**Status: prototype, real sensors ready for the first power-on.** The Wi-Fi link and the [UDP protocol](../docs/protocol.md) work. The robot build reads the **real lidar and HLK-LD2450** over its UARTs; the other builds run **simulated sensors** (a D500 or D800 lidar, an LD2450 and the MR60BHA2 vital signs, seeing a small home office with a person walking around and sitting down, byte-exact to the real frames), on a Wemos D1 mini (ESP8266) as well as on the ESP32-S3 boards, so the whole chain can be tested without the robot. On the XIAO builds, the face runs on the screen, the speaker and microphone stream over UDP and the camera streams MJPEG over HTTP (untested on hardware so far).

## Build environments

| Env | Board | Sensors |
|---|---|---|
| `xiao_esp32s3` | XIAO ESP32S3 Sense, **the robot** | **Real**: lidar on UART0, LD2450 on UART1 (`MARVIN_SENSORS=REAL`, `MARVIN_LIDAR_MODEL=2` = D800); screen, audio, camera |
| `xiao_esp32s3_sim` | XIAO ESP32S3 Sense | Simulated; screen, audio, camera |
| `esp32s3` | ESP32-S3 DevKitC-1 (bench) | Simulated |
| `esp32dev` | Classic ESP32 DevKit (ESP32-WROOM-32, 30 pins; bench, a second device) | Simulated |
| `d1_mini` | Wemos D1 mini (ESP8266), default | Simulated (real sensors need an ESP32: the build stops with an error) |
| `native` | Your computer | Unit tests of the portable code: `pio test -e native` |

Build flags: `MARVIN_SENSORS=SIM|REAL`, `MARVIN_LIDAR_MODEL=1|2` for the real lidar (1 = D500 at 230 400 baud, 2 = D800 at 921 600 baud), `MARVIN_SIM_LIDAR_MODEL=1|2` for the simulated one. `MARVIN_HAS_SCREEN` (the face), `MARVIN_HAS_AUDIO` (speaker and microphone, see [Audio](#audio)) and `MARVIN_HAS_CAMERA` (see [Camera](#camera)) switch the optional parts on; the audio and camera ones are for the XIAO ESP32S3 Sense only (the build stops with an error elsewhere). The `d1_mini`, `esp32s3` and `esp32dev` builds have neither.

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

The robot: `pio run -e xiao_esp32s3 -t upload && pio device monitor -e xiao_esp32s3`. If the upload does not start, hold **BOOT**, press **RESET**, release **BOOT**, and upload again (the first flash of a new XIAO often needs it). The serial log goes over the USB-C port; UART0 belongs to the lidar.

Then run `marvin-host run` on a computer on the same network (see [host/README.md](../host/README.md)). The on-board LED blinks fast while the robot looks for the host, and flashes briefly every 2 s once linked.

## First power-on with the real sensors

Wire as in [docs/wiring.md](../docs/wiring.md), flash `xiao_esp32s3`, open the serial monitor. Expected:

```text
Marvin firmware 0.1.0, board 3, real sensors
lidar D800 on UART0 RX GPIO44 at 921600 baud
LD2450 on UART1 (RX GPIO5, TX GPIO6) at 256000 baud: multi-target tracking set
joining MyWifi....
IP 192.168.1.42, RSSI -51 dBm
lidar 1800 pkt/s, 0 CRC err, 0 B dropped; LD2450 10.0 frame/s, 0 bad; 0 UART overflows
linked to host 192.168.1.10
```

The counters line comes every 5 s, host or not. A D800 gives about 1 800 packets/s (21 600 points/s), a D500 about 375; the LD2450 10 frames/s. A few CRC errors right after boot are normal.

| Symptom | Likely cause | Fix |
|---|---|---|
| `0 pkt/s` and `0 B dropped` | No lidar data reaches GPIO44 | Check the lidar's power (motor spinning?) and that its **Tx** goes to **D7** |
| `0 pkt/s`, many CRC errors or dropped bytes | Wrong baud rate: the model does not match the build | Set `MARVIN_LIDAR_MODEL` (1 = D500, 2 = D800) in `platformio.ini`, flash again |
| `UART overflows` keep growing | The loop is too slow to drain the UARTs | Report it: something blocks `loop()` |
| `LD2450 ... no answer` and `0.0 frame/s` | TX and RX swapped, or no power | Swap the wires on **D4 / D5** (LD2450 TX → D4, D5 → LD2450 RX), check 5 V |
| `LD2450 ... no answer` but `10.0 frame/s` | Only the robot → radar wire (D5 → LD2450 RX) is wrong | The radar still streams in its saved mode (multi-target by default); fix the wire for the boot configuration |
| `LD2450 0.0 frame/s`, many bad frames | Wrong baud rate (the radar's was changed from 256 000) | Reset it to factory settings with the HLKRadarTool app |

## Audio

`MARVIN_HAS_AUDIO` ([`src/audio/`](src/audio/)): the host streams speech to the speaker and asks for the microphone over the same UDP link as everything else ([protocol](../docs/protocol.md#audio-messages), [docs/audio.md](../docs/audio.md) for the earcons, the volume cap and the latency budget).

- **Speaker**: MAX98357A on I2S1, BCLK GPIO8 (D9), LRC/WS GPIO43 (D6), DIN GPIO3 (D2), 16 kHz 16-bit, the mono stream on both slots. `AUDIO_OUT` datagrams go through a jitter buffer (100 ms prebuffer, 750 ms capacity; lost datagrams become silence, late ones are dropped); `SOUND` plays a built-in earcon, mixed over the stream; `AUDIO_CTRL` stops playback or sets the volume (0–100, the loudest setting is capped at −6 dBFS for the 1 W speaker). The *hello* earcon plays on the first link after boot (`-DMARVIN_BOOT_SOUND=0` to silence it).
- **Microphone**: the Sense board's PDM mic on I2S0, CLK GPIO42, DATA GPIO41, 16 kHz. Off by default: the PDM clock only runs while the host has asked for it (`AUDIO_CTRL` *mic start*, repeated every second) and the link is up. DC offset removed, +12 dB of gain by default, 20 ms per `AUDIO_IN` datagram.
- **Tasks**: `speaker` (priority 6) and `mic` (priority 5) on core 1, above the Arduino loop (priority 1), so the I2S DMA never starves; each 10–20 ms block costs well under a millisecond. The loop sends the microphone blocks itself, so `AUDIO_IN` leaves from the robot's UDP port like every other message.
- **GPIO43**: it is UART0 TX at boot, where the ROM bootloader and ESP-IDF logs print (the Arduino `Serial` is the USB port, and UART0 only receives the lidar on GPIO44). `audio_app::begin()` runs after the sensors, resets GPIO43 (`gpio_reset_pin`) and routes I2S1 WS to it, so no console byte ever reaches the amp's LRC input. The boot ROM messages still toggle it for a moment before the firmware starts: harmless, the amp stays silent without BCLK. Do not call `Serial0.begin()` or `Serial0.setPins()` after `audio_app::begin()`.
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
| Stream stutters, lidar datagrams lost | Wi-Fi air time | Lower `MARVIN_CAMERA_FPS` or raise `MARVIN_CAMERA_QUALITY` |
| `503` from the camera | Four clients already connected | Close a viewer tab |

## Tests

```bash
pio test -e native
```

Runs the Unity tests in `test/` on your computer: the lidar and LD2450 stream framers against a real LD19 packet, the LD2450 datasheet frames, garbage, split and corrupted streams, round trips through the simulator, and the LD2450 command bytes; the face renderer; and (`test_audio`) the speaker jitter buffer (prebuffering, lost, late, duplicated and overlapping datagrams, index wrap, overflow, underrun, stop), the earcons, the audio payloads, the DSP helpers and the camera server's HTTP routing.

## Source layout

| File | Role |
|---|---|
| `src/main.cpp` | Wi-Fi, host discovery (`HELLO` / `HOST_ACK`), host message dispatch, streaming loop, status LED, sensor source selection |
| `src/protocol.h` | UDP envelope, mirrors `host/marvin_host/protocol.py` |
| `src/sensor_framing.cpp` | Portable stream framers: LDROBOT lidar packets (CRC-8), LD2450 target and ACK frames, LD2450 command builders |
| `src/sensors_real.cpp` | ESP32 only: the lidar and LD2450 UARTs, LD2450 boot configuration, counters |
| `src/sensors_sim.cpp` | Simulated lidar packets, LD2450 frames and MR60BHA2 vital signs |
| `src/scene_data.h` | The simulated room and person, generated from `host/marvin_host/scene.py` |
| `src/audio/audio_app.cpp` | ESP32-S3 only: I2S speaker and PDM microphone drivers, their tasks, `AUDIO_*` / `SOUND` handling |
| `src/audio/jitter_buffer.cpp`, `earcons.cpp`, `audio_dsp.cpp`, `audio_packets.h` | Portable: speaker jitter buffer, procedural earcons, DC blocker / gain / volume, audio payloads |
| `src/camera/camera_app.cpp` | ESP32-S3 only: esp32-camera setup and the MJPEG server on port 81 |
| `src/camera/http_request.h` | Portable: the camera server's request routing and multipart framing |
| `test/` | Unit tests (`pio test -e native`) |

## Responsibilities (full firmware)

1. Read the **lidar** on UART0 (RX = GPIO44 / D7): 921 600 baud for the D800 (STL-27L), 230 400 baud for the D500 (STL-19P). Validate each 47-byte LDROBOT packet (header `0x54`, CRC8).
2. Read the **HLK-LD2450** on UART1 (RX = GPIO5 / D4, TX = GPIO6 / D5, 256 000 baud), switch it to multi-target mode at boot and parse its target frames.
3. Stamp every packet with a monotonic microsecond clock, synchronised to the host.
4. Send lidar and radar packets to the host as **UDP** datagrams, in a small versioned binary envelope.
5. Serve the camera as an **MJPEG** stream over HTTP (done: [Camera](#camera)).
6. Drive the **1.69" ST7789 screen** (SPI), the robot's face:
   - **eyes** by default: blinking, looking towards the nearest person tracked by the LD2450, sleepy when nobody is around, and any expression the host sends;
   - a top-down lidar **mini-map** with radar targets, and a status page (Wi-Fi, host link), on request.
7. **Audio in**: read the built-in PDM microphone (I2S0, 16 kHz) and stream it on request (done: [Audio](#audio)), or detect simple sound events locally.
8. **Audio out**: play chirps, beeps and speech streamed from the host through the MAX98357A (I2S1), with a volume cap (done: [Audio](#audio)).
9. Keep the robot **alive without a computer**: eyes, presence reactions and sounds work on the device alone; the host adds understanding and language.
10. Support **OTA** updates after the first USB flash.

The MR60BHA2 kit keeps its own ESP32-C6: [`mr60_bridge/`](mr60_bridge/) replaces Seeed's stock firmware on it and sends the vital signs straight to the host, in the same protocol.

## Pin map

Unchanged since rev D.

| Signal | XIAO pin | GPIO | Peripheral |
|---|---|---|---|
| Lidar Tx → | D7 | 44 | UART0 RX |
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
