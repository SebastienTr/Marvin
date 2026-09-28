// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One device's link counters, as the Python receiver keeps them ({@code receiver.Stats}). Updated by
 * the socket thread (and {@link #shed} by the dispatch thread), read by anyone.
 */
public final class LinkStats {
    private final AtomicLong datagrams = new AtomicLong();
    private final AtomicLong lidarPackets = new AtomicLong();
    private final AtomicLong crcErrors = new AtomicLong();
    private final AtomicLong radarFrames = new AtomicLong();
    private final AtomicLong lost = new AtomicLong();
    private final AtomicLong bad = new AtomicLong();
    private final Map<String, AtomicLong> shed = new ConcurrentHashMap<>();

    /** An immutable copy of the counters. {@code shed}: sink calls dropped because the host was behind, by kind. */
    public record Snapshot(long datagrams, long lidarPackets, long crcErrors, long radarFrames, long lost, long bad,
                           Map<String, Long> shed) {
        public long shedTotal() {
            return shed.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    void datagram() {
        datagrams.incrementAndGet();
    }

    void lidarPacket() {
        lidarPackets.incrementAndGet();
    }

    void crcError() {
        crcErrors.incrementAndGet();
    }

    void radarFrame() {
        radarFrames.incrementAndGet();
    }

    void lost(long n) {
        lost.addAndGet(n);
    }

    void bad() {
        bad.incrementAndGet();
    }

    /** A sink call of this kind ({@code scan}, {@code targets}, ...) was dropped; returns how many so far. */
    public long shed(String kind) {
        return shed.computeIfAbsent(kind, k -> new AtomicLong()).incrementAndGet();
    }

    public long datagrams() {
        return datagrams.get();
    }

    public long lost() {
        return lost.get();
    }

    public Snapshot snapshot() {
        Map<String, Long> s = new TreeMap<>();
        shed.forEach((k, v) -> s.put(k, v.get()));
        return new Snapshot(datagrams.get(), lidarPackets.get(), crcErrors.get(), radarFrames.get(), lost.get(),
                bad.get(), Map.copyOf(s));
    }
}
