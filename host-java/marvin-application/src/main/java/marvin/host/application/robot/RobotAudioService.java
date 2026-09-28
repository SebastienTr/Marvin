// SPDX-License-Identifier: MIT
package marvin.host.application.robot;

import java.util.List;
import java.util.logging.Logger;

import marvin.host.application.conversation.port.out.VoiceRobotAudio;
import marvin.host.application.robot.port.in.RobotAudioIn;
import marvin.host.application.robot.port.in.RobotLinkQuery;
import marvin.host.application.robot.port.out.LinkNoticeListener;
import marvin.host.application.robot.port.out.RobotOutbound;
import marvin.host.domain.robot.AudioOut;
import marvin.host.domain.robot.Device;
import marvin.host.domain.robot.DeviceMonitor;
import marvin.host.domain.robot.HostMessages;
import marvin.host.domain.robot.MessageType;
import marvin.host.domain.robot.SensorFrame;

/**
 * The robot's audio between the robot link and the voice (docs/design.md 4.3): {@code AUDIO_IN} relayed as it
 * comes, the voice's speaker frames, audio controls and sounds sent as {@code AUDIO_OUT}, {@code AUDIO_CTRL}
 * and {@code SOUND}, and the robots with audio (their {@code HELLO} flag) told to the voice as they connect
 * and leave. Only ports: the robot gateway and the voice can each move to their own process.
 */
public final class RobotAudioService implements RobotAudioIn, LinkNoticeListener, VoiceRobotAudio.RobotSpeaker {
    private static final Logger log = Logger.getLogger("marvin.host.robot");
    private final RobotOutbound out;
    private final RobotLinkQuery devices;
    private final VoiceRobotAudio voice;
    private final Runnable changed;

    /** {@code changed}: a robot with audio connected or the last one left. */
    public RobotAudioService(RobotOutbound out, RobotLinkQuery devices, VoiceRobotAudio voice, Runnable changed) {
        this.out = out;
        this.devices = devices;
        this.voice = voice;
        this.changed = changed;
        voice.setSpeaker(this);
    }

    @Override
    public void heard(SensorFrame.AudioChunk a) {
        voice.robotMic(a.device().name(), a.index(), a.tUs(), a.pcm());
    }

    @Override
    public void onNotice(DeviceMonitor.Notice n) {
        switch (n) {
            case DeviceMonitor.Notice.Connected c -> linked(c.device().name(), true, c.device().audio());
            case DeviceMonitor.Notice.Reconnected r -> linked(r.device().name(), true, r.device().audio());
            case DeviceMonitor.Notice.Disconnected d -> linked(d.device().name(), false, false);
            case DeviceMonitor.Notice.Log l -> {
            }
        }
    }

    private void linked(String name, boolean connected, boolean audio) {
        boolean before = voice.robotWithAudio();
        voice.robotLink(name, connected, audio);
        if (before != voice.robotWithAudio()) {
            changed.run();
        }
    }

    @Override
    public void speaker(String device, int stream, long sampleIndex, short[] pcm) {
        send(device, MessageType.AUDIO_OUT, new AudioOut(stream, sampleIndex, pcm).encode());
    }

    @Override
    public void control(String device, int command, int argument) {
        send(device, MessageType.AUDIO_CTRL, HostMessages.audioCtrl(command, argument));
    }

    @Override
    public void sound(String device, int id) {
        send(device, MessageType.SOUND, HostMessages.sound(id));
    }

    private void send(String name, MessageType type, byte[] payload) {
        Device d = device(name);
        if (d == null) {
            log.fine(() -> "audio for " + name + ", which is not connected");
            return;
        }
        out.send(d, type, payload);
    }

    /** The device of that name that spoke last (a robot that came back at another address replaces itself). */
    private Device device(String name) {
        List<Device> all = devices.connected();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).name().equals(name)) {
                return all.get(i);
            }
        }
        return null;
    }
}
