// SPDX-License-Identifier: MIT
package marvin.host.app;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import marvin.host.application.presence.PresenceHistoryService;

/**
 * The history's life: marks {@code host_started} before the robot link starts and samples the presence
 * once a second; at shutdown keeps the last minute and marks {@code host_stopped}.
 */
public final class HistoryLifecycle implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(HistoryLifecycle.class);

    private final PresenceHistoryService history;
    private ScheduledExecutorService sampler;

    public HistoryLifecycle(PresenceHistoryService history) {
        this.history = history;
    }

    @Override
    public synchronized void start() {
        history.start();
        sampler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("history-sampler").daemon().factory());
        sampler.scheduleAtFixedRate(() -> {
            try {
                history.sample();
            } catch (RuntimeException e) {
                log.warn("history sampler failed", e);
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public synchronized void stop() {
        if (sampler == null) {
            return;
        }
        sampler.shutdownNow();
        sampler = null;
        try {
            history.stop();
        } catch (RuntimeException e) {
            log.warn("could not mark the host's stop in the history: {}", e.getMessage());
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return sampler != null;
    }

    /** Before the robot link starts, after the database; stopped after the link. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE / 2 - 100;
    }
}
