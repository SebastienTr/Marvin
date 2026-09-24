# Host software (planned)

The desktop side of SuperLens. **Not written yet.** This file is the spec it will follow.

## Responsibilities

1. Receive the UDP frames from the head, the MJPEG camera stream and the MR60BHA2 vitals.
2. Align every stream on a single timeline.
3. Place every sensor in the device frame, using the extrinsics in [docs/architecture.md](../docs/architecture.md#coordinate-frame).
4. Show a live overlay in [Rerun](https://rerun.io): camera image, lidar points projected onto the image, top-down lidar map, radar targets with speed, and vital-sign time series.
5. Record sessions to disk for replay.

## Planned stack

Python 3.11+ with `rerun-sdk`, `numpy` and `opencv-python`. A Rust port may follow once the pipeline is stable.
