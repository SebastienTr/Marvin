# The XIAO bench: Marvin's own electronics on a breadboard

**The robot's real board and parts, wired on a breadboard before they go into the shell, one module at a time.** The pins are exactly those of [wiring.md](wiring.md), so nothing changes when the parts move into the robot. A boot self-test prints one line per part in the serial monitor, shows colours then the eyes on the screen, and plays a short chime: you see at once what answers and, when something does not, which wires to check.

Firmware: the `xiao_bench` env (USB) and `xiao_bench_ota` (Wi-Fi) in [`firmware/platformio.ini`](../firmware/platformio.ini): the robot's firmware with a real LD2450 and no lidar (`MARVIN_LIDAR_MODEL=0`), the face on the ST7789 (`MARVIN_HAS_SCREEN`), the speaker and microphone (`MARVIN_HAS_AUDIO`), the camera (`MARVIN_HAS_CAMERA`), OTA, and the self-test (`MARVIN_SELF_TEST`, wording in [`self_test.h`](../firmware/src/self_test.h)). The host sees it as the robot (board 3).

## Parts

The robot's electronics from the [BOM](bom.md), without the shell:

| Part | Notes |
|---|---|
| Seeed XIAO ESP32S3 **Sense** | With its Sense board (camera + microphone) clipped on. Header pins soldered, or the pre-soldered version |
| HLK-LD2450 (KIT-A) | Its cable ends in Dupont plugs |
| 1.69" ST7789 screen, 240 × 280 | 8 labelled wires: VCC, GND, DIN, CLK, CS, DC, RST, BL |
| MAX98357A amplifier + 2030 speaker (8 Ω, 1 W) | Amp header soldered; speaker on the screw terminal |
| 6 × 6 mm push button (4 legs) | The knob, on the breadboard |
| Breadboard, jumper wires | Male-to-male for the breadboard, female-to-male for the modules |

The MR60BHA2 kit (60 GHz, vital signs) has its own ESP32-C6 and Wi-Fi: it needs only 5 V and GND (USB-C), not the XIAO ([mr60_bridge](../firmware/mr60_bridge/README.md)).

## Wiring

Put the XIAO across the breadboard's centre gap, USB-C at the end. Use the breadboard's two rails as the robot's buses: one rail **5V** (from the XIAO's 5V pin), one rail **GND**, and a row for **3V3**. On the bench a single GND rail replaces the robot's GND-A and GND-B.

| Module | Its pin | XIAO pin | GPIO |
|---|---|---|---:|
| LD2450 | 5V | **5V** rail | – |
| LD2450 | GND | GND rail | – |
| LD2450 | TX | **D4** | 5 |
| LD2450 | RX | **D5** | 6 |
| Screen | VCC | **3V3** (not 5V) | – |
| Screen | BL | **3V3** | – |
| Screen | GND | GND rail | – |
| Screen | DIN | **D10** | 9 |
| Screen | CLK | **D8** | 7 |
| Screen | CS | **D0** | 1 |
| Screen | DC | **D1** | 2 |
| Screen | RST | **D3** | 4 |
| Amp | VIN | **5V** rail | – |
| Amp | GND | GND rail | – |
| Amp | BCLK | **D9** | 8 |
| Amp | LRC | **D6** | 43 |
| Amp | DIN | **D2** | 3 |
| Speaker | + / − | amp terminal + / − | – |
| Microphone, camera | on the Sense board | nothing to wire | 41, 42; camera internal |
| Knob (push button) | one leg / the diagonally opposite leg | **D7** / GND rail | 44 |

- **TX goes to D4, RX to D5**: the radar's TX talks, the XIAO's D4 (UART1 RX) listens; the XIAO answers on D5.
- **3V3 for the screen**, 5 V for the radar and the amp. 5 V never goes to a D pin.
- Leave the amp's **GAIN** and **SD** pins unconnected (9 dB, mono mix).
- Leave the Sense board's **microSD slot empty**: the card shares the screen's SPI pins and D9 (GPIO8, its MISO).
- **D6 and D7** are UART0's default pins (GPIO43 TX, GPIO44 RX). The console uses the native USB port instead, so D6 is free for the amp's LRC and D7 for the knob.
- **The knob**: a 4-leg push button across the centre gap; two **diagonally opposite** legs are always the two sides of the switch. No resistor: the XIAO's internal pull-up holds D7 high, the button pulls it to ground. Tap: Marvin listens (*Talk now*); hold 1.5 s: mute or unmute.
- Wire **one module at a time** if you like, power unplugged each time: the firmware runs with any of them missing, and its line says so.

