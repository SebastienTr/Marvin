// SPDX-License-Identifier: MIT
package marvin.host.application.system;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import marvin.host.application.system.port.in.HostLog;
import marvin.host.application.system.port.out.HostLogListener;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.system.LogEntry;

/** The Log panel's lines, in memory: the last {@link #SIZE}. Thread-safe. */
public final class HostLogService implements HostLog {
    public static final int SIZE = 500;

    private final Clocks clocks;
    private final Deque<LogEntry> entries = new ArrayDeque<>();
    private final List<HostLogListener> listeners = new CopyOnWriteArrayList<>();
    private long nextId;

    public HostLogService(Clocks clocks) {
        this.clocks = clocks;
    }

    public void addListener(HostLogListener l) {
        listeners.add(l);
    }

    @Override
    public LogEntry add(String source, String level, String text, Map<String, Object> extra) {
        return add(source, level, text, clocks.wallSeconds(), extra);
    }

    @Override
    public LogEntry add(String source, String level, String text, double ts, Map<String, Object> extra) {
        LogEntry e;
        synchronized (entries) {
            e = new LogEntry(++nextId, ts, source, level, text, extra);
            entries.addLast(e);
            while (entries.size() > SIZE) {
                entries.removeFirst();
            }
        }
        for (HostLogListener l : listeners) {
            l.onEntry(e);
        }
        return e;
    }

    @Override
    public List<LogEntry> entries(int limit, Set<String> sources, long since) {
        List<LogEntry> out = new ArrayList<>();
        synchronized (entries) {
            Iterator<LogEntry> it = entries.descendingIterator();
            while (it.hasNext() && out.size() < limit) {
                LogEntry e = it.next();
                if (e.id() > since && (sources.isEmpty() || sources.contains(e.source()))) {
                    out.add(e);
                }
            }
        }
        return out;
    }
}
