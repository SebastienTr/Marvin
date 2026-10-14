# SPDX-License-Identifier: MIT
"""Tests of tools/doclint.py on a small synthetic repository (each rule finds what it should, and only that)."""

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import doclint  # noqa: E402

ROBOT = "host-java/marvin-domain/src/main/java/marvin/host/domain/robot"

PLATFORMIO = """\
[env]
build_flags =
    -DMARVIN_FW_VERSION=\\"0.1.0\\"

[env:xiao_esp32s3]
board = seeed_xiao_esp32s3
build_flags =
    ${env.build_flags}
    -DMARVIN_HAS_SCREEN
{robot_flags}

[env:xiao_esp32s3_ota]
extends = env:xiao_esp32s3

[env:esp32dev]
board = esp32dev
build_flags =
    ${env.build_flags}
    -DMARVIN_BUTTON_TALK=32
    -DMARVIN_BUZZER_PIN=25
;   -DMARVIN_STATUS_RGB=18

[env:native]
platform = native
"""

FIRMWARE_README = """\
# Firmware

## Build environments

| Env | Board |
|---|---|
| `xiao_esp32s3` | the robot |
| `esp32dev` | a bench |
| `native` | tests |

The `xiao_esp32s3_ota` env is its twin over Wi-Fi.

## Pin map

| Signal | XIAO pin | GPIO | Peripheral |
|---|---|---|---|
| Spare | D7 | 44 | `MARVIN_LIDAR_MODEL=1|2` |
| LD2450 TX → | D4 | 5 | UART1 RX |
| Screen CS | D0 | 1 | GPIO |
"""

WIRING = """\
# Wiring

| # | Wire | Goes to |
|---:|---|---|
| 1 | Screen **CS** | XIAO **D0** |
| 2 | Amp **BCLK** | XIAO **D9** |
"""

BENCH = """\
# Bench

## Classic ESP32 DevKit (`esp32dev`, board 5)

| Part | Its pin | DevKit pin | GPIO |
|---|---|---|---|
| Talk button | one leg | **D32** | 32 |
| Buzzer | + | **D25** | 25 |
"""

PROTOCOL = """\
# Protocol

## Ports and link

| Side | UDP port |
|---|---:|
| Host listens on | 47100 |
| Robot listens on | 47101 |

## Messages

| Type | Name | Direction | Payload |
|---:|---|---|---|
| `0x01` | `HELLO` | robot → host | stuff |
| `0x07` | `BUTTON` | robot → host | button |
| `0x86` | `SOUND` | host → robot | id |

| Bit | Mask | Meaning |
|---:|---:|---|
| 0 | `0x01` | The sensor data is simulated |
| 3 | `0x08` | Has buttons |

## Sounds

| Id | Name | Length | | Id | Name | Length |
|---:|---|---:|---|---:|---|---:|
| 1 | `chirp` | 140 ms | | 2 | `beep` | 120 ms |

## Buttons

| Offset | Size | Field |
|---:|---:|---|
| 0 | 1 | Button: 1 = Talk, 2 = Mute |
| 1 | 1 | Action: 1 = press (a tap), 2 = long press (held 1.5 s or more) |
"""

JAVA = {
    "ProtocolV1.java": """public final class ProtocolV1 {
    public static final int HOST_PORT = 47100;
    public static final int DEVICE_PORT = 47101;
    public static final int FLAG_SIMULATED = 0x01;
    public static final int FLAG_BUTTONS = 0x08;
    public static final int BUTTON_LONG_PRESS = 2;
    public static final int BUTTON_LONG_PRESS_MS = 1500;
}""",
    "Button.java": """public record Button(int button, int action) {
    public static final int TALK = 1;
    public static final int MUTE = 2;
    public static final int PRESS = 1;
    public static final int LONG_PRESS = ProtocolV1.BUTTON_LONG_PRESS;
}""",
    "MessageType.java": """public enum MessageType {
    HELLO(0x01, Direction.ROBOT_TO_HOST),
    BUTTON(0x07, Direction.ROBOT_TO_HOST),
    SOUND(0x86, Direction.HOST_TO_ROBOT);
}""",
    "HostMessages.java": """public final class HostMessages {
    public static final Map<String, Integer> SOUNDS = Map.of("chirp", 1, "beep", 2);
    public static final Map<String, Integer> SOUND_DURATIONS_MS = Map.of("chirp", 140, "beep", 120);
}""",
}

