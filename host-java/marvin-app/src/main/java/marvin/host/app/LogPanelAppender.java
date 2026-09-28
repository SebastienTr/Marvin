// SPDX-License-Identifier: MIT
package marvin.host.app;

import java.util.Map;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import marvin.host.application.system.port.in.HostLog;

/** The host's warnings and errors, from any logger, in the app's Log panel (as "host"). */
public final class LogPanelAppender extends AppenderBase<ILoggingEvent> {
    private static final ThreadLocal<Boolean> BUSY = ThreadLocal.withInitial(() -> false);
    private final HostLog log;

    private LogPanelAppender(HostLog log) {
        this.log = log;
    }

    /** Attaches an appender to the root logger; returns it (to detach at shutdown). */
    public static LogPanelAppender attach(HostLog log) {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        LogPanelAppender a = new LogPanelAppender(log);
        a.setContext(ctx);
        a.setName("log-panel");
        a.start();
        ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(a);
        return a;
    }

    public void detach() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).detachAppender(this);
        stop();
    }

    @Override
    protected void append(ILoggingEvent e) {
        if (!e.getLevel().isGreaterOrEqual(Level.WARN) || BUSY.get()) {
            return;
        }
        BUSY.set(true);
        try {
            String text = e.getFormattedMessage();
            IThrowableProxy t = e.getThrowableProxy();
            if (t != null) {
                String name = t.getClassName();
                text += " (" + name.substring(name.lastIndexOf('.') + 1) + ": " + t.getMessage() + ")";
            }
            log.add("host", e.getLevel().isGreaterOrEqual(Level.ERROR) ? "error" : "warning", text,
                    Map.of("logger", e.getLoggerName()));
        } catch (RuntimeException ex) {
            // logging must never fail
        } finally {
            BUSY.set(false);
        }
    }
}
