# Firmware

Firmware for the Marvin robot's XIAO ESP32S3 Sense, built with PlatformIO.

**Status: prototype, real sensors ready for the first power-on.** The Wi-Fi link and the [UDP protocol](../docs/protocol.md) work. The robot build reads the **real lidar and HLK-LD2450** over its UARTs; the other builds run **simulated sensors** (a D500 or D800 lidar, an LD2450 and the MR60BHA2 vital signs, seeing a small home office with a person walking around and sitting down, byte-exact to the real frames), on a Wemos D1 mini (ESP8266) as well as on the ESP32-S3 boards, so the whole chain can be tested without the robot. The screen and the audio come next.

## Build environments

| Env | Board | Sensors |
|---|---|---|
| `xiao_esp32s3` | XIAO ESP32S3 Sense, **the robot** | **Real**: lidar on UART0, LD2450 on UART1 (`MARVIN_SENSORS=REAL`, `MARVIN_LIDAR_MODEL=2` = D800) |
| `xiao_esp32s3_sim` | XIAO ESP32S3 Sense | Simulated |
| `esp32s3` | ESP32-S3 DevKitC-1 (bench) | Simulated |
| `d1_mini` | Wemos D1 mini (ESP8266), default | Simulated (real sensors need an ESP32: the build stops with an error) |
| `native` | Your computer | Unit tests of the portable code: `pio test -e native` |

Build flags: `MARVIN_SENSORS=SIM|REAL`, `MARVIN_LIDAR_MODEL=1|2` for the real lidar (1 = D500 at 230 400 baud, 2 = D800 at 921 600 baud), `MARVIN_SIM_LIDAR_MODEL=1|2` for the simulated one.

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

## Tests

```bash
pio test -e native
```

Runs the Unity tests in `test/` on your computer: the lidar and LD2450 stream framers against a real LD19 packet, the LD2450 datasheet frames, garbage, split and corrupted streams, round trips through the simulator, and the LD2450 command bytes.

## Source layout

| File | Role |
|---|---|
| `src/main.cpp` | Wi-Fi, host discovery (`HELLO` / `HOST_ACK`), host message dispatch, streaming loop, status LED, sensor source selection |
| `src/protocol.h` | UDP envelope, mirrors `host/marvin_host/protocol.py` |
| `src/sensor_framing.cpp` | Portable stream framers: LDROBOT lidar packets (CRC-8), LD2450 target and ACK frames, LD2450 command builders |
| `src/sensors_real.cpp` | ESP32 only: the lidar and LD2450 UARTs, LD2450 boot configuration, counters |
| `src/sensors_sim.cpp` | Simulated lidar packets, LD2450 frames and MR60BHA2 vital signs |
| `src/scene_data.h` | The simulated room and person, generated from `host/marvin_host/scene.py` |
| `test/` | Unit tests (`pio test -e native`) |

## Responsibilities (full firmware)

1. Read the **lidar** on UART0 (RX = GPIO44 / D7): 921 600 baud for the D800 (STL-27L), 230 400 baud for the D500 (STL-19P). Validate each 47-byte LDROBOT packet (header `0x54`, CRC8).
2. Read the **HLK-LD2450** on UART1 (RX = GPIO5 / D4, TX = GPIO6 / D5, 256 000 baud), switch it to multi-target mode at boot and parse its target frames.
3. Stamp every packet with a monotonic microsecond clock, synchronised to the host.
4. Send lidar and radar packets to the host as **UDP** datagrams, in a small versioned binary envelope.
5. Serve the camera as an **MJPEG** stream over HTTP.
6. Drive the **1.69" ST7789 screen** (SPI), the robot's face:
   - **eyes** by default: blinking, looking towards the nearest person tracked by the LD2450, sleepy when nobody is around, and any expression the host sends;
   - a top-down lidar **mini-map** with radar targets, and a status page (Wi-Fi, host link), on request.
7. **Audio in**: read the built-in PDM microphone (I2S0, 16 kHz) and stream it on request, or detect simple sound events locally.
8. **Audio out**: play chirps, beeps and speech streamed from the host through the MAX98357A (I2S1), with a volume cap.
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
