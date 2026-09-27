// SPDX-License-Identifier: MIT
//
// Over-the-air updates, see ota.h.
#include "ota.h"

#if defined(MARVIN_HAS_OTA) && defined(ARDUINO) && (defined(ESP32) || defined(ESP8266))

#include <Arduino.h>
#include <ArduinoOTA.h>
#if defined(ESP8266)
#include <ESP8266WiFi.h>
#else
#include <WiFi.h>
#endif

#if __has_include("secrets.h")
#include "secrets.h"
#endif

namespace ota {

namespace {

char name[16] = "marvin";
Callbacks cb;
bool busy = false;
int last_percent = -1;

const char *error_name(ota_error_t e) {
  switch (e) {
    case OTA_AUTH_ERROR: return "authentication failed (wrong or missing OTA_PASSWORD)";
    case OTA_BEGIN_ERROR: return "could not start (image too large for the OTA partition?)";
    case OTA_CONNECT_ERROR: return "could not connect back to the uploader";
    case OTA_RECEIVE_ERROR: return "receive failed";
    case OTA_END_ERROR: return "could not finish (image corrupt or incomplete)";
    default: return "unknown error";
  }
}

}  // namespace

void begin(const uint8_t mac[6], const Callbacks &callbacks) {
  cb = callbacks;
  snprintf(name, sizeof(name), "marvin-%02x%02x%02x", mac[3], mac[4], mac[5]);
  ArduinoOTA.setHostname(name);
#if defined(OTA_PASSWORD)
  ArduinoOTA.setPassword(OTA_PASSWORD);
  const bool locked = OTA_PASSWORD[0] != '\0';
#else
  const bool locked = false;
#endif

  ArduinoOTA.onStart([] {
    busy = true;
    last_percent = -1;
    Serial.printf("OTA: receiving %s\n", ArduinoOTA.getCommand() == U_FLASH ? "firmware" : "filesystem");
    if (cb.on_start) cb.on_start();
  });
  ArduinoOTA.onProgress([](unsigned int done, unsigned int total) {
    const int pct = total ? (int)((uint64_t)done * 100 / total) : 0;
    if (pct == last_percent) return;
    last_percent = pct;
    if (pct % 10 == 0) Serial.printf("OTA: %d%%\n", pct);
    if (cb.on_progress) cb.on_progress((uint8_t)pct);
  });
  ArduinoOTA.onEnd([] {
    busy = false;
    Serial.println("OTA: done, rebooting");
    Serial.flush();
    if (cb.on_end) cb.on_end(true);
  });
  ArduinoOTA.onError([](ota_error_t e) {
    busy = false;
    Serial.printf("OTA: failed, %s\n", error_name(e));
    if (cb.on_end) cb.on_end(false);
  });

  ArduinoOTA.begin();   // also starts mDNS as <name>.local
  Serial.printf("OTA: ready as %s.local (%s), port %d%s\n", name, WiFi.localIP().toString().c_str(),
#if defined(ESP8266)
                8266,
#else
                3232,
#endif
                locked ? ", password protected" : ", NO PASSWORD: set OTA_PASSWORD in include/secrets.h");
}

void handle() { ArduinoOTA.handle(); }

const char *hostname() { return name; }

bool in_progress() { return busy; }

}  // namespace ota

#endif
