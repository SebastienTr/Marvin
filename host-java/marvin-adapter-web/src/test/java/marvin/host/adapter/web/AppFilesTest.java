// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The app's files are the Python host's (host/marvin_host/ui/static), byte for byte: the app works
 * unchanged with both hosts. A change to the app goes to both copies.
 */
class AppFilesTest {

    static Path pythonStatic() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("host/marvin_host/ui/static"))) {
            p = p.getParent();
        }
        Assumptions.assumeTrue(p != null, "the Python host is not in a parent directory");
        return p.resolve("host/marvin_host/ui/static");
    }

    @Test
    void sameFilesAsThePythonHost() throws Exception {
        Path dir = pythonStatic();
        try (var files = Files.list(dir)) {
            for (Path f : files.toList()) {
                String name = f.getFileName().toString();
                assertThat(AppController.STATIC_TYPES).as("served: " + name).containsKey(name);
                try (InputStream in = AppFilesTest.class.getResourceAsStream("/app/" + name)) {
                    assertThat(in).as(name).isNotNull();
                    assertThat(in.readAllBytes()).as(name + " differs from the Python host's copy").isEqualTo(Files.readAllBytes(f));
                }
            }
        }
    }
}
