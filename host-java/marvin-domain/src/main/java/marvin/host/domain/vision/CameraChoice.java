// SPDX-License-Identifier: MIT
package marvin.host.domain.vision;

import java.util.List;

/**
 * Which camera Marvin looks through (the {@code camera} voice setting, as {@code audio_route} chooses where it hears):
 * {@code auto} (the robot's when a robot with a camera is connected, else the computer's), {@code computer},
 * {@code robot}, or {@code off}. A camera that cannot take a picture now is not chosen, and the reason says why.
 */
public final class CameraChoice {
    public static final String AUTO = "auto";
    public static final String COMPUTER = "computer";
    public static final String ROBOT = "robot";
    public static final String OFF = "off";
    /** The setting's values, in the order the app shows them. */
    public static final List<String> SETTINGS = List.of(AUTO, COMPUTER, ROBOT, OFF);

    private CameraChoice() {
    }

    /**
     * A camera and whether it can take a picture now.
     *
     * @param name      {@link #COMPUTER} or {@link #ROBOT}
     * @param available it can take a picture now (as far as can be known without taking one)
     * @param detail    which camera it is, or why it cannot
     * @param fix       what to do when it cannot ({@code ""}: nothing to do)
     */
    public record Source(String name, boolean available, String detail, String fix) {
        public Source {
            detail = detail == null ? "" : detail;
            fix = fix == null ? "" : fix;
        }
    }

    /**
     * The camera chosen: {@code source} {@code null} when none can be used, {@code reason} and {@code fix} then say why
     * and what to do; otherwise {@code reason} names the camera.
     */
    public record Choice(String setting, String source, String reason, String fix) {
        public boolean available() {
            return source != null;
        }
    }

    /** The setting as stored: an unknown value is {@code auto}. */
    public static String setting(String value) {
        return value != null && SETTINGS.contains(value) ? value : AUTO;
    }

    /** The camera to use for {@code setting}, given what each camera says of itself. */
    public static Choice pick(String setting, Source computer, Source robot) {
        String s = setting(setting);
        return switch (s) {
            case OFF -> new Choice(s, null, "The camera is off", "Choose a camera in Marvin > Voice & models > Camera.");
            case COMPUTER -> use(s, computer);
            case ROBOT -> use(s, robot);
            default -> {
                if (robot != null && robot.available()) {
                    yield use(s, robot);
                }
                if (computer != null && computer.available()) {
                    yield use(s, computer);
                }
                String why = (robot == null ? "No robot with a camera is connected" : robot.detail())
                        + (computer == null ? "" : "; " + lower(computer.detail()));
                yield new Choice(s, null, why, computer == null ? "" : computer.fix());
            }
        };
    }

    private static Choice use(String setting, Source source) {
        if (source == null) {
            return new Choice(setting, null, "This camera is not supported here", "");
        }
        return source.available() ? new Choice(setting, source.name(), source.detail(), "")
                : new Choice(setting, null, source.detail(), source.fix());
    }

    private static String lower(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
