# Contributing to Marvin

Thanks for your interest! The project is at an early stage, so every contribution counts, especially **build reports**.

## Ways to help

- **Build one and report back.** Photos, print settings, parts that did not fit, the actual sizes of your modules. Open an issue titled `Build report: <your name or handle>`.
- **Fix the docs.** Typos, unclear steps, missing warnings.
- **Improve the CAD.** Keep everything parametric in the OpenSCAD source under `hardware/cad/`.
- **Write the firmware or host software**, following the specs in `firmware/README.md` and `host/README.md`.

## Ground rules for CAD changes

1. Edit only the `.scad` source. STLs are generated from it.
2. Keep every printable part support-free in its `print_*` orientation.
3. Regenerate the files before committing:
   ```bash
   hardware/scripts/export_stl.sh
   hardware/scripts/render_previews.sh   # prefix with xvfb-run -a on headless Linux
   ```
4. If you change a module's position, update the extrinsics table in `docs/architecture.md`.
5. Record the change in `CHANGELOG.md` under *Unreleased*.

## Pull requests

- One topic per pull request.
- Describe what changed and why. Add a before/after render for mechanical changes.
- By contributing, you agree to license your work under the project's licences: CERN-OHL-P-2.0 for hardware, MIT for software, CC BY 4.0 for documentation.
