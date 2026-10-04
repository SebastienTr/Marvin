// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import marvin.host.application.conversation.port.out.VoiceprintStore;
import marvin.host.domain.conversation.Voiceprint;
import marvin.host.domain.shared.JsonText;

/**
 * The voiceprints of the people Marvin knows, in {@code voiceprints.json} beside {@code voice.json} (docs/voice.md,
 * "Your voice"): {@code {"voiceprints": [{"name", "created", "device", "model", "embedding"}]}}. Readable by its owner
 * only where the file system says so; never sent anywhere but to the voice sidecar on this computer.
 */
public final class VoiceprintsFile implements VoiceprintStore {
    private static final Logger log = LoggerFactory.getLogger("marvin.voice");
    public static final String NAME = "voiceprints.json";

    private final Path path;
    private final Object lock = new Object();

    public VoiceprintsFile(Path path) {
        this.path = path;
    }

    /** {@code voiceprints.json} in the configuration directory (the one of {@code voice.json}). */
    public static VoiceprintsFile inConfigDir(String configured) {
        return new VoiceprintsFile(VoiceSettingsFile.configDir(configured).resolve(NAME));
    }

    public Path path() {
        return path;
    }

    @Override
    public List<Voiceprint> load() {
        synchronized (lock) {
            String text;
            try {
                text = Files.readString(path, StandardCharsets.UTF_8);
            } catch (NoSuchFileException e) {
                return List.of();
            } catch (IOException e) {
                log.warn("ignoring {}: {}", path, e.getMessage());
                return List.of();
            }
            List<Voiceprint> out = new ArrayList<>();
            try {
                if (JsonText.parse(text) instanceof Map<?, ?> m && m.get("voiceprints") instanceof List<?> l) {
                    for (Object o : l) {
                        Voiceprint v = Voiceprint.fromMap(o);
                        if (v != null) {
                            out.add(v);
                        }
                    }
                }
            } catch (IllegalArgumentException e) {
                log.warn("ignoring {}: {}", path, e.getMessage());
            }
            return List.copyOf(out);
        }
    }

    @Override
    public void save(List<Voiceprint> voiceprints) {
        synchronized (lock) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("voiceprints", voiceprints.stream().map(Voiceprint::toMap).toList());
            try {
                Files.createDirectories(path.getParent());
                Path tmp = path.resolveSibling(NAME + ".tmp");
                Files.writeString(tmp, JsonText.write(data) + "\n", StandardCharsets.UTF_8);
                try {
                    Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException e) {
                    // not a POSIX file system: its own rules apply
                }
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new IllegalStateException("cannot write " + path + ": " + e.getMessage(), e);
            }
        }
    }
}
