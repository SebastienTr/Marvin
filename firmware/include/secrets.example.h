// Copy this file to secrets.h (git-ignored) and fill in your Wi-Fi.
// SPDX-License-Identifier: MIT
#pragma once

#define WIFI_SSID "your-network"
#define WIFI_PASSWORD "your-password"

// Over-the-air updates (envs built with -DMARVIN_HAS_OTA): a password you choose. The *_ota envs
// read it from here when uploading (scripts/ota_auth.py, see docs/tools.md). Letters, digits and
// - _ . ! @ only. Leave it out and anyone on your network can flash the robot.
#define OTA_PASSWORD "change-me"
