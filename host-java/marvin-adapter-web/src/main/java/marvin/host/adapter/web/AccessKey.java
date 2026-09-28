// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The app's access key (docs/ui.md): requests from this computer need nothing, other devices need the
 * key, as {@code ?token=}, {@code Authorization: Bearer} or the {@code marvin_key} cookie. With
 * {@code auto}, a random key is created on first run and kept next to the database in {@code ui_token},
 * the same file the Python host uses, so both hosts accept the same key.
 */
public final class AccessKey {
    private static final Logger log = LoggerFactory.getLogger(AccessKey.class);
    static final String FILE = "ui_token";

    private final String key;

    private AccessKey(String key) {
        this.key = key;
    }

    /** From {@code marvin.web.token}. */
    public static AccessKey of(WebProperties props) {
        return switch (props.token()) {
            case "off", "none" -> new AccessKey(null);
            case "auto" -> new AccessKey(auto(props.dataDir()));
            default -> new AccessKey(props.token());
        };
    }

    static AccessKey fixed(String key) {
        return new AccessKey(key);
    }

    /** The key, or {@code null} when there is none. */
    public String value() {
        return key;
    }

    public boolean enabled() {
        return key != null;
    }

    /** Constant-time comparison. */
    public boolean matches(String given) {
        return key != null && given != null
                && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8));
    }

    private static String auto(Path dir) {
        if (dir == null) {
            return random();
        }
        Path file = dir.resolve(FILE);
        try {
            if (Files.isRegularFile(file)) {
                String v = Files.readString(file).strip();
                if (!v.isEmpty()) {
                    return v;
                }
            }
        } catch (IOException e) {
            // make a new one below
        }
        String v = random();
        try {
            Files.createDirectories(dir);
            if (!Files.exists(file)) {
                try {
                    Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                } catch (UnsupportedOperationException e) {
                    Files.createFile(file);
                }
            }
            Files.writeString(file, v + "\n");
        } catch (IOException e) {
            log.warn("could not save the access key to {}", file);
        }
        return v;
    }

    /** 12 random bytes, URL-safe base64 (Python's {@code secrets.token_urlsafe(12)}). */
    private static String random() {
        byte[] b = new byte[12];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
