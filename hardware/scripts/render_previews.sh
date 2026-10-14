#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# OpenSCAD previews used in the docs (headless Linux: prefix with xvfb-run -a).
# The side section diagram (docs/images/robot_section.png) comes from hardware/scripts/section_diagram.py,
# the robot renders (docs/images/robot_*.png) from hardware/scripts/build_blend.sh.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
src="$here/../cad/marvin.scad"
img="$here/../../docs/images"
mkdir -p "$img"
render() { openscad -q --preview --imgsize="$4" --colorscheme=Tomorrow -D "C8_AMEND=${C8_AMEND:-false}" \
  -D "PART=\"$1\"" --camera="$2" -o "$img/$3.png" "$src"; }
render exploded 0,10,100,65,0,25,620 cad_exploded 1000,1100
render head_exploded 0,45,96,62,0,-125,480 cad_head_exploded 1100,850
echo "Done: $img"
