// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.port.out;

/**
 * The voice's robot route (docs/design.md 4.3): the robots that have a microphone and a speaker, their
 * microphone frames in, and what the voice plays on them out through a {@link RobotSpeaker}.
 */
public interface VoiceRobotAudio {

    /** A robot connected ({@code hasAudio}: it has a microphone and a speaker) or left. */
    void robotLink(String device, boolean connected, boolean hasAudio);

    /** Whether a robot with a microphone and a speaker is connected. */
    boolean robotWithAudio();

    /** A robot's microphone frame, relayed as it came (same samples, sample index and robot clock). */
    void robotMic(String device, long sampleIndex, long robotTimeUs, short[] pcm);

    /** Where the voice's robot-bound audio goes. */
    void setSpeaker(RobotSpeaker speaker);

    /** The robot-bound audio: speaker frames, audio controls and sounds, by device name. */
    interface RobotSpeaker {
        void speaker(String device, int stream, long sampleIndex, short[] pcm);

        void control(String device, int command, int argument);

        void sound(String device, int id);
    }
}
