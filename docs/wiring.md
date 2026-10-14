# Wiring

**27 connections, and the only soldering is two header strips.** This guide assumes you have never wired electronics before.

The same parts on a breadboard first, with a boot self-test that says what answers? See [xiao-bench.md](xiao-bench.md). A bench board instead (an ESP32 DevKit with Marvin's eyes on a small OLED, a Talk button, a buzzer, a PIR and a DHT11, no soldering)? See [wiring-bench.md](wiring-bench.md).

Rev F has **no lidar** (since 14 October 2026): the two radars and the camera sense the room. D7, the last free pin, reads the **orange knob**: a 6 × 6 mm push button (tap it: Marvin listens; hold it 1.5 s: mute or unmute).

## The idea in one paragraph

There are two kinds of wires:

- **Power wires** (5 V, 3.3 V and GND) go into one of four WAGO lever connectors. Cut the plug off, strip 10 mm of insulation, lift the lever, insert, and close the lever.
- **Data wires** keep their female Dupont plug and push straight onto a pin of the ESP32.

The USB-C cable (charger or power bank at the other end) plugs into the ESP32's USB-C port. The ESP32's **5V** pin is connected directly to that port's 5 V line, so it feeds the WAGO bus, and the bus powers every sensor.

## Where everything sits in the robot

Body heights come from the rev F CAD; head heights are planned until the head CAD lands.

| Part | Place | Height above the desk |
|---|---|---:|
| XIAO ESP32S3 Sense | Face frame, tilted 20°, camera above the screen | ≈ 113 mm |
| Screen | Face frame, vertical, behind the acrylic window | ≈ 92 mm |
| MR60BHA2 kit | Body, upper part of the sensor sled, tilted 20° | 42 mm |
| **Lever connectors** | Body, stuck on the back plate of the sensor sled | ≈ 20–55 mm |
| **Amp** | Body, back plate of the sensor sled, low down | ≈ 20 mm |
| Speaker | Body, against the dot grille in the right wall | 18 mm |
| HLK-LD2450 | Body, bottom front of the sensor sled, tilted 10° | 16 mm |
| Knob (push button) | Head, in the orange knob | placed by the head CAD |

Everything between head and body (XIAO power and data, screen, knob) runs through the neck damper. Every run fits the 20 cm Dupont jumpers. The USB-C power cable enters at the back of the plinth and climbs through the neck to the XIAO.

## Three rules

1. **Read the label, not the colour.** Wire colours differ between sellers. The pin names printed on each board (TX, GND, 5V…) are what count.
2. **5 V never touches a D pin.** 5 V goes only into its WAGO and onto the ESP32 pin marked **5V**. Anywhere else, it can destroy the ESP32.
3. **Connect the power last.** Wire everything, re-check the table line by line, then plug in the charger. If anything gets hot or smells, unplug it.

## Diagram

```mermaid
flowchart LR
  BANK["Charger or power bank"] -- "USB-C cable, 90° plug" --> XIAO

  subgraph XIAO["XIAO ESP32S3 Sense (built-in mic)"]
    P5V["5V"]; PGND["GND"]; P33["3V3"]; D4["D4 / GPIO5"]; D5["D5 / GPIO6"]
    D0["D0 / GPIO1"]; D1["D1 / GPIO2"]; D3["D3 / GPIO4"]; D8["D8 / GPIO7"]; D10["D10 / GPIO9"]
    D2["D2 / GPIO3"]; D6["D6 / GPIO43"]; D9["D9 / GPIO8"]; D7["D7 / GPIO44"]
  end

  W5["WAGO 5V"]; WGA["WAGO GND-A"]; WGB["WAGO GND-B"]; W33["WAGO 3V3"]
  P5V --> W5
  PGND --> WGA
  WGA -- "link wire" --- WGB
  P33 --> W33

  subgraph RAD["HLK-LD2450"]
    R5["5V"]; RG["GND"]; RT["TX"]; RR["RX"]
  end
  W5 --> R5
  WGA --> RG
  RT --> D4
  D5 --> RR

  subgraph MR["MR60BHA2 kit"]
    MU["USB-C (pigtail)"]
  end
  W5 -- "red" --> MU
  WGB -- "black" --> MU

  subgraph LCD["1.69in screen (ST7789)"]
    SV["VCC"]; SG["GND"]; SDIN["DIN"]; SCLK["CLK"]; SCS["CS"]; SDC["DC"]; SRST["RST"]; SBL["BL"]
  end
  W33 --> SV
  W33 --> SBL
  WGB --> SG
  D10 --> SDIN
  D8 --> SCLK
  D0 --> SCS
  D1 --> SDC
  D3 --> SRST

  subgraph AMP["MAX98357A amp"]
    AV["VIN"]; AG["GND"]; ABC["BCLK"]; ALR["LRC"]; ADI["DIN"]; ASP["speaker + / -"]
  end
  W5 --> AV
  WGB --> AG
  D9 --> ABC
  D6 --> ALR
  D2 --> ADI
  ASP --> SPK["2030 speaker 8 Ω"]

  subgraph KNOB["Knob: 6 × 6 mm push button"]
    KA["leg"]; KB["diagonally opposite leg"]
  end
  KA --> D7
  KB --> WGA
```

## Wire-by-wire table

Tick each line as you go.

| # | Wire (name printed on the board) | Goes to | How |
|---:|---|---|---|
| 1 | XIAO **5V** | WAGO 5V | Dupont female on the pin, other end cut and stripped |
| 2 | XIAO **GND** | WAGO GND-A | Same as above |
| 3 | XIAO **3V3** | WAGO 3V3 | Same as above |
| 4 | Link wire | WAGO GND-A ↔ WAGO GND-B | A short stripped wire joining the two GND connectors |
| 5 | LD2450 **5V** | WAGO 5V | Cut, strip |
| 6 | LD2450 **GND** | WAGO GND-A | Cut, strip |
| 7 | LD2450 **TX** | XIAO **D4** | Push the Dupont plug on |
| 8 | LD2450 **RX** | XIAO **D5** | Push the Dupont plug on |
| 9 | USB-C pigtail **red** | WAGO 5V | Usually pre-stripped. The plug goes into the MR60BHA2 kit. |
| 10 | USB-C pigtail **black** | WAGO GND-B | Same as above |
| 11 | Screen **VCC** | WAGO 3V3 | Cut, strip. **3V3, not 5V**: the screen's logic must match the ESP32's 3.3 V. |
| 12 | Screen **BL** | WAGO 3V3 | Cut, strip. The backlight stays on at full brightness; the eyes dim in software by drawing darker colours. |
| 13 | Screen **GND** | WAGO GND-B | Cut, strip |
| 14 | Screen **DIN** | XIAO **D10** | Push the Dupont plug on (SPI data) |
| 15 | Screen **CLK** | XIAO **D8** | Push the Dupont plug on (SPI clock) |
| 16 | Screen **CS** | XIAO **D0** | Push the Dupont plug on |
| 17 | Screen **DC** | XIAO **D1** | Push the Dupont plug on |
| 18 | Screen **RST** | XIAO **D3** | Push the Dupont plug on |
| 19 | Amp **VIN** | WAGO 5V | Dupont female on the amp pin, other end cut and stripped |
| 20 | Amp **GND** | WAGO GND-B | Same as above |
| 21 | Amp **BCLK** | XIAO **D9** | Dupont female–female |
| 22 | Amp **LRC** | XIAO **D6** | Dupont female–female |
| 23 | Amp **DIN** | XIAO **D2** | Dupont female–female |
| 24 | Speaker **+** | Amp terminal **+** | Screw terminal on the amp |
| 25 | Speaker **−** | Amp terminal **−** | Screw terminal on the amp |
| 26 | Knob button, one leg | XIAO **D7** | Female Dupont pushed onto the leg |
| 27 | Knob button, the **diagonally opposite** leg | WAGO GND-A | Female Dupont on the leg, other end cut and stripped |

A 4-leg push button connects its legs in pairs: two **diagonally opposite** legs are always on the two sides of the switch, so you cannot get the pair wrong. No resistor: the XIAO's internal pull-up holds D7 high and the button pulls it to ground. If a Dupont plug is loose on the thin leg, pinch it, or hold it with a dab of solder.

The screen ships with a cable that ends in 8 labelled Dupont plugs. Leave the amp's **GAIN** and **SD** pins unconnected: the defaults give 9 dB of gain and a mono mix.

When you are done:

| Connector | Wires | Ports used |
|---|---|---|
| WAGO 5V | XIAO, LD2450, MR60, amp | 4 / 5 |
| WAGO GND-A | XIAO, LD2450, link, knob | 4 / 5 |
| WAGO GND-B | link, MR60, screen, amp | 4 / 5 |
| WAGO 3V3 | XIAO, screen VCC, screen BL | 3 / 5 |

Every XIAO pin is used: **D7 (GPIO44)** is the knob's. The free WAGO ports are there for a later module.

## The only soldering

The standard XIAO ESP32S3 Sense ships with loose header pins, and so do most MAX98357A amp boards. Solder these two header strips (7 + 7 pins on the XIAO, 7 pins on the amp) before wiring. It takes about ten minutes. If you buy both boards pre-soldered, the build needs no soldering at all.

## XIAO pin map

As seen **from the pin side** (pins pointing at you, USB-C at the top). In the head frame this is how you see the board from behind; its USB-C port points to the side.

```
               ┌──[ USB-C ]──┐
         5V  ● │             │ ●  D0
        GND  ● │             │ ●  D1
        3V3  ● │             │ ●  D2      Seen from below, the two sides
        D10  ● │             │ ●  D3      are swapped compared with the
         D9  ● │             │ ●  D4      usual top-view pinout drawings.
         D8  ● │             │ ●  D5
         D7  ● │             │ ●  D6
               └─────────────┘
```

If in doubt, compare with Seeed's official pinout.

## Signal details

| Link | ESP32-S3 peripheral | Settings | Logic level |
|---|---|---|---|
| LD2450 ↔ ESP32 | UART1, RX on GPIO5 (D4), TX on GPIO6 (D5) | 256 000 baud, 8N1 | 3.3 V |
| ESP32 → screen | SPI2 (FSPI): SCK GPIO7 (D8), MOSI GPIO9 (D10); CS GPIO1 (D0), DC GPIO2 (D1), RST GPIO4 (D3). Backlight tied to 3V3. | Up to 80 MHz | 3.3 V |
| ESP32 → amp | I2S1 TX: BCLK GPIO8 (D9), LRC/WS GPIO43 (D6), DOUT GPIO3 (D2) | 16 kHz – 44.1 kHz, 16-bit | 3.3 V |
| Knob → ESP32 | GPIO44 (D7), input with the internal pull-up | Pressed = low; the firmware tells a tap from a long press (1.5 s) | 3.3 V |
| Microphone → ESP32 | Built-in PDM mic on the Sense board: CLK GPIO42, DATA GPIO41, on I2S0 | 16 kHz | internal |
| MR60BHA2 → PC | Its own ESP32-C6 over Wi-Fi | — | — |

The ESP32-S3 console uses native USB, which leaves UART0's pins free: GPIO43 (D6) is the amp's LRC, GPIO44 (D7) the knob. No level shifters are needed: the LD2450 uses 3.3 V UART levels.

## Power budget

| Load | Average | Peak |
|---|---:|---:|
| XIAO ESP32S3 + camera + Wi-Fi | 300 mA | 450 mA |
| HLK-LD2450 | 120 mA | 200 mA |
| MR60BHA2 kit | 160 mA | 300 mA |
| 1.69" screen (from 3V3) | 60 mA | 90 mA |
| MAX98357A + speaker | 20 mA idle | 400 mA (loud sound) |
| **Total at 5 V** | **≈ 0.7 A** | **≈ 1.5 A** |

Pick a charger or power bank that delivers at least 2 A. Keep the speaker volume moderate in the firmware: a 1 W speaker can be overdriven by the amp at 5 V.

## Flashing the ESP32

Unplug the charger and plug the same cable into your computer: it goes straight to the XIAO. The two sources must never be connected at the same time, because the 5V pin is wired straight to the port. After the first flash, updates go over Wi-Fi (OTA).
