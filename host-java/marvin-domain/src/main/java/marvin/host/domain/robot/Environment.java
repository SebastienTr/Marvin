// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * {@code ENVIRONMENT} payload, 12 bytes: a device's room sensors (docs/protocol.md). Sensors (u8: {@link #PIR},
 * {@link #CLIMATE}, {@link #LIGHT}: the ones it has), flags (u8: motion, warming up, which readings are valid,
 * simulated), temperature (i16, 0.1 °C), humidity (u16, 0.1 %RH), light level (u16, 0..1000, higher is brighter),
 * light raw (u16, the ADC's 0..4095), seconds since the last motion (u16, 0 while there is motion, 0xFFFF: none since
 * the PIR warmed up). Invalid readings are {@code null} here and 0 on the wire. A longer payload keeps this prefix;
 * flag bits this host does not know are ignored.
 *
 * @param pir          has a PIR motion sensor
 * @param climate      has a temperature and humidity sensor (DHT11)
 * @param lightSensor  has a light sensor (LDR)
 * @param motion       the PIR sees motion now
 * @param warmingUp    the PIR is still warming up (about a minute after power-on): no motion reported
 * @param temperatureC °C, or {@code null}
 * @param humidity     %RH, or {@code null}
 * @param light        0 (dark) .. 1 (full scale), or {@code null}
 * @param lightRaw     the ADC reading, or {@code null}
 * @param motionAgeS   seconds since the motion ended, 0 while it lasts; {@code null}: none since the PIR warmed up
 * @param simulated    the readings are simulated
 */
public record Environment(boolean pir, boolean climate, boolean lightSensor, boolean motion, boolean warmingUp,
                          Double temperatureC, Double humidity, Double light, Integer lightRaw, Integer motionAgeS,
                          boolean simulated) {
    public static final int SIZE = 12;
    /** Sensors byte. */
    public static final int PIR = 0x01;
    public static final int CLIMATE = 0x02;
    public static final int LIGHT = 0x04;
    /** Flags byte. */
    public static final int MOTION = 0x01;
    public static final int WARMING_UP = 0x02;
    public static final int TEMPERATURE_VALID = 0x04;
    public static final int HUMIDITY_VALID = 0x08;
    public static final int LIGHT_VALID = 0x10;
    public static final int SIMULATED = 0x20;
    /** Motion age: none since the PIR warmed up. */
    public static final int NO_MOTION = 0xFFFF;

    private static final Map<Integer, String> SENSOR_NAMES = Map.of(PIR, "pir", CLIMATE, "climate", LIGHT, "light");

    public byte[] encode() {
        int sensors = (pir ? PIR : 0) | (climate ? CLIMATE : 0) | (lightSensor ? LIGHT : 0);
        int flags = (motion ? MOTION : 0) | (warmingUp ? WARMING_UP : 0)
                | (temperatureC != null ? TEMPERATURE_VALID : 0) | (humidity != null ? HUMIDITY_VALID : 0)
                | (light != null ? LIGHT_VALID : 0) | (simulated ? SIMULATED : 0);
        long t = temperatureC == null ? 0 : Math.max(-32768, Math.min(32767, Wire.round(temperatureC * 10)));
        long h = humidity == null ? 0 : Math.max(0, Math.min(0xFFFF, Wire.round(humidity * 10)));
        long lv = light == null ? 0 : Math.max(0, Math.min(1000, Wire.round(light * 1000)));
        int raw = light == null || lightRaw == null ? 0 : Math.max(0, Math.min(0xFFFF, lightRaw));
        int age = motionAgeS == null ? NO_MOTION : Math.max(0, Math.min(0xFFFE, motionAgeS));
        ByteBuffer b = Wire.le(SIZE);
        b.put((byte) sensors).put((byte) flags).putShort((short) t).putShort((short) h).putShort((short) lv)
                .putShort((short) raw).putShort((short) age);
        return b.array();
    }

    /** Decodes a payload (extra bytes are ignored); a {@link ProtocolException} if it is too short. */
    public static Environment decode(byte[] payload) {
        if (payload.length < SIZE) {
            throw new ProtocolException("ENVIRONMENT too short");
        }
        ByteBuffer b = Wire.le(payload);
        int sensors = b.get() & 0xff;
        int flags = b.get() & 0xff;
        short t = b.getShort();
        int h = b.getShort() & 0xffff;
        int lv = b.getShort() & 0xffff;
        int raw = b.getShort() & 0xffff;
        int age = b.getShort() & 0xffff;
        boolean lightOk = (flags & LIGHT_VALID) != 0;
        return new Environment((sensors & PIR) != 0, (sensors & CLIMATE) != 0, (sensors & LIGHT) != 0,
                (flags & MOTION) != 0, (flags & WARMING_UP) != 0,
                (flags & TEMPERATURE_VALID) != 0 ? t / 10.0 : null, (flags & HUMIDITY_VALID) != 0 ? h / 10.0 : null,
                lightOk ? lv / 1000.0 : null, lightOk ? raw : null, age == NO_MOTION ? null : age,
                (flags & SIMULATED) != 0);
    }

    /** The PIR can be trusted: fitted and warm. */
    public boolean motionKnown() {
        return pir && !warmingUp;
    }

    /** The sensor bits by name: {@code pir}, {@code climate}, {@code light}. */
    public static Map<Integer, String> sensorNames() {
        return SENSOR_NAMES;
    }
}
