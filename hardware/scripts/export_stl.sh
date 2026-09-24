#!/usr/bin/env bash
# Export every printable part of SuperLens, already oriented for the print bed.
# Usage: hardware/scripts/export_stl.sh          (needs OpenSCAD >= 2021.01 in PATH)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
src="$here/../cad/superlens.scad"
out="$here/../stl"
mkdir -p "$out"

parts=(
  "print_template:0_lidar_template"
  "print_head:1_head"
  "print_cover:2_back_cover"
  "print_grip:3_grip"
  "print_dock:4_stand"
)
for p in "${parts[@]}"; do
  part="${p%%:*}"; name="${p##*:}"
  echo "-> $name.stl"
  openscad -q -D "PART=\"$part\"" -o "$out/$name.stl" "$src"
done
echo "Done: $out"
