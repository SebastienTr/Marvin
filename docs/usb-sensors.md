# USB sensors: the lidar (or the radar) on the computer, no ESP32

**A lidar on a 2 € USB-serial adapter, plugged into the computer that runs Marvin's host: its scan is live in the app within seconds of `./marvin up`.** No board to flash, no Wi-Fi: a small Python process, the *USB sensor bridge*, reads the adapter and sends the sensor's frames to the host exactly as a robot board does (protocol v1, [protocol.md](protocol.md)). The host sees one more device, board id 6, with real (not simulated) data.

Marvin itself has no lidar since rev F (14 October 2026); the bridge keeps reading one, for the bench and for other projects. The same adapter can later carry an HLK-LD2450 radar instead: the bridge finds out which sensor is there.

Code: [`host/marvin_host/usb_sensors.py`](../host/marvin_host/usb_sensors.py) (the bridge) and [`host/marvin_host/sensor_framing.py`](../host/marvin_host/sensor_framing.py) (the Python mirror of the firmware's framers, [`firmware/src/sensor_framing.cpp`](../firmware/src/sensor_framing.cpp)).

## Parts

| Part | What | Notes |
|---|---|---|
| Lidar | LDROBOT **D800 kit (STL-27L)**, or a D500 (STL-19P / LD19) | UART 3.3 V, 921600 baud (D500: 230400), 8N1 |
| Adapter | A USB-TTL adapter with a **+5V** pin: CP2102 (e.g. the HW-598A board), CH340, FT232 | Its +5V comes from USB: enough for the lidar's motor (about 300 mA at start) |
| Wires | 4 female-to-male jumpers and a breadboard, or the lidar's ZH1.5 cable cut and crimped | |

## Wiring: STL-27L to a CP2102 adapter

The lidar's connector is a 4-pin ZH1.5. **Pin order, from the STL-27L manual: 1 TX, 2 PWM, 3 GND, 4 P5V.**

| Lidar pin | Signal | Cable colour (verified on one D800 kit) | Adapter pin |
|---:|---|---|---|
| 1 | TX (lidar → computer) | **black** | **RXD** |
| 2 | PWM (motor speed) | **white** | **GND** (with the lidar's GND) |
| 3 | GND | **green** | **GND** |
| 4 | P5V | **red** | **+5V** |

- **The colours are not the usual ones** (black is a signal here, not ground), and they vary from batch to batch. Before plugging anything in, check with a multimeter: P5V and GND are found on the lidar's own board (continuity to the motor driver's supply and to the shield), or measure the red and green wires with the lidar powered from a bench supply. When in doubt, go by the pin numbers on the connector.
- **PWM goes to ground.** The STL-27L manual: when the motor's speed is not controlled from outside, PWM must be grounded, else the speed is not regulated. The adapter has one GND pin: put the lidar's GND, its PWM and a jumper to the adapter's GND in the same breadboard row.
- The adapter's **TXD stays unconnected** (the lidar needs no commands). Leave its 3V3 pin unconnected too.
- The CP2102's RXD accepts the lidar's 3.3 V TX directly.

For an **HLK-LD2450** on the same adapter: radar TX → adapter RXD, radar RX → adapter TXD (optional: the bridge then sets multi-target tracking at start, as the firmware does), radar GND → GND, radar 5V → +5V. 256000 baud.

## Use

```bash
./marvin up            # finds the adapter, starts the bridge with the host
./marvin status        # usb:  bridge running (pid 1234): lidar STL-27L on /dev/cu.usbserial-0001 @921600 ...
./marvin logs          # the host's log and the bridge's, side by side
```

`./marvin up` (and `restart`) look for USB-serial adapters (`/dev/cu.usbserial-*`, `/dev/cu.SLAB_USBtoUART*`, `/dev/cu.wchusbserial*` on macOS, `/dev/ttyUSB*` on Linux; never a Bluetooth port). When one is plugged in, the bridge starts in the background while the host starts, listens to each adapter for about a second at 921600 baud (D800 / STL-27L), 230400 (D500 / STL-19P) and 256000 (LD2450), and keeps the first speed that carries valid frames (header and CRC). It says what it found once the app is up:

```
Marvin's app: http://localhost:8765/
  USB sensors: lidar STL-27L on /dev/cu.usbserial-0001 @921600 as marvin-2d81ee, sending to the host, 21600 pts/s
```

If no sensor answers, the bridge stops and `up` says so (wiring and power: see above). `./marvin down` stops it with the host. The first time, `up` adds pyserial to `host/.venv`.

In the app, **Marvin > Robot**: the device **USB sensor bridge** ("Real sensors over USB", on this computer) with its datagram rate and lidar scans per second, and the **Lidar** panel with the scan, the robot facing up. The bar at the top says *Sensors live · no presence sensor* while only the lidar is there: the lidar draws the room, the presence (arrived, seated, breaks) needs a radar.

Its log, every 5 seconds (`./marvin logs`; the host's log has the same counters, sent as the robot's `LOG`):

```
usb-sensors: lidar STL-27L on /dev/cu.usbserial-0001 @921600, 21600 pts/s
```

### By hand

```bash
./marvin usb-sensors --detect      # what is plugged in, and exit (1: nothing)
./marvin usb-sensors -v            # the bridge in the foreground (it replaces the one `up` started)
./marvin usb-sensors --serial /dev/cu.usbserial-0001 --no-radar
```

Or without the launcher: `cd host && .venv/bin/python -m marvin_host.usb_sensors` (also `marvin-host usb-sensors`), with `--host` and `--port` for a host on another computer.

Settings: `MARVIN_USB_SENSORS=off` (never start the bridge), `MARVIN_USB_PORTS=/dev/cu.usbserial-0001` (only these ports or globs, comma-separated).

## How it behaves

- **A robot board, as far as the host knows.** `HELLO` with board id 6 (`USB sensor bridge (computer)`), no simulated flag and no capability, RSSI 0 (no Wi-Fi), firmware `usb-<version>`, and a stable, locally administered MAC address made from the adapter's USB serial number, so the same lidar is the same device after a replug. It sends from UDP port 47120 + (the MAC's last byte mod 64) when free, so a restarted bridge is the same device too. `HELLO` every 0.5 s until the host answers `HOST_ACK`, then every 2 s; no `HOST_ACK` for 6 s: searching again (the firmware's `link_step`).
- **The lidar's packets as the firmware sends them**: framed with the same resynchronising framer and CRC-8, 10 packets per `LIDAR` datagram (model byte first), a partial batch after 20 ms, each datagram stamped with the time its first packet was read. LD2450 frames one per `LD2450` datagram. Nothing is sent before the host answers.
- **Unplugged and plugged back in**: the bridge notices the port going away, waits for it, and probes it again when it is back. A lidar that stops sending for 30 s (power, a loose wire) is listened to again every 10 s. A port where no sensor ever answered is tried twice more (10 and 30 s later), then left alone until it is plugged in again: it may be an ESP32 board's USB port, and the bridge does not want to keep opening it (it opens ports exclusively, with DTR and RTS held low, which keeps most boards from resetting).
- **Alongside a robot.** The bridge and a robot are two devices in Marvin > Robot. Real sensors beat simulated ones: while a device with real data has sent a scan in the last 2 s, a simulated device's scans are not drawn (`SensorScene`), and while a real radar has sent targets in the last 2 s, a simulated device's targets and vital signs do not reach the brain (`PresenceFeed`). Between two real lidars, the newest scan is drawn. The brain never uses the lidar.

## Troubleshooting

| What you see | What to check |
|---|---|
| `./marvin status`: `usb: no USB serial adapter plugged in` | The cable carries data (some USB cables only charge); the adapter's driver (CH340 may need the WCH driver). `ls /dev/cu.*` |
| `up`: *no lidar or radar answered* | The lidar spins? (no spin: P5V or GND). It spins but nothing: TX on RXD, not TXD; PWM grounded; the colours (see above). |
| Linux: *no permission to open /dev/ttyUSB0* | `sudo usermod -aG dialout $USER`, log out and in |
| CRC errors in the log | A long or loose TX wire; keep the wires short |
| The port is busy | A serial monitor (PlatformIO, `screen`) has it: close it. `./marvin usb-sensors` while the bridge from `up` runs replaces it |
