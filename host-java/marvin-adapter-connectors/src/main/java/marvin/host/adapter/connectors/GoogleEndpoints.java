// SPDX-License-Identifier: MIT
package marvin.host.adapter.connectors;

import java.net.URI;

/**
 * Where Google is: the consent page, the token and revocation endpoints, and the APIs' base. Tests point them at a local
 * fake server; nothing else changes them.
 */
public record GoogleEndpoints(URI auth, URI token, URI revoke, URI api) {
    public static final GoogleEndpoints GOOGLE = new GoogleEndpoints(URI.create("https://accounts.google.com/o/oauth2/v2/auth"),
            URI.create("https://oauth2.googleapis.com/token"), URI.create("https://oauth2.googleapis.com/revoke"),
            URI.create("https://www.googleapis.com"));

    /** The same endpoints on another server (a test's): {@code base} + the paths. */
    public static GoogleEndpoints at(URI base) {
        String b = base.toString().replaceAll("/+$", "");
        return new GoogleEndpoints(URI.create(b + "/o/oauth2/v2/auth"), URI.create(b + "/token"), URI.create(b + "/revoke"),
                URI.create(b));
    }
}
