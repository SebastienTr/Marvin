// SPDX-License-Identifier: MIT
package marvin.host.adapter.robot;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import marvin.host.domain.robot.SensorFrame;

/**
 * Hands the link's frames to the application on a thread of its own, in order, so the socket thread
 * never waits for a slow consumer (the Python receiver's dispatch thread).
 *
 * <p>If the consumers fall behind, the oldest lidar revolutions are dropped first (a newer one
 * replaces them); other frames are dropped only when the whole queue is full. Drops are counted per
 * kind in the device's {@code shed} counters. {@link #timings()} tells how long each kind takes and
 * how long frames wait.
 *
 * <p>When not started, {@link #submit} calls the consumer on the caller's thread (replays, tests), so
 * a replay stays deterministic.
 */
public final class FrameDispatcher {
    private static final Logger log = LoggerFactory.getLogger(FrameDispatcher.class);
    static final long SLOW_CALL_NANOS = 200_000_000L;
    private static final long WARN_EVERY_NANOS = 10_000_000_000L;

    private final Consumer<SensorFrame> consumer;
    private final int queueSize;
    private final long maxScanLagNanos;
    private final int maxPendingScans;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition ready = lock.newCondition();
    private final Deque<Item> queue = new ArrayDeque<>();
    private final Deque<Long> scanTimes = new ArrayDeque<>();
    private final Map<String, Timing> timings = new ConcurrentHashMap<>();
    private final Map<String, Long> warned = new ConcurrentHashMap<>();
    private Thread worker;
    private boolean stopping;
    private int maxDepth;

    private record Item(SensorFrame frame, long enqueued) {
    }

    /** Per kind: calls, total and max duration, slow calls, longest wait. */
    private static final class Timing {
        long calls;
        long totalNanos;
        long maxNanos;
        long slow;
        long maxWaitNanos;
    }

    /**
     * @param queueSize       at most this many frames wait
     * @param maxScanLagS     a lidar revolution older than this is dropped when a newer one waits
     * @param maxPendingScans at most this many revolutions wait
     */
    public FrameDispatcher(Consumer<SensorFrame> consumer, int queueSize, double maxScanLagS, int maxPendingScans) {
        this.consumer = consumer;
        this.queueSize = Math.max(1, queueSize);
        this.maxScanLagNanos = (long) (maxScanLagS * 1e9);
        this.maxPendingScans = Math.max(1, maxPendingScans);
    }

    public FrameDispatcher(Consumer<SensorFrame> consumer) {
        this(consumer, 2000, 0.3, 10);
    }

    public void start() {
        lock.lock();
        try {
            if (worker != null) {
                throw new IllegalStateException("already started");
            }
            stopping = false;
            worker = Thread.ofPlatform().name("robot-dispatch").daemon().start(this::loop);
        } finally {
            lock.unlock();
        }
    }

    public boolean running() {
        lock.lock();
        try {
            return worker != null;
        } finally {
            lock.unlock();
        }
    }

    /** Frames waiting. */
    public int pending() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    public int maxQueueDepth() {
        lock.lock();
        try {
            return maxDepth;
        } finally {
            lock.unlock();
        }
    }

    public void submit(SensorFrame frame) {
        lock.lock();
        boolean direct = worker == null;
        try {
            if (!direct) {
                long now = System.nanoTime();
                if (frame instanceof SensorFrame.LidarRevolution) {
                    while (!scanTimes.isEmpty() && (scanTimes.size() >= maxPendingScans
                            || now - scanTimes.peekFirst() > maxScanLagNanos)) {
                        shedOldest(true);
                    }
                    scanTimes.addLast(now);
                }
                if (queue.size() >= queueSize) {
                    shedOldest(false);
                }
                queue.addLast(new Item(frame, now));
                maxDepth = Math.max(maxDepth, queue.size());
                ready.signal();
            }
        } finally {
            lock.unlock();
        }
        if (direct) {
            call(frame, 0);
        }
    }

    /** Drops the oldest queued revolution (or, if none and not scanOnly, the oldest frame). Holds the lock. */
    private void shedOldest(boolean scanOnly) {
        Item victim = null;
        if (!scanTimes.isEmpty()) {
            for (Iterator<Item> it = queue.iterator(); it.hasNext();) {
                Item i = it.next();
                if (i.frame() instanceof SensorFrame.LidarRevolution) {
                    it.remove();
                    scanTimes.pollFirst();
                    victim = i;
                    break;
                }
            }
        }
        if (victim == null && !scanOnly && !queue.isEmpty()) {
            victim = queue.pollFirst();
            if (victim.frame() instanceof SensorFrame.LidarRevolution) {
                scanTimes.pollFirst();
            }
        }
        if (victim != null) {
            countShed(victim.frame());
        }
    }

