// SPDX-License-Identifier: MIT
package marvin.host.domain.connector;

import java.util.Collection;
import java.util.Set;

/**
 * Which OAuth scopes Marvin may ever ask for (docs/design.md 7.3): an allow-list of the narrowest ones its tools need.
 * Never a scope whose purpose is to send ({@code gmail.send}), to delete or change a mailbox ({@code gmail.modify},
 * {@code https://mail.google.com/}), to write a calendar, or anything else; a token granted more than asked is refused
 * too.
 *
 * <p>{@code gmail.compose} is the narrowest scope that can create a draft; Google lets it send drafts as well, so the
 * Gmail connector's HTTP client allows only the calls it needs (search, read, create a draft) and refuses every other,
 * the draft-sending one first. Sending stays the owner's click in Gmail.
 */
public final class ScopePolicy {
    /** The only scopes that may be asked for or kept. */
    public static final Set<String> ALLOWED = Set.of(Connector.CALENDAR_READONLY, Connector.GMAIL_READONLY,
            Connector.GMAIL_COMPOSE);

    private ScopePolicy() {
    }

    /** @throws IllegalArgumentException a scope outside {@link #ALLOWED} */
    public static void check(Collection<String> scopes) {
        for (String s : scopes) {
            if (!ALLOWED.contains(s)) {
                throw new IllegalArgumentException("Marvin never asks for the scope " + s);
            }
        }
    }

    /** Whether a token's granted scopes are all allowed (Google may grant earlier scopes again: those count). */
    public static boolean grantedOk(Collection<String> granted) {
        return granted.stream().allMatch(s -> ALLOWED.contains(s) || "openid".equals(s)
                || s.endsWith("/userinfo.email") || "email".equals(s));
    }
}
