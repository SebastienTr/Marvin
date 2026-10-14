// SPDX-License-Identifier: MIT
package marvin.host.adapter.connectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import marvin.host.application.connector.port.out.SecretStore;

/** Where tokens are kept: a 0600 file in a 0700 folder, or the macOS Keychain with the secret never on a command line. */
class SecretStoresTest {
    @TempDir
    Path dir;

    @Test
    void theFileIsOnlyItsOwnersAndKeepsEachKey() throws Exception {
        Path file = dir.resolve("secrets").resolve("secrets.json");
        FileSecretStore s = new FileSecretStore(file);
        assertThat(s.get("connector.gmail")).isEmpty();
        s.put("connector.gmail", "{\"refresh_token\": \"rt-1\"}");
        s.put("connector.google_calendar", "{\"refresh_token\": \"rt-2\"}");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent()))).isEqualTo("rwx------");
        assertThat(new FileSecretStore(file).get("connector.gmail")).contains("{\"refresh_token\": \"rt-1\"}");
        s.delete("connector.gmail");
        assertThat(s.get("connector.gmail")).isEmpty();
        assertThat(s.get("connector.google_calendar")).isPresent();
        assertThat(Files.list(file.getParent()).map(p -> p.getFileName().toString())).containsExactly("secrets.json");
        // a file loosened by hand is tightened again
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        s.get("connector.google_calendar");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        Files.writeString(file, "not json");
        assertThatThrownBy(() -> s.get("x")).isInstanceOf(SecretStore.Failed.class).hasMessageNotContaining("not json");
    }

    record Call(List<String> command, String stdin) {
    }

    @Test
    void theKeychainGetsTheSecretOnItsStandardInputOnly() {
        List<Call> calls = new ArrayList<>();
        String[] stored = {null};
        KeychainSecretStore k = new KeychainSecretStore((command, stdin) -> {
            calls.add(new Call(command, stdin));
            if (command.contains("find-generic-password")) {
                return stored[0] == null ? new KeychainSecretStore.Result(44, "")
                        : new KeychainSecretStore.Result(0, stored[0] + "\n");
            }
            if (command.contains("-i")) {
                stored[0] = new String(HexFormat.of().parseHex(stdin.replaceAll("(?s).* -X ([0-9a-f]+)\n.*", "$1")),
                        StandardCharsets.UTF_8);
            }
            return new KeychainSecretStore.Result(0, "");
        });
        assertThat(k.get("connector.gmail")).isEmpty();
        k.put("connector.gmail", "{\"refresh_token\": \"rt-secret\"}");
        Call put = calls.getLast();
        assertThat(put.command()).containsExactly("/usr/bin/security", "-i");
        assertThat(String.join(" ", put.command())).doesNotContain("rt-secret");
        assertThat(put.stdin()).startsWith("add-generic-password -U -a marvin -s marvin.connector.gmail ").doesNotContain("rt-secret");
        assertThat(k.get("connector.gmail")).contains("{\"refresh_token\": \"rt-secret\"}");
        assertThat(calls.getLast().command()).containsExactly("/usr/bin/security", "find-generic-password", "-a", "marvin", "-s",
                "marvin.connector.gmail", "-w");
        // data the tool shows as hexadecimal is read back
        stored[0] = HexFormat.of().formatHex("{\"a\": 1}".getBytes(StandardCharsets.UTF_8));
        assertThat(k.get("connector.gmail")).contains("{\"a\": 1}");
        k.delete("connector.gmail");
        assertThat(calls.getLast().command()).contains("delete-generic-password");
        assertThatThrownBy(() -> k.get("bad key; rm -rf")).isInstanceOf(IllegalArgumentException.class);
        assertThat(k.where()).isEqualTo("the macOS Keychain");
    }

    @Test
    void choosingAStore() {
        Path file = dir.resolve("s.json");
        assertThat(SecretStores.choose("auto", file, "Linux")).isInstanceOf(FileSecretStore.class);
        assertThat(SecretStores.choose("file", file, "Mac OS X")).isInstanceOf(FileSecretStore.class);
        assertThat(SecretStores.choose("keychain", file, "Linux")).isInstanceOf(KeychainSecretStore.class);
        assertThat(SecretStores.choose("memory", file, "Linux")).isInstanceOf(MemorySecretStore.class);
        assertThatThrownBy(() -> SecretStores.choose("vault", file, "Linux")).isInstanceOf(IllegalArgumentException.class);
    }
}