    private void countShed(SensorFrame f) {
        long n = f.device().stats().shed(f.kind());
        warn("shed-" + f.kind(), "the host is behind ({} frames queued): dropping {} frames, {} so far for {}",
                queue.size(), f.kind(), n, f.device().name());
    }

    private void loop() {
        Thread me = Thread.currentThread();
        while (true) {
            Item item;
            long wait;
            lock.lock();
            try {
                while (queue.isEmpty() && !stopping && worker == me) {
                    ready.awaitUninterruptibly();
                }
                if (worker != me || queue.isEmpty()) {
                    return;                                     // stopped, and everything was dispatched
                }
                item = queue.pollFirst();
                wait = System.nanoTime() - item.enqueued();
                if (item.frame() instanceof SensorFrame.LidarRevolution) {
                    scanTimes.pollFirst();
                    if (!scanTimes.isEmpty() && wait > maxScanLagNanos) {   // stale, and a newer one waits
                        countShed(item.frame());
                        continue;
                    }
                }
            } finally {
                lock.unlock();
            }
            try {
                call(item.frame(), wait);
            } catch (RuntimeException e) {
                warn("error-" + item.frame().kind(), "handling a {} frame failed", item.frame().kind(), e);
            }
        }
    }

    private void call(SensorFrame frame, long waitNanos) {
        long t = System.nanoTime();
        try {
            consumer.accept(frame);
        } finally {
            long dt = System.nanoTime() - t;
            Timing tm = timings.computeIfAbsent(frame.kind(), k -> new Timing());
            long slow;
            synchronized (tm) {
                tm.calls++;
                tm.totalNanos += dt;
                tm.maxNanos = Math.max(tm.maxNanos, dt);
                tm.maxWaitNanos = Math.max(tm.maxWaitNanos, waitNanos);
                if (dt > SLOW_CALL_NANOS) {
                    tm.slow++;
                }
                slow = tm.slow;
            }
            if (dt > SLOW_CALL_NANOS) {
                warn("slow-" + frame.kind(), "handling a {} frame took {} ms ({} calls over {} ms so far)",
                        frame.kind(), dt / 1_000_000, slow, SLOW_CALL_NANOS / 1_000_000);
            }
        }
    }

    /**
     * Stops the thread after the frames still queued are handled, for at most {@code drainTimeoutMs};
     * the rest is dropped (and counted as shed).
     */
    public void stop(long drainTimeoutMs) {
        Thread w;
        lock.lock();
        try {
            w = worker;
            if (w == null) {
                return;
            }
            stopping = true;
            ready.signalAll();
        } finally {
            lock.unlock();
        }
        try {
            w.join(TimeUnit.MILLISECONDS.toMillis(drainTimeoutMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        lock.lock();
        try {
            int left = queue.size();
            for (Item i : queue) {
                i.frame().device().stats().shed(i.frame().kind());
            }
            queue.clear();
            scanTimes.clear();
            worker = null;                                      // a stuck worker exits after its current call
            ready.signalAll();
            if (left > 0) {
                log.warn("robot link stopped with {} frames not handled (a consumer is still busy)", left);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Per kind: calls, mean_ms, max_ms, slow, max_wait_ms. */
    public Map<String, Map<String, Number>> timings() {
        Map<String, Map<String, Number>> out = new LinkedHashMap<>();
        timings.forEach((k, t) -> {
            synchronized (t) {
                Map<String, Number> m = new LinkedHashMap<>();
                m.put("calls", t.calls);
                m.put("mean_ms", t.calls == 0 ? 0.0 : Math.rint(t.totalNanos / 1e3 / t.calls) / 1e3);
                m.put("max_ms", Math.rint(t.maxNanos / 1e3) / 1e3);
                m.put("slow", t.slow);
                m.put("max_wait_ms", Math.rint(t.maxWaitNanos / 1e3) / 1e3);
                out.put(k, m);
            }
        });
        return out;
    }

    private void warn(String key, String msg, Object... args) {
        long now = System.nanoTime();
        Long last = warned.get(key);
        if (last != null && now - last < WARN_EVERY_NANOS) {
            return;
        }
        warned.put(key, now);
        log.warn(msg, args);
    }
}
