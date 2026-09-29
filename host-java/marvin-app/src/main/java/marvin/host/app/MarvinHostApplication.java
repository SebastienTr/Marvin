// SPDX-License-Identifier: MIT
package marvin.host.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Marvin's host: {@code java -jar marvin-host.jar}, or {@code ./marvin up} at the repository root. */
@SpringBootApplication(scanBasePackages = "marvin.host")
public class MarvinHostApplication {

    /** The fewest carrier threads for virtual threads, whatever the number of processors. */
    static final int MIN_CARRIERS = 8;

    public static void main(String[] args) {
        carriers();
        SpringApplication.run(MarvinHostApplication.class, args);
    }

    /**
     * Before the first virtual thread: at least {@link #MIN_CARRIERS} carrier threads. A virtual thread that loads a
     * class stays pinned to its carrier; with one carrier per processor, two of them waiting for the application
     * jar's lock on a two-processor machine left no carrier for the virtual thread holding it, and the host hung at
     * start. Idle carriers cost nothing. An explicit {@code -Djdk.virtualThreadScheduler.parallelism} is kept.
     */
    static void carriers() {
        String key = "jdk.virtualThreadScheduler.parallelism";
        if (System.getProperty(key) == null) {
            System.setProperty(key, Integer.toString(Math.max(MIN_CARRIERS, Runtime.getRuntime().availableProcessors())));
        }
    }
}
