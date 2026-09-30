// SPDX-License-Identifier: MIT
package marvin.host.domain.robot.event;

/**
 * The room around the robot as its room sensors last read it (the Python host's {@code room.RoomState}): a PIR,
 * a temperature and humidity sensor, a light sensor. An immutable snapshot; readings a sensor did not give are
 * {@code null}.
 *
 * @param ageS          seconds since the reading came, on the host's clock
 * @param device        the device that sent it
 * @param simulated     the readings are simulated
 * @param motionSensor  it has a PIR
 * @param warmingUp     the PIR is still warming up: it says nothing yet
 * @param motion        the PIR sees motion now
 * @param motionAgeS    seconds since the last motion (the robot's figure plus {@code ageS}), 0 while it lasts;
 *                      {@code null}: none seen
 * @param climateSensor it has a temperature and humidity sensor
 * @param temperatureC  °C
 * @param humidity      relative humidity, %
 * @param lightSensor   it has a light sensor
 * @param light         0 (dark) .. 1 (full scale)
 */
public record RoomState(double ageS, String device, boolean simulated, boolean motionSensor, boolean warmingUp,
                        boolean motion, Double motionAgeS, boolean climateSensor, Double temperatureC,
                        Double humidity, boolean lightSensor, Double light) {

    /** A reading this old is no longer the room "now": not given to the language model. */
    public static final double FRESH_S = 120.0;
    /** Motion this recent is "just now". */
    public static final double JUST_NOW_S = 10.0;
    static final double DARK_BELOW = 0.15;
    static final double DIM_BELOW = 0.45;

    public boolean fresh() {
        return ageS < FRESH_S;
    }

    /** {@code dark}, {@code dim} or {@code bright}; {@code null} without a light reading. */
    public String lightWord() {
        return lightWord(light);
    }

    public static String lightWord(Double light) {
        if (light == null) {
            return null;
        }
        return light < DARK_BELOW ? "dark" : light < DIM_BELOW ? "dim" : "bright";
    }
}
