# The "looks" bench: a head that turns to look at you

**A classic ESP32 DevKit, a 24 GHz radar and a 9 g servo: Marvin turns its head towards the nearest person, chirps hello when someone arrives, and its OLED eyes follow them.** No lidar, no PIR, no DHT11 on this one. The host treats it like any bench board (the Talk button, the eyes, the buzzer), and also hears where the head looks (`HEAD`) and can steer it (`HEAD_CTRL`).

Firmware: the env `esp32dev_looks` (and `esp32dev_looks_ota` over Wi-Fi), with real sensors (`MARVIN_SENSORS=REAL`, `MARVIN_LIDAR_MODEL=0`: the LD2450 only), `MARVIN_HAS_BENCH_IO` ([`firmware/src/bench/`](../firmware/src/bench/)) and `MARVIN_HAS_HEAD` ([`firmware/src/head/`](../firmware/src/head/)). Protocol: [protocol.md, Head](protocol.md#head). The simulated bench (`esp32dev`, [wiring-bench.md](wiring-bench.md)) is unchanged.

## Parts

| Part | What to buy | Notes |
|---|---|---|
| Board | Classic ESP32 DevKit, 30 pins (ELEGOO ESP32 / ESP32-WROOM-32, "DevKit V1") | Header pins already soldered |
| Radar | **HLK-LD2450** 24 GHz, with its 4-wire cable | 5 V supply, 3.3 V serial |
| Servo | 9 g hobby servo, 180° (SG90 or MG90S) | Brown GND, red 5 V, orange signal |
| Buzzer | **Passive** buzzer | 220 Ω in series |
| Button | 6 × 6 mm push button | The Talk button |
| Screen (optional) | 0.96" OLED, 128 × 64, SSD1306, I2C | 0x3C or 0x3D; the board runs without it |
| WS2812 strip (optional) | Any WS2812B strip | Off by default, see below |
| Capacitor (recommended) | 470–1000 µF, 10 V electrolytic | Across the servo's 5 V and GND, against brownouts |

## Wiring

The pin names are printed on the board: `D26` is GPIO26.

| Part | Its pin | DevKit pin | GPIO |
|---|---|---|---:|
| LD2450 | 5V | **VIN** (5 V from the USB) | – |
| LD2450 | GND | **GND** | – |
| LD2450 | TX | **D26** (the board's RX) | 26 |
| LD2450 | RX | **D27** (the board's TX) | 27 |
| Servo | red (+) | **VIN** | – |
| Servo | brown (−) | **GND** | – |
| Servo | orange (signal) | **D13** | 13 |
| Buzzer | **+**, through the 220 Ω resistor | **D25** | 25 |
| Buzzer | **−** | **GND** | – |
| Talk button | one leg / the diagonally opposite leg | **D32** / **GND** | 32 |
| OLED (optional) | VCC / GND | **3V3** / **GND** | – |
| OLED (optional) | SDA / SCL | **D21** / **D22** | 21 / 22 |
| WS2812 strip (optional) | DIN | **D18** | 18 |
| Status LED | the blue LED on the board | nothing to wire | 2 |

GPIO34 and GPIO4 stay free.

- **Radar**: TX to the board's RX (D26) and RX to its TX (D27): the lines cross. 256 000 baud; the firmware sets it to multi-target tracking at boot. Mount it upright, its face towards the room, at the head's centre of rotation or on the base below it (see [Mounting](#mounting)).
- **Servo and brownouts**: a 9 g servo draws spikes of 500 mA or more when it starts moving. On USB power that can drop the 5 V enough to reset the ESP32 (`Brownout detector was triggered` in the monitor). The firmware keeps the servo silent for its first second, starts it at the centre, ramps every move, and lets it rest (no pulses) once the head has held still for 5 s. Add the capacitor across VIN and GND close to the servo (mind its polarity: the stripe is −), and use a USB port or charger that gives 1 A. A powered hub is better than a laptop port.
- **Buttons, buzzer, OLED**: as on the simulated bench ([wiring-bench.md](wiring-bench.md#classic-esp32-devkit-esp32dev-board-5)). Without an OLED the board only says so once in the log and looks again every 5 s: plug one in later and the eyes start.
- **WS2812 strip**: off by default. In `platformio.ini`, uncomment `-DMARVIN_STATUS_RGB=18` and `-DMARVIN_STATUS_RGB_ALT=-1` in `esp32dev_looks`: the strip's first LED then shows the link status (blue blinking: Wi-Fi; amber: looking for the host; green: linked) instead of the blue LED. Power a longer strip from VIN, not from the board's 3V3.

## Mounting

- The servo turns the head; the radar can sit on the base (fixed) or on the head. Fixed is the default: the target's angle is then measured from the base, and the head turns to it. A radar on the head sees the target relative to where the head looks: build with `-DMARVIN_HEAD_RADAR_ON_HEAD=1`.
- The OLED is expected on the head (`MARVIN_OLED_ON_HEAD`, 1): the eyes look at the person as seen from the head, so they lead the turn and settle straight ahead once the head faces them. `-DMARVIN_OLED_ON_HEAD=0` for a screen on the base.
- Centre the servo before fixing the horn: power the board, wait for `head: servo on, at the centre`, then press the horn on facing straight ahead.

**Which way round.** Stand to one side of the robot. If the eyes look away from you, the radar is mounted upside down: `-DMARVIN_LD2450_X_SIGN=-1`. Then, if the head turns away from you, the servo turns the other way: `-DMARVIN_HEAD_MIRROR=1`. Both are build flags in `esp32dev_looks`.

## Flash it

Once, copy `firmware/include/secrets.example.h` to `firmware/include/secrets.h` and put your 2.4 GHz Wi-Fi in it (and an `OTA_PASSWORD`). Then, board on USB, always naming the port (`ls /dev/cu.usbserial-*` on a Mac, `/dev/ttyUSB*` on Linux):

```bash
cd firmware
pio run -e esp32dev_looks -t upload --upload-port /dev/cu.usbserial-XXXX
pio device monitor -e esp32dev_looks --port /dev/cu.usbserial-XXXX
```

If the upload stops at `Connecting...`, hold the board's **BOOT** button until it starts. After that first USB upload, update it over Wi-Fi ([tools.md](tools.md)), with the name the board prints at boot (or its IP):

```bash
pio run -e esp32dev_looks_ota -t upload --upload-port marvin-xxxxxx.local
```

Start the host on your computer with `./marvin up`.

Expected in the serial monitor:

```text
Marvin firmware 0.1.0, board 5, real sensors
head: servo on GPIO13, 500..2500 us, centre 90 deg, limits 20..160 deg, driven in 1000 ms
no lidar (MARVIN_LIDAR_MODEL=0)
LD2450 on UART1 at 256000 baud: multi-target tracking set
oled: SSD1306 at 0x3C, eyes on
joining MyWifi....
head: servo on, at the centre
IP 192.168.1.43, RSSI -48 dBm
linked to host 192.168.1.10
LD2450 10.0 frame/s, 0 bad; 0 UART overflows
head: someone arrived
button talk
head: they left
```

## What it does

- **Head**: follows the nearest LD2450 target within 6 m: its angle `atan2(x, y)`, smoothed (0.25 s), with a 3° deadband so the radar's jitter does not twitch it, at most 120°/s and slowing as it arrives. Servo travel 20°–160° around a centre of 90°, pulses of 500–2500 µs for 0–180°. Nobody for 3 s: back to the centre, slowly (30°/s). Nobody for 20 s: now and then (every 6 to 15 s) a small glance to one side (10° to 30°), held a second, and back. It works before Wi-Fi is up, and without a host.
- **Presence chirps**: someone arriving after nobody for 10 s (seen in three radar frames in a row, so a ghost frame says nothing) plays the *hello* arpeggio and a happy look; nobody for 10 s plays a softer, lower *bye* (a narrow pulse on the buzzer). None in the first 10 s after boot. Build with `-DMARVIN_PRESENCE_CHIRPS=0` to mute them.
- **Eyes**: Marvin's eyes ([wiring-bench.md](wiring-bench.md#what-it-does)) with their blinks. Without the host's `FACE_STATE` (before linking, or a host that stopped), they follow the radar's target themselves, wake up when someone comes, get sleepy and fall asleep when nobody is there. Linked, the host's `FACE_STATE` wins. The bottom line says *joining Wi-Fi* or *looking for the host* until it is linked.
- **Talk button**: `BUTTON` (talk) to the host, debounced; without a host, a low double beep.
- **To the host**: the raw LD2450 frames (as any robot), `HEAD` 5 times a second (mode, yaw, goal, target, whether it tracks, glances or rests), `HELLO` with the buttons, buzzer and (while the OLED answers) face flags.
- **From the host**: `FACE_STATE`, `FACE_EVENT`, `SOUND` as on the bench, and `HEAD_CTRL`: auto, manual at a given yaw, or centre ([protocol.md](protocol.md#head)). Manual holds until the host says otherwise or is lost. The Python and Java hosts can encode it (`protocol.head_ctrl`, `HostMessages.headCtrl`); nothing in the app sends it yet.

Build flags, in `esp32dev_looks` (defaults in brackets): `MARVIN_HEAD_PIN` (13), `MARVIN_HEAD_MIN_US` / `MARVIN_HEAD_MAX_US` (500 / 2500), `MARVIN_HEAD_CENTER_DEG` (90), `MARVIN_HEAD_MIN_DEG` / `MARVIN_HEAD_MAX_DEG` (20 / 160), `MARVIN_HEAD_MIRROR` (0), `MARVIN_HEAD_RADAR_ON_HEAD` (0), `MARVIN_LD2450_X_SIGN` (1), `MARVIN_OLED_ON_HEAD` (1), `MARVIN_HEAD_MAX_DPS` (120), `MARVIN_HEAD_ATTACH_MS` (1000), `MARVIN_HEAD_REST_MS` (5000, 0: never rest), `MARVIN_HEAD_LEDC_CHANNEL` (4: a timer of its own, the buzzer has channel 6), `MARVIN_PRESENCE_CHIRPS` (1), `MARVIN_EYES_TARGET_Z_MM` (300: how high the eyes look).

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| The board resets when the head moves (`Brownout detector was triggered`) | The servo's current on USB: the capacitor, a 1 A port or a powered hub; a shorter, thicker USB cable |
| `LD2450 ... no answer, reading its stream anyway`, and `LD2450 0.0 frame/s` | TX and RX not crossed, or no 5 V on the radar |
| The head jitters while you stand still | A servo humming at its position: it rests after 5 s; a loose horn; raise the deadband in `head_logic.h` |
| The head turns away from you | `MARVIN_HEAD_MIRROR=1` (or `MARVIN_LD2450_X_SIGN=-1` if the eyes look away too) |
| The head hits its end stops | Narrow `MARVIN_HEAD_MIN_DEG` / `MARVIN_HEAD_MAX_DEG`, or adjust `MARVIN_HEAD_MIN_US` / `MARVIN_HEAD_MAX_US` to your servo |
| No hello | Nobody for 10 s first; three frames in a row; `MARVIN_PRESENCE_CHIRPS=1`; a passive buzzer |
