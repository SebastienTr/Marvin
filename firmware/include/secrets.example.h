// Copy this file to secrets.h (git-ignored) and fill in your Wi-Fi.
// SPDX-License-Identifier: MIT
#pragma once

#define WIFI_SSID "your-network"
#define WIFI_PASSWORD "your-password"

// Over-the-air updates (envs built with -DMARVIN_HAS_OTA): uploads must give this password
// (espota --auth, see docs/tools.md). Leave it out and anyone on your network can flash the robot.
#define OTA_PASSWORD "change-me"
