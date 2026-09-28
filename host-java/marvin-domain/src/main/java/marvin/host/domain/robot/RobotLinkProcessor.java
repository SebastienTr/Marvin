// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The receiving half of the robot link, without the socket: checks each datagram, keeps the device
 * registry and its counters, assembles lidar revolutions, parses the sensor frames, and says whether
 * to answer with a {@code HOST_ACK}. The behaviour of the Python {@code Receiver.handle}, datagram for
 * datagram, so a recording replays to the same frames and counters.
 *
 * <p>Not thread-safe: one thread (the socket thread, or a replay) calls {@link #handle}. The device
 * list can be read from any thread.
 */
public final class RobotLinkProcessor {
    private final Map<Endpoint, Device> devices = new ConcurrentHashMap<>();
    private final Map<Endpoint, Revolution> revolutions = new ConcurrentHashMap<>();
    private final Extrinsics extrinsics;

    /**
     * What to do with one datagram.
     *
     * @param acknowledge answer the sender with a {@code HOST_ACK} (a valid {@code HELLO})
     * @param device      the device it came from, or {@code null} (not Marvin's, or no {@code HELLO} yet)
     * @param frames      the sensor frames it carried, in order
     * @param error       why it was dropped as a whole, or {@code null}
     */
    public record Reception(boolean acknowledge, Device device, List<SensorFrame> frames, String error) {
        static final Reception IGNORED = new Reception(false, null, List.of(), null);

        static Reception dropped(String why) {
            return new Reception(false, null, List.of(), why);
        }
    }

    /** @param extrinsics the radar mount used to put LD2450 targets in the device frame */
    public RobotLinkProcessor(Extrinsics extrinsics) {
        this.extrinsics = Objects.requireNonNull(extrinsics, "extrinsics");
    }

    public Collection<Device> devices() {
        return List.copyOf(devices.values());
    }

    public Device device(Endpoint endpoint) {
        return devices.get(endpoint);
    }

    public Extrinsics extrinsics() {
        return extrinsics;
    }

    public Reception handle(byte[] data, Endpoint from) {
        return handle(data, data.length, from);
    }

    /** One datagram of {@code length} bytes from {@code from}. */
    public Reception handle(byte[] data, int length, Endpoint from) {
        Header hdr;
        try {
            hdr = Wire.header(data, length);
        } catch (ProtocolException e) {
            return Reception.dropped(e.getMessage());
        }
        byte[] payload = Wire.payload(data, length);
        boolean ack = false;
        List<SensorFrame> out = new ArrayList<>(2);
        if (hdr.type() == MessageType.HELLO.code()) {
            Hello hello;
            try {
                hello = Hello.decode(payload);
            } catch (IllegalArgumentException e) {
                hello = null;
            }
            if (hello != null) {
                ack = true;
                Device dev = devices.get(from);
                if (dev == null) {
                    dev = new Device(hello, from);
                    devices.put(from, dev);
                    out.add(new SensorFrame.Connected(dev));
                } else {
                    dev.hello(hello);
                }
            }
        }
        Device dev = devices.get(from);
        if (dev == null) {
            return new Reception(ack, null, List.of(), null);    // nothing before the device says HELLO
        }
        dev.stats().datagram();
        dev.sequence(hdr.seq());
        int type = hdr.type();
        if (type == MessageType.LIDAR.code()) {
            lidar(dev, from, hdr, payload, out);
        } else if (type == MessageType.LD2450.code()) {
            try {
                List<Ld2450.Target> targets = Ld2450.parse(payload);
                dev.stats().radarFrame();
                List<double[]> pts = new ArrayList<>(targets.size());
                for (Ld2450.Target t : targets) {
                    pts.add(extrinsics.ld2450ToDevice(t.xMm(), t.yMm()));
                }
                out.add(new SensorFrame.RadarTargets(dev, hdr.tUs(), List.copyOf(targets), List.copyOf(pts)));
            } catch (ProtocolException e) {
                dev.stats().bad();
            }
        } else if (type == MessageType.VITALS.code()) {
            try {
                out.add(new SensorFrame.VitalSigns(dev, hdr.tUs(), Vitals.decode(payload)));
            } catch (ProtocolException e) {
                dev.stats().bad();
            }
        } else if (type == MessageType.LOG.code()) {
            out.add(new SensorFrame.LogLine(dev, hdr.tUs(), new String(payload, StandardCharsets.UTF_8)));
        } else if (type == MessageType.AUDIO_IN.code()) {
            try {
                AudioIn a = AudioIn.decode(payload);
                out.add(new SensorFrame.AudioChunk(dev, hdr.tUs(), a.index(), a.pcm()));
            } catch (ProtocolException e) {
                dev.stats().bad();
            }
        }
        return new Reception(ack, dev, out, null);
    }

    private void lidar(Device dev, Endpoint from, Header hdr, byte[] payload, List<SensorFrame> out) {
        if (payload.length == 0) {
            return;
        }
        Revolution rev = revolutions.computeIfAbsent(from, k -> new Revolution());
        for (int off : Ldrobot.split(1, payload.length)) {
            Ldrobot.Packet p;
            try {
                p = Ldrobot.parse(payload, off, Ldrobot.SIZE);
            } catch (ProtocolException e) {
                dev.stats().crcError();
                continue;
            }
            dev.stats().lidarPacket();
            double start = p.anglesDeg()[0];
            if (rev.hasLast && start < rev.lastStart && rev.count > 0) {     // wrapped past 0
                out.add(rev.close(dev, hdr.tUs()));
            }
            rev.add(p);
            rev.hasLast = true;
            rev.lastStart = start;
            rev.speed = p.speedDps();
        }
    }

    /** The packets of the revolution being assembled for one sender. */
    private static final class Revolution {
        double[] angles = new double[64 * Ldrobot.POINTS];
        float[] dist = new float[64 * Ldrobot.POINTS];
        int[] inten = new int[64 * Ldrobot.POINTS];
        int count;
        boolean hasLast;
        double lastStart;
        int speed;

        void add(Ldrobot.Packet p) {
            int n = Ldrobot.POINTS;
            if (count + n > angles.length) {
                int cap = angles.length * 2;
                angles = Arrays.copyOf(angles, cap);
                dist = Arrays.copyOf(dist, cap);
                inten = Arrays.copyOf(inten, cap);
            }
            System.arraycopy(p.anglesDeg(), 0, angles, count, n);
            System.arraycopy(p.distancesMm(), 0, dist, count, n);
            System.arraycopy(p.intensities(), 0, inten, count, n);
            count += n;
        }

        SensorFrame.LidarRevolution close(Device dev, long tUs) {
            SensorFrame.LidarRevolution r = new SensorFrame.LidarRevolution(dev, tUs, Arrays.copyOf(angles, count),
                    Arrays.copyOf(dist, count), Arrays.copyOf(inten, count), count, speed);
            count = 0;
            return r;
        }
    }
}
