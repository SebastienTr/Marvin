#!/usr/bin/env bash
# Render the preview images used in the docs (needs OpenSCAD; on a headless Linux box prefix with xvfb-run -a).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
src="$here/../cad/superlens.scad"
img="$here/../../docs/images"
mkdir -p "$img"
render() { openscad -q --preview --viewall --autocenter --imgsize=1100,1300 --colorscheme=Tomorrow \
  -D "PART=\"$1\"" --camera="$2" -o "$img/$3.png" "$src"; }
render assembly 0,0,0,62,0,35,0  render_front
render assembly 0,0,0,62,0,210,0 render_back
render exploded 0,0,0,65,0,140,0 render_exploded
echo "Done: $img"
