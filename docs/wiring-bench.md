# Bench wiring: OLED eyes, buttons, a buzzer and room sensors

**A second Marvin on the desk, before the robot: 8 jumper wires on a classic ESP32 DevKit, 6 more for the room sensors, no soldering.** The board runs the simulated sensors and a few real parts: Marvin's eyes on a small OLED screen, a **Talk** button (the app's *Talk now*), a **Mute** button, a buzzer for the chimes, and room sensors: a **PIR** motion sensor, a **DHT11** for the temperature and humidity and, later, a **light sensor**. The host treats it like a robot: the eyes follow the person the brain sees, the buttons drive the voice, and the PIR tells the brain whether someone is really there (the board's radar is simulated).

A variant with a real radar and a servo head that turns to look at you, no PIR or DHT11: [looks-bench.md](looks-bench.md).

Firmware: `MARVIN_HAS_BENCH_IO` ([`firmware/src/bench/`](../firmware/src/bench/)) and `MARVIN_HAS_ROOM_SENSORS` ([`firmware/src/room/`](../firmware/src/room/)), built by the `esp32dev` and `esp32s3` envs. Protocol: the `HELLO` flags, the `BUTTON` message ([protocol.md](protocol.md#buttons)) and the `ENVIRONMENT` message ([protocol.md](protocol.md#room-sensors)).

## Parts

| Part | What to buy | Notes |
|---|---|---|
| Board | Classic ESP32 DevKit, 30 pins (ESP32-WROOM-32, "DevKit V1"), or an ESP32-S3 DevKitC-1 | Header pins already soldered |
| Screen | 0.96" OLED, 128 × 64, **SSD1306**, I2C, 4 pins (VCC, GND, SCL, SDA) | Address 0x3C or 0x3D, both are found |
| Talk button | 6 × 6 mm push button (4 legs) | |
| Mute button | The same (optional, it can come later) | |
| Buzzer | **Passive** buzzer (no built-in oscillator), 3.3–5 V | An active buzzer only beeps at one pitch |
| Resistor | 220 Ω | In series with the buzzer |
| Motion sensor | **HC-SR501** PIR module (3 pins: VCC, OUT, GND) | Powered from 5 V, its output is 3.3 V |
| Temperature and humidity | **DHT11** module, 3 pins (+, OUT or S, −), on a small board with its own pull-up | The bare 4-pin sensor needs a 10 kΩ pull-up from DATA to 3V3 |
| Light sensor (later) | LDR module, 4 pins (VCC, GND, DO, AO) | Only AO is used |
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
| PIR (HC-SR501) | VCC | **VIN** (5 V from the USB) | – |
| PIR | OUT | **D34** | 34 |
| PIR | GND | **GND** | – |
| DHT11 module | **+** (VCC) | **3V3** | – |
| DHT11 module | OUT (or S, DATA) | **D4** | 4 |
| DHT11 module | **−** | **GND** | – |
| Light sensor (later) | VCC / GND | **3V3** / **GND** | – |
| Light sensor (later) | AO | **D35** | 35 |

- **Buttons**: a 4-leg push button connects its legs in pairs. Take two **diagonally opposite** legs and you cannot get the pair wrong. No resistor: the ESP32's internal pull-up holds the pin high, the button pulls it to ground. A Mute button that is not fitted yet does no harm: its pin just stays high.
- **Buzzer**: the resistor goes between D25 and the buzzer's **+** leg (either way round). Several GND pins on the DevKit are the same ground: use any.
- **OLED**: 3V3, not VIN (5 V). Most modules have their own pull-ups on SDA and SCL.
- **PIR**: the HC-SR501 wants 5 V (VIN, next to GND at the end of the board), and its output is 3.3 V, safe for the ESP32. The pins are marked under the white dome's lens (lift it off to read them). GPIO34 is an input only, without a pull-up: the PIR drives it both ways. Its two orange potentiometers: **time** (T_x, hold) fully **anticlockwise**, its minimum, about 3 s; **sensitivity** (S_x) as you like, about 3 m in the middle. The jumper: **H** (repeat trigger, the output stays high while you keep moving), as most modules come.
- **DHT11**: on 3V3. The module's own resistor pulls DATA up (the firmware adds the ESP32's pull-up as well). Keep it away from the ESP32 and the PIR, which warm up a little, and from your breath unless you mean it.
- **Light sensor**: AO on D35 (ADC1, the ADC that works with Wi-Fi on), DO unused. Its firmware part is off until you enable it ([below](#light-sensor)).

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
| PIR | VCC to **5V**, OUT, GND | 14 |
| DHT11 module | + to 3V3, OUT, − to GND | 15 |
| Light sensor (later) | VCC to 3V3, AO, GND | 5 (ADC1) |

The RGB LED's data pin moved between board versions: GPIO48 on v1.0, GPIO38 on v1.1. The firmware drives both, so either board lights up; if you wire something to the other one, set `-DMARVIN_STATUS_RGB_ALT=-1` (or `-DMARVIN_STATUS_RGB=38 -DMARVIN_STATUS_RGB_ALT=-1` on a v1.1) in `platformio.ini`.

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
pir: on GPIO34, warming up for 60 s
dht11: on GPIO4
simulated room ready in 40 ms
joining MyWifi.....
IP 192.168.1.43, RSSI -48 dBm
dht11: 23.4 °C, 48 % humidity
linked to host 192.168.1.10
button talk
pir: warmed up, no motion
pir: motion
pir: still
room: 23.4 °C, 48 %, last motion 41 s ago
```

The `room:` line comes every minute; `pir: motion` and `pir: still` as they happen, from about a minute after power-on.

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
- **PIR**: for about a minute after power-on it settles and reports nothing (`warming up`). Then each motion starts and ends at once in the host (`ENVIRONMENT`, [protocol.md](protocol.md#room-sensors)), and its state every 5 s. Since the board's radar is simulated, the host takes **the real PIR** as the presence: wave at it and the eyes wake up, *Here with you* on Home; after 2 minutes without any movement the brain decides you left and the eyes fall asleep ([intelligence.md](intelligence.md#a-pir-on-the-bench-board)). The simulated person still walks around the Robot panel, but no longer drives the brain.
- **DHT11**: read every 5 s, in a task of its own. The Home screen's *Room* card shows the temperature and the humidity, and Marvin knows them when you ask ("il fait quelle température ?"). Breathe on it: the humidity climbs within a few seconds. Unplugged, its readings are dropped within 12 s (`dht11: no reading for 12 s`), and come back when it answers again.

### Light sensor

Not built in yet: the classic ESP32's GPIO35 has no pull-up or pull-down, so an unwired pin reads noise that would look like light, and the firmware cannot tell. Once the module is wired, enable it in `platformio.ini`, in the env of your board: uncomment `-DMARVIN_HAS_LDR` and flash again. The *Room* card then says *bright*, *dim* or *dark*. The common module's AO voltage falls as the light rises, which the firmware expects; for a sensor wired the other way round (brighter, higher), add `-DMARVIN_LDR_BRIGHT_HIGH=1`.

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
| `pir: motion` every few seconds with nobody there | Still warming up in its first minute, sensitivity too high, or the PIR sees the Wi-Fi antenna's bursts | Wait for `warmed up`; turn S_x down; keep the PIR's wires short and the module away from the DevKit's antenna |
| `pir: motion` never comes | OUT on the wrong pin, or no 5 V | OUT on D34, VCC on **VIN**; the module needs a minute after power-on |
| The eyes stay awake long after you left | The PIR's hold (T_x) turned up, or the jumper on L with a long hold | T_x fully anticlockwise; the host waits 2 minutes without movement anyway |
| `dht11: no reading on GPIO4 (no answer)` | DATA not on D4, no power, or the bare 4-pin sensor without its pull-up | OUT on D4, + on 3V3; a 10 kΩ from DATA to 3V3 for the bare sensor |
| `dht11: ... (bad checksum)` now and then | A long or noisy wire | Shorter wires; an occasional bad read is retried, and a reading lasts 12 s |
