"""PlatformIO post-script for the *_ota envs: gives espota the robot's OTA password.

It reads OTA_PASSWORD from include/secrets.h, the same line the firmware is built with, so there
is nothing to export. It must run as a post-script: the platform's espota setup replaces the upload
flags. MARVIN_OTA_PASSWORD in the environment still wins when set (CI, another robot).

SPDX-License-Identifier: MIT
"""
import os
import re

Import("env")  # noqa: F821 - provided by PlatformIO

DEFINE = re.compile(r'^\s*#\s*define\s+OTA_PASSWORD\s+"((?:[^"\\]|\\.)*)"', re.M)


def ota_password(project_dir):
    if os.environ.get("MARVIN_OTA_PASSWORD"):
        return os.environ["MARVIN_OTA_PASSWORD"]
    path = os.path.join(project_dir, "include", "secrets.h")
    try:
        with open(path, encoding="utf-8") as f:
            m = DEFINE.search(f.read())
    except OSError:
        return ""
    return re.sub(r"\\(.)", r"\1", m.group(1)) if m else ""


password = ota_password(env.subst("$PROJECT_DIR"))  # noqa: F821
if password and re.search(r"[\s\"'`$\\]", password):
    # the upload command goes through a shell, which would mangle these
    print("OTA: OTA_PASSWORD must not contain spaces, quotes, backslashes or $ (use letters, digits, - _ . ! @)")
    env.Exit(1)  # noqa: F821
if password:
    env.Append(UPLOADERFLAGS=["--auth=" + password])  # noqa: F821
else:
    print("OTA: no OTA_PASSWORD in include/secrets.h, uploading without a password")
