// SPDX-License-Identifier: MIT
package marvin.host.domain.memory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Secrets are never stored (docs/design.md 5.7): card numbers (checked with Luhn), IBANs, and a value said
 * right after "password", "PIN", "code" (English and French) are replaced before an event enters the log.
 * Rules, not the model's judgement: what they miss, the extraction step still drops as {@code secret}.
 */
public final class Redaction {
    public static final String MARK = "[redacted]";

    private static final Pattern CARD = Pattern.compile("(?<![\\d])(?:\\d[ -]?){12,18}\\d(?![\\d])");
    private static final Pattern IBAN = Pattern.compile("\\b[A-Z]{2}\\d{2}(?:[ ]?[A-Z0-9]{4}){2,7}(?:[ ]?[A-Z0-9]{1,4})?\\b");
    private static final Pattern SAID_SECRET = Pattern.compile(
            "(?iu)\\b(password|passcode|passphrase|pin(?: code)?|security code|access code|mot de passe|code secret|code pin|code d'accès|digicode)"
                    + "(\\s*(?:is|was|:|=|est|c'est|,)?\\s*)(?!(?:is|was|est|c'est)\\b)([\\p{L}\\p{N}!@#$%^&*_\\-]{2,}(?:\\.[\\p{L}\\p{N}!@#$%^&*_\\-]+)*)");

    private Redaction() {
    }

    /** The text with every secret replaced by {@link #MARK}; redacting twice changes nothing more. */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        String out = replaceCards(text);
        out = IBAN.matcher(out).replaceAll(MARK);
        Matcher m = SAID_SECRET.matcher(out);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(b, Matcher.quoteReplacement(m.group(1) + m.group(2) + MARK));
        }
        m.appendTail(b);
        return b.toString();
    }

    private static final Pattern CODE_LIKE = Pattern.compile("(?<![\\p{L}\\p{N}])(?=[\\p{L}\\p{N}]*\\p{N})[\\p{L}\\p{N}]{4,}(?![\\p{L}\\p{N}])");

    /**
     * Stronger, for a text the model said holds a secret that the rules did not find: {@link #redact}, then every word
     * of 4 or more characters with a digit in it (codes, PINs, plate numbers).
     */
    public static String redactLikelyCodes(String text) {
        return CODE_LIKE.matcher(redact(text)).replaceAll(MARK);
    }

    /** Whether {@link #redact} would change the text. */
    public static boolean containsSecret(String text) {
        return text != null && !redact(text).equals(text);
    }

    private static String replaceCards(String text) {
        Matcher m = CARD.matcher(text);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String digits = m.group().replaceAll("[ -]", "");
            m.appendReplacement(b, Matcher.quoteReplacement(luhn(digits) ? MARK : m.group()));
        }
        m.appendTail(b);
        return b.toString();
    }

    static boolean luhn(String digits) {
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        return digits.length() >= 13 && sum % 10 == 0;
    }
}
