#!/usr/bin/env bash
# Rebuild hardware/blender/marvin.blend from marvin.scad (needs OpenSCAD and Blender 4.2+ or the bpy wheel).
# Usage: hardware/scripts/build_blend.sh [render_dir]
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
src="$here/../cad/marvin.scad"
tmp="$(mktemp -d)"
for p in body plinth sled neck grommet foot ghost_mr60 ghost_ld2450 ghost_speaker; do
  openscad -q -D "PART=\"$p\"" -o "$tmp/$p.stl" "$src"
done
if command -v blender >/dev/null; then
  blender --background --python "$here/../blender/build_blend.py" -- "$tmp" "$here/../blender/marvin.blend" ${1:-}
else
  python3 "$here/../blender/build_blend.py" "$tmp" "$here/../blender/marvin.blend" ${1:-}
fi
rm -rf "$tmp"
