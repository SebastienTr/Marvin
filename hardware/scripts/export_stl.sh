#!/usr/bin/env bash
# Export every printable part of Marvin (rev F), already oriented for the print bed.
# Usage: hardware/scripts/export_stl.sh          (needs OpenSCAD >= 2021.01 in PATH)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
src="$here/../cad/marvin.scad"
out="${OUT:-$here/../stl}"   # set OUT to write elsewhere
mkdir -p "$out"

parts=(
  "print_template:0_lidar_template"
  "print_body:1_body"
  "print_plinth:2_plinth"
  "print_sled:3_sensor_sled"
  "print_tpu:4_tpu_neck_grommets_feet"
)
for p in "${parts[@]}"; do
  part="${p%%:*}"; name="${p##*:}"
  echo "-> $name.stl"
  openscad -q -D "PART=\"$part\"" -o "$out/$name.stl" "$src"
done
echo "Done: $out"
