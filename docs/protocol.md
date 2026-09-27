# Robot ↔ host protocol (v1)

The robot streams its sensors to the host over **UDP** on the local network. Each datagram carries one message: a 16-byte header, then a payload. All integers are little-endian.

Reference implementations: [`host/marvin_host/protocol.py`](../host/marvin_host/protocol.py) and [`firmware/src/protocol.h`](../firmware/src/protocol.h).

## Ports and link

| Side | UDP port |
|---|---:|
| Host listens on | 47100 |
| Robot listens on | 47101 |

1. The robot joins Wi-Fi and **broadcasts** a `HELLO` to port 47100 every 0.5 s.
2. The host answers each `HELLO` with a `HOST_ACK`, sent back to the address and port the `HELLO` came from.
3. From the first `HOST_ACK`, the robot **unicasts** everything to that host and sends a `HELLO` every 2 s as a heartbeat.
4. With no `HOST_ACK` for 6 s, the robot goes back to step 1.

No configuration is needed on either side: the host does not need to know the robot's address, and the robot finds whichever host is running.

## Header

| Offset | Size | Field |
|---:|---:|---|
| 0 | 2 | Magic, `"MV"` |
| 2 | 1 | Protocol version, `1` |
| 3 | 1 | Message type |
| 4 | 4 | Sequence number, per sender, wraps. Gaps count lost datagrams. |
| 8 | 8 | Sender clock, microseconds since boot |

## Messages

| Type | Name | Direction | Payload |
|---:|---|---|---|
| `0x01` | `HELLO` | robot → host | MAC address (6), board id (u8), flags (u8), Wi-Fi RSSI dBm (i8), uptime ms (u32), firmware version (u8 length + UTF-8) |
| `0x02` | `LIDAR` | robot → host | Lidar model (u8), then N raw 47-byte LDROBOT packets, unchanged (10 per datagram by default) |
| `0x03` | `LD2450` | robot → host | One raw 30-byte HLK-LD2450 target frame, unchanged |
| `0x04` | `LOG` | robot → host | UTF-8 text |
| `0x81` | `HOST_ACK` | host → robot | Host clock, microseconds (u64) |

Board ids: 1 = Wemos D1 mini (ESP8266), 2 = ESP32-S3 DevKitC, 3 = XIAO ESP32S3 Sense, 255 = host-side simulator. Lidar models: 1 = D500 (STL-19P), 2 = D800 (STL-27L). Flag bit 0 = the sensor data is simulated.

The sensor frames travel **raw**, CRC included, so the host validates them exactly as it would on a serial port, and a recording can be replayed through the same parsers.

## Sensor frames

**LDROBOT lidar packet (47 bytes):** `0x54`, `0x2C`, speed (u16, deg/s), start angle (u16, 0.01°), 12 × (distance u16 mm, intensity u8), end angle (u16, 0.01°), timestamp (u16, ms, wraps at 30 000), CRC-8 (polynomial `0x4D`) over the first 46 bytes. Angles are clockwise seen from above. A distance of 0 means no return.

**HLK-LD2450 frame (30 bytes):** `AA FF 03 00`, 3 targets × (x i16 mm, y i16 mm, speed i16 cm/s, distance resolution u16 mm), `55 CC`. x, y and speed use a sign bit: bit 15 set = positive. An all-zero target is an empty slot.

## Frames of reference

The host converts every point into the device frame (X right as seen facing the robot, Y backwards, Z up, origin on the desk under the body axis), with the extrinsics in [`host/marvin_host/frames.py`](../host/marvin_host/frames.py) and [architecture.md](architecture.md#coordinate-frame).

- Lidar: angle θ (after removing the calibration yaw) points to (−sin θ, −cos θ), at the lidar height. 0° is straight ahead of the face, 90° is the robot's right.
- LD2450: x is kept, y is along the boresight, tilted 10° up from the radar face at (0, −33.1, 16.4).

## Bandwidth

| Stream | Rate | Datagrams/s | Payload |
|---|---:|---:|---:|
| D500 lidar | 4 500 points/s | 38 | ≈ 18 kB/s |
| D800 lidar | 21 600 points/s | 180 | ≈ 85 kB/s |
| LD2450 | 10 frames/s | 10 | 0.5 kB/s |

Even the D800 uses under 1 Mbit/s, well within an ESP8266's Wi-Fi.

## Planned

Camera (MJPEG over HTTP), microphone audio, host → robot commands (face expression, sounds, speech) and clock synchronisation from `HOST_ACK` will come in a later version, with a version bump if the header changes.
