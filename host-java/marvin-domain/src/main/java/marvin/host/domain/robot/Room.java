// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

import marvin.host.domain.robot.event.RoomState;

/**
 * The room around the robot: the latest {@code ENVIRONMENT} of its room sensors and when it came, on the host's
 * clock, so the app and the voice know how fresh it is (the Python host's {@code room.Room}). With several devices
 * sending, the latest reading wins: one room. Thread-safe.
 */
public final class Room {
    private record Last(String device, Environment env, double at) {
    }

    private volatile Last last;

    /** A reading from {@code device}, received at {@code now} (host monotonic seconds). */
    public void record(Device device, Environment env, double now) {
        last = new Last(device.name(), env, now);
    }

    /** The room as of {@code now}, or {@code null} before any reading. */
    public RoomState state(double now) {
        Last l = last;
        if (l == null) {
            return null;
        }
        Environment e = l.env();
        double age = Math.max(0.0, now - l.at());
        Double motionAge = null;
        if (e.pir() && !e.warmingUp()) {
            motionAge = e.motion() ? Double.valueOf(0.0) : e.motionAgeS() == null ? null : e.motionAgeS() + age;
        }
        return new RoomState(age, l.device(), e.simulated(), e.pir(), e.pir() && e.warmingUp(),
                e.pir() && e.motion() && !e.warmingUp(), motionAge, e.climate(), e.temperatureC(), e.humidity(),
                e.lightSensor(), e.light());
    }
}
