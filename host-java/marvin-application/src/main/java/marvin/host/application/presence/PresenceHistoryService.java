// SPDX-License-Identifier: MIT
package marvin.host.application.presence;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import marvin.host.application.presence.port.in.PresenceHistory;
import marvin.host.application.presence.port.in.PresenceQuery;
import marvin.host.application.presence.port.out.PresenceHistoryListener;
import marvin.host.application.presence.port.out.PresenceHistoryStore;
import marvin.host.domain.presence.event.EventKind;
import marvin.host.domain.presence.event.PresenceEvent;
import marvin.host.domain.presence.event.PresenceState;
import marvin.host.domain.presence.history.DayStats;
import marvin.host.domain.presence.history.HistoryKinds;
import marvin.host.domain.presence.history.Sample;
import marvin.host.domain.presence.history.StoredEvent;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.shared.LocalDays;

/**
 * Keeps the presence history (the Python {@code UIServer}'s store half): every brain event stored with
 * the wall clock, the host's own markers ({@code host_started}, {@code robot_offline} ...), one sample
 * per minute, and the days computed from them.
 *
 * <p>{@link #sample} is called about once a second: it notices the robot going quiet (no new brain
 * state for {@code offlineAfterS}) and coming back, and accumulates the minute's sample. Thread-safe.
 *
 * <p>Every write goes through a {@link HistoryWriter}: the brain's thread never waits for the database, and a
 * write that fails is retried until the store is back. The app hears of a stored event once it has its id
 * (the app orders and de-duplicates events by id).
 */
public final class PresenceHistoryService implements PresenceHistory {
    /** Events read before a day, to know the state at midnight. */
    public static final double LOOKBACK_S = 36 * 3600;
    private static final double TODAY_CACHE_S = 2.0;
    /** At stop, how long the last writes may take. */
    static final long STOP_FLUSH_MS = 5000;

    private final PresenceQuery presence;
    private final PresenceHistoryStore store;
    private final List<PresenceHistoryListener> listeners;
    private final Clocks clocks;
    private final LocalDays days;
    private final double offlineAfterS;
    private final HistoryWriter writer;

    private final Object lock = new Object();
    private volatile boolean online;
    private volatile boolean everOnline;
    private long lastTUs;
    private double lastChange;
    private volatile Double leftAt;
    private Double bucket;
    private int n;
    private double presentSum;
    private double seatedSum;
    private final List<Double> breaths = new ArrayList<>();
    private final List<Double> hearts = new ArrayList<>();
    private volatile DayStats today;
    private volatile double todayAt;

    public PresenceHistoryService(PresenceQuery presence, PresenceHistoryStore store,
                                  List<PresenceHistoryListener> listeners, Clocks clocks, LocalDays days,
                                  double offlineAfterS, HistoryWriter writer) {
        this.presence = Objects.requireNonNull(presence, "presence");
        this.store = Objects.requireNonNull(store, "store");
        this.listeners = List.copyOf(listeners);
        this.clocks = Objects.requireNonNull(clocks, "clocks");
        this.days = Objects.requireNonNull(days, "days");
        this.offlineAfterS = offlineAfterS;
        this.writer = Objects.requireNonNull(writer, "writer");
        this.lastTUs = presence.state().tUs();
        this.lastChange = clocks.monotonicSeconds();
    }

    // ------------------------------------------------------------------ lifecycle

    /** The host started: marks it in the history. */
    public void start() {
        record(HistoryKinds.HOST_STARTED, Map.of());
    }

    /** The host stops: the minute so far is kept, and the stop is marked. */
    public void stop() {
        Sample last;
        synchronized (lock) {
            last = takeSample();
        }
        store(last);
        record(HistoryKinds.HOST_STOPPED, Map.of());
        writer.close(STOP_FLUSH_MS);
    }

    /** Writes not yet in the store (a database outage): for the health report. */
    public int pendingWrites() {
        return writer.pending();
    }

    // ------------------------------------------------------------------ events

    /** A brain event (the presence event bus calls it, on the frame thread). */
    public void onEvent(PresenceEvent ev) {
        if (!online) {                                          // an event means frames: online right away
            setOnline(true, true);
        }
        double ts = clocks.wallSeconds();
        if (ev.kind() == EventKind.LEFT) {
            leftAt = ts;
        }
        Map<String, Object> data = new LinkedHashMap<>(ev.data());
        StoredEvent e = new StoredEvent(ts, ev.kind().wireName(), ev.detail(), data);
        long device = ev.tUs();
        writer.submit(() -> publish(store.add(e, device)));
    }

    private void record(String kind, Map<String, Object> data) {
        StoredEvent e = new StoredEvent(clocks.wallSeconds(), kind, "", data);
        writer.submit(() -> publish(store.add(e, null)));
    }

