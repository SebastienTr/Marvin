#!/usr/bin/env bash
# Export every printable part of Fanou, already oriented for the print bed.
# Usage: hardware/scripts/export_stl.sh          (needs OpenSCAD >= 2021.01 in PATH)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
src="$here/../cad/fanou.scad"
out="$here/../stl"
mkdir -p "$out"

parts=(
  "print_template:0_lidar_template"
  "print_base:1_base_island"
  "print_bottom:2_bottom_plate"
  "print_shell:3_tower_shell"
  "print_spine:4_spine"
  "print_cap:5_gallery_cap"
)
for p in "${parts[@]}"; do
  part="${p%%:*}"; name="${p##*:}"
  echo "-> $name.stl"
  openscad -q -D "PART=\"$part\"" -o "$out/$name.stl" "$src"
done
echo "Done: $out"
