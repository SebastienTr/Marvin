// Over-the-air updates: the main firmware's ArduinoOTA wrapper (firmware/src/ota.cpp, found through
// -I../src), built here too so the kit answers as marvin-xxxxxx.local exactly like the robot.
// Only compiled into the envs with -DMARVIN_HAS_OTA. OTA_PASSWORD comes from this project's include/secrets.h.
// SPDX-License-Identifier: MIT
#include "ota.cpp"
