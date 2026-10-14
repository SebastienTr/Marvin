// SPDX-License-Identifier: MIT
package marvin.host.adapter.connectors;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import marvin.host.application.connector.port.out.SecretStore;
import marvin.host.domain.shared.JsonText;

/**
 * The last resort for secrets (docs/design.md 7.3): one JSON file only its owner can read and write (0600, in a 0700
 * folder), replaced atomically. Used where there is no Keychain (Linux), or when the owner asks for it.
 */
public final class FileSecretStore implements SecretStore {
    static final Set<PosixFilePermission> FILE = PosixFilePermissions.fromString("rw-------");
    static final Set<PosixFilePermission> DIR = PosixFilePermissions.fromString("rwx------");

    private final Path file;

    public FileSecretStore(Path file) {
        this.file = file;
    }

    @Override
    public synchronized Optional<String> get(String key) {
        return Optional.ofNullable(read().get(key));
    }

    @Override
    public synchronized void put(String key, String value) {
        Map<String, String> all = read();
        all.put(key, value);
        write(all);
    }

    @Override
    public synchronized void delete(String key) {
        Map<String, String> all = read();
        if (all.remove(key) != null) {
            write(all);
        }
    }

    @Override
    public String where() {
        return "a file only you can read (" + file + ")";
    }

    private Map<String, String> read() {
        Map<String, String> out = new TreeMap<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            tighten(file, FILE);
            if (JsonText.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof Map<?, ?> m) {
                m.forEach((k, v) -> {
                    if (v instanceof String s) {
                        out.put(String.valueOf(k), s);
                    }
                });
            }
            return out;
        } catch (IOException | IllegalArgumentException e) {
            throw new Failed("the secrets file cannot be read (" + e.getClass().getSimpleName() + ")");
        }
    }

    private void write(Map<String, String> all) {
        try {
            Path dir = file.toAbsolutePath().getParent();
            if (!Files.isDirectory(dir)) {
                Files.createDirectories(dir);
                tighten(dir, DIR);
            }
            Path tmp = dir.resolve(file.getFileName() + ".tmp");
            Files.deleteIfExists(tmp);
            if (posix(dir)) {
                Files.createFile(tmp, PosixFilePermissions.asFileAttribute(FILE));
            } else {
                Files.createFile(tmp);
            }
            Files.writeString(tmp, JsonText.write(new LinkedHashMap<>(all)), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            tighten(file, FILE);
        } catch (IOException e) {
            throw new Failed("the secrets file cannot be written (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static boolean posix(Path p) {
        return Files.getFileAttributeView(p, java.nio.file.attribute.PosixFileAttributeView.class) != null;
    }

    private static void tighten(Path p, Set<PosixFilePermission> perms) throws IOException {
        if (posix(p) && !Files.getPosixFilePermissions(p).equals(perms)) {
            Files.setPosixFilePermissions(p, perms);
        }
    }
}
