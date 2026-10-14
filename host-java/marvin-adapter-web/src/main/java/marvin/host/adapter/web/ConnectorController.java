// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import marvin.host.application.connector.port.in.ConnectorControl;

/**
 * The connectors (docs/design.md 7.3; golden/api/get/connectors.json). Connecting runs in the browser of this computer:
 * Google sends it back to this host's loopback address.
 *
 * <ul>
 * <li>{@code GET /api/connectors}: each connector (state, "connected as", scopes, tools and their class, last error) and
 * whether the owner's OAuth client is installed;</li>
 * <li>{@code POST /api/connectors/{id}/connect}: {@code {auth_url}}, the consent page to open (409 without the OAuth
 * client, 404 for an unknown connector);</li>
 * <li>{@code GET /api/connectors/callback}: Google's redirect; a small page that says whether it worked;</li>
 * <li>{@code POST /api/connectors/{id}/disconnect}: the token revoked and forgotten; the connectors.</li>
 * </ul>
 */
@RestController
public class ConnectorController {
    static final String CALLBACK = "/api/connectors/callback";

    private final ConnectorControl connectors;

    public ConnectorController(ConnectorControl connectors) {
        this.connectors = connectors;
    }

    @GetMapping("/api/connectors")
    public ResponseEntity<byte[]> list() {
        return Responses.json(connectors.connectors());
    }

    @PostMapping("/api/connectors/{id}/connect")
    public ResponseEntity<byte[]> connect(@PathVariable String id, HttpServletRequest rq) {
        try {
            return Responses.json(Map.of("auth_url", connectors.connect(id, "http://127.0.0.1:" + rq.getLocalPort() + CALLBACK)));
        } catch (ConnectorControl.NotReady e) {
            return Responses.error(409, e.getMessage());
        } catch (IllegalArgumentException e) {
            return Responses.error(404, "no such connector");
        }
    }

    @PostMapping("/api/connectors/{id}/disconnect")
    public ResponseEntity<byte[]> disconnect(@PathVariable String id) {
        try {
            connectors.disconnect(id);
        } catch (IllegalArgumentException e) {
            return Responses.error(404, "no such connector");
        }
        return Responses.json(connectors.connectors());
    }

    @GetMapping(CALLBACK)
    public ResponseEntity<byte[]> callback(HttpServletRequest rq) {
        String state = rq.getParameter("state");
        String code = rq.getParameter("code");
        String error = rq.getParameter("error");
        try {
            String id = connectors.callback(state, code, error);
            return page(200, "Connected", "Marvin can now use " + ("gmail".equals(id) ? "Gmail" : "Google Calendar")
                    + ". You can close this tab.");
        } catch (ConnectorControl.Failed | IllegalArgumentException e) {
            return page(400, "Not connected", e.getMessage());
        }
    }

    /** A plain page, no script: the browser shows it after Google's consent page. */
    static ResponseEntity<byte[]> page(int status, String title, String text) {
        String html = "<!doctype html>\n<!-- SPDX-License-Identifier: MIT -->\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>Marvin: " + escape(title)
                + "</title><link rel=\"stylesheet\" href=\"/static/style.css\"></head><body class=\"locked\"><main "
                + "class=\"locked-card\"><h1>" + escape(title) + "</h1><p>" + escape(text) + "</p><p><a href=\"/\">Back to "
                + "Marvin</a></p></main></body></html>";
        return Responses.send(status, html.getBytes(StandardCharsets.UTF_8), Responses.HTML);
    }

    static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
