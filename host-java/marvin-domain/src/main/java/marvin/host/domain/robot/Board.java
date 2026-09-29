// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

import java.util.Map;
import java.util.Set;

/** The board ids a {@code HELLO} can carry, and what the host makes of them. */
public final class Board {
    public static final int D1_MINI = 1;
    public static final int ESP32_S3_DEVKITC = 2;
    public static final int XIAO_ESP32S3_SENSE = 3;
    /** The MR60BHA2 kit's own ESP32-C6, bridging the vital-signs radar: a device of its own. */
    public static final int MR60BHA2_KIT = 4;
    /** A classic ESP32 DevKit (ESP32-WROOM-32): no screen, no audio; simulated sensors on the bench. */
    public static final int ESP32_DEVKIT = 5;
    public static final int SIMULATOR = 255;

    /** Boards with the face screen: the host sends them {@code FACE_STATE} and {@code FACE_EVENT}. */
    public static final Set<Integer> SCREEN_BOARDS = Set.of(ESP32_S3_DEVKITC, XIAO_ESP32S3_SENSE);

    private static final Map<Integer, String> NAMES = Map.of(
            D1_MINI, "Wemos D1 mini (ESP8266)",
            ESP32_S3_DEVKITC, "ESP32-S3 DevKitC",
            XIAO_ESP32S3_SENSE, "XIAO ESP32S3 Sense",
            MR60BHA2_KIT, "MR60BHA2 kit (XIAO ESP32C6)",
            ESP32_DEVKIT, "ESP32 DevKit (ESP32-WROOM-32)",
            SIMULATOR, "simulator");

    private static final Map<Integer, String> LIDAR_MODELS = Map.of(1, "D500 (STL-19P)", 2, "D800 (STL-27L)");

    /** What a device is for the app. */
    public enum Role {
        ROBOT("robot", "Robot"), VITALS("vitals", "Vital signs radar"), SIMULATOR("simulator", "Simulated robot");

        private final String wireName;
        private final String label;

        Role(String wireName, String label) {
            this.wireName = wireName;
            this.label = label;
        }

        public String wireName() {
            return wireName;
        }

        public String label() {
            return label;
        }
    }

    private Board() {
    }

    /** The board's name, or {@code null} for an id this version does not know. */
    public static String name(int board) {
        return NAMES.get(board);
    }

    public static String lidarModel(int model) {
        return LIDAR_MODELS.get(model);
    }

    public static boolean hasScreen(int board) {
        return SCREEN_BOARDS.contains(board);
    }

    public static Role role(int board) {
        return switch (board) {
            case SIMULATOR -> Role.SIMULATOR;
            case MR60BHA2_KIT -> Role.VITALS;
            default -> Role.ROBOT;
        };
    }
}
