// SPDX-License-Identifier: MIT
package marvin.host.application.robot;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import marvin.host.application.robot.port.in.RobotChimes;
import marvin.host.application.robot.port.in.RobotLinkQuery;
import marvin.host.application.robot.port.out.RobotOutbound;
import marvin.host.domain.robot.Device;
import marvin.host.domain.robot.HostMessages;
import marvin.host.domain.robot.MessageType;

/**
 * {@code SOUND} to the robots with a buzzer ({@code HELLO} flag {@code FLAG_BUZZER}, the bench board): the voice's
 * moments as chimes, and a robot's own feedback. Robots with a speaker ({@code FLAG_AUDIO}) are left to the voice,
 * which plays its sounds there itself.
 */
public final class BuzzerChimeService implements RobotChimes {
    private final RobotLinkQuery link;
    private final RobotOutbound out;
    private final AtomicLong sent = new AtomicLong();

    public BuzzerChimeService(RobotLinkQuery link, RobotOutbound out) {
        this.link = Objects.requireNonNull(link, "link");
        this.out = Objects.requireNonNull(out, "out");
    }

    /** The connected robots with a buzzer and no speaker. */
    public List<Device> buzzers() {
        return link.connected().stream().filter(d -> d.hello().hasBuzzer() && !d.hello().hasAudio()).toList();
    }

    @Override
    public void chime(String sound) {
        Integer id = HostMessages.SOUNDS.get(sound);
        if (id == null) {
            return;
        }
        for (Device d : buzzers()) {
            out.send(d, MessageType.SOUND, HostMessages.sound(id));
            sent.incrementAndGet();
        }
    }

    @Override
    public void chime(String device, String sound) {
        Integer id = HostMessages.SOUNDS.get(sound);
        if (id == null) {
            return;
        }
        List<Device> all = link.connected();
        for (int i = all.size() - 1; i >= 0; i--) {     // the one that spoke last, as RobotAudioService does
            Device d = all.get(i);
            if (d.name().equals(device)) {
                if (d.hello().hasBuzzer() || d.hello().hasAudio()) {
                    out.send(d, MessageType.SOUND, HostMessages.sound(id));
                    sent.incrementAndGet();
                }
                return;
            }
        }
    }

    public long sent() {
        return sent.get();
    }
}
