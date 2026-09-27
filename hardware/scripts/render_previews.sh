#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# OpenSCAD previews used in the docs (headless Linux: prefix with xvfb-run -a).
# The side section diagram (docs/images/robot_section.png) comes from hardware/scripts/section_diagram.py.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
src="$here/../cad/marvin.scad"
img="$here/../../docs/images"
mkdir -p "$img"
render() { openscad -q --preview --imgsize="$4" --colorscheme=Tomorrow \
  -D "PART=\"$1\"" --camera="$2" -o "$img/$3.png" "$src"; }
render exploded 0,0,55,65,0,20,440 cad_exploded 1000,1100
echo "Done: $img"