## Flash it

Once, copy `firmware/include/secrets.example.h` to `firmware/include/secrets.h` and put your 2.4 GHz Wi-Fi in it (and an `OTA_PASSWORD`). Then, XIAO on USB (`ls /dev/cu.usbmodem*` on a Mac, `/dev/ttyACM*` on Linux):

```bash
cd firmware
pio run -e xiao_bench -t upload --upload-port /dev/cu.usbmodemXXXX
pio device monitor -e xiao_bench --port /dev/cu.usbmodemXXXX
```

**No port, or the upload cannot connect?** Put the XIAO in download mode: unplug it, hold its **B** (BOOT) button, plug the USB cable in, release. Upload again; then press **R** (RESET) once so the new firmware starts. This is only needed when the firmware on it does not run its USB port (a crash loop, another program), never for normal updates.

The board restarts after the upload and the self-test runs once. It waits up to 3 s for the monitor; if you open the monitor later, **press Enter** in it to see the self-test lines again, or press **R** to run it again.

After that first USB upload, update it over Wi-Fi ([tools.md](tools.md)), with the name in its `OTA:` line (or its IP); the password comes from `secrets.h`:

```bash
pio run -e xiao_bench_ota -t upload --upload-port marvin-xxxxxx.local
```

Start the host on your computer with `./marvin up`.

## The self-test

### At boot, everything wired

```text
Marvin firmware 0.1.0, board 3, real sensors
self-test: one line per part (docs/xiao-bench.md); press Enter to see them again
screen: ok (ST7789 id 85 85 52)
speaker: chime played
mic: level -52 dBFS
radar: 10.0 frame/s
wifi: joining "MyWifi"
wifi: 192.168.1.42 (RSSI -55 dBm)
OTA: ready as marvin-a1b2c3.local (192.168.1.42), port 3232, password protected
camera: ok 640x480 (OV3660), http://192.168.1.42:81/stream
host: linked (192.168.1.10)
```

Then every 5 s:

```text
radar: 10.0 frame/s
mic: level -55 dBFS
```

The numbers are examples: your panel's ID, levels, addresses and the board's name differ. The order is the order the parts start in: screen, speaker and mic, radar (about 2 s), Wi-Fi, OTA, camera, host.

### Part by part

| Part | When it works | When it is missing or miswired | What you see and hear |
|---|---|---|---|
| Screen | `screen: ok (ST7789 id 85 85 52)` | `screen: no answer - check DIN->D10, CLK->D8, CS->D0, DC->D1, RST->D3, VCC and BL->3V3, GND (if the eyes show anyway, your module cannot answer: ignore this)` | Red, green, blue, 0.3 s each, then the eyes (below) |
| Speaker | `speaker: chime played` | the same line: the amp cannot be detected, listen | Two short rising notes (660 Hz then 990 Hz, 0.2 s), at a moderate volume |
| Microphone | `mic: level -52 dBFS` (about -60 to -45 in a quiet room, higher when you talk or clap) | `mic: silent (only zeros) - is the Sense board (camera + mic) clipped on?` or `mic: no data - the PDM microphone did not start (is the Sense board clipped on?)` | – |
| Radar | `radar: 10.0 frame/s` | `radar: no frames - check TX->D4, RX->D5, 5V/GND` | With a screen: stand in front of it and the eyes wake up and follow you |
| Wi-Fi | `wifi: 192.168.1.42 (RSSI -55 dBm)` | every 20 s: `wifi: still joining "MyWifi" after 20 s - check WIFI_SSID and WIFI_PASSWORD in include/secrets.h (2.4 GHz networks only)` | The orange LED blinks fast while joining |
| Camera | `camera: ok 640x480 (OV3660), http://192.168.1.42:81/stream` | `camera: init failed (0x...), camera disabled` then `camera: not found - check the Sense board (camera + mic) is clipped onto the XIAO and the camera's ribbon is in its connector` | Open the address in a browser: the live image |
| Host | `host: linked (192.168.1.10)` | 10 s after Wi-Fi, then every 30 s: `host: not found after 10 s - start the host on your computer (./marvin up), on the same Wi-Fi; the robot keeps calling` | On linking, a rising four-note arpeggio on the speaker; the LED blinks slowly |

