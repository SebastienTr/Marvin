// SPDX-License-Identifier: MIT
package marvin.host.application.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Logger;

import marvin.host.domain.memory.MemoryEvent;

/**
 * The event log's writes, off the threads that produce them (the voice's, the brain's): a bounded queue drained
 * by one thread that appends what is queued in batches and retries with backoff while the database is away.
 * Nothing waits for the database; nothing is lost while the queue has room.
 */
final class LogWriter implements AutoCloseable {
    private static final Logger log = Logger.getLogger("marvin.memory");
    static final int CAPACITY = 10_000;
    static final int BATCH = 200;
    static final double FIRST_BACKOFF_S = 0.5;
    static final double MAX_BACKOFF_S = 30.0;

    private final BlockingQueue<MemoryEvent> queue;
    private final Consumer<List<MemoryEvent>> append;
    private final Thread thread;
    private final AtomicLong dropped = new AtomicLong();
    private final Object idle = new Object();
    private volatile boolean closing;
    private volatile int inFlight;

    private LogWriter(Consumer<List<MemoryEvent>> append, boolean start) {
        this.append = append;
        this.queue = new ArrayBlockingQueue<>(CAPACITY);
        this.thread = start ? Thread.ofPlatform().name("memory-log-writer").daemon().start(this::drain) : null;
    }

    static LogWriter start(Consumer<List<MemoryEvent>> append) {
        return new LogWriter(append, true);
    }

    /** Appends on the caller's thread (tests of what is written, not when). */
    static LogWriter inline(Consumer<List<MemoryEvent>> append) {
        return new LogWriter(append, false);
    }

    void submit(MemoryEvent e) {
        if (thread == null) {
            append.accept(List.of(e));
            return;
        }
        if (!queue.offer(e)) {
            long n = dropped.incrementAndGet();
            if (n == 1 || n % 1000 == 0) {
                log.warning("the memory log is behind: " + n + " events dropped so far");
            }
        }
    }

    int pending() {
        return queue.size() + inFlight;
    }

    boolean flush(long timeoutMs) {
        synchronized (idle) {
            for (long waited = 0; pending() > 0; waited += 50) {
                if (waited >= timeoutMs) {
                    return false;
                }
                try {
                    idle.wait(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    private void drain() {
        while (true) {
            MemoryEvent first;
            try {
                first = queue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (first == null) {
                if (closing) {
                    return;
                }
                continue;
            }
            List<MemoryEvent> batch = new ArrayList<>();
            batch.add(first);
            queue.drainTo(batch, BATCH - 1);
            inFlight = batch.size();
            double backoff = FIRST_BACKOFF_S;
            for (int failures = 0; ; failures++) {
                try {
                    append.accept(batch);
                    if (failures > 0) {
                        log.info("the memory log is back after " + failures + " failed attempts");
                    }
                    break;
                } catch (RuntimeException e) {
                    if (failures == 0) {
                        log.warning("could not write to the memory log (retrying): " + e.getMessage());
                    }
                    if (closing && failures >= 2) {
                        log.warning("giving up " + batch.size() + " memory events at shutdown: " + e.getMessage());
                        break;
                    }
                    try {
                        Thread.sleep((long) (backoff * 1000));
                    } catch (InterruptedException ie) {
                        inFlight = 0;
                        return;
                    }
                    backoff = Math.min(MAX_BACKOFF_S, backoff * 2);
                }
            }
            inFlight = 0;
            synchronized (idle) {
                idle.notifyAll();
            }
        }
    }

    void close(long timeoutMs) {
        if (thread == null) {
            return;
        }
        flush(timeoutMs);
        closing = true;
        thread.interrupt();
    }

    @Override
    public void close() {
        close(5000);
    }
}
