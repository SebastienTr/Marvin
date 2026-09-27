# Host software (planned)

The desktop side of Marvin: the robot's big brain. **Not written yet.** This file is the spec it will follow.

## Responsibilities

1. Receive the UDP frames from the robot (lidar, radar, microphone audio), the MJPEG camera stream and the MR60BHA2 vitals.
2. Align every stream on a single timeline.
3. Place every sensor in the device frame, using the extrinsics in [docs/architecture.md](../docs/architecture.md#coordinate-frame).
4. Show a live overlay in [Rerun](https://rerun.io): camera image, lidar points projected onto the image, top-down lidar map, radar targets with speed, and vital-sign time series.
5. Record sessions to disk for replay.
6. Turn the fused picture into **events**: someone arrived, sat down, has been still for an hour, breathes fast, left.
7. Give the robot its **behaviour and words**: choose an expression for the face, and answer spoken questions with a local LLM and text-to-speech streamed back to the speaker.

## Planned stack

Python 3.11+ with `rerun-sdk`, `numpy` and `opencv-python`; a local speech-to-text model, a local LLM and a small text-to-speech voice for the companion layer. A Rust port may follow once the pipeline is stable.