PERSONA = '''public final class Persona {
    static final String PERSONA = """
            You are Marvin. You have no lidar and nothing that turns.
            """;
}'''

README_EN = """\
# Marvin

[Français](README.fr.md)

![The robot](docs/images/robot.png)

## One

See [the wiring](docs/wiring.md#wiring).

## Two
"""

README_FR = """\
# Marvin (français)

[English](README.md)

![Le robot](docs/images/robot.png)

## Un

Voir [le câblage](docs/wiring.md).

## Deux
"""


def write(root: Path, rel: str, text: str) -> None:
    p = root / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    write(tmp_path, "firmware/platformio.ini", PLATFORMIO.replace("{robot_flags}", ""))
    write(tmp_path, "firmware/README.md", FIRMWARE_README)
    write(tmp_path, "firmware/src/face/face_app.cpp", "#define MARVIN_TFT_CS 1     // D0\n")
    write(tmp_path, "firmware/src/audio/audio_app.cpp", "constexpr int AMP_BCLK = 8;    // D9\n")
    write(tmp_path, "firmware/src/sensors_real.cpp",
          "#if X\nconstexpr int LD2450_RX = 26;\n#else\nconstexpr int LD2450_RX = 5;\n#endif\n")
    write(tmp_path, "docs/wiring.md", WIRING)
    write(tmp_path, "docs/wiring-bench.md", BENCH)
    write(tmp_path, "docs/protocol.md", PROTOCOL)
    write(tmp_path, "docs/images/robot.png", "")
    write(tmp_path, "README.md", README_EN)
    write(tmp_path, "README.fr.md", README_FR)
    write(tmp_path, doclint.PERSONA, PERSONA)
    write(tmp_path, doclint.APP_DIR + "/index.html", "<h3>Lidar</h3><p>No lidar connected: a USB lidar shows here.</p>\n")
    for name, text in JAVA.items():
        write(tmp_path, f"{ROBOT}/{name}", text)
    return tmp_path


def lint(root: Path, rule: str) -> list[str]:
    return [str(f) for f in doclint.run(root, [rule])]


def edit(root: Path, rel: str, old: str, new: str) -> None:
    p = root / rel
    text = p.read_text(encoding="utf-8")
    assert old in text, old
    p.write_text(text.replace(old, new), encoding="utf-8")


@pytest.mark.parametrize("rule", doclint.RULES)
def test_a_consistent_repository_passes(repo: Path, rule: str) -> None:
    assert lint(repo, rule) == []


def test_main_exit_status(repo: Path, capsys) -> None:
    assert doclint.main(["--root", str(repo)]) == 0
    edit(repo, "docs/wiring.md", "XIAO **D0**", "XIAO **D1**")
    assert doclint.main(["--root", str(repo), "--rule", "pin-tables"]) == 1
    assert "pin-tables" in capsys.readouterr().out


# ---------------------------------------------------------------------------------------------- pin-tables


def test_a_wrong_xiao_pin_is_found(repo: Path) -> None:
    edit(repo, "docs/wiring.md", "XIAO **D0**", "XIAO **D1**")
    out = lint(repo, "pin-tables")
    assert len(out) == 1 and "docs/wiring.md:5" in out[0] and "MARVIN_TFT_CS=1" in out[0]


def test_a_build_flag_overrides_the_source_pin(repo: Path) -> None:
    edit(repo, "firmware/platformio.ini", "    -DMARVIN_HAS_SCREEN\n", "    -DMARVIN_HAS_SCREEN\n    -DMARVIN_TFT_CS=2\n")
    out = lint(repo, "pin-tables")
    assert any("docs/wiring.md:5" in f for f in out) and any("firmware/README.md:19" in f for f in out)


