// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation;

import java.util.Map;

/**
 * One audio device the voice hears and speaks with, and how: the computer's microphone and speaker, or a robot's
 * (its PDM microphone and I2S speaker). The voice is given the device the route chose (only here: the voice sidecar
 * never decides the route) with this profile, and swaps it under the running voice when a robot links or leaves.
 *
 * @param kind              {@code computer} or {@code robot}
 * @param robot             with {@code robot}: the robot's device name ({@code ""} until one is chosen)
 * @param duplex            keep listening while speaking (only with echo cancellation, or a headset)
 * @param echoTailS         half duplex: still deaf this long after the speaker really stopped
 * @param outputLatencyS    from a sample queued to it being heard, added to the tail; 0: what the speaker reports
 * @param vadMinDbfs        a 20 ms frame quieter than this is never speech
 * @param speechMinDbfs     an utterance whose loudest half is quieter than this is "too quiet"
 * @param vadAggressiveness WebRTC VAD, 0 to 3
 * @param vadThreshold      Silero VAD: a frame is speech from this probability (0.5; the voice calibration may raise it
 *                          in a room whose noise sounds like speech)
 * @param hasEarcons        it can play the voice's short sounds (the computer's speaker, the robot's built-in ones)
 * @param hasAec            it cancels its own echo (none does yet)
 * @param inputDevice       computer: the microphone ({@code ""}: the system's default)
 * @param outputDevice      computer: the speaker
 */
public record AudioDeviceProfile(String kind, String robot, boolean duplex, double echoTailS, double outputLatencyS,
                                 double vadMinDbfs, double speechMinDbfs, int vadAggressiveness, boolean hasEarcons,
                                 boolean hasAec, String inputDevice, String outputDevice, double vadThreshold) {

    public static final String COMPUTER = "computer";
    public static final String ROBOT = "robot";

    /**
     * The computer's defaults: speakers some way from the microphone, half duplex with the tail the voice always had
     * (0.8 s, plus what the speaker reports as its output latency).
     */
    public static final AudioDeviceProfile COMPUTER_DEFAULT = new AudioDeviceProfile(COMPUTER, "", false, 0.8, 0.0,
            -60.0, -50.0, 2, true, false, "", "", 0.5);
    /**
     * The robot's defaults (docs/audio.md): the speaker sits in the same body as the microphone and nothing cancels
     * the echo, so half duplex; the robot's player reports its own end, so a shorter tail (0.5 s) and only the
     * Wi-Fi hop as latency; its microphone has 12 dB of gain and the lidar's motor turns a few centimetres away,
     * so a higher floor (-55 dBFS per frame, -45 dBFS for an utterance) and the strictest VAD. Starting points, to
     * tune on the hardware.
     */
    public static final AudioDeviceProfile ROBOT_DEFAULT = new AudioDeviceProfile(ROBOT, "", false, 0.5, 0.05,
            -55.0, -45.0, 3, true, false, "", "", 0.5);

    public AudioDeviceProfile {
        robot = robot == null ? "" : robot;
        inputDevice = inputDevice == null ? "" : inputDevice;
        outputDevice = outputDevice == null ? "" : outputDevice;
        vadAggressiveness = Math.max(0, Math.min(3, vadAggressiveness));
        vadThreshold = vadThreshold > 0 ? Math.max(0.3, Math.min(0.95, vadThreshold)) : 0.5;
    }

    /** The profile without Silero's threshold (as before the voice calibration). */
    public AudioDeviceProfile(String kind, String robot, boolean duplex, double echoTailS, double outputLatencyS,
                              double vadMinDbfs, double speechMinDbfs, int vadAggressiveness, boolean hasEarcons,
                              boolean hasAec, String inputDevice, String outputDevice) {
        this(kind, robot, duplex, echoTailS, outputLatencyS, vadMinDbfs, speechMinDbfs, vadAggressiveness, hasEarcons,
                hasAec, inputDevice, outputDevice, 0.5);
    }

    /** {@code computer}, or the robot's name. */
    public String name() {
        return ROBOT.equals(kind) && !robot.isEmpty() ? robot : kind;
    }

    /** This profile for that robot. */
    public AudioDeviceProfile forRobot(String name) {
        return new AudioDeviceProfile(kind, name, duplex, echoTailS, outputLatencyS, vadMinDbfs, speechMinDbfs,
                vadAggressiveness, hasEarcons, hasAec, inputDevice, outputDevice, vadThreshold);
    }

    /**
     * {@code base} with what {@code m} (a {@code voice.json} {@code devices} entry) changes: {@code duplex},
     * {@code echo_tail_s}, {@code output_latency_s}, {@code vad_min_dbfs}, {@code speech_min_dbfs},
     * {@code vad_aggressiveness}, {@code vad_threshold}, {@code earcons}, {@code input_device}, {@code output_device} (the
     * voice calibration writes {@code vad_min_dbfs}, {@code speech_min_dbfs}, {@code vad_threshold} and a
     * {@code calibration} record, which the profile does not use). Values of the wrong type are ignored.
     */
    public static AudioDeviceProfile with(AudioDeviceProfile base, Map<?, ?> m) {
        if (m == null || m.isEmpty()) {
            return base;
        }
        return new AudioDeviceProfile(base.kind(), base.robot(),
                m.get("duplex") instanceof Boolean b ? b : base.duplex(),
                num(m.get("echo_tail_s"), base.echoTailS(), 0, 5),
                num(m.get("output_latency_s"), base.outputLatencyS(), 0, 2),
                num(m.get("vad_min_dbfs"), base.vadMinDbfs(), -100, 0),
                num(m.get("speech_min_dbfs"), base.speechMinDbfs(), -100, 0),
                (int) num(m.get("vad_aggressiveness"), base.vadAggressiveness(), 0, 3),
                m.get("earcons") instanceof Boolean b ? b : base.hasEarcons(), base.hasAec(),
                m.get("input_device") != null ? text(m.get("input_device")) : base.inputDevice(),
                m.get("output_device") != null ? text(m.get("output_device")) : base.outputDevice(),
                num(m.get("vad_threshold"), base.vadThreshold(), 0.3, 0.95));
    }

    /** A device as voice.json names it: its PortAudio number (written 2, never 2.0) or its name. */
    static String text(Object v) {
        if (v instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) {
            return String.valueOf(d.longValue());
        }
        return String.valueOf(v);
    }

    private static double num(Object v, double dflt, double min, double max) {
        if (v instanceof Number n && !(v instanceof Double d && d.isNaN())) {
            return Math.max(min, Math.min(max, n.doubleValue()));
        }
        return dflt;
    }
}
