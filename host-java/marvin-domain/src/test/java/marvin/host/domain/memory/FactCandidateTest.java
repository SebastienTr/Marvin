// SPDX-License-Identifier: MIT
package marvin.host.domain.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class FactCandidateTest {
    static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    static FactCandidate.Checked check(String subject, String statement, String sensitivity) {
        return FactCandidate.check(new FactCandidate.Raw(subject, statement, "plan", "2026-10-06", "2026-10-06T18:30",
                12, sensitivity, 1.7), PARIS, Sensitivity.NORMAL);
    }

    @Test
    void aCandidateIsNormalised() {
        FactCandidate c = ((FactCandidate.Accepted) check("User", "  The owner   flies to Oslo  ", "normal")).candidate();
        assertThat(c.subject()).isEqualTo("owner");
        assertThat(c.statement()).isEqualTo("The owner flies to Oslo");
        assertThat(c.kind()).isEqualTo(FactKind.PLAN);
        assertThat(c.importance()).isEqualTo(10);
        assertThat(c.confidence()).isEqualTo(1.0);
        assertThat(c.validFrom()).isEqualTo(Instant.parse("2026-10-05T22:00:00Z"));      // local midnight
        assertThat(c.validTo()).isEqualTo(Instant.parse("2026-10-06T16:30:00Z"));
    }

    @Test
    void subjectsHaveAKnownPrefix() {
        assertThat(FactCandidate.subject("person: Julie")).isEqualTo("person:Julie");
        assertThat(FactCandidate.subject("Place:Lyon")).isEqualTo("place:Lyon");
        assertThat(FactCandidate.subject("Tofu")).isEqualTo("thing:Tofu");
        assertThat(FactCandidate.subject("animal:Tofu")).isEqualTo("thing:Tofu");
        assertThat(FactCandidate.subject("")).isEqualTo("owner");
    }

    @Test
    void secretsAreDroppedAndHealthIsSensitiveByRule() {
        assertThat(check("owner", "The owner's door code is 4812", "secret")).isInstanceOf(FactCandidate.Dropped.class);
        assertThat(check("owner", "The owner's password is hunter22", "normal")).isEqualTo(new FactCandidate.Dropped("secret"));
        assertThat(check("owner", "", "normal")).isInstanceOf(FactCandidate.Dropped.class);
        FactCandidate c = ((FactCandidate.Accepted) check("owner", "The owner sees a doctor for back pain", "normal")).candidate();
        assertThat(c.sensitivity()).isEqualTo(Sensitivity.SENSITIVE);
        FactCandidate fromVitals = ((FactCandidate.Accepted) FactCandidate.check(new FactCandidate.Raw("owner",
                "The owner was calm", null, null, null, null, null, null), PARIS, Sensitivity.SENSITIVE)).candidate();
        assertThat(fromVitals.sensitivity()).isEqualTo(Sensitivity.SENSITIVE);
        assertThat(fromVitals.importance()).isEqualTo(5);
    }

    @Test
    void unreadableDatesAreDropped() {
        assertThat(FactCandidate.date("next week", PARIS)).isNull();
        assertThat(FactCandidate.date("", PARIS)).isNull();
        assertThat(FactCandidate.date("2026-10-06T10:00:00+02:00", PARIS)).isEqualTo(Instant.parse("2026-10-06T08:00:00Z"));
    }
}
