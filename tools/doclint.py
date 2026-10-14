#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Doc lint: the docs say what the code does (Marvin v1 contract C9, frozen 2026-10-14).

Rules (each one a check with an id; a failure prints file:line, the rule id and what to fix):

  no-lidar-claim   No robot-facing text claims Marvin has a lidar: README.md, README.fr.md, docs/*.md (except
                   the history and the parts about the bench, the USB sensor bridge and the room scanner), the
                   LLM persona (host-java/.../domain/conversation/Persona.java) and the app's strings. Rev F has
                   no lidar: two radars (HLK-LD2450, MR60BHA2), a camera, a microphone, a screen, a speaker, a
                   knob.
  pin-tables       Every pin table in the docs (docs/wiring.md, docs/xiao-bench.md, firmware/README.md) matches
                   the -DMARVIN_*_PIN / -DMARVIN_BUTTON_* build flags of its environment in
                   firmware/platformio.ini.
  env-table        The table of PlatformIO environments in the docs (firmware/README.md, docs/tools.md) lists
                   exactly the [env:*] sections of firmware/platformio.ini.
  protocol-table   The message types, HELLO flags, button ids and actions, and sound ids in docs/protocol.md
                   match the constants of ProtocolV1.java / Button.java (and therefore protocol.h and
                   protocol.py, which the protocol vectors keep in lockstep).
  relative-links   Every relative link and image in *.md resolves to a file (and an anchor, when it has one).
  readme-sync      README.md and README.fr.md have the same sections: same number of headings at each level,
                   same images, same links.

Usage: python tools/doclint.py [--rule ID ...] [--root DIR]; exit status 1 when a rule fails, 2 on bad usage.

A finding that is deliberate is waived on its line, or the line above, with `<!-- doclint: allow RULE -->`
(`// doclint: allow RULE` in code), naming the rule; use it sparingly, a reviewer reads each one.

How each rule decides (the details a doc writer needs):

  no-lidar-claim   In the Markdown docs, every sentence that names a lidar must say it is not Marvin's: a
                   negation ("no lidar", "without the lidar", "sans lidar", "n'en a plus"), or a context that is
                   not the robot (the bench, the USB sensor bridge, the simulator, the room scanner, rev A-E, the
                   wire format: `LIDAR`, LDROBOT, D500/D800). Whole files about the bench and the USB bridge,
                   and sections whose heading is about history, the bench, USB, the simulator, calibration or the
                   sensor frames, are skipped. A "[x] Rev ..." roadmap line is history. The persona must only
                   ever deny a lidar. In the app (adapter-web/src/main/resources/app), a lidar panel is fine (it
                   shows a USB or bench lidar); only a sentence that gives the robot a lidar fails ("a lidar on
                   the robot", "Marvin's lidar", "360-degree lidar").
  pin-tables       A pin table is a Markdown table with a GPIO column, GPIOn in its cells, or XIAO pins (D0-D10,
                   mapped to their GPIO). Its environment: an env named in backticks in the section heading
                   (`esp32dev`), else the file's (docs/wiring.md and firmware/README.md: xiao_esp32s3,
                   docs/xiao-bench.md: xiao_bench, docs/looks-bench.md: esp32dev_looks). The expected pins: the
                   env's -D build flags (MARVIN_*_PIN, MARVIN_BUTTON_*, MARVIN_OLED_SDA/SCL, MARVIN_STATUS_RGB*),
                   and for the XIAO envs the fixed pins of the source (screen: face_app.cpp, amp and mic:
                   audio_app.cpp, LD2450: sensors_real.cpp). A row naming a part must show that part's GPIO and
                   no other; a row marked spare/free must not hold a used pin; every flag pin >= 0 must be in a
                   row of the env's doc.
  env-table        The "Env" table of firmware/README.md lists every [env:*] (an env that `extends` a listed
                   one may instead be named anywhere in the file, as the *_ota twins are), and nothing else. In
                   every doc, `pio ... -e NAME` and "the `NAME` env" must name an existing env (the
                   mr60_bridge's own platformio.ini counts for its README).
  protocol-table   docs/protocol.md tables: Messages (code, name, direction) = MessageType.java; HELLO flags
                   (bit, mask, meaning) = ProtocolV1.FLAG_*; UDP ports = HOST_PORT / DEVICE_PORT; the BUTTON
                   payload's "1 = Talk, 2 = Mute" and "1 = press, 2 = long press" = Button.java, with
                   BUTTON_LONG_PRESS_MS in the section; built-in sounds (id, name, length) = HostMessages.SOUNDS
                   and SOUND_DURATIONS_MS.
  relative-links   Every tracked *.md (not private/): Markdown links and images, reference definitions, and
                   <img src>/<a href> in them; GitHub's heading anchors (and <a id|name>) for #fragments.
  readme-sync      Heading counts per level, the list of images, the list of relative link targets (without
                   their #fragment, since headings are translated) and of external links.
"""

from __future__ import annotations

import argparse
import configparser
import re
import subprocess
import sys
import urllib.parse
from dataclasses import dataclass
from pathlib import Path

RULES = ("no-lidar-claim", "pin-tables", "env-table", "protocol-table", "relative-links", "readme-sync")

ROBOT_DOMAIN = "host-java/marvin-domain/src/main/java/marvin/host/domain/robot"
PERSONA = "host-java/marvin-domain/src/main/java/marvin/host/domain/conversation/Persona.java"
APP_DIR = "host-java/marvin-adapter-web/src/main/resources/app"
PLATFORMIO = "firmware/platformio.ini"


@dataclass(order=True, frozen=True)
class Finding:
    path: str
    line: int
    rule: str
    message: str

    def __str__(self) -> str:
        return f"{self.path}:{self.line}: [{self.rule}] {self.message}"


# ------------------------------------------------------------------------------------------- Markdown


@dataclass
class Heading:
    line: int
    level: int
    text: str


@dataclass
class Table:
    line: int                      # the header line
    header: list[str]
    rows: list[tuple[int, list[str]]]
    heading: str                   # the closest heading above it


@dataclass
class Doc:
    path: Path
    rel: str
    lines: list[str]
    code: list[bool]               # line i (0-based) is inside a fenced code block
    headings: list[Heading]

    def heading_at(self, lineno: int) -> list[Heading]:
        """The chain of headings (outermost first) that a 1-based line sits under."""
        chain: list[Heading] = []
        for h in self.headings:
            if h.line > lineno:
                break
            while chain and chain[-1].level >= h.level:
                chain.pop()
            chain.append(h)
        return chain


_FENCE = re.compile(r"^\s{0,3}(```+|~~~+)")
_HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")


def read_doc(root: Path, rel: str) -> Doc:
    path = root / rel
    lines = path.read_text(encoding="utf-8").splitlines()
    code, headings, fence = [], [], None
    for i, line in enumerate(lines):
        m = _FENCE.match(line)
        if fence is None and m:
            fence = m.group(1)[0]
            code.append(True)
            continue
        if fence is not None:
            code.append(True)
            if m and m.group(1)[0] == fence:
                fence = None
            continue
        code.append(False)
        h = _HEADING.match(line)
        if h:
            headings.append(Heading(i + 1, len(h.group(1)), h.group(2)))
    return Doc(path, rel, lines, code, headings)


def split_cells(line: str) -> list[str]:
    """The cells of a table line; a | inside `code` or escaped as \\| does not split."""
    s = line.strip()
    if s.startswith("|"):
        s = s[1:]
    if s.endswith("|") and not s.endswith("\\|"):
        s = s[:-1]
    cells, cur, tick = [], [], False
    i = 0
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s) and s[i + 1] == "|":
            cur.append("|")
            i += 2
            continue
        if c == "`":
            tick = not tick
        if c == "|" and not tick:
            cells.append("".join(cur).strip())
            cur = []
        else:
            cur.append(c)
        i += 1
    cells.append("".join(cur).strip())
    return cells


_SEPARATOR = re.compile(r"^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$")


def tables(doc: Doc) -> list[Table]:
    out, i, n = [], 0, len(doc.lines)
    while i < n - 1:
        if not doc.code[i] and doc.lines[i].lstrip().startswith("|") and _SEPARATOR.match(doc.lines[i + 1]):
            chain = doc.heading_at(i + 1)
            t = Table(i + 1, split_cells(doc.lines[i]), [], chain[-1].text if chain else "")
            j = i + 2
            while j < n and not doc.code[j] and doc.lines[j].lstrip().startswith("|"):
                t.rows.append((j + 1, split_cells(doc.lines[j])))
                j += 1
            out.append(t)
            i = j
        else:
            i += 1
    return out


def plain(text: str) -> str:
    """Markdown inline formatting removed: links keep their text, emphasis and code marks go."""
    text = re.sub(r"!?\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = re.sub(r"<[^>]+>", " ", text)
    return text.replace("**", "").replace("__", "").replace("`", "").replace("*", "")


def slug(text: str) -> str:
    """GitHub's anchor for a heading."""
    s = plain(text).strip().lower()
    s = re.sub(r"[^\w\- ]", "", s)
    return s.replace(" ", "-")


def anchors(doc: Doc) -> set[str]:
    seen: dict[str, int] = {}
    out = set()
    for h in doc.headings:
        base = slug(h.text)
        k = seen.get(base, 0)
        out.add(base if k == 0 else f"{base}-{k}")
        seen[base] = k + 1
    for i, line in enumerate(doc.lines):
        if not doc.code[i]:
            for m in re.finditer(r"<a\s+[^>]*(?:id|name)=\"([^\"]+)\"", line):
                out.add(m.group(1))
    return out


PRAGMA = re.compile(r"doclint:\s*allow\s+([a-z]+(?:-[a-z]+)*(?:\s*,\s*[a-z]+(?:-[a-z]+)*)*)")


def allowed(lines: list[str], i: int, rule: str) -> bool:
    """A `<!-- doclint: allow RULE -->` (or `// doclint: allow RULE`) on line i or the line above it."""
    for j in (i, i - 1):
        if 0 <= j < len(lines):
            m = PRAGMA.search(lines[j])
            if m and rule in [r.strip() for r in m.group(1).split(",")]:
                return True
    return False


def strip_inline_code(line: str) -> str:
    return re.sub(r"`[^`]*`", lambda m: " " * len(m.group(0)), line)


_LINK = re.compile(r"(!?)\[((?:[^\[\]]|\[[^\]]*\])*)\]\(\s*<?([^)\s>]*)>?(?:\s+[\"'][^)]*[\"'])?\s*\)")
_REFDEF = re.compile(r"^\s{0,3}\[[^\]]+\]:\s+<?(\S+?)>?(\s|$)")
_HTML_ATTR = re.compile(r"<(img|a|source)\b[^>]*?\s(src|href|srcset)=\"([^\"]+)\"", re.I)


@dataclass
class Link:
    line: int
    target: str
    image: bool


def links(doc: Doc) -> list[Link]:
    out = []
    for i, raw in enumerate(doc.lines):
        if doc.code[i]:
            continue
        line = strip_inline_code(raw)
        for m in _LINK.finditer(line):
            out.append(Link(i + 1, m.group(3), m.group(1) == "!"))
        m = _REFDEF.match(line)
        if m:
            out.append(Link(i + 1, m.group(1), False))
        for m in _HTML_ATTR.finditer(line):
            target = m.group(3).split()[0] if m.group(2).lower() == "srcset" else m.group(3)
            out.append(Link(i + 1, target, m.group(1).lower() in ("img", "source")))
    return out


def is_external(target: str) -> bool:
    return bool(re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*:", target)) or target.startswith("//")


def markdown_files(root: Path) -> list[str]:
    try:
        out = subprocess.run(["git", "-C", str(root), "ls-files", "-z", "--", "*.md"], capture_output=True,
                             check=True).stdout.decode("utf-8").split("\0")
        files = [f for f in out if f]
    except (OSError, subprocess.CalledProcessError):
        files = [str(p.relative_to(root)) for p in root.rglob("*.md")]
    skip = ("private/", "node_modules/", "target/", ".git/", ".pio/", ".venv/")
    return sorted(f for f in files if not any(f.startswith(s) or f"/{s}" in f for s in skip)
                  and (root / f).is_file())


# ------------------------------------------------------------------------------------------- relative-links


def rule_relative_links(root: Path) -> list[Finding]:
    found, cache = [], {}

    def doc(rel: str) -> Doc:
        if rel not in cache:
            cache[rel] = read_doc(root, rel)
        return cache[rel]

    for rel in markdown_files(root):
        d = doc(rel)
        for link in links(d):
            t = link.target
            if not t or is_external(t):
                continue
            path_part, _, frag = t.partition("#")
            path_part = urllib.parse.unquote(path_part.split("?")[0])
            if path_part:
                base = root if path_part.startswith("/") else d.path.parent
                target = (base / path_part.lstrip("/")).resolve()
                try:
                    target_rel = str(target.relative_to(root.resolve()))
                except ValueError:
                    found.append(Finding(rel, link.line, "relative-links", f"{t}: points outside the repository"))
                    continue
                if not target.exists():
                    what = "image" if link.image else "link"
                    found.append(Finding(rel, link.line, "relative-links", f"{what} {t}: no such file"))
                    continue
            else:
                target, target_rel = d.path, rel
            if frag and target.is_file() and target.suffix == ".md":
                if frag not in anchors(doc(target_rel)):
                    found.append(Finding(rel, link.line, "relative-links",
                                         f"{t}: no heading '#{frag}' in {target_rel}"))
    return found


# ------------------------------------------------------------------------------------------- readme-sync


def rule_readme_sync(root: Path) -> list[Finding]:
    en_rel, fr_rel = "README.md", "README.fr.md"
    if not (root / en_rel).exists() or not (root / fr_rel).exists():
        return [Finding(en_rel, 1, "readme-sync", "README.md or README.fr.md is missing")]
    en, fr = read_doc(root, en_rel), read_doc(root, fr_rel)
    found = []
    for level in range(1, 7):
        a = [h for h in en.headings if h.level == level]
        b = [h for h in fr.headings if h.level == level]
        if len(a) != len(b):
            found.append(Finding(fr_rel, (b or a or [Heading(1, 0, "")])[0].line, "readme-sync",
                                 f"{len(b)} level-{level} headings, {len(a)} in README.md"))
    # per section: the same number of headings of each level under the same position
    if [h.level for h in en.headings] != [h.level for h in fr.headings]:
        for i, (a, b) in enumerate(zip(en.headings, fr.headings)):
            if a.level != b.level:
                found.append(Finding(fr_rel, b.line, "readme-sync",
                                     f"heading {i + 1} is level {b.level}, README.md's ({a.text!r}) is level {a.level}"))
                break

    def kinds(d: Doc):
        imgs, rel_links, ext_links = [], [], []
        for link in links(d):
            if link.image:
                imgs.append(link)
            elif is_external(link.target):
                ext_links.append(link)
            elif not link.target.startswith("#") and link.target.partition("#")[0] not in (en_rel, fr_rel):
                rel_links.append(link)
        return imgs, rel_links, ext_links

    for name, a, b, key in zip(("images", "relative links", "external links"), kinds(en), kinds(fr),
                               (lambda t: t, lambda t: t.partition("#")[0], lambda t: t)):
        ta = [key(x.target) for x in a]
        tb = [key(x.target) for x in b]
        if sorted(ta) != sorted(tb):
            only_en = _multiset_minus(ta, tb)
            only_fr = _multiset_minus(tb, ta)
            for t in only_en:
                line = next(x.line for x in a if key(x.target) == t)
                found.append(Finding(en_rel, line, "readme-sync", f"{name}: {t} is not in README.fr.md"))
            for t in only_fr:
                line = next(x.line for x in b if key(x.target) == t)
                found.append(Finding(fr_rel, line, "readme-sync", f"{name}: {t} is not in README.md"))
        elif name == "images" and ta != tb:
            found.append(Finding(fr_rel, b[0].line, "readme-sync", "the same images, in another order"))
    return found


def _multiset_minus(a: list[str], b: list[str]) -> list[str]:
    rest = list(b)
    out = []
    for x in a:
        if x in rest:
            rest.remove(x)
        else:
            out.append(x)
    return out


# ------------------------------------------------------------------------------------------- PlatformIO


@dataclass
class Env:
    name: str
    extends: str | None
    board: str | None
    flags: dict[str, str]          # -D NAME=VALUE (VALUE "" for a bare -DNAME), [env] and extends resolved


def read_platformio(path: Path) -> dict[str, Env]:
    cp = configparser.ConfigParser(interpolation=None, inline_comment_prefixes=(";",), strict=False)
    cp.optionxform = str
    cp.read_string(path.read_text(encoding="utf-8"))
    raw = {s[4:]: dict(cp[s]) for s in cp.sections() if s.startswith("env:")}
    base = dict(cp["env"]) if cp.has_section("env") else {}

    def flags_of(text: str) -> dict[str, str]:
        out = {}
        for line in text.splitlines():
            line = line.split(";", 1)[0]
            for m in re.finditer(r"-D\s*([A-Za-z_]\w*)(?:=(\S+))?", line):
                out[m.group(1)] = (m.group(2) or "").strip('"\\')
        return out

    def resolve(name: str, depth: int = 0) -> dict[str, str]:
        sec = raw[name]
        parent = sec.get("extends", "").strip()
        values = dict(base)
        if parent.startswith("env:") and parent[4:] in raw and depth < 8:
            values.update(resolve(parent[4:], depth + 1))
        values.update(sec)
        return values

    envs = {}
    for name in raw:
        v = resolve(name)
        bf = v.get("build_flags", "")
        bf = bf.replace("${env.build_flags}", base.get("build_flags", ""))
        ext = raw[name].get("extends", "").strip()
        envs[name] = Env(name, ext[4:] if ext.startswith("env:") else None, v.get("board"), flags_of(bf))
    return envs


# ------------------------------------------------------------------------------------------- env-table


def rule_env_table(root: Path) -> list[Finding]:
    found = []
    envs = read_platformio(root / PLATFORMIO)
    readme = "firmware/README.md"
    d = read_doc(root, readme)
    env_tables = [t for t in tables(d) if t.header and plain(t.header[0]).strip().lower() in ("env", "environment")]
    if not env_tables:
        found.append(Finding(readme, 1, "env-table", "no table of the PlatformIO environments (first column 'Env')"))
    else:
        t = env_tables[0]
        listed: dict[str, int] = {}
        for line, cells in t.rows:
            for m in re.finditer(r"`([A-Za-z0-9_]+)`", cells[0]):
                listed[m.group(1)] = line
        text = "\n".join(l for i, l in enumerate(d.lines) if not d.code[i])
        for name in listed:
            if name not in envs:
                found.append(Finding(readme, listed[name], "env-table",
                                     f"`{name}` is not an [env:*] of {PLATFORMIO}"))
        for name, env in envs.items():
            if name in listed:
                continue
            if env.extends and env.extends in listed and re.search(rf"`{re.escape(name)}`", text):
                continue
            found.append(Finding(readme, t.line, "env-table",
                                 f"[env:{name}] of {PLATFORMIO} is not in the table"
                                 + (f" (nor named beside its twin `{env.extends}`)" if env.extends else "")))
    # every env a doc names must exist
    bridge = root / "firmware/mr60_bridge/platformio.ini"
    bridge_envs = set(read_platformio(bridge)) if bridge.exists() else set()
    for rel in markdown_files(root):
        if rel.startswith(("host-java/NOTES", "CHANGELOG")):
            continue
        known = set(envs) | (bridge_envs if "mr60" in rel else set())
        doc = read_doc(root, rel)
        for i, line in enumerate(doc.lines):
            names = [m.group(1) for m in re.finditer(r"\bpio\s+(?:run|test|device\s+monitor)\b[^\n|`]*?\s-e\s+([A-Za-z0-9_]+)", line)]
            names += [m.group(1) for m in re.finditer(r"\s-e\s+([A-Za-z0-9_]+)\s+-e\s+", line)]
            if not doc.code[i]:
                names += [m.group(1) for m in re.finditer(r"`([a-z0-9_]+)`\s+env\b", line)]
                names += [m.group(1) for m in re.finditer(r"\benv\s+`([a-z0-9_]+)`", line)]
            for name in dict.fromkeys(names):
                if name not in known and name not in bridge_envs:
                    found.append(Finding(rel, i + 1, "env-table", f"env `{name}` is not in {PLATFORMIO}"))
    return found


# ------------------------------------------------------------------------------------------- pin-tables

XIAO_D = {0: 1, 1: 2, 2: 3, 3: 4, 4: 5, 5: 6, 6: 43, 7: 44, 8: 7, 9: 8, 10: 9}

# signal -> (part pattern, pin pattern or None); matched against a row's text (its non-GPIO cells)
FLAG_SIGNALS = {
    "MARVIN_BUTTON_TALK": (r"\btalk\b|\bknob\b", None),
    "MARVIN_BUTTON_MUTE": (r"\bmute\b", None),
    "MARVIN_BUZZER_PIN": (r"\bbuzzer\b", None),
    "MARVIN_OLED_SDA": (r"\boled\b|\bscreen\b|\bdisplay\b", r"\bSDA\b"),
    "MARVIN_OLED_SCL": (r"\boled\b|\bscreen\b|\bdisplay\b", r"\bSCL\b"),
    "MARVIN_STATUS_RGB": (r"\bstatus\b|\bRGB\b", None),
    "MARVIN_STATUS_RGB_ALT": (r"\bstatus\b|\bRGB\b", None),
    "MARVIN_PIR_PIN": (r"\bPIR\b|HC-SR501", None),
    "MARVIN_DHT11_PIN": (r"\bDHT11\b", None),
    "MARVIN_LDR_PIN": (r"\blight sensor\b|\bLDR\b", None),
    "MARVIN_HEAD_PIN": (r"\bservo\b", None),
}
SOURCE_SIGNALS = {
    "MARVIN_TFT_SCK": (r"\bscreen\b", r"\bCLK\b|\bSCK\b"),
    "MARVIN_TFT_MOSI": (r"\bscreen\b", r"\bDIN\b|\bMOSI\b"),
    "MARVIN_TFT_CS": (r"\bscreen\b", r"\bCS\b"),
    "MARVIN_TFT_DC": (r"\bscreen\b", r"\bDC\b"),
    "MARVIN_TFT_RST": (r"\bscreen\b", r"\bRST\b"),
    "AMP_BCLK": (r"\bamp\b", r"\bBCLK\b"),
    "AMP_WS": (r"\bamp\b", r"\bLRC\b|\bWS\b"),
    "AMP_DIN": (r"\bamp\b", r"\bDIN\b|\bDOUT\b"),
    "MIC_CLK": (r"\bmic(rophone)?\b", None),
    "MIC_DATA": (r"\bmic(rophone)?\b", None),
    "LD2450_RX": (r"\bLD2450\b", r"\bTX\b"),
    "LD2450_TX": (r"\bLD2450\b", r"\bRX\b"),
}
PIN_FLAG = re.compile(r"^MARVIN_(\w+_PIN|BUTTON_\w+|OLED_SDA|OLED_SCL|STATUS_RGB(_ALT)?)$")
PIN_DOCS = {  # file -> its env when no heading names one
    "docs/wiring.md": "xiao_esp32s3",
    "firmware/README.md": "xiao_esp32s3",
    "docs/xiao-bench.md": "xiao_bench",
    "docs/wiring-bench.md": None,
    "docs/looks-bench.md": "esp32dev_looks",
}
SPARE = re.compile(r"\bspare\b|\bfree\b|\bunused\b|\bnot connected\b|\blibre\b", re.I)


def source_pins(root: Path) -> dict[str, int]:
    """The XIAO's fixed pins in the firmware source (a -D flag of the same name overrides them)."""
    pins: dict[str, int] = {}
    face = root / "firmware/src/face/face_app.cpp"
    if face.exists():
        for m in re.finditer(r"#define\s+(MARVIN_TFT_(?:SCK|MOSI|CS|DC|RST))\s+(-?\d+)", face.read_text()):
            pins[m.group(1)] = int(m.group(2))
    audio = root / "firmware/src/audio/audio_app.cpp"
    if audio.exists():
        for m in re.finditer(r"constexpr\s+int\s+((?:AMP|MIC)_\w+)\s*=\s*(-?\d+)", audio.read_text()):
            pins[m.group(1)] = int(m.group(2))
    sensors = root / "firmware/src/sensors_real.cpp"
    if sensors.exists():
        text = sensors.read_text()
        # the ESP32-S3 branch (after #else) is the XIAO's
        tail = text.split("#else", 1)[1] if "#else" in text else text
        for m in re.finditer(r"constexpr\s+int\s+(LD2450_(?:RX|TX))\s*=\s*(-?\d+)", tail):
            pins.setdefault(m.group(1), int(m.group(2)))
    return pins


def expected_pins(env: Env, src: dict[str, int]) -> dict[str, tuple[int, tuple[str, str | None]]]:
    out = {}
    for name, value in env.flags.items():
        if PIN_FLAG.match(name):
            try:
                gpio = int(value, 0)
            except ValueError:
                continue
            sig = FLAG_SIGNALS.get(name)
            if sig is None:
                word = re.sub(r"^MARVIN_|_PIN$", "", name).replace("_", " ").lower()
                sig = (rf"\b{re.escape(word)}\b", None)
            out[name] = (gpio, sig)
    if env.board == "seeed_xiao_esp32s3":
        for name, gpio in src.items():
            if name in SOURCE_SIGNALS:
                value = env.flags.get(name)
                try:
                    gpio = int(value, 0) if value else gpio
                except ValueError:
                    pass
                out.setdefault(name, (gpio, SOURCE_SIGNALS[name]))
    return out


_NUM = re.compile(r"(?<![A-Za-z\d.])(\d{1,2})(?![\d.])")


def row_gpios(header: list[str], cells: list[str], xiao: bool) -> tuple[set[int], str]:
    """The GPIOs a table row names, and its text without the GPIO column."""
    gpios: set[int] = set()
    text_cells = []
    for h, c in zip(header + [""] * (len(cells) - len(header)), cells):
        hp = plain(h).strip().lower()
        cp = plain(c)
        if hp == "gpio":
            gpios.update(int(n) for n in _NUM.findall(cp))
            continue
        gpios.update(int(n) for n in re.findall(r"\bGPIO\s?(\d{1,2})\b", cp))
        if xiao:
            for m in re.finditer(r"(?:\bXIAO\s+|^\s*|\()D(\d{1,2})\b", cp):
                if int(m.group(1)) in XIAO_D:
                    gpios.add(XIAO_D[int(m.group(1))])
        elif "pin" in hp:
            gpios.update(int(n) for n in re.findall(r"\bD(\d{1,2})\b", cp))
        text_cells.append(cp)
    return gpios, " | ".join(text_cells)


def rule_pin_tables(root: Path) -> list[Finding]:
    found = []
    envs = read_platformio(root / PLATFORMIO)
    src = source_pins(root)
    covered: dict[tuple[str, str], bool] = {}
    flag_pins: dict[tuple[str, str], int] = {}
    first_table: dict[str, int] = {}
    for rel, default_env in PIN_DOCS.items():
        if not (root / rel).exists():
            continue
        doc = read_doc(root, rel)
        for t in tables(doc):
            # the env: one named in a heading above the table, else the file's
            env_name = default_env
            for h in reversed(doc.heading_at(t.line)):
                named = [n for n in re.findall(r"`([A-Za-z0-9_]+)`", h.text) if n in envs]
                if named:
                    env_name = named[0]
                    break
            if env_name is None or env_name not in envs:
                continue
            env = envs[env_name]
            xiao = env.board == "seeed_xiao_esp32s3"
            exp = expected_pins(env, src)
            for name, (gpio, _) in exp.items():
                if name in env.flags and PIN_FLAG.match(name) and gpio >= 0:
                    flag_pins[(rel, name)] = gpio
                    covered.setdefault((rel, name), False)
            used = {gpio: name for name, (gpio, _) in exp.items() if gpio >= 0}
            for line, cells in t.rows:
                gpios, text = row_gpios(t.header, cells, xiao)
                if not gpios:
                    continue
                first_table.setdefault(rel, t.line)
                if SPARE.search(text) or any(SPARE.search(c) for c in cells):
                    for g in sorted(gpios & set(used)):
                        found.append(Finding(rel, line, "pin-tables",
                                             f"GPIO{g} is marked spare, but {env_name} uses it for {used[g]}"))
                    continue
                matched = {name: gpio for name, (gpio, (part, pin)) in exp.items()
                           if gpio >= 0 and re.search(part, text, re.I) and (pin is None or re.search(pin, text))}
                if not matched:
                    continue
                want = set(matched.values())
                for name, gpio in matched.items():
                    if gpio in gpios:
                        covered[(rel, name)] = True
                if not want & gpios:
                    names = ", ".join(f"{n}={g}" for n, g in matched.items())
                    found.append(Finding(rel, line, "pin-tables",
                                         f"row says GPIO {sorted(gpios)}, {env_name} has {names}"))
                elif gpios - want - (set(used) if len(matched) > 1 else set()):
                    extra = sorted(gpios - want)
                    names = ", ".join(f"{n}={g}" for n, g in matched.items())
                    found.append(Finding(rel, line, "pin-tables",
                                         f"row also names GPIO {extra}, not the {env_name} pin of {names}"))
    for (rel, name), ok in covered.items():
        if not ok:
            found.append(Finding(rel, first_table.get(rel, 1), "pin-tables",
                                 f"{name}={flag_pins[(rel, name)]} (platformio.ini) is in no pin table of this file"))
    return found


# ------------------------------------------------------------------------------------------- protocol-table


def java_ints(text: str) -> dict[str, int]:
    out: dict[str, int] = {}
    for m in re.finditer(r"static\s+final\s+int\s+(\w+)\s*=\s*([^;]+);", text):
        out[m.group(1)] = m.group(2).strip()
    return out


def eval_int(expr: str, scopes: list[dict]) -> int | None:
    expr = expr.strip().replace("_", "")
    try:
        return int(expr, 0)
    except ValueError:
        pass
    name = expr.split(".")[-1]
    for s in scopes:
        if name in s:
            return eval_int(s[name], scopes)
    return None


def rule_protocol_table(root: Path) -> list[Finding]:
    found = []
    rel = "docs/protocol.md"
    doc = read_doc(root, rel)
    jdir = root / ROBOT_DOMAIN
    proto = java_ints((jdir / "ProtocolV1.java").read_text())
    button = java_ints((jdir / "Button.java").read_text())
    scopes = [proto, button]
    P = {k: eval_int(v, scopes) for k, v in proto.items()}
    B = {k: eval_int(v, scopes) for k, v in button.items()}
    types = {m.group(1): (int(m.group(2), 16), m.group(3)) for m in re.finditer(
        r"\b([A-Z][A-Z0-9_]*)\(0x([0-9A-Fa-f]+),\s*Direction\.(\w+)\)", (jdir / "MessageType.java").read_text())}
    hm = (jdir / "HostMessages.java").read_text()

    def map_of(name: str) -> dict[str, int]:
        m = re.search(rf"\b{name}\s*=\s*Map\.of\(([^;]*)\);", hm, re.S)
        return {k: int(v) for k, v in re.findall(r"\"(\w+)\"\s*,\s*(\d+)", m.group(1))} if m else {}

    sounds, durations = map_of("SOUNDS"), map_of("SOUND_DURATIONS_MS")
    tbls = tables(doc)

    def header_is(t: Table, *words: str) -> bool:
        h = [plain(c).strip().lower() for c in t.header]
        return all(w in h for w in words)

    # messages
    msg = [t for t in tbls if header_is(t, "type", "name", "direction")]
    if not msg:
        found.append(Finding(rel, 1, "protocol-table", "no Messages table (Type | Name | Direction | Payload)"))
    else:
        t = msg[0]
        seen = set()
        for line, cells in t.rows:
            code_m = re.search(r"0x([0-9A-Fa-f]+)", cells[0])
            name = plain(cells[1]).strip()
            if not code_m:
                continue
            seen.add(name)
            direction = "ROBOT_TO_HOST" if re.search(r"robot\s*→\s*host", cells[2]) else \
                "HOST_TO_ROBOT" if re.search(r"host\s*→\s*robot", cells[2]) else "?"
            if name not in types:
                found.append(Finding(rel, line, "protocol-table", f"message {name} is not in MessageType.java"))
                continue
            code, d = types[name]
            if int(code_m.group(1), 16) != code:
                found.append(Finding(rel, line, "protocol-table",
                                     f"{name} is 0x{code:02x} in MessageType.java, 0x{code_m.group(1)} here"))
            if direction != d:
                found.append(Finding(rel, line, "protocol-table", f"{name} goes {d} in MessageType.java"))
        for name, (code, _) in types.items():
            if name not in seen:
                found.append(Finding(rel, t.line, "protocol-table",
                                     f"message {name} (0x{code:02x}) of MessageType.java is not in the table"))
    # HELLO flags
    flag_words = {"SIMULATED": r"simulat", "CAMERA": r"camera", "AUDIO": r"audio", "BUTTONS": r"button",
                  "BUZZER": r"buzzer", "FACE": r"face", "ENVIRONMENT": r"room|environment"}
    flags = {k[5:]: v for k, v in P.items() if k.startswith("FLAG_") and v is not None}
    ft = [t for t in tbls if header_is(t, "bit", "mask", "meaning")]
    if not ft:
        found.append(Finding(rel, 1, "protocol-table", "no HELLO flags table (Bit | Mask | Meaning)"))
    else:
        t = ft[0]
        rows = {}
        for line, cells in t.rows:
            mm = re.search(r"0x([0-9A-Fa-f]+)", cells[1])
            if mm:
                rows[int(mm.group(1), 16)] = (line, cells)
                bit = int(re.sub(r"\D", "", cells[0]) or -1)
                if bit >= 0 and 1 << bit != int(mm.group(1), 16):
                    found.append(Finding(rel, line, "protocol-table", f"bit {bit} is mask 0x{1 << bit:02x}"))
        for name, mask in flags.items():
            if mask not in rows:
                found.append(Finding(rel, t.line, "protocol-table",
                                     f"HELLO flag FLAG_{name} (0x{mask:02x}) is not in the table"))
                continue
            line, cells = rows[mask]
            word = flag_words.get(name, name.lower())
            if not re.search(word, plain(cells[2]), re.I):
                found.append(Finding(rel, line, "protocol-table",
                                     f"0x{mask:02x} is FLAG_{name} in ProtocolV1.java: the meaning does not say so"))
        for mask, (line, _) in rows.items():
            if mask not in flags.values():
                found.append(Finding(rel, line, "protocol-table",
                                     f"HELLO flag 0x{mask:02x} has no FLAG_* constant in ProtocolV1.java"))
    # ports
    pt = [t for t in tbls if any("udp port" in plain(c).lower() for c in t.header)]
    for t in pt[:1]:
        for line, cells in t.rows:
            port = int(re.sub(r"\D", "", cells[-1]) or 0)
            side = cells[0].lower()
            want = P.get("HOST_PORT") if "host" in side else P.get("DEVICE_PORT") if "robot" in side else None
            if want is not None and port != want:
                found.append(Finding(rel, line, "protocol-table", f"{cells[0]}: {want} in ProtocolV1.java"))
    # buttons
    bt = None
    for t in tbls:
        if any(re.search(r"\bButton:\s*\d+\s*=", c) for _, cells in t.rows for c in cells):
            bt = t
            break
    if bt is None:
        found.append(Finding(rel, 1, "protocol-table", "no BUTTON payload table ('Button: 1 = Talk, ...')"))
    else:
        want_buttons = {"talk": B.get("TALK"), "mute": B.get("MUTE")}
        want_actions = {"press": B.get("PRESS"), "long press": B.get("LONG_PRESS")}
        for line, cells in bt.rows:
            text = plain(" ".join(cells))
            for label, want in (("Button", want_buttons), ("Action", want_actions)):
                m = re.search(rf"\b{label}:\s*(.*)", text)
                if not m:
                    continue
                got = {}
                for n, w in re.findall(r"(\d+)\s*=\s*([A-Za-z][A-Za-z ]*?)(?=\s*[,(;]|$)", m.group(1)):
                    got[w.strip().lower()] = int(n)
                for w, v in want.items():
                    if v is None:
                        continue
                    if got.get(w) != v:
                        found.append(Finding(rel, line, "protocol-table",
                                             f"{label.lower()} '{w}' is {v} in Button.java"
                                             + (f", {got.get(w)} here" if w in got else ", missing here")))
                for w, v in got.items():
                    if w not in want:
                        found.append(Finding(rel, line, "protocol-table",
                                             f"{label.lower()} {v} = {w} is not in Button.java"))
        ms = P.get("BUTTON_LONG_PRESS_MS")
        if ms:
            chain_end = next((h.line for h in doc.headings if h.line > bt.line), len(doc.lines) + 1)
            start = max((h.line for h in doc.headings if h.line < bt.line), default=1)
            section = "\n".join(doc.lines[start - 1:chain_end - 1])
            secs = f"{ms / 1000:g}"
            if not re.search(rf"\b{re.escape(secs)}\s*s\b|\b{ms}\s*ms\b", section):
                found.append(Finding(rel, bt.line, "protocol-table",
                                     f"the long press is BUTTON_LONG_PRESS_MS = {ms} in ProtocolV1.java: say {secs} s"))
    # sounds
    st = [t for t in tbls if header_is(t, "id", "name", "length")]
    if not st:
        found.append(Finding(rel, 1, "protocol-table", "no built-in sounds table (Id | Name | Length)"))
    else:
        t = st[0]
        got: dict[str, tuple[int, int | None, int]] = {}
        for line, cells in t.rows:
            for m in re.finditer(r"\|?\s*(\d+)\s*\|\s*`(\w+)`\s*\|\s*(?:(\d+)\s*ms)?", "|" + "|".join(cells)):
                got[m.group(2)] = (int(m.group(1)), int(m.group(3)) if m.group(3) else None, line)
        for name, sid in sounds.items():
            if name not in got:
                found.append(Finding(rel, t.line, "protocol-table", f"sound {name} ({sid}) is not in the table"))
                continue
            gid, gms, line = got[name]
            if gid != sid:
                found.append(Finding(rel, line, "protocol-table", f"sound {name} is {sid} in HostMessages.java"))
            if name in durations and gms is not None and gms != durations[name]:
                found.append(Finding(rel, line, "protocol-table",
                                     f"sound {name} lasts {durations[name]} ms in HostMessages.java"))
        for name, (_, _, line) in got.items():
            if name not in sounds:
                found.append(Finding(rel, line, "protocol-table", f"sound {name} is not in HostMessages.SOUNDS"))
    return found


# ------------------------------------------------------------------------------------------- no-lidar-claim

LIDAR = re.compile(r"lidar", re.I)
NEGATION = re.compile(
    r"\bno\b|\bnot\b|\bwithout\b|\bnone\b|\bno longer\b|\bremoved\b|\bdropped\b|\bnever\b|\bnor\b|\bisn't\b"
    r"|\bsans\b|\bpas de\b|\bplus de\b|\bn'en a plus\b|\bn'a (?:pas|plus)\b|\bne \w+ plus\b|retiré|supprimé", re.I)
CONTEXT = re.compile(
    r"\busb\b|\bbench\b|\bbanc\b|simulat|\bsim\b|\bbridge\b|\bscanner\b|\brev(?:ision)?\.?\s*[A-E]\b"
    r"|\br[ée]v\.\s*[A-E]\b|\bD500\b|\bD800\b|\bSTL-\d|LDROBOT|\bLD19\b|`LIDAR`|MARVIN_(?:SIM_)?LIDAR_MODEL"
    r"|\boptional\b|\b(?:when|if|whenever) a lidar\b|\ba lidar alone\b|\bany lidar\b|\bD7\b"
    r"|\bhistory\b|\bhistorique\b", re.I)
CLAIM = re.compile(
    r"\b(?:marvin|the robot|robot)(?:'s|’s)?\s+(?:own\s+)?(?:\w+\s+){0,2}lidar\b"
    r"|\b(?:its|his|son|sa)\s+(?:\w+\s+){0,2}lidar\b"
    r"|\blidar\s+(?:on|in|atop)\s+(?:the\s+)?(?:robot|head|top|marvin)\b"
    r"|\b360[- ]?(?:degree|°)\s+lidar\b|\blidar\s+(?:ring|turret|base)\b"
    r"|\blidar\s+(?:du|de)\s+(?:robot|marvin)\b|\blidar\s+(?:sur|dans)\s+(?:le robot|la tête|marvin)\b", re.I)
SKIP_FILES = {"docs/usb-sensors.md", "docs/looks-bench.md", "docs/wiring-bench.md"}
SKIP_HEADING = re.compile(
    r"histor|changelog|journal|bench|banc|\busb\b|simulat|calibration|room scanner|sensor frames"
    r"|frames of reference|bandwidth|the lidar question", re.I)
SENTENCE = re.compile(r"(?<=[.!?;])\s+(?=[A-Z(\"'*`_\[])")


def sentences(line: str) -> list[str]:
    return [s for s in SENTENCE.split(line) if s.strip()]


def rule_no_lidar_claim(root: Path) -> list[Finding]:
    found = []
    md = ["README.md", "README.fr.md"] + sorted(
        str(p.relative_to(root)) for p in (root / "docs").glob("*.md"))
    for rel in md:
        if rel in SKIP_FILES or not (root / rel).exists():
            continue
        doc = read_doc(root, rel)
        for i, line in enumerate(doc.lines):
            if doc.code[i] or not LIDAR.search(strip_inline_code(line)) or _HEADING.match(line):
                continue
            if any(SKIP_HEADING.search(h.text) for h in doc.heading_at(i + 1)):
                continue
            if re.match(r"^\s*[-*]\s+\[x\]\s+.*\brev\b", line, re.I):
                continue
            # a name in `code` (the LIDAR message, a JSON field) is the wire format, not a claim
            text = re.sub(r"`[^`]*`", lambda m: "`...`" if LIDAR.search(m.group(0)) else m.group(0),
                          re.sub(r"<!--.*?-->", "", line))
            for s in sentences(text):
                if not LIDAR.search(s):
                    continue
                if CLAIM.search(s) and not NEGATION.search(s):
                    found.append(Finding(rel, i + 1, "no-lidar-claim", f"gives Marvin a lidar: {_quote(s)}"))
                elif not CLAIM.search(s) and not NEGATION.search(s) and not CONTEXT.search(s):
                    found.append(Finding(rel, i + 1, "no-lidar-claim",
                                         f"names a lidar without saying it is not Marvin's (rev F has none): "
                                         f"{_quote(s)}"))
    # the persona: only ever a denial
    if (root / PERSONA).exists():
        lines = (root / PERSONA).read_text(encoding="utf-8").splitlines()
        for i, line in enumerate(lines):
            for s in java_strings(line):
                for sent in sentences(s):
                    if LIDAR.search(sent) and not NEGATION.search(sent):
                        found.append(Finding(PERSONA, i + 1, "no-lidar-claim",
                                             f"the persona names a lidar without denying it: {_quote(sent)}"))
    # the app's strings: no sentence giving the robot a lidar
    app = root / APP_DIR
    if app.is_dir():
        for p in sorted(app.rglob("*")):
            if p.suffix not in (".js", ".html", ".json") or not p.is_file():
                continue
            rel = str(p.relative_to(root))
            for i, line in enumerate(p.read_text(encoding="utf-8").splitlines()):
                if not LIDAR.search(line):
                    continue
                parts = html_texts(line) if p.suffix == ".html" else js_strings(line)
                for s in parts:
                    for sent in sentences(s):
                        if CLAIM.search(sent) and not NEGATION.search(sent):
                            found.append(Finding(rel, i + 1, "no-lidar-claim",
                                                 f"the app gives Marvin a lidar: {_quote(sent)}"))
    return found


def _quote(s: str, n: int = 110) -> str:
    s = " ".join(s.split())
    m = LIDAR.search(s)
    if len(s) > n and m:
        start = max(0, m.start() - n // 2)
        s = ("..." if start else "") + s[start:start + n] + "..."
    return f'"{s}"'


_in_text_block = False


def java_strings(line: str) -> list[str]:
    """String content on one line of Java: text blocks (\"\"\" ... \"\"\") and "literals"."""
    global _in_text_block
    out = []
    if _in_text_block:
        end = line.find('"""')
        if end >= 0:
            out.append(line[:end])
            _in_text_block = False
            line = line[end + 3:]
        else:
            return [line]
    start = line.find('"""')
    if start >= 0:
        rest = line[start + 3:]
        end = rest.find('"""')
        if end >= 0:
            out.append(rest[:end])
        else:
            _in_text_block = True
            out.append(rest)
        line = line[:start]
    out += re.findall(r'"((?:[^"\\]|\\.)*)"', line)
    return out


def js_strings(line: str) -> list[str]:
    code = re.sub(r"^\s*//.*$", "", line)
    return [m.group(2) for m in re.finditer(r"(['\"`])((?:(?!\1)[^\\]|\\.)*)\1", code)]


