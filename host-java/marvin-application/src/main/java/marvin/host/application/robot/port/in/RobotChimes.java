// SPDX-License-Identifier: MIT
package marvin.host.application.robot.port.in;

/**
 * Built-in sounds ({@code SOUND}, {@link marvin.host.domain.robot.HostMessages#SOUNDS}) for the robots that play
 * them on a buzzer (docs/wiring-bench.md): the robots with a speaker hear the voice's own sounds instead.
 */
public interface RobotChimes {

    /** Plays {@code sound} on every connected robot with a buzzer and no speaker. Unknown names are ignored. */
    void chime(String sound);

    /** Plays {@code sound} on the connected device of that name, if it has a buzzer or a speaker. */
    void chime(String device, String sound);
}
