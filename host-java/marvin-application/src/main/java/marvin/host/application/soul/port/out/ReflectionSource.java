// SPDX-License-Identifier: MIT
package marvin.host.application.soul.port.out;

import java.time.Instant;
import java.util.List;

import marvin.host.domain.soul.ReflectionPrompt;

/** The week the reflection reads (from memory): its day summaries, and what the owner said that reads as feedback. */
public interface ReflectionSource {

    /** The day summaries between {@code from} and {@code to}, oldest first ({@code event} 0). */
    List<ReflectionPrompt.Evidence> days(Instant from, Instant to);

    /** The owner's lines between {@code from} and {@code to} that read as feedback, oldest first, with their event ids. */
    List<ReflectionPrompt.Evidence> feedback(Instant from, Instant to);
}
