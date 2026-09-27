# Tools: OTA updates, recordings, calibration

Three things you need once the robot is assembled: flashing it without opening the shell, recording a session to work on it later, and telling the host how the lidar is mounted.

## Over-the-air firmware updates

The ESP32 builds (`xiao_esp32s3`, `xiao_esp32s3_sim`, `esp32s3`) and the D1 mini include [ArduinoOTA](../firmware/src/ota.h). Once running, the robot answers on mDNS as **`marvin-xxxxxx.local`**, the name `marvin-host` prints when the robot connects (the last 3 bytes of its Wi-Fi MAC address).

1. **Set a password.** In `firmware/include/secrets.h`:

   ```c
   #define OTA_PASSWORD "something-long"
   ```

   Without it, anyone on your network can flash the robot (the serial log says so at boot).

2. **First upload over USB**, as usual: `pio run -e xiao_esp32s3 -t upload`. OTA only works once a firmware with OTA is running.

3. **Every upload after that goes over Wi-Fi:**

   ```sh
   export MARVIN_OTA_PASSWORD='something-long'     # the same password; espota sends it
   pio run -e xiao_esp32s3_ota -t upload --upload-port marvin-a1b2c3.local
   ```

   The `xiao_esp32s3_ota` env is `xiao_esp32s3` with `upload_protocol = espota`. You can also give an IP address, or set `upload_port` in `platformio.ini` once for your robot.

What to expect:

- The update takes about 10 to 20 s for the ~0.9 MB image. The sensors stop streaming meanwhile; the host sees `OTA update started` in the log, then the link drops and comes back after the reboot.
- The flash has two 3.2 MB app slots (`default_8MB.csv`): the new image is written next to the running one and only used once it is complete and verified. A failed or interrupted upload leaves the old firmware running.
- There is no automatic rollback: if the new firmware crashes before Wi-Fi is up, you need USB again. Keep `ota::begin()` early in `setup()`.

Troubleshooting:

- **`.local` names**: macOS resolves them out of the box. Linux needs Avahi (`nss-mdns`), Windows needs Bonjour; otherwise use the IP shown in the serial log (`OTA: ready as ...`).
- **espota hangs at "Waiting for device..."**: the robot connects *back* to your computer over TCP. Allow incoming connections for Python in the macOS firewall (System Settings → Network → Firewall), or pin the port with `upload_flags = --host_port=47110` and open that one.
- **"Authentication failed"**: `MARVIN_OTA_PASSWORD` does not match `OTA_PASSWORD` in the firmware that is *running*.

## Recording and replaying sessions

`marvin-host` can record everything the robot sends and play it back later, with the viewer, the brain and the console behaving exactly as they did live. Use it to work on the brain without the robot, to report a bug, or to build test cases.

```sh
marvin-host run --record desk.mvrec             # live, and record (desk.mvrec.gz for gzip, about half the size)
marvin-host replay desk.mvrec                   # real time, with the viewer
marvin-host replay desk.mvrec --speed 4 --no-viewer
marvin-host replay desk.mvrec --fast --loop     # as fast as possible, over and over
marvin-host replay desk.mvrec --info            # duration, robot, firmware
```

A recording holds the raw datagrams, byte for byte, with their host arrival time, so nothing is lost and old recordings work with newer host code. The brain runs on the device clock carried in each frame, so a replay produces the same events at any speed. A replay never sends anything back to a robot. A recording cut short (crash, Ctrl-C, full disk) plays up to its last complete record. About 1 MB per minute with the D500, 5 MB with the D800 (uncompressed).

From Python (tests, notebooks):

```python
from marvin_host import record
from marvin_host.brain import Brain

brain = Brain()
record.replay("desk.mvrec", brain, speed=None)      # None = as fast as possible
print(list(brain.events))

record.write_simulated("sim.mvrec", seconds=60, model=2)   # a simulated session, in about a second
```

The file format is described at the top of [`record.py`](../host/marvin_host/record.py).

## Lidar calibration

The lidar's 0° mark points wherever it happened to be screwed in. The host needs to know which lidar angle is the robot's front (`yaw`), otherwise the lidar map is rotated relative to the radar and the camera.

**Automatic** (recommended): start the command, then walk slowly back and forth in front of the robot, alone, between 0.5 and 4 m, for 30 s. The LD2450 radar knows where you are relative to the robot; the tool matches that with the lidar and averages the angle between the two, ignoring the frames where it matched a chair.

```sh
marvin-host calibrate lidar --save
```

**Manual**: stand straight in front of the robot's face at about 1 m, with nothing else within 1.5 m, and keep still.

```sh
marvin-host calibrate lidar --manual --seconds 10 --save
```

Both print the yaw and its spread; without `--save` nothing is written. A spread above ~5° means the measurement was disturbed (several people, too fast) or the LD2450's `x_sign` is wrong (the radar is mounted upside down). Both also work from a recording: `marvin-host calibrate lidar --from walk.mvrec --save`.

The result goes to `~/.config/marvin/calibration.json` (`$MARVIN_CONFIG_DIR/calibration.json` if set), which every `marvin-host` command reads at start:

```json
{
  "version": 1,
  "lidar": {"yaw_deg": 37.2},
  "ld2450": {"x_sign": 1, "speed_sign": -1},
  "camera": {"pitch_deg": null},
  "updated": "2026-09-27T18:00:00+02:00"
}
```

You can edit it by hand. `ld2450.x_sign` flips the radar's left/right, `ld2450.speed_sign` the sign of its speeds for the brain; `camera.pitch_deg` is reserved.

To try the tool without the robot, simulate a lidar mounted at 37°: `marvin-host sim --lidar-yaw 37`, then `marvin-host calibrate lidar` in another terminal.
