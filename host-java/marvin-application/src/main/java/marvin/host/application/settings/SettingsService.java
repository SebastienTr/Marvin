// SPDX-License-Identifier: MIT
package marvin.host.application.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import marvin.host.application.settings.port.in.ManageSettings;
import marvin.host.application.settings.port.out.SettingsListener;
import marvin.host.application.settings.port.out.SettingsStore;
import marvin.host.domain.settings.AppSettings;

/**
 * The settings: the defaults, what the owner stored (values from other versions that no longer validate
 * are ignored, see {@link #ignored()}), and updates from the app.
 */
public final class SettingsService implements ManageSettings {
    private final SettingsStore store;
    private final AppSettings defaults;
    private final List<SettingsListener> listeners = new CopyOnWriteArrayList<>();
    private final List<String> ignored = new ArrayList<>();
    private volatile AppSettings current;

    public SettingsService(SettingsStore store, AppSettings defaults) {
        this.store = Objects.requireNonNull(store, "store");
        this.defaults = Objects.requireNonNull(defaults, "defaults");
        AppSettings s = defaults;
        for (Map.Entry<String, Object> e : store.load().entrySet()) {
            try {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put(e.getKey(), e.getValue());
                s = s.apply(one);
            } catch (IllegalArgumentException ex) {
                ignored.add(e.getKey() + "=" + e.getValue());
            }
        }
        this.current = s;
    }

    public void addListener(SettingsListener l) {
        listeners.add(l);
    }

    /** Stored settings that were ignored at start ({@code key=value}), for the log. */
    public List<String> ignored() {
        return List.copyOf(ignored);
    }

    @Override
    public AppSettings current() {
        return current;
    }

    @Override
    public AppSettings defaults() {
        return defaults;
    }

    @Override
    public AppSettings update(Map<String, ?> update) {
        AppSettings next;
        synchronized (this) {
            AppSettings before = current;
            next = before.apply(update);
            Map<String, Object> changed = new LinkedHashMap<>();
            Map<String, Object> old = before.toMap();
            next.toMap().forEach((k, v) -> {
                if (!same(old.get(k), v)) {
                    changed.put(k, v);
                }
            });
            if (!changed.isEmpty()) {
                store.save(changed);
            }
            current = next;
        }
        for (SettingsListener l : listeners) {
            l.onChanged(next);
        }
        return next;
    }

    /** 50 and 50.0 are the same setting, as in Python. */
    private static boolean same(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return x.doubleValue() == y.doubleValue();
        }
        return Objects.equals(a, b);
    }
}
