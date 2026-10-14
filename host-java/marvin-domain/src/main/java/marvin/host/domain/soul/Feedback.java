// SPDX-License-Identifier: MIT
package marvin.host.domain.soul;

import java.util.regex.Pattern;

/**
 * The owner's feedback on how Marvin is with them (docs/design.md 8.2), as the weekly reflection reads it: "be quiet",
 * "shorter", "don't remind me during calls", "j'aime bien quand tu…". A rule on what they said, in English and French.
 */
public final class Feedback {
    static final Pattern FEEDBACK = Pattern.compile("(?iu)(?<![\\p{L}])(?:"
            + "be quiet|shut up|stop (?:talking|it|that|reminding|interrupting)|not now|too (?:long|short|much|often|loud|chatty)"
            + "|shorter|longer|less (?:talk|chatty|often)|more briefly|don't (?:remind|interrupt|tell|say|talk|ask)"
            + "|do not (?:remind|interrupt|tell|say|talk|ask)|please (?:don't|do not|stop)|i (?:prefer|like|love|hate|don't like|do not like)"
            + "|i'd rather|thank(?:s| you)|good job|well done|that was (?:helpful|useless|annoying|great)|annoying|you always|you never"
            + "|tais[- ]toi|chut|arr[êe]te|pas maintenant|trop (?:long|court|souvent|bavard|fort)|plus court|moins (?:souvent|bavard)"
            + "|ne me (?:rappelle|dérange|coupe|dis) (?:pas|plus)|je pr[ée]f[èe]re|j'aime (?:bien|pas)|je n'aime pas|merci|c'[ée]tait (?:utile|nul|bien)"
            + "|tu (?:parles|es) trop)(?![\\p{L}])");

    private Feedback() {
    }

    /** Whether a line the owner said reads as feedback on how Marvin behaves. */
    public static boolean looksLikeFeedback(String said) {
        return said != null && !said.isBlank() && FEEDBACK.matcher(said).find();
    }
}
