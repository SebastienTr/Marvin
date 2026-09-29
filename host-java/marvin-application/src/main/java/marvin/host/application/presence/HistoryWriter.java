// SPDX-License-Identifier: MIT
package marvin.host.application.presence;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The presence history's writes, off the threads that produce them (the in-process form of an outbox): a
 * bounded queue drained in order by one writer thread, which retries a failing write with backoff until the
 * store takes it. The brain's frame thread only enqueues: a slow or stopped database never holds it up, and
 * no event is lost while the queue has room.
 */
public final class HistoryWriter implements AutoCloseable {
    private static final Logger log = Logger.getLogger("marvin.history");
    static final int CAPACITY = 10_000;
    static final double FIRST_BACKOFF_S = 0.5;
    static final double MAX_BACKOFF_S = 30.0;

    /** One write; it runs until it returns without throwing. */
    public interface Write {
        void run();
    }

    private final BlockingQueue<Write> queue;
    private final Thread thread;
    private final AtomicLong dropped = new AtomicLong();
    private final Object idle = new Object();
    private volatile boolean closing;
    /** Writes accepted and not yet done or given up: counted from the offer, not from the queue's contents. */
    private final AtomicLong outstanding = new AtomicLong();

    private HistoryWriter(int capacity, boolean start) {
        queue = new ArrayBlockingQueue<>(capacity);
        thread = start ? Thread.ofPlatform().name("history-writer").daemon().start(this::drain) : null;
    }

    /** A writer thread with the default queue. */
    public static HistoryWriter start() {
        return new HistoryWriter(CAPACITY, true);
    }

    static HistoryWriter start(int capacity) {
        return new HistoryWriter(capacity, true);
    }

    /** Writes on the caller's thread, failures thrown: for tests of what is written, not when. */
    public static HistoryWriter inline() {
        return new HistoryWriter(1, false);
    }

    /** Queues a write; if the queue is full (the store has been down for a long while), it is dropped and counted. */
    public void submit(Write w) {
        if (thread == null) {
            w.run();
            return;
        }
        outstanding.incrementAndGet();
        if (!queue.offer(w)) {
            outstanding.decrementAndGet();
            long n = dropped.incrementAndGet();
            if (n == 1 || n % 1000 == 0) {
                log.warning("the history store is behind: " + n + " writes dropped so far");
            }
        }
    }

    /** Writes waiting (and the one being retried). */
    public int pending() {
        return (int) outstanding.get();
    }

    public long dropped() {
        return dropped.get();
    }

    /** Waits until everything queued so far is written, at most {@code timeoutMs}; true if it was. */
    public boolean flush(long timeoutMs) throws InterruptedException {
        synchronized (idle) {
            for (long waited = 0; pending() > 0; waited += 50) {      // steps of 50 ms: no clock here
                if (waited >= timeoutMs) {
                    return false;
                }
                idle.wait(50);
            }
        }
        return true;
    }

    private void drain() {
        while (true) {
            Write w;
            try {
                w = queue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (w == null) {
                if (closing) {
                    return;
                }
                continue;
            }
            double backoff = FIRST_BACKOFF_S;
            int failures = 0;
            while (true) {
                try {
                    w.run();
                    if (failures > 0) {
                        log.info("the history store is back after " + failures + " failed attempts");
                    }
                    break;
                } catch (RuntimeException e) {
                    failures++;
                    if (failures == 1) {
                        log.log(Level.WARNING, "could not write to the history (retrying): " + e.getMessage());
                    }
                    if (closing && failures >= 3) {
                        log.warning("giving up a history write at shutdown: " + e.getMessage());
                        break;
                    }
                    try {
                        Thread.sleep((long) (backoff * 1000));
                    } catch (InterruptedException ie) {
                        outstanding.decrementAndGet();
                        return;
                    }
                    backoff = Math.min(MAX_BACKOFF_S, backoff * 2);
                }
            }
            outstanding.decrementAndGet();
            synchronized (idle) {
                idle.notifyAll();
            }
        }
    }

    /** Writes what is queued (waiting at most {@code timeoutMs}), then stops the thread. */
    public void close(long timeoutMs) {
        if (thread == null) {
            return;
        }
        try {
            flush(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        closing = true;
        try {
            thread.join(1000);              // not interrupted in the middle of a write
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            thread.interrupt();
        }
    }

    @Override
    public void close() {
        close(5000);
    }
}
