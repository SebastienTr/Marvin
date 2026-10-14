#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Export every printable part of Marvin (rev F), already oriented for the print bed, and the
# face window's outline (DXF, for a laser cutter or a print shop).
# Usage: hardware/scripts/export_stl.sh          (needs OpenSCAD >= 2021.01 in PATH)
#        C8_AMEND=true hardware/scripts/export_stl.sh   exports the head of the proposed C8 amendment
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
src="$here/../cad/marvin.scad"
out="${OUT:-$here/../stl}"   # set OUT to write elsewhere
dxf="${OUT_DXF:-$here/../dxf}"
mkdir -p "$out" "$dxf"
defs=(-D "C8_AMEND=${C8_AMEND:-false}")

parts=(
  "print_body:1_body"
  "print_plinth:2_plinth"
  "print_sled:3_sensor_sled"
  "print_tpu:4_tpu_neck_grommets_feet"
  "print_head_shell:5_head_shell"
  "print_head_cap:6_head_cap"
  "print_face_frame:7_face_frame"
  "print_knob:8_knob"
  "print_switch_bracket:9_switch_bracket"
  "print_window_jig:10_window_template"
)
for p in "${parts[@]}"; do
  part="${p%%:*}"; name="${p##*:}"
  ( openscad -q "${defs[@]}" -D "PART=\"$part\"" -o "$out/$name.stl" "$src" && echo "-> $name.stl" ) &
  while [ "$(jobs -r | wc -l)" -ge "${JOBS:-4}" ]; do sleep 0.2; done
done
wait
openscad -q "${defs[@]}" -D 'PART="window_2d"' -o "$dxf/window.dxf" "$src" && echo "-> window.dxf"
echo "Done: $out, $dxf"
