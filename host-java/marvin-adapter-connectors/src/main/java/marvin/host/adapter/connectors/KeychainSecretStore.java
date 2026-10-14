// SPDX-License-Identifier: MIT
package marvin.host.adapter.connectors;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import marvin.host.application.connector.port.out.SecretStore;

/**
 * Secrets in the macOS Keychain (docs/design.md 7.3), through Apple's {@code security} tool: generic passwords of the
 * service {@code marvin.<key>}, account {@code marvin}. A secret is written through the tool's standard input in its
 * interactive mode, as hexadecimal ({@code -X}), so it never appears on a command line that another process could read.
 */
public final class KeychainSecretStore implements SecretStore {
    static final String TOOL = "/usr/bin/security";
    static final String ACCOUNT = "marvin";
    /** {@code security}'s exit status when the item does not exist. */
    static final int NOT_FOUND = 44;

    /** Runs a command; tests replace it. */
    @FunctionalInterface
    interface Runner {
        Result run(List<String> command, String stdin) throws IOException;
    }

    record Result(int exit, String out) {
    }

    private final Runner runner;

    public KeychainSecretStore() {
        this(KeychainSecretStore::exec);
    }

    KeychainSecretStore(Runner runner) {
        this.runner = runner;
    }

    static String service(String key) {
        if (!key.matches("[A-Za-z0-9._-]{1,100}")) {
            throw new IllegalArgumentException("a secret's key is letters, digits, dots, dashes and underscores");
        }
        return "marvin." + key;
    }

    @Override
    public Optional<String> get(String key) {
        Result r = run(List.of(TOOL, "find-generic-password", "-a", ACCOUNT, "-s", service(key), "-w"), null);
        if (r.exit() == NOT_FOUND) {
            return Optional.empty();
        }
        if (r.exit() != 0) {
            throw new Failed("the Keychain refused to read a secret (exit " + r.exit() + ")");
        }
        String v = r.out().strip();
        // printable data comes back as it is; data the tool deems binary comes back as hexadecimal
        if (!v.startsWith("{") && v.length() % 2 == 0 && v.matches("[0-9a-fA-F]+")) {
            v = new String(HexFormat.of().parseHex(v), StandardCharsets.UTF_8);
        }
        return Optional.of(v);
    }

    @Override
    public void put(String key, String value) {
        String hex = HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8));
        String line = "add-generic-password -U -a " + ACCOUNT + " -s " + service(key) + " -l Marvin-" + key + " -X " + hex
                + "\n";
        Result r = run(List.of(TOOL, "-i"), line);
        if (r.exit() != 0) {
            throw new Failed("the Keychain refused to keep a secret (exit " + r.exit() + ")");
        }
    }

    @Override
    public void delete(String key) {
        Result r = run(List.of(TOOL, "delete-generic-password", "-a", ACCOUNT, "-s", service(key)), null);
        if (r.exit() != 0 && r.exit() != NOT_FOUND) {
            throw new Failed("the Keychain refused to delete a secret (exit " + r.exit() + ")");
        }
    }

    @Override
    public String where() {
        return "the macOS Keychain";
    }

    private Result run(List<String> command, String stdin) {
        try {
            return runner.run(command, stdin);
        } catch (IOException e) {
            throw new Failed("the Keychain cannot be reached (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Runs {@code security}; its output is read, never logged. */
    static Result exec(List<String> command, String stdin) throws IOException {
        Process p = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try (OutputStream in = p.getOutputStream()) {
            if (stdin != null) {
                in.write(stdin.getBytes(StandardCharsets.UTF_8));
                in.write("quit\n".getBytes(StandardCharsets.UTF_8));
            }
        }
        String out;
        try (InputStream o = p.getInputStream()) {
            out = new String(o.readAllBytes(), StandardCharsets.UTF_8);
        }
        try {
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("security did not answer");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("interrupted");
        }
        return new Result(p.exitValue(), out);
    }
}
