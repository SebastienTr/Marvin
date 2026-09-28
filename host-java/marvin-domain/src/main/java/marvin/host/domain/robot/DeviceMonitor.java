// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Follows the devices for the app: turns their counters into rates, notices devices that stop
 * sending (and come back), and queues their logs and connections for listeners. The logic of the
 * Python {@code ui/sink.py} {@code UISink}, without the sensor views.
 *
 * <p>{@link #record} is called for every frame (O(1)); {@link #tick} about once a second, with the
 * host's monotonic and wall clocks passed in. Thread-safe.
 */
public final class DeviceMonitor {
    /** The robot sends a {@code HELLO} every 2 s: three missed heartbeats. */
    public static final double OFFLINE_AFTER_S = 6.0;
    /** Rates and loss are averaged over this window. */
    public static final double RATE_WINDOW_S = 5.0;

    private final double offlineAfterS;
    private final Extrinsics.LidarMount lidar;
    private final Map<Endpoint, Entry> devices = new LinkedHashMap<>();
    private final Deque<Pending> pending = new ArrayDeque<>();
    private List<DeviceStatus> statuses = List.of();

    /** Something a listener hears about on {@link #tick}. */
    public sealed interface Notice {
        /** A device said its first {@code HELLO}. */
        record Connected(DeviceStatus device) implements Notice {
        }

        record Disconnected(DeviceStatus device) implements Notice {
        }

        record Reconnected(DeviceStatus device) implements Notice {
        }

        /** @param ts wall clock, seconds */
        record Log(String device, String text, double ts) implements Notice {
        }
    }

    private record Pending(boolean log, Endpoint key, String text, double ts) {
    }

    private static final class Entry {
        final Device dev;
        final double connectedAt;
        double lastSeen;
        boolean online = true;
        boolean announced;
        long scans;
        long radar;
        long vitals;
        long audio;
        int points;
        long seenDatagrams = -1;
        final Deque<double[]> history = new ArrayDeque<>();   // {t, datagrams, scans, radar, vitals, audio, lost}
        Map<String, Double> rates = Map.of();
        Double lossPct;

        Entry(Device dev, double connectedAt, double now) {
            this.dev = dev;
            this.connectedAt = connectedAt;
            this.lastSeen = now;
        }
    }

    /** @param lidar decides which returns count as points */
    public DeviceMonitor(double offlineAfterS, Extrinsics.LidarMount lidar) {
        this.offlineAfterS = offlineAfterS;
        this.lidar = lidar;
    }

    public DeviceMonitor(Extrinsics.LidarMount lidar) {
        this(OFFLINE_AFTER_S, lidar);
    }

    /** One frame from the link. {@code now}: monotonic seconds; {@code wall}: seconds since the epoch. */
    public synchronized void record(SensorFrame frame, double now, double wall) {
        Entry e = entry(frame.device(), now, wall);
        switch (frame) {
            case SensorFrame.LidarRevolution r -> {
                e.scans++;
                int kept = 0;
                for (int i = 0; i < r.count(); i++) {
                    if (lidar.keeps(r.distancesMm()[i])) {
                        kept++;
                    }
                }
                e.points = kept;
            }
            case SensorFrame.RadarTargets t -> e.radar++;
            case SensorFrame.VitalSigns v -> e.vitals++;
            case SensorFrame.AudioChunk a -> e.audio++;
            case SensorFrame.LogLine l -> pending.addLast(new Pending(true, e.dev.endpoint(), l.text(), wall));
            case SensorFrame.Connected c -> {
                // entry() did it
            }
        }
    }

    private Entry entry(Device dev, double now, double wall) {
        Entry e = devices.get(dev.endpoint());
        if (e == null || e.dev != dev) {
            e = new Entry(dev, wall, now);
            devices.put(dev.endpoint(), e);
            pending.addLast(new Pending(false, dev.endpoint(), "", wall));
        }
        return e;
    }

    /** Updates rates and online states; returns what listeners should hear, in order. */
    public synchronized List<Notice> tick(double now) {
        List<Notice> changes = new ArrayList<>();
        List<Endpoint> changed = new ArrayList<>();
        List<Boolean> cameBack = new ArrayList<>();
        for (Entry e : devices.values()) {
            LinkStats.Snapshot s = e.dev.stats().snapshot();
            long n = s.datagrams();
            if (n != e.seenDatagrams) {
                e.seenDatagrams = n;
                e.lastSeen = now;
            }
            e.history.addLast(new double[] {now, n, e.scans, e.radar, e.vitals, e.audio, s.lost()});
            while (e.history.size() > 2 && now - secondOf(e.history)[0] >= RATE_WINDOW_S) {
                e.history.removeFirst();
            }
            if (e.history.size() > 64) {
                e.history.removeFirst();
            }
            double[] first = e.history.peekFirst();
            double[] last = e.history.peekLast();
            double dt = now - first[0];
            if (dt > 0.5) {
                Map<String, Double> rates = new LinkedHashMap<>();
                String[] names = {"datagrams", "scans", "radar", "vitals", "audio"};
                for (int i = 0; i < names.length; i++) {
                    rates.put(names[i], round1((last[i + 1] - first[i + 1]) / dt));
                }
                e.rates = java.util.Collections.unmodifiableMap(rates);
                double got = last[1] - first[1];
                double lost = last[6] - first[6];
                e.lossPct = got + lost > 0 ? round1(100.0 * lost / (got + lost)) : null;
            }
            boolean online = now - e.lastSeen < offlineAfterS;
            if (online != e.online) {
                e.online = online;
                if (e.announced) {
                    changed.add(e.dev.endpoint());
                    cameBack.add(online);
                }
            }
        }
        List<DeviceStatus> out = new ArrayList<>(devices.size());
        Map<Endpoint, DeviceStatus> byKey = new LinkedHashMap<>();
        for (Entry e : devices.values()) {
            DeviceStatus d = describe(e, now);
            out.add(d);
            byKey.put(e.dev.endpoint(), d);
        }
        statuses = List.copyOf(out);
        while (!pending.isEmpty()) {
            Pending p = pending.removeFirst();
            DeviceStatus d = byKey.get(p.key());
            if (!p.log()) {
                Entry e = devices.get(p.key());
                if (e != null) {
                    e.announced = true;
                }
                if (d != null) {
                    changes.add(new Notice.Connected(d));
                }
            } else {
                changes.add(new Notice.Log(d != null ? d.name() : p.key().toString(), p.text(), p.ts()));
            }
        }
        for (int i = 0; i < changed.size(); i++) {
            DeviceStatus d = byKey.get(changed.get(i));
            changes.add(cameBack.get(i) ? new Notice.Reconnected(d) : new Notice.Disconnected(d));
        }
        return changes;
    }

    /** Every device seen since start, as of the last {@link #tick}. */
    public synchronized List<DeviceStatus> statuses() {
        return statuses;
    }

    /** The devices seen so far (their entities, not a snapshot). */
    public synchronized List<Device> devices() {
        List<Device> out = new ArrayList<>(devices.size());
        for (Entry e : devices.values()) {
            out.add(e.dev);
        }
        return out;
    }

    /** Whether any device is online, as of the last {@link #tick}. */
    public synchronized boolean anyOnline() {
        return statuses.stream().anyMatch(DeviceStatus::online);
    }

    private static double[] secondOf(Deque<double[]> d) {
        var it = d.iterator();
        it.next();
        return it.next();
    }

    private DeviceStatus describe(Entry e, double now) {
        Hello h = e.dev.hello();
        Board.Role role = Board.role(h.board());
        Integer rssi = h.rssi() == 0 || role == Board.Role.SIMULATOR ? null : h.rssi();
        String board = Board.name(h.board());
        return new DeviceStatus(e.dev.endpoint().toString(), h.deviceName(), role,
                board != null ? board : "board " + h.board(), h.firmware(), e.dev.endpoint().host(), rssi,
                rssiBars(rssi), (long) Math.rint(h.uptimeMs() / 1000.0), h.simulated(), h.hasCamera(), h.hasAudio(),
                h.hasScreen(), e.online, round1(Math.max(0.0, now - e.lastSeen)), e.connectedAt, e.rates, e.lossPct,
                e.dev.stats().snapshot(), e.points);
    }

    /** Wi-Fi signal (dBm) as 0..4 bars, {@code null} when unknown. */
    public static Integer rssiBars(Integer rssi) {
        if (rssi == null) {
            return null;
        }
        int[][] floors = {{4, -55}, {3, -65}, {2, -75}, {1, -85}};
        for (int[] f : floors) {
            if (rssi >= f[1]) {
                return f[0];
            }
        }
        return 0;
    }

    private static double round1(double v) {
        return Math.rint(v * 10) / 10;
    }
}