def html_texts(line: str) -> list[str]:
    attrs = re.findall(r"\b(?:aria-label|title|alt|placeholder|content)=\"([^\"]*)\"", line)
    text = re.sub(r"<[^>]*>", "\n", line)
    return attrs + [t for t in text.split("\n") if t.strip()]


# ------------------------------------------------------------------------------------------- main

CHECKS = {
    "no-lidar-claim": rule_no_lidar_claim,
    "pin-tables": rule_pin_tables,
    "env-table": rule_env_table,
    "protocol-table": rule_protocol_table,
    "relative-links": rule_relative_links,
    "readme-sync": rule_readme_sync,
}


def run(root: Path, rules: list[str]) -> list[Finding]:
    global _in_text_block
    findings: list[Finding] = []
    for r in rules:
        _in_text_block = False
        try:
            findings += CHECKS[r](root)
        except (OSError, KeyError, IndexError, configparser.Error) as e:
            findings.append(Finding("tools/doclint.py", 1, r, f"the rule could not run: {type(e).__name__}: {e}"))
    lines: dict[str, list[str]] = {}

    def waived(f: Finding) -> bool:
        if f.path not in lines:
            try:
                lines[f.path] = (root / f.path).read_text(encoding="utf-8").splitlines()
            except OSError:
                lines[f.path] = []
        return allowed(lines[f.path], f.line - 1, f.rule)

    return sorted({f for f in findings if not waived(f)}, key=lambda f: (f.rule, f.path, f.line, f.message))


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Marvin's doc lint (contract C9).")
    ap.add_argument("--rule", action="append", choices=RULES, help="run only this rule (repeatable)")
    ap.add_argument("--root", default=str(Path(__file__).resolve().parent.parent), help="the repository")
    args = ap.parse_args(argv)
    root = Path(args.root).resolve()
    rules = args.rule or list(RULES)
    findings = run(root, rules)
    for f in findings:
        print(f)
    counts = {r: sum(1 for f in findings if f.rule == r) for r in rules}
    summary = ", ".join(f"{r} {'ok' if n == 0 else f'{n} failed'}" for r, n in counts.items())
    print(f"doclint: {summary}", file=sys.stderr if findings else sys.stdout)
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
