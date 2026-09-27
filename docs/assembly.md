# Assembly

> **Rev F status:** the body half (plinth, sensor sled, body, neck) is designed and printable; the steps below are exact for it. The head steps are the plan until the head CAD lands. A photo guide will follow the first build.

You need a small screwdriver, a wire stripper and, for two header strips, a soldering iron. The whole build takes an evening.

<p align="center"><img src="images/robot_section.png" alt="Section of the robot: upright shell, 24 GHz radar tilted 10° low in the body with the 60 GHz radar tilted 20° above it, screen vertical and camera tilted 20° in the head, lidar on top" width="760"></p>

How it goes together: the **plinth** carries the **sensor sled** (both radars, the lever connectors and the amp) and slides up into the **body**, which is held by four screws through its back wall. The **head** sits on the body through a **TPU gasket and four TPU grommets**, so no rigid part links the lidar motor to the heart-rate radar. Every wire between head and body passes through the hole in the neck.

The 24 GHz radar sits **below** the 60 GHz radar on purpose: the 60 GHz beam leaves the robot upwards, so nothing may stand in front of the upper part of that radar.

## Screws

| Where | Screw | Qty |
|---|---|---:|
| Plinth → sensor sled (from below) | M3 × 12 | 2 |
| Body back wall → sensor sled | M3 × 10, button head | 4 |
| Neck: grommets → head floor (from inside the body) | M3 × 14 + washer | 4 |
| Lidar → head | M2.5 × 10 | 3 |

All M3 screws thread-form into the printed pilot holes: no nuts.

## 0. Solder the headers

Solder the 2 × 7 header pins under the XIAO ESP32S3 Sense (long side of the pins pointing away from the camera board) and the 7-pin header on the MAX98357A amp. Skip this step if you bought them pre-soldered.

## 1. Check the lidar on the template

Lay the lidar on the printed template. Its three holes must fall inside the slots. If they don't, adjust `lidPairDY` / `lidSingleDY` in the CAD and please open an issue with your measurements.

## 2. Head (planned)

1. Drill a 6 mm hole in the smoked acrylic for the camera, file the corners round, peel the film, and clip the window into the face frame.
2. **Screen** into the face frame, glass against the acrylic, connector at the back.
3. **XIAO ESP32S3 Sense** into its 20° cradle, lens right behind the hole in the acrylic.
4. Face frame into the head shell; lidar cable up through the lidar ring; lidar on top with 3 × M2.5 × 10.

## 3. Neck

1. Push the four **TPU grommets** up through the deck of the empty body, flange underneath.
2. Lay the **TPU gasket** on the deck, over the grommet shafts.
3. Pass the head wires down through the neck hole, set the head on the gasket and screw it from inside the body: 4 × M3 × 14 with a washer, up through the grommets into the head floor. Stop as soon as the gasket is lightly squeezed.

## 4. Sensor sled

1. **HLK-LD2450**: antenna side forward, bottom edge on the two small lips at the front of the sled, 3 mm foam on the inner ribs behind it.
2. **MR60BHA2 kit** in its printed case: landscape, radar face forward, standing on the sloped ledge above the LD2450, 3 mm foam on the ribs behind it.
3. Stick the four **lever connectors** and the **amp** on the back plate.
4. Screw the sled onto the **plinth**: 2 × M3 × 12 from below.

## 5. Speaker

Slide the **speaker** between the two rails on the inside of the right wall, face against the dot grille, on a strip of foam tape.

## 6. Wire it

Follow the [wire-by-wire table](wiring.md#wire-by-wire-table) and tick each line. Tug gently on every wire in a lever connector: none should come out.

## 7. Close it

1. Pass the USB-C cable in through the notch at the back of the plinth and up through the neck, and plug the right-angle end into the XIAO.
2. Slide the plinth with the sled up into the body and fix it with 4 × M3 × 10 through the back wall.
3. Stick the four TPU (or silicone) feet into the pockets under the plinth.

## 8. First power-up

Plug the other end of the USB-C cable into a charger. You should see:

- the ESP32 LED light up,
- the MR60BHA2 kit's LED light up,
- the screen backlight turn on,
- the lidar start spinning.

If nothing happens, unplug and re-check lines 1 to 5 of the wiring table.

## Check on arrival

These dimensions are not guaranteed by the datasheets. Measure them before printing the big parts, and change the matching parameter in `marvin.scad` if needed:

| What | Designed for | Parameter |
|---|---|---|
| Lidar holes | the printed template (step 1) | `lidPairDY`, `lidSingleDY` |
| MR60BHA2 kit in its case | 54 × 35 × 22 mm | `kitW`, `kitH`, `kitD` |
| HLK-LD2450 board | 44 × 15 × 1.6 mm | `ldW`, `ldH`, `ldT` |
| Speaker | 30 × 20 mm face, at most 5 mm deep | `spkL`, `spkH` |
| 1.69" screen module | to be measured: board size, glass thickness, holes | head (next) |
| Camera lens position on your XIAO Sense | relative to the board edge | head (next) |
| LD2450 cable | should end in female Dupont plugs; if male, add a few male–female jumpers | — |
