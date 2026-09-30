# Bench wiring: OLED eyes, buttons and a buzzer

**A second Marvin on the desk, before the robot: 8 jumper wires on a classic ESP32 DevKit, no soldering.** The board runs the simulated sensors and a few real parts: Marvin's eyes on a small OLED screen, a **Talk** button (the app's *Talk now*), a **Mute** button, and a buzzer for the chimes. The host treats it like a robot: the eyes follow the person the brain sees, and the buttons drive the voice.

Firmware: `MARVIN_HAS_BENCH_IO` ([`firmware/src/bench/`](../firmware/src/bench/)), built by the `esp32dev` and `esp32s3` envs. Protocol: the `HELLO` flags and the `BUTTON` message in [protocol.md](protocol.md#buttons).

## Parts

| Part | What to buy | Notes |
|---|---|---|
| Board | Classic ESP32 DevKit, 30 pins (ESP32-WROOM-32, "DevKit V1"), or an ESP32-S3 DevKitC-1 | Header pins already soldered |
| Screen | 0.96" OLED, 128 × 64, **SSD1306**, I2C, 4 pins (VCC, GND, SCL, SDA) | Address 0x3C or 0x3D, both are found |
| Talk button | 6 × 6 mm push button (4 legs) | |
| Mute button | The same (optional, it can come later) | |
| Buzzer | **Passive** buzzer (no built-in oscillator), 3.3–5 V | An active buzzer only beeps at one pitch |
| Resistor | 220 Ω | In series with the buzzer |
| Wires | Female-to-male jumper wires, a breadboard | |

**No soldering.** The female end of each jumper goes onto a pin of the DevKit, the male end into the breadboard row of the part: the buttons, the buzzer and its resistor sit on the breadboard, and the OLED plugs into it by its own 4 pins (or takes female-to-female jumpers). Leave the DevKit off the breadboard: a 30-pin DevKit covers all but one row of it.

## Classic ESP32 DevKit (`esp32dev`, board 5)

The pin names are printed on the board: `D21` is GPIO21, and so on.

| Part | Its pin | DevKit pin | GPIO |
|---|---|---|---:|
| OLED | VCC | **3V3** | – |
| OLED | GND | **GND** | – |
| OLED | SCL | **D22** | 22 |
| OLED | SDA | **D21** | 21 |
| Talk button | one leg | **D32** | 32 |
| Talk button | the diagonally opposite leg | **GND** | – |
| Mute button (later) | one leg / the opposite leg | **D33** / **GND** | 33 |
| Buzzer | **+** (the longer leg), through the 220 Ω resistor | **D25** | 25 |
| Buzzer | **−** | **GND** | – |
| Status LED | the blue LED on the board | nothing to wire | 2 |

- **Buttons**: a 4-leg push button connects its legs in pairs. Take two **diagonally opposite** legs and you cannot get the pair wrong. No resistor: the ESP32's internal pull-up holds the pin high, the button pulls it to ground. A Mute button that is not fitted yet does no harm: its pin just stays high.
- **Buzzer**: the resistor goes between D25 and the buzzer's **+** leg (either way round). Several GND pins on the DevKit are the same ground: use any.
- **OLED**: 3V3, not VIN (5 V). Most modules have their own pull-ups on SDA and SCL.

Reserved for later (not in the firmware yet): a **PIR** motion sensor on D34 (GPIO34), a **DHT11** on D4 (GPIO4), a **light sensor** (LDR divider) on D35 (GPIO35). GPIO34 and 35 are inputs only, without pull-ups, which suits these sensors.

## ESP32-S3 DevKitC-1 (`esp32s3`, board 2)

The same parts on other pins. The OLED is on its own I2C bus: it works next to the ST7789 face screen (SPI on GPIO7, 9, 1, 2 and 4, [wiring.md](wiring.md)), and each shows the face.

| Part | Its pin | GPIO |
|---|---|---:|
| OLED | VCC / GND | 3V3 / GND |
| OLED | SCL | 18 |
| OLED | SDA | 17 |
| Talk button | one leg, the opposite leg to GND | 11 |
| Mute button | one leg, the opposite leg to GND | 12 |
| Buzzer | **+** through 220 Ω; **−** to GND | 13 |
| Status LED | the RGB LED on the board | 48 (v1.0) or 38 (v1.1) |

The RGB LED's data pin moved between board versions: GPIO48 on v1.0, GPIO38 on v1.1. The firmware drives both, so either board lights up; if you wire something to the other one, set `-DMARVIN_STATUS_RGB_ALT=-1` (or `-DMARVIN_STATUS_RGB=38 -DMARVIN_STATUS_RGB_ALT=-1` on a v1.1) in `platformio.ini`.

Reserved for later: PIR on GPIO14, DHT11 on GPIO15, light sensor on GPIO5.

## Flash it

Once, copy `firmware/include/secrets.example.h` to `firmware/include/secrets.h` and put your 2.4 GHz Wi-Fi in it. Then, board on USB:

```bash
cd firmware
pio run -e esp32dev -t upload && pio device monitor -e esp32dev     # classic DevKit
pio run -e esp32s3 -t upload && pio device monitor -e esp32s3       # ESP32-S3 DevKitC-1
```

If the classic board's upload stops at `Connecting...`, hold its **BOOT** button until the upload starts. After the first USB upload, `esp32dev_ota` updates it over Wi-Fi ([tools.md](tools.md)). Start the host on your computer with `./marvin up`.

Expected in the serial monitor (the order of the first lines may vary):

```text
Marvin firmware 0.1.0, board 5, simulated sensors
oled: SSD1306 at 0x3C, eyes on
simulated room ready in 40 ms
joining MyWifi.....
IP 192.168.1.43, RSSI -48 dBm
linked to host 192.168.1.10
button talk
```

## What it does

| Part | Wi-Fi | Looking for the host | Linked |
|---|---|---|---|
| Blue LED (classic) | Slow blink | Fast blink | On |
| RGB LED (S3), dim | Blue, slow blink | Amber, fast blink | Green |
| OLED | Eyes asleep, `joining Wi-Fi` | Eyes asleep, `looking for the host` | The eyes, without text |
| Buzzer | – | – | A rising arpeggio on the first link after boot (`-DMARVIN_BOOT_SOUND=0` to silence it) |

- **Eyes**: the robot's face ([face.md](face.md)) in white on black, at half scale, from the brain's presence state and events: they wake when someone comes, follow them, get sleepy when nobody is there and fall asleep. The board streams its simulated room, so the brain sees its simulated person walk in, sit down and leave. The voice's sounds show too: wide open while listening, a glance to the side while thinking. About 20–25 frames a second, drawn in their own task.
- **Talk**: a click, then the app's *Talk now*: a listening window opens (two notes up, eyes wide), ask your question; the window closes when the question is heard (two notes down). Press it while listening to close the window. With the voice off, a low double beep.
- **Mute**: a click, then the microphone is muted (two notes down) or unmuted (two notes up), as the Mute button of the app.
- Without a host, a press plays the low double beep and sends nothing.
- The chimes follow the voice's `chime` setting (on by default, in `voice.json`): off, the buzzer only clicks.

A screen that is not there, or loses contact, does not stop anything: the firmware looks for it every 5 s and starts it again when it answers (`oled: the screen stopped answering` in the log, then `eyes on` again).

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `oled: no SSD1306 at 0x3C or 0x3D` | SDA and SCL swapped, or no power | SCL on D22, SDA on D21, VCC on 3V3 |
| The screen shows noise, or the image is shifted by two columns | A 1.3" module with an **SH1106**, not an SSD1306 | Use an SSD1306 module; an SH1106 option (U8g2's `sh1106` driver) is a possible future build flag |
| The eyes stay asleep once linked | The host sees nobody (the simulated person is out of the room for a while), or the host is older than this firmware | Wait for the simulated person; update the host |
| No sound at all | Active buzzer, or the buzzer on the wrong pin | Use a passive buzzer; **+** through 220 Ω to D25 |
| Very quiet | The 220 Ω and a buzzer that wants 5 V | Lower the resistor to 100 Ω (not less) |
| A press does nothing in the app | Not linked (low double beep), or the voice is off | Check `linked to host` in the log; turn the voice on in the app |
| One press counts twice | A worn button | The firmware debounces 30 ms; try another button |
| The upload never starts (classic) | The auto-reset circuit misses | Hold **BOOT** while `Connecting...` |
