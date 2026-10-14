// Copy this file to secrets.h (git-ignored) and fill in your Wi-Fi.
// SPDX-License-Identifier: MIT
#pragma once

#define WIFI_SSID "your-network"
#define WIFI_PASSWORD "your-password"

// Over-the-air updates: a password you choose (letters, digits and - _ . ! @ only). The xiao_esp32c6_ota
// env reads it from here when uploading. Leave it out and anyone on your network can flash the kit.
#define OTA_PASSWORD "change-me"
