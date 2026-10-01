// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import marvin.host.domain.conversation.SpeechLanguage.Clause;

class SpeechLanguageTest {

    static Clause fr(String t) {
        return new Clause(t, "fr");
    }

    static Clause en(String t) {
        return new Clause(t, "en");
    }

    @Test
    void anEnglishRunInAFrenchSentenceIsItsOwnClause() {
        assertThat(SpeechLanguage.clauses("C'est une bonne idée, let me know when it's done.", "fr"))
                .containsExactly(fr("C'est une bonne idée,"), en("let me know when it's done."));
        assertThat(SpeechLanguage.clauses("Bonjour Sébastien, I hope you are well today. On commence ?", "fr"))
                .containsExactly(fr("Bonjour Sébastien,"), en("I hope you are well today."), fr("On commence ?"));
        assertThat(SpeechLanguage.clauses("Le weekend, it was really great fun.", "fr"))
                .containsExactly(fr("Le weekend,"), en("it was really great fun."));
    }

    @Test
    void isolatedAnglicismsStayInTheFrenchClause() {
        for (String s : List.of("Je vais lancer le build demain.", "On fait une code review cet après-midi ?",
                "Le meeting est à quinze heures, ok ?", "Il fait beau à Nice, tu as de la chance.",
                "C'est le bug du parser, pas du lexer.", "Marvin, c'est moi.")) {
            assertThat(SpeechLanguage.clauses(s, "fr")).as(s).containsExactly(fr(s));
        }
    }

    @Test
    void aFrenchRunInAnEnglishSentence() {
        assertThat(SpeechLanguage.clauses("He said je ne sais pas, which is funny.", "en"))
                .containsExactly(en("He said"), fr("je ne sais pas,"), en("which is funny."));
        assertThat(SpeechLanguage.clauses("The croissant was great.", "en")).containsExactly(en("The croissant was great."));
    }

    @Test
    void aSentenceWithNothingOfTheRepliesLanguageIsTheOthers() {
        assertThat(SpeechLanguage.clauses("Yes, of course.", "fr")).containsExactly(en("Yes, of course."));
        assertThat(SpeechLanguage.clauses("The answer is forty-two.", "fr")).containsExactly(en("The answer is forty-two."));
        assertThat(SpeechLanguage.clauses("D'accord, très bien.", "en")).containsExactly(fr("D'accord, très bien."));
        assertThat(SpeechLanguage.clauses("OK.", "fr")).containsExactly(fr("OK."));            // shows nothing: the reply's
        assertThat(SpeechLanguage.clauses("Twenty-one.", "en")).containsExactly(en("Twenty-one."));
    }

    @Test
    void edges() {
        assertThat(SpeechLanguage.clauses("  ", "fr")).isEmpty();
        assertThat(SpeechLanguage.clauses("Hola, ¿qué tal?", "es")).containsExactly(new Clause("Hola, ¿qué tal?", "es"));
        // two English words are not enough to switch voices mid-sentence
        assertThat(SpeechLanguage.clauses("Je te dis thank you pour tout.", "fr"))
                .containsExactly(fr("Je te dis thank you pour tout."));
        assertThat(SpeechLanguage.wordLanguage("l’ordinateur")).isEqualTo("fr");
        assertThat(SpeechLanguage.wordLanguage("on")).isNull();
        assertThat(SpeechLanguage.wordLanguage("Nice")).isNull();
    }
}