    private void publish(StoredEvent e) {
        today = null;
        for (PresenceHistoryListener l : listeners) {
            try {
                l.onStored(e);
            } catch (RuntimeException ex) {
                java.util.logging.Logger.getLogger("marvin.history").log(java.util.logging.Level.WARNING,
                        "history listener failed", ex);          // stored already: never written twice
            }
        }
    }

    // ------------------------------------------------------------------ sampling

    /** About once a second: online state and the minute's sample. */
    public void sample() {
        PresenceState s = presence.state();
        double mono = clocks.monotonicSeconds();
        boolean goOffline = false;
        boolean goOnline = false;
        Sample done = null;
        synchronized (lock) {
            if (s.tUs() != lastTUs) {
                lastTUs = s.tUs();
                lastChange = mono;
                goOnline = !online;
            } else if (online && mono - lastChange > offlineAfterS) {
                done = takeSample();
                goOffline = true;
            }
        }
        store(done);
        if (goOnline) {
            setOnline(true, false);
        } else if (goOffline) {
            setOnline(false, false);
        }
        double now = clocks.wallSeconds();
        double b = Math.floor(now / 60) * 60;
        Sample minute = null;
        synchronized (lock) {
            if (bucket != null && b != bucket) {
                minute = takeSample();
            }
            bucket = b;
            if (online) {
                n++;
                presentSum += s.present() ? 1 : 0;
                seatedSum += s.seated() ? 1 : 0;
                if (s.breathRate() != null && s.heartRate() != null) {
                    breaths.add(s.breathRate());
                    hearts.add(s.heartRate());
                }
            }
        }
        store(minute);
    }

    private void setOnline(boolean on, boolean fromEvent) {
        synchronized (lock) {
            if (online == on) {
                return;
            }
            online = on;
            lastChange = clocks.monotonicSeconds();
            if (on) {
                everOnline = true;
            }
        }
        if (on) {
            PresenceState s = presence.state();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("present", s.present() && !fromEvent);         // from an event: the event says who is there
            data.put("seated", s.seated() && !fromEvent);
            record(HistoryKinds.ROBOT_ONLINE, data);
        } else {
            record(HistoryKinds.ROBOT_OFFLINE, Map.of());
        }
    }

    /** The minute so far, and a fresh start for the next (under the lock); {@code null} when nothing was seen. */
    private Sample takeSample() {
        Sample out = null;
        if (n > 0 && bucket != null) {
            boolean reliable = breaths.size() * 2 >= n;             // vitals for at least half of the minute
            out = new Sample(bucket, presentSum / n, seatedSum / n,
                    reliable ? average(breaths) : null, reliable ? average(hearts) : null);
        }
        n = 0;
        presentSum = 0;
        seatedSum = 0;
        breaths.clear();
        hearts.clear();
        return out;
    }

    private void store(Sample sample) {
        if (sample != null) {
            writer.submit(() -> store.addSample(sample));
        }
    }

    private static double average(List<Double> v) {
        double s = 0;
        for (double x : v) {
            s += x;
        }
        return s / v.size();
    }

    // ------------------------------------------------------------------ queries

    @Override
    public DayStats day(LocalDate day) {
        double now = clocks.wallSeconds();
        double[] b = days.bounds(day);
        return DayStats.compute(store.events(b[0] - LOOKBACK_S, b[1]), day, days, now, true,
                store.samples(b[0] - LOOKBACK_S, b[1]));
    }

    @Override
    public DayStats today() {
        double t = clocks.monotonicSeconds();
        DayStats d = today;
        if (d == null || t - todayAt > TODAY_CACHE_S) {
            d = day(days.dayOf(clocks.wallSeconds()));
            today = d;
            todayAt = t;
        }
        return d;
    }

    @Override
    public List<DayStats.DaySummary> history(LocalDate last, int n) {
        List<DayStats.DaySummary> out = new ArrayList<>(n);
        for (int k = n - 1; k >= 0; k--) {
            out.add(day(last.minusDays(k)).summary());
        }
        return out;
    }

    @Override
    public List<StoredEvent> recent(int limit, long sinceId, boolean quiet) {
        return store.recent(limit, sinceId, quiet ? HistoryKinds.VITALS : List.of());
    }

    @Override
    public List<StoredEvent> after(long afterId, int limit) {
        return store.after(afterId, limit);
    }

    @Override
    public LocalDate dayOf(double ts) {
        return days.dayOf(ts);
    }

    @Override
    public boolean online() {
        return online;
    }

    @Override
    public boolean everOnline() {
        return everOnline;
    }

    @Override
    public Double awaySeconds() {
        Double left = leftAt;
        return left != null && !presence.state().present() ? clocks.wallSeconds() - left : null;
    }
}
