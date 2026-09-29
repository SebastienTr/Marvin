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
| `0x05` | `VITALS` | robot → host | MR60BHA2 readings: valid (u8), breath rate (u16, 0.01/min), heart rate (u16, 0.01/min), breathing wave (i16, ±32767), heartbeat wave (i16, ±32767), distance (u16, mm) |
| `0x06` | `AUDIO_IN` | robot → host | Microphone: sample index (u32), then 320 × i16 PCM (see [Audio messages](#audio-messages)) |
| `0x81` | `HOST_ACK` | host → robot | Host clock, microseconds (u64) |
| `0x82` | `FACE_STATE` | host → robot | Presence state for the face, 17 bytes, ≈ 10 Hz (see [Face messages](#face-messages)) |
| `0x83` | `FACE_EVENT` | host → robot | One brain event: event code (u8) |
| `0x84` | `AUDIO_OUT` | host → robot | Speaker: stream id (u16), sample index (u32), then 1–480 × i16 PCM |
| `0x85` | `AUDIO_CTRL` | host → robot | Audio command (u8), argument (u8) |
| `0x86` | `SOUND` | host → robot | Built-in sound id (u8) |

`VITALS` is sent by the simulators and by the real MR60BHA2 kit: the [MR60BHA2 bridge](../firmware/mr60_bridge/README.md) firmware runs on the kit's own ESP32-C6, reads the radar and sends its readings in this format (board id 4), so the host sees one stream.

Board ids: 1 = Wemos D1 mini (ESP8266), 2 = ESP32-S3 DevKitC, 3 = XIAO ESP32S3 Sense, 4 = MR60BHA2 kit (XIAO ESP32C6, vitals bridge), 5 = ESP32 DevKit (ESP32-WROOM-32), 255 = host-side simulator. Lidar models: 1 = D500 (STL-19P), 2 = D800 (STL-27L).

`HELLO` flags:

| Bit | Mask | Meaning |
|---:|---:|---|
| 0 | `0x01` | The sensor data is simulated |
| 1 | `0x02` | Has a camera: MJPEG at `http://<robot ip>:81/stream` (see [Camera](#camera)) |
| 2 | `0x04` | Has audio: speaker and microphone, `AUDIO_IN` / `AUDIO_OUT` / `AUDIO_CTRL` / `SOUND` |

Bits 1 and 2 were added to v1 without a version bump: older robots send 0 there, and older hosts only look at bit 0. A robot sets a capability bit only once that part has started (a camera that failed to initialise is not announced). Other bits are reserved and sent as 0.

The sensor frames travel **raw**, CRC included, so the host validates them exactly as it would on a serial port, and a recording can be replayed through the same parsers.

## Face messages

The host sends the robot's face what its brain knows (`host/marvin_host/link.py`), only to robots whose `HELLO` board id has the screen (2 and 3), at the address the `HELLO` came from. They were added to v1 without a version bump: a robot that does not know them ignores them. Both are optional for the robot: with no `FACE_STATE` for 5 s, the face assumes nobody is there and falls asleep on its own.

**`FACE_STATE` (0x82, 17 bytes), about 10 times a second:**

| Offset | Size | Field |
|---:|---:|---|
| 0 | 1 | Flags: bit 0 present, bit 1 seated, bit 2 head valid, bit 3 position valid, bit 4 distance valid, bit 5 heart rate valid |
| 1 | 6 | Head x, y, z (3 × i16, mm, device frame) |
| 7 | 6 | Nearest person's position x, y, z (3 × i16, mm, device frame) |
| 13 | 2 | Horizontal distance (u16, mm) |
| 15 | 2 | Heart rate (u16, 0.01 per minute) |

Fields without their valid bit are sent as 0. Coordinates are rounded to the millimetre and clamped to ±32 767.

**`FACE_EVENT` (0x83, 1 byte), sent as soon as the brain emits the event.** One table for both sides (`protocol.FACE_EVENT_CODES` in Python, `face::Event` in `firmware/src/face/face.h`); the robot ignores codes it does not know, so new events can be added.

| Code | Event | | Code | Event |
|---:|---|---|---:|---|
| 1 | `arrived` | | 5 | `stood_up` |
| 2 | `left` | | 6 | `still_long` |
| 3 | `approached` | | 7 | `vitals_acquired` |
| 4 | `sat_down` | | 8 | `vitals_lost` |

Events are not repeated: a lost `FACE_EVENT` only loses that reaction, and the next `FACE_STATE` keeps the face consistent with the brain.

## Audio messages

Audio travels in both directions as **16 kHz mono signed 16-bit little-endian PCM** (the host contract in [`host/marvin_host/audio.py`](../host/marvin_host/audio.py)), in the same UDP envelope as everything else, to and from the same ports. Like the face messages, they were added to v1 without a version bump; a robot without audio ignores them, and only robots whose `HELLO` has flag bit 2 are sent them. Sound design, volume cap and latency budget: [audio.md](audio.md). Implementations: [`firmware/src/audio/`](../firmware/src/audio/) and [`host/marvin_host/robot_audio.py`](../host/marvin_host/robot_audio.py).

**Sample indexes.** Each audio stream counts its samples from 0 (u32, wrapping after 74 hours). Every datagram carries the index of its first sample, so the receiver knows exactly where it goes: a datagram ahead of the expected index means samples were lost (filled with silence, up to a limit, so the rest stays in time), one behind means it is late or duplicated (dropped, or trimmed if it overlaps). This is independent of the header sequence number, which counts all messages of the sender.

**`AUDIO_IN` (0x06), robot → host, 644 bytes, every 20 ms while the microphone is on:**

| Offset | Size | Field |
|---:|---:|---|
| 0 | 4 | Sample index of the first sample (u32). Restarts at 0 each time the microphone starts |
| 4 | 640 | 320 samples (i16) |

The header clock is the robot time of the first sample (to within a DMA block). The microphone is **off by default**: the robot streams only after an `AUDIO_CTRL` *mic start*, only to the linked host, and stops on *mic stop* or when the link drops (no `HOST_ACK` for 6 s). The host repeats *mic start* every second while it listens, so a lost command or a robot reboot does not end the stream. A host sees a restarted stream as an index that jumps back while the header clock moves forward.

**`AUDIO_OUT` (0x84), host → robot, at most 982 bytes with the header:**

| Offset | Size | Field |
|---:|---:|---|
| 0 | 2 | Stream id (u16). A new id makes the robot drop whatever it has queued and start the new stream |
| 2 | 4 | Sample index of the first sample in this stream (u32) |
| 6 | 2 N | N samples (i16), 1 ≤ N ≤ 480 (30 ms). The host sends 320 (20 ms) |

The robot keeps a jitter buffer: it starts playing once 100 ms are queued (or when nothing new has arrived for 60 ms, for a short sound), then plays at its I2S clock. The host paces the stream at real time plus a 150 ms lead (the first 150 ms go out at once). After a pause, the host starts a new stream id.

**`AUDIO_CTRL` (0x85), host → robot, 2 bytes:** command (u8), argument (u8, 0 when unused). Unknown commands are ignored.

| Command | Name | Argument | Effect |
|---:|---|---|---|
| 1 | `MIC_START` | – | Start streaming `AUDIO_IN` (idempotent; repeat every second while listening) |
| 2 | `MIC_STOP` | – | Stop streaming `AUDIO_IN`; the PDM clock stops |
| 3 | `PLAY_STOP` | – | Drop the queued `AUDIO_OUT` and the sound being played; datagrams of the stopped stream still in flight are ignored until the host starts another stream id |
| 4 | `VOLUME` | 0–100 | Speaker volume (default 60): a gain of cap × (volume / 100)², the cap protecting the 1 W speaker |
| 5 | `MIC_GAIN` | 0–36 | Microphone gain in dB (default 12) |

**`SOUND` (0x86), host → robot, 1 byte:** play a built-in earcon, generated on the robot, mixed over any stream. Unknown ids are ignored.

| Id | Name | Length | | Id | Name | Length |
|---:|---|---:|---|---:|---|---:|
| 1 | `chirp` | 140 ms | | 4 | `done` | 200 ms |
| 2 | `beep` | 120 ms | | 5 | `error` | 360 ms |
| 3 | `wake` | 200 ms | | 6 | `hello` | 430 ms |

The largest host → robot datagram is `AUDIO_OUT`: robots with audio read datagrams up to 1 024 bytes, others 512.

## Camera

The camera does not use UDP: the robot serves it over **HTTP on TCP port 81**, and the host builds the URL from the address the `HELLO` came from (`protocol.camera_url(ip)`), when `HELLO` flag bit 1 is set.

| URL | Response |
|---|---|
| `http://<ip>:81/stream` | `multipart/x-mixed-replace; boundary=marvinframe`: one JPEG per part, each part with `Content-Type: image/jpeg`, `Content-Length` and `X-Marvin-Time-Us` |
| `http://<ip>:81/capture` | One JPEG (`Content-Length`, `X-Marvin-Time-Us`) |
| `http://<ip>:81/` | An HTML page showing the stream |

Each part is `--marvinframe` CRLF, its headers, an empty line, the JPEG, CRLF. `X-Marvin-Time-Us` is the robot clock when the frame was captured, the same clock as the UDP headers' `t_us`, so frames line up with the lidar and radar data. VGA (640 × 480), at most 10 frames/s by default. Up to 4 clients at once share each frame; a fifth gets `503`. Responses carry `Access-Control-Allow-Origin: *`. Host client: [`host/marvin_host/camera_stream.py`](../host/marvin_host/camera_stream.py).

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
| Vitals | 10 messages/s | 10 | 0.3 kB/s |
| Face state (host → robot) | 10 messages/s | 10 | 0.3 kB/s |
| Microphone, `AUDIO_IN` (while listening) | 16 000 samples/s | 50 | 32 kB/s (33 kB/s with headers) |
| Speaker, `AUDIO_OUT` (while speaking) | 16 000 samples/s | 50 | 32 kB/s (33 kB/s with headers) |
| Camera, MJPEG over TCP (while watched) | VGA, 10 frames/s | – | ≈ 250–400 kB/s |

Even the D800 uses under 1 Mbit/s, well within an ESP8266's Wi-Fi. On the robot, the D800, both audio directions and the camera add up to about 4 Mbit/s: comfortable on a good 2.4 GHz link, and the camera (the largest part) only streams while someone watches.

## Planned

More host → robot commands and clock synchronisation from `HOST_ACK` will come in a later version, with a version bump if the header changes.
