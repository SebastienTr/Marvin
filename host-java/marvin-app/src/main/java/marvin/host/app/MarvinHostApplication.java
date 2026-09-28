// SPDX-License-Identifier: MIT
package marvin.host.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Marvin's host: {@code java -jar marvin-host.jar}, or {@code ./marvin up} at the repository root. */
@SpringBootApplication(scanBasePackages = "marvin.host")
public class MarvinHostApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarvinHostApplication.class, args);
    }
}
