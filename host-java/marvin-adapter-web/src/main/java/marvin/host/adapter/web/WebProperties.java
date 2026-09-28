// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code marvin.web.*}: the app.
 *
 * @param token   the access key for other devices: {@code auto} (a random key kept next to the database
 *                as {@code ui_token}, shared with the Python host), {@code off} (none: anyone on the
 *                network can open the app), or the key itself
 * @param dataDir where {@code ui_token} is kept ({@code marvin.data-dir})
 */
@ConfigurationProperties("marvin.web")
public record WebProperties(String token, Path dataDir) {

    public WebProperties {
        token = token == null || token.isBlank() ? "auto" : token.strip();
    }
}
