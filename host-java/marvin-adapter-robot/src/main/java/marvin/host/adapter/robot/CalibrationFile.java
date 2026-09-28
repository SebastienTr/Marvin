// SPDX-License-Identifier: MIT
package marvin.host.adapter.robot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import marvin.host.domain.robot.SensorCalibration;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the per-robot calibration the Python host writes ({@code calibration.py}):
 * {@code $MARVIN_CONFIG_DIR/calibration.json}, else {@code $XDG_CONFIG_HOME/marvin/calibration.json},
 * else {@code ~/.config/marvin/calibration.json}.
 *
 * <pre>
 * {"version": 1, "lidar": {"yaw_deg": 0.0}, "ld2450": {"x_sign": 1, "speed_sign": -1}, ...}
 * </pre>
 *
 * No file, or an unreadable one: the defaults (a warning for an unreadable one). This host only reads
 * it; {@code marvin-host calibrate lidar --save} writes it.
 */
public final class CalibrationFile {
    private static final Logger log = LoggerFactory.getLogger(CalibrationFile.class);
    public static final String FILE_NAME = "calibration.json";

    private CalibrationFile() {
    }

    /** The configuration directory, from the environment given (normally {@link System#getenv()}). */
    public static Path configDir(Map<String, String> env, String userHome) {
        String dir = env.get("MARVIN_CONFIG_DIR");
        if (dir != null && !dir.isBlank()) {
            return Path.of(dir.startsWith("~/") ? userHome + dir.substring(1) : dir);
        }
        String xdg = env.get("XDG_CONFIG_HOME");
        return (xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(userHome, ".config")).resolve("marvin");
    }

    public static Path defaultPath() {
        return configDir(System.getenv(), System.getProperty("user.home")).resolve(FILE_NAME);
    }

    public static SensorCalibration load(Path path) {
        String text;
        try {
            text = Files.readString(path);
        } catch (NoSuchFileException e) {
            return SensorCalibration.DEFAULT;
        } catch (IOException e) {
            log.warn("ignoring calibration file {}: {}", path, e.getMessage());
            return SensorCalibration.DEFAULT;
        }
        try {
            JsonNode d = new ObjectMapper().readTree(text);
            JsonNode lidar = d.path("lidar");
            JsonNode radar = d.path("ld2450");
            SensorCalibration c = new SensorCalibration(
                    lidar.path("yaw_deg").isMissingNode() ? 0.0 : number(lidar.get("yaw_deg")),
                    radar.path("x_sign").isMissingNode() ? 1 : (int) number(radar.get("x_sign")),
                    radar.path("speed_sign").isMissingNode() ? -1 : (int) number(radar.get("speed_sign")));
            log.info("calibration from {}: lidar yaw {} deg, LD2450 x sign {}", path,
                    String.format(java.util.Locale.ROOT, "%.1f", c.lidarYawDeg()), c.ld2450XSign());
            return c;
        } catch (JacksonException | IllegalArgumentException e) {
            log.warn("ignoring calibration file {}: {}", path, e.getMessage());
            return SensorCalibration.DEFAULT;
        }
    }

    private static double number(JsonNode n) {
        if (n == null || !n.isNumber()) {
            throw new IllegalArgumentException("not a number: " + n);
        }
        return n.asDouble();
    }
}
