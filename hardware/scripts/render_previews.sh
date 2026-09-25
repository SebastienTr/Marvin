#!/usr/bin/env bash
# Quick OpenSCAD previews (headless Linux: prefix with xvfb-run -a).
# The nicer renders in docs/images come from hardware/blender/build_blend.py.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
src="$here/../cad/fanou.scad"
img="$here/../../docs/images"
mkdir -p "$img"
render() { openscad -q --preview --viewall --autocenter --imgsize=1100,1300 --colorscheme=Tomorrow \
  -D "PART=\"$1\"" --camera="$2" -o "$img/$3.png" "$src"; }
render exploded 0,0,0,70,0,330,0 cad_exploded
render section  0,0,0,80,0,270,0 cad_section
echo "Done: $img"