def test_a_new_flag_pin_must_be_documented_and_not_spare(repo: Path) -> None:
    edit(repo, "firmware/platformio.ini", "    -DMARVIN_HAS_SCREEN\n",
         "    -DMARVIN_HAS_SCREEN\n    -DMARVIN_BUTTON_TALK=44   ; the knob on D7\n")
    out = lint(repo, "pin-tables")
    assert any("firmware/README.md:17" in f and "spare" in f for f in out)
    assert any("docs/wiring.md" in f and "MARVIN_BUTTON_TALK=44" in f and "no pin table" in f for f in out)
    edit(repo, "firmware/README.md", "| Spare | D7 | 44 |", "| Knob (Talk) | D7 | 44 |")
    edit(repo, "docs/wiring.md", "| 2 | Amp", "| 3 | Knob switch | XIAO **D7** |\n| 2 | Amp")
    assert lint(repo, "pin-tables") == []


def test_bench_tables_follow_the_env_named_in_their_heading(repo: Path) -> None:
    edit(repo, "docs/wiring-bench.md", "| **D25** | 25 |", "| **D26** | 26 |")
    out = lint(repo, "pin-tables")
    assert len(out) == 2  # the row is wrong, and so MARVIN_BUZZER_PIN=25 is documented nowhere
    assert all("docs/wiring-bench.md" in f for f in out)


def test_commented_out_flags_do_not_count(repo: Path) -> None:
    envs = doclint.read_platformio(repo / "firmware/platformio.ini")
    assert "MARVIN_STATUS_RGB" not in envs["esp32dev"].flags
    assert envs["xiao_esp32s3_ota"].extends == "xiao_esp32s3"
    assert envs["xiao_esp32s3_ota"].board == "seeed_xiao_esp32s3"


# ---------------------------------------------------------------------------------------------- env-table


def test_env_table(repo: Path) -> None:
    edit(repo, "firmware/README.md", "| `native` | tests |", "| `nativ` | tests |")
    out = lint(repo, "env-table")
    assert any("`nativ` is not an [env:*]" in f for f in out)
    assert any("[env:native]" in f for f in out)


def test_an_ota_twin_must_be_named(repo: Path) -> None:
    edit(repo, "firmware/README.md", "The `xiao_esp32s3_ota` env is its twin over Wi-Fi.", "")
    assert any("xiao_esp32s3_ota" in f for f in lint(repo, "env-table"))


def test_a_pio_command_naming_an_unknown_env(repo: Path) -> None:
    write(repo, "docs/tools.md", "# Tools\n\n```bash\npio run -e xiao_robot -t upload\n```\n")
    out = lint(repo, "env-table")
    assert out == ["docs/tools.md:4: [env-table] env `xiao_robot` is not in firmware/platformio.ini"]


# ---------------------------------------------------------------------------------------------- protocol-table


def test_protocol_mismatches(repo: Path) -> None:
    edit(repo, "docs/protocol.md", "| `0x86` | `SOUND` | host → robot |", "| `0x87` | `SOUND` | robot → host |")
    edit(repo, "docs/protocol.md", "| 3 | `0x08` | Has buttons |", "| 3 | `0x08` | Has a camera |")
    edit(repo, "docs/protocol.md", "| 2 | `beep` | 120 ms |", "| 3 | `beep` | 100 ms |")
    edit(repo, "docs/protocol.md", "2 = Mute", "3 = Mute")
    edit(repo, "docs/protocol.md", "held 1.5 s", "held 2 s")
    out = "\n".join(lint(repo, "protocol-table"))
    for needle in ("SOUND is 0x86", "SOUND goes HOST_TO_ROBOT", "FLAG_BUTTONS", "sound beep is 2",
                   "sound beep lasts 120 ms", "button 'mute' is 2", "BUTTON_LONG_PRESS_MS = 1500"):
        assert needle in out, needle