The radar and mic lines come back every 5 s. Still, **unplug the USB cable before wiring anything**, then plug it back and read the new self-test. When the radar's bad frames or UART overflows are not zero, the radar line says so: `radar: 10.0 frame/s (2 bad frames, 0 UART overflows)` (a loose wire, or a long one).

Other lines you may see: `audio: speaker 0 dgram, ... 1 sounds; mic ...` and `camera: ...` every 10 s while sound plays or someone watches the camera (counters, [audio.md](audio.md)); `OTA: ...` during a Wi-Fi update; `host lost, searching` if the host stops.

### The screen

1. Shortly after power-on (up to 3 s later on a computer while it waits for the serial monitor): **full red, full green, full blue**, 0.3 s each. They must be exactly those colours: if red shows as blue, the panel's colour order differs (build with `-DMARVIN_TFT_MADCTL=0x08`); if they look like their negatives (cyan, magenta, yellow), set `-DMARVIN_TFT_INVERT=0`.
2. Then **the eyes**: two rounded, warm-white eyes on a near-black background. They start closed (asleep), open wide as if someone had arrived, blink, and look straight at you for about 7 s.
3. Then, with no host and nobody in front of the radar, Marvin acts alone: the eyes look around, grow sleepy (half-closed, warmer amber) after 4 s and close after 20 s, breathing slowly. Someone in front of the radar wakes them again, and they follow that person. Once the host is linked, its `FACE_STATE` decides.

The eyes are drawn whether the screen answers its ID or not: some modules cannot be read back, so trust what you see.

### The sounds

- At boot: the **chime**, two short rising notes, 0.2 s. A 1 W speaker on a 3 W amp can be overdriven: the firmware caps the speaker at half of full scale at volume 100 and starts at volume 60 (about a fifth of full scale), so the chime is moderate; the host changes the volume (`AUDIO_CTRL`).
- On the first link to the host after boot: the **hello** arpeggio, four rising notes.

## Voice and microphone through the robot

The bench is the robot as far as the host is concerned: its `HELLO` has the audio flag, so the Java host can route the voice through it. By default (`"audio_route": "auto"` in `~/.config/marvin/voice.json`, [voice.md](voice.md#audio-devices)) it uses the robot's speaker and microphone whenever a robot with audio is connected, the computer's otherwise; `"computer"` keeps the Mac's, `"robot"` always the robot's. On that route the host asks for the microphone (`AUDIO_CTRL` MIC_START, `AUDIO_IN` back at 16 kHz) and sends the spoken replies as `AUDIO_OUT`, and the voice's earcons as `SOUND`. The self-test's level lines keep running meanwhile: they listen to the same microphone.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| No `/dev/cu.usbmodem*` port | A charge-only USB cable; or download mode (hold **B** while plugging in) |
| The monitor shows nothing | It opened after the self-test: press **Enter**, or **R** on the board. Check the port, and that no other monitor holds it |
| `screen: no answer` and a black screen | VCC/BL on 3V3, GND, then DIN→D10 and CLK→D8 (the two most often swapped), CS→D0, DC→D1, RST→D3 |
| `screen: no answer` but the eyes show | The module cannot answer its ID: nothing to do |
| Colours wrong | `MARVIN_TFT_MADCTL` / `MARVIN_TFT_INVERT` (above); the picture shifted by 20 rows: `MARVIN_TFT_ROW_OFFSET` |
| No chime | Amp VIN on 5V and GND; BCLK→D9, LRC→D6, DIN→D2; the speaker on the terminal |
| Chime distorted or too loud | Volume lower from the host; the amp's GAIN pin must stay unconnected |
| `radar: no frames` | TX and RX swapped (TX→D4, RX→D5), no 5 V on the radar, or a loose GND |
| Radar frames but the eyes do not follow | Stand 0.3–6 m in front of the radar's face; mounted upside down: `-DMARVIN_LD2450_X_SIGN=-1` |
| `camera: not found` | The Sense board not fully clipped on, or the camera's ribbon out of its connector |
| `mic: silent` / `mic: no data` | The Sense board (the microphone is on it, next to the camera) |
| `wifi: still joining` | `secrets.h`: SSID and password, a 2.4 GHz network (no 5 GHz) |
| `host: not found` | `./marvin up` running on the computer, on the same network; a firewall letting UDP 47100 in |
| The board resets when the speaker plays | A weak USB port: a powered hub or a 2 A charger; a shorter cable |
