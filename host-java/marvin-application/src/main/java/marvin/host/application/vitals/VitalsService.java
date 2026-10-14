// SPDX-License-Identifier: MIT
package marvin.host.application.vitals;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import marvin.host.application.vitals.port.in.ObserveVitals;
import marvin.host.application.vitals.port.in.VitalsQuery;
import marvin.host.application.vitals.port.out.PresenceStillness;
import marvin.host.application.vitals.port.out.VitalsStore;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.vitals.GateConfig;
import marvin.host.domain.vitals.MinuteAggregator;
import marvin.host.domain.vitals.QualityGate;
import marvin.host.domain.vitals.Stillness;
import marvin.host.domain.vitals.VitalsCurrent;
import marvin.host.domain.vitals.VitalsInput;
import marvin.host.domain.vitals.VitalsMinute;

/**
 * The vitals use cases around one {@link QualityGate} and one {@link MinuteAggregator}: readings in (about 10 a
 * second per device), {@link #tick()} once a second (samples the current values into the minute, stores each ended
 * minute, prunes old ones once an hour), and the current values and the history out.
 *
 * <p>Stillness comes from the presence context only while a presence radar has sent targets in the last
 * {@link #RADAR_LIVE_S} seconds; without one (an MR60BHA2 on its own), it is unknown and the sender's validity and
 * signal quality decide. A minute the store refuses (database down) is kept and offered again at the next tick.
 */
public final class VitalsService implements ObserveVitals, VitalsQuery {
    private static final Logger log = Logger.getLogger("marvin.vitals");
    /** How long the presence radar's word on stillness counts after its last targets. */
    public static final double RADAR_LIVE_S = 2.0;
    static final double PRUNE_EVERY_S = 3600.0;
    static final int MAX_PENDING = 24 * 60;

    private final QualityGate gate;
    private final MinuteAggregator minutes = new MinuteAggregator();
    private final VitalsStore store;
    private final PresenceStillness stillness;
    private final Clocks clocks;
    private final double keepS;
    private final Object lock = new Object();
    private final Deque<VitalsMinute> pending = new ArrayDeque<>();
    private volatile double radarT = Double.NEGATIVE_INFINITY;
    private double nextPrune = Double.NEGATIVE_INFINITY;

    /**
     * @param keepDays how long the per-minute history is kept
     */
    public VitalsService(GateConfig config, VitalsStore store, PresenceStillness stillness, Clocks clocks, double keepDays) {
        this.gate = new QualityGate(config);
        this.store = Objects.requireNonNull(store, "store");
        this.stillness = Objects.requireNonNull(stillness, "stillness");
        this.clocks = Objects.requireNonNull(clocks, "clocks");
        this.keepS = keepDays * 86400.0;
    }

    @Override
    public void onReading(VitalsInput reading) {
        double t = clocks.monotonicSeconds();
        Stillness still = t - radarT <= RADAR_LIVE_S ? stillness.now() : Stillness.UNKNOWN;
        synchronized (lock) {
            gate.onReading(t, clocks.wallSeconds(), reading, still);
        }
    }

    /** The device whose readings the gate follows (one seat), {@code null} before the first reading. */
    String source() {
        synchronized (lock) {
            return gate.source();
        }
    }

    @Override
    public void onPresenceRadar() {
        radarT = clocks.monotonicSeconds();
    }

    @Override
    public Optional<VitalsCurrent> current() {
        synchronized (lock) {
            return Optional.ofNullable(gate.current(clocks.monotonicSeconds()));
        }
    }

    @Override
    public List<VitalsMinute> minutes(int hours) {
        double now = clocks.wallSeconds();
        double start = MinuteAggregator.minuteOf(now - hours * 3600.0);
        TreeMap<Double, VitalsMinute> out = new TreeMap<>();
        for (VitalsMinute m : store.minutes(start, now + 60)) {
            out.put(m.t(), m);
        }
        synchronized (lock) {
            for (VitalsMinute p : pending) {                 // a minute being stored right now may be in both
                if (p.t() >= start) {
                    out.put(p.t(), p);
                }
            }
            VitalsMinute open = minutes.inProgress();
            if (open != null) {
                out.put(open.t(), open);
            }
        }
        return new ArrayList<>(out.values());
    }

    /** Once a second: the current values into the minute; an ended minute to the store; old minutes pruned hourly. */
    public void tick() {
        double wall = clocks.wallSeconds();
        List<VitalsMinute> toStore;
        synchronized (lock) {
            VitalsMinute ended = minutes.sample(wall, gate.current(clocks.monotonicSeconds()));
            if (ended != null) {
                pending.addLast(ended);
                while (pending.size() > MAX_PENDING) {
                    pending.removeFirst();
                }
            }
            toStore = List.copyOf(pending);
        }
        for (VitalsMinute m : toStore) {
            try {
                store.add(m);
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "could not store a minute of vital signs, will retry: " + e.getMessage());
                return;
            }
            synchronized (lock) {
                pending.remove(m);
            }
        }
        if (wall >= nextPrune) {
            nextPrune = wall + PRUNE_EVERY_S;
            try {
                int n = store.deleteBefore(wall - keepS);
                if (n > 0) {
                    log.info("vitals: " + n + " minute(s) older than the retention deleted");
                }
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "could not prune the vital signs history: " + e.getMessage());
            }
        }
    }
}