def test_a_message_missing_from_the_doc(repo: Path) -> None:
    edit(repo, f"{ROBOT}/MessageType.java", "SOUND(0x86, Direction.HOST_TO_ROBOT);",
         "SOUND(0x86, Direction.HOST_TO_ROBOT),\n    HEAD_CTRL(0x87, Direction.HOST_TO_ROBOT);")
    assert any("HEAD_CTRL (0x87)" in f for f in lint(repo, "protocol-table"))


# ---------------------------------------------------------------------------------------------- relative-links


def test_relative_links(repo: Path) -> None:
    edit(repo, "docs/wiring.md", "# Wiring\n", "# Wiring\n\n[up](../README.md#one) [gone](missing.md) "
         "[bad anchor](protocol.md#nowhere) ![x](images/nope.png) `[code](not-checked.md)`\n")
    out = lint(repo, "relative-links")
    assert len(out) == 3
    assert any("missing.md: no such file" in f for f in out)
    assert any("#nowhere" in f for f in out)
    assert any("image images/nope.png" in f for f in out)


def test_links_in_code_blocks_are_ignored(repo: Path) -> None:
    edit(repo, "docs/wiring.md", "# Wiring\n", "# Wiring\n\n```\n[x](nope.md)\n```\n")
    assert lint(repo, "relative-links") == []


# ---------------------------------------------------------------------------------------------- readme-sync


def test_readme_sync(repo: Path) -> None:
    edit(repo, "README.fr.md", "## Deux\n", "")
    edit(repo, "README.fr.md", "![Le robot](docs/images/robot.png)", "")
    out = lint(repo, "readme-sync")
    assert any("1 level-2 headings, 2 in README.md" in f for f in out)
    assert any("images: docs/images/robot.png is not in README.fr.md" in f for f in out)


# ---------------------------------------------------------------------------------------------- no-lidar-claim


@pytest.mark.parametrize("sentence, flagged", [
    ("Marvin sees the room with its lidar.", True),
    ("The lidar map shows the desk.", True),
    ("A 360-degree lidar sits on top.", True),
    ("Rev F has no lidar.", False),
    ("Without the lidar it costs less.", False),
    ("A lidar on the USB sensor bridge shows here.", False),
    ("The bench board simulates the lidar.", False),
    ("Le robot n'a pas de lidar.", False),
    ("The `LIDAR` message carries raw packets.", False),
])
def test_lidar_sentences_in_docs(repo: Path, sentence: str, flagged: bool) -> None:
    write(repo, "docs/senses.md", f"# Senses\n\n{sentence}\n")
    assert bool(lint(repo, "no-lidar-claim")) == flagged


def test_history_and_bench_sections_are_skipped(repo: Path) -> None:
    write(repo, "docs/senses.md", "# Senses\n\n## History\n\nThe lidar spun on top.\n\n"
          "- [x] Rev D: D800 lidar as the reference\n")
    assert lint(repo, "no-lidar-claim") == []


def test_the_persona_may_only_deny_a_lidar(repo: Path) -> None:
    edit(repo, doclint.PERSONA, "You have no lidar and nothing that turns.", "You have a 360-degree lidar on top.")
    out = lint(repo, "no-lidar-claim")
    assert len(out) == 1 and "the persona" in out[0]


def test_the_app_may_show_a_lidar_but_not_give_the_robot_one(repo: Path) -> None:
    write(repo, doclint.APP_DIR + "/senses.js", 'const t = "A lidar on the robot shows its scan here.";\n')
    out = lint(repo, "no-lidar-claim")
    assert len(out) == 1 and "senses.js:1" in out[0]


def test_a_waiver_on_the_line_above(repo: Path) -> None:
    write(repo, "docs/senses.md", "# Senses\n\n<!-- doclint: allow no-lidar-claim -->\nThe lidar map.\n")
    assert lint(repo, "no-lidar-claim") == []
