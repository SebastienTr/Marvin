// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import marvin.host.application.presence.port.in.PresenceHistory;
import marvin.host.application.robot.port.in.RobotLinkQuery;

/**
 * While someone has the app open: the day summary every 5 s and the devices every second (the Python
 * host's sampler does the same), so the app follows even when nothing happens.
 */
public final class WebTicker implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(WebTicker.class);
    static final long TODAY_PERIOD_S = 5;

    private final EventHub hub;
    private final PresenceHistory history;
    private final RobotLinkQuery robot;
    private ScheduledExecutorService timer;
    private long ticks;

    public WebTicker(EventHub hub, PresenceHistory history, RobotLinkQuery robot) {
        this.hub = hub;
        this.history = history;
        this.robot = robot;
    }

    private void tick() {
        try {
            if (hub.size() == 0) {
                return;
            }
            if (ticks++ % TODAY_PERIOD_S == 0) {
                hub.publish("today", Views.day(history.today()));
            }
            hub.publish("devices", Views.devices(robot.devices()));
        } catch (RuntimeException e) {
            log.warn("app ticker failed", e);
        }
    }

    @Override
    public synchronized void start() {
        timer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("app-ticker").daemon().factory());
        timer.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public synchronized void stop() {
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return timer != null;
    }
}
