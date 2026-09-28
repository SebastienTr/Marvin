// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import marvin.host.application.conversation.port.out.VoiceSettingsStore;
import marvin.host.domain.conversation.VoiceSettings;
import marvin.host.domain.shared.JsonText;

/**
 * The voice's settings in {@code voice.json}, the file the Python host's {@code marvin-host talk} and
 * {@code run} read: {@code $MARVIN_CONFIG_DIR}, else {@code $XDG_CONFIG_HOME/marvin}, else
 * {@code ~/.config/marvin}. Written as the Python host writes it (two-space indent, other keys kept).
 */
public final class VoiceSettingsFile implements VoiceSettingsStore {
    private static final Logger log = LoggerFactory.getLogger("marvin.voice");
    public static final String NAME = "voice.json";

    private final Path path;
    private final Object lock = new Object();
    private String warned = "";

    public VoiceSettingsFile(Path path) {
        this.path = path;
    }

    /** {@code voice.json} in the configuration directory. */
    public static VoiceSettingsFile inConfigDir(String configured) {
        return new VoiceSettingsFile(configDir(configured).resolve(NAME));
    }

    /**
     * The demo's own {@code voice.json} in {@code dir}, made again at every start from {@code source} (or empty
     * when there is none): the demo can change its settings without touching the owner's (the Python demo does
     * the same).
     */
    public static VoiceSettingsFile demoCopy(VoiceSettingsFile source, Path dir) {
        VoiceSettingsFile copy = new VoiceSettingsFile(dir.resolve(NAME));
        try {
            Files.deleteIfExists(copy.path);
        } catch (IOException e) {
            throw new IllegalStateException("cannot reset " + copy.path + ": " + e.getMessage(), e);
        }
        copy.save(source.load());
        return copy;
    }

    static Path configDir(String configured) {
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.replaceFirst("^~", System.getProperty("user.home")));
        }
        String env = System.getenv("MARVIN_CONFIG_DIR");
        if (env != null && !env.isBlank()) {
            return Path.of(env.replaceFirst("^~", System.getProperty("user.home")));
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".config");
        return base.resolve("marvin");
    }

    public Path path() {
        return path;
    }

    @Override
    public Map<String, Object> load() {
        synchronized (lock) {
            String text;
            try {
                text = Files.readString(path, StandardCharsets.UTF_8);
            } catch (NoSuchFileException e) {
                return new LinkedHashMap<>();
            } catch (IOException e) {
                log.warn("ignoring {}: {}", path, e.getMessage());
                return new LinkedHashMap<>();
            }
            Object data;
            try {
                data = JsonText.parse(text);
            } catch (IllegalArgumentException e) {
                log.warn("ignoring {}: {}", path, e.getMessage());
                return new LinkedHashMap<>();
            }
            if (!(data instanceof Map<?, ?> m)) {
                log.warn("ignoring {}: not a JSON object", path);
                return new LinkedHashMap<>();
            }
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            Set<String> unknown = new TreeSet<>(out.keySet());
            unknown.removeAll(VoiceSettings.FILE_KEYS);
            if (!unknown.isEmpty() && !unknown.toString().equals(warned)) {
                warned = unknown.toString();
                log.warn("{}: unknown keys {} (known: {})", path, unknown, new TreeSet<>(VoiceSettings.FILE_KEYS));
            }
            return out;
        }
    }

    @Override
    public void save(Map<String, Object> values) {
        synchronized (lock) {
            Map<String, Object> data = load();
            data.putAll(values);
            StringBuilder b = new StringBuilder();
            if (data.isEmpty()) {
                b.append("{}");
            } else {
                b.append("{\n");
                int i = 0;
                for (Map.Entry<String, Object> e : data.entrySet()) {
                    b.append("  ").append(JsonText.write(e.getKey())).append(": ").append(JsonText.write(e.getValue()));
                    b.append(++i < data.size() ? ",\n" : "\n");
                }
                b.append("}");
            }
            b.append("\n");
            try {
                Files.createDirectories(path.getParent());
                Path tmp = path.resolveSibling(NAME + ".tmp");
                Files.writeString(tmp, b.toString(), StandardCharsets.UTF_8);
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new IllegalStateException("cannot write " + path + ": " + e.getMessage(), e);
            }
        }
    }
}
