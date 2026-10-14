#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Rebuild hardware/blender/marvin.blend from marvin.scad (needs OpenSCAD and Blender 4.2+ or the bpy wheel).
# Usage: hardware/scripts/build_blend.sh [render_dir]
#   with a render_dir it also renders robot_threequarter, robot_front, robot_side and robot_head_exploded
#   (VIEWS=a,b to pick some, SAMPLES=n for quality, BLENDER_PY=python-with-bpy to use the wheel,
#   C8_AMEND=true for the head of the proposed C8 amendment).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
src="$here/../cad/marvin.scad"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
for p in body plinth sled neck grommet foot ghost_mr60 ghost_ld2450 ghost_speaker \
         head_shell head_cap face_frame knob switch_bracket window ghost_lcd ghost_xiao ghost_usb ghost_tact; do
  openscad -q -D "C8_AMEND=${C8_AMEND:-false}" -D "PART=\"$p\"" -o "$tmp/$p.stl" "$src" &
  while [ "$(jobs -r | wc -l)" -ge "${JOBS:-4}" ]; do sleep 0.2; done
done
wait
openscad -q -D "C8_AMEND=${C8_AMEND:-false}" -D 'PART="check_values"' -o "$tmp/values.echo" "$src"
if [ -n "${BLENDER_PY:-}" ]; then
  "$BLENDER_PY" "$here/../blender/build_blend.py" "$tmp" "$here/../blender/marvin.blend" ${1:-}
elif command -v blender >/dev/null; then
  blender --background --python "$here/../blender/build_blend.py" -- "$tmp" "$here/../blender/marvin.blend" ${1:-}
else
  python3 "$here/../blender/build_blend.py" "$tmp" "$here/../blender/marvin.blend" ${1:-}
fi
