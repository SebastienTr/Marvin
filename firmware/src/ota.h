// SPDX-License-Identifier: MIT
//
// Over-the-air firmware updates (ArduinoOTA), so a closed robot can be flashed over Wi-Fi.
// Built only with -DMARVIN_HAS_OTA (platformio.ini) on an Arduino target (ESP32 or ESP8266).
//
//   setup(), once Wi-Fi is up:  ota::begin(mac, callbacks);     // mac = WiFi.macAddress(mac)
//   loop():                     ota::handle();
//
// The robot then answers on mDNS as `marvin-<last 3 MAC bytes, hex>.local`, the same name the host
// prints (`Hello.device_name`). Upload with `pio run -e xiao_esp32s3_ota -t upload --upload-port <ip>`
// (see docs/tools.md). If include/secrets.h defines OTA_PASSWORD, uploads must give it
// (espota --auth); without it anyone on the network can flash the robot, and a warning is printed.
//
// An update runs inside ota::handle(): the loop is blocked until it ends (the sensors stop sending,
// the face task keeps running on the other core). On success the board reboots into the new image.
// Progress goes to Serial; the callbacks let main.cpp show it (face, LED, a LOG to the host).
#pragma once

#include <stdint.h>

#if defined(MARVIN_HAS_OTA) && defined(ARDUINO) && (defined(ESP32) || defined(ESP8266))

namespace ota {

struct Callbacks {
  void (*on_start)() = nullptr;               // an upload was accepted and is about to be written
  void (*on_progress)(uint8_t percent) = nullptr;   // called when the percentage changes
  void (*on_end)(bool ok) = nullptr;          // finished (ok: rebooting into it) or failed
};

// Start the OTA service and mDNS. `mac` is the Wi-Fi station MAC (6 bytes). Call after Wi-Fi is up.
void begin(const uint8_t mac[6], const Callbacks &callbacks = Callbacks());

// Serve OTA requests; call on every loop() iteration.
void handle();

// "marvin-xxxxxx", valid after begin().
const char *hostname();

// True while an update is being received.
bool in_progress();

}  // namespace ota

#endif
