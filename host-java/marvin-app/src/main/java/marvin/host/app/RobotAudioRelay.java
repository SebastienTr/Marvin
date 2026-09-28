// SPDX-License-Identifier: MIT
package marvin.host.app;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import marvin.host.adapter.robot.UdpRobotLink;
import marvin.host.adapter.sidecar.GrpcVoiceSidecar;
import marvin.host.application.robot.port.out.LinkNoticeListener;
import marvin.host.domain.robot.AudioOut;
import marvin.host.domain.robot.Device;
import marvin.host.domain.robot.DeviceMonitor;
import marvin.host.domain.robot.HostMessages;
import marvin.host.domain.robot.MessageType;
import marvin.host.domain.robot.SensorFrame;

/**
 * The robot's audio between the robot link and the voice sidecar (docs/design.md 4.3): {@code AUDIO_IN}
 * relayed as it comes (from the socket thread), the sidecar's speaker frames, audio controls and sounds sent
 * as {@code AUDIO_OUT}, {@code AUDIO_CTRL} and {@code SOUND}, and the robots with audio (HELLO flag) told to
 * the voice as they connect and leave.
 */
public final class RobotAudioRelay implements GrpcVoiceSidecar.RobotAudio, LinkNoticeListener, Consumer<SensorFrame.AudioChunk> {
    private static final Logger log = LoggerFactory.getLogger("marvin.host.robot");
    private final UdpRobotLink link;
    private final GrpcVoiceSidecar voice;
    private final Runnable changed;

    /** {@code changed}: a robot with audio connected or left. */
    public RobotAudioRelay(UdpRobotLink link, GrpcVoiceSidecar voice, Runnable changed) {
        this.link = link;
        this.voice = voice;
        this.changed = changed;
        voice.setRobotAudio(this);
        link.addAudioListener(this);
    }

    @Override
    public void accept(SensorFrame.AudioChunk a) {
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

    private Device device(String name) {
        for (Device d : link.processor().devices()) {
            if (d.name().equals(name)) {
                return d;
            }
        }
        return null;
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
            log.debug("audio for {}, which is not connected", name);
            return;
        }
        link.send(d, type, payload);
    }
}
