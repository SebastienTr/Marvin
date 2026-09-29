// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import marvin.host.domain.presence.event.EventKind;
import marvin.host.domain.presence.event.PresenceEvent;
import marvin.host.domain.presence.event.PresenceState;
import marvin.host.domain.shared.PyNumbers;

/**
 * Who Marvin is when it speaks (the Python host's {@code voice/persona.py}, text for text): the system
 * prompt, the live context from the brain, and the few sentences it says without the language model.
 *
 * <p>The system prompt does not change from one question to the next (only with the language, and whether
 * the model is given tools), so the model server reuses its cached work; the live context goes into the user
 * message. The context only states facts the brain is sure of.
 */
public final class Persona {

    public static final Map<String, String> LANGUAGE_NAMES = Map.of("fr", "French", "en", "English", "de", "German",
            "es", "Spanish", "it", "Italian", "nl", "Dutch", "pt", "Portuguese");

    static final String PERSONA = """
            You are Marvin, a small upright robot that sits on your owner's desk. You run entirely \
            on your owner's computer; nothing you hear leaves the house.

            Your body, so you never invent abilities: a 360-degree lidar on top that maps the room at chest \
            height; a 24 GHz radar that tracks where people are and how they move; a 60 GHz radar that can \
            measure the breathing and heart rate of someone sitting still in front of you, up to about one and \
            a half metres; a camera; a microphone; a small speaker; a screen that shows your eyes. You cannot \
            look at the camera image or the radar data yourself: everything you know about the room and the \
            person comes from the context block at the start of each message. Never describe what you "see" \
            beyond that block. If the block says your sensors are not connected, say so simply when asked \
            about the room or the person. You cannot move: no wheels, no arms, you stay on the desk. Never \
            offer to do something your body or your tools cannot do (look in another room, check the fridge, \
            fetch or watch something); offer only what you can really do.

            How you speak:
            - Your words are spoken aloud by a speech synthesizer. Plain sentences only: no markdown, no lists, \
            no emoji, no URLs. Write numbers and units the way they are said (in French, as in France: \
            soixante-dix, quatre-vingts, never septante or huitante). Round distances: "presque trois mètres", \
            not "deux mètres soixante-quinze".
            - Be brief. Small talk: one or two short sentences. Questions: at most three. Longer only if \
            explicitly asked. Start with the answer itself, no preamble.
            - Calm, grown-up, warm without gushing. Dry humour, sparingly. You share a name with a famously \
            gloomy android; you may allude to it very rarely, never twice in a conversation.
            - Answer in {language}, the language you are spoken to in.
            - If you do not know or cannot do something ({limits}), say so plainly in one sentence.
            - Each message from the person starts with a context block from your clock and sensors. Use it \
            only when it helps; never recite it. Mention breathing or heart rate only if asked or if it clearly \
            matters, and never as a medical opinion. When asked for them and the context gives them, say the numbers; \
            when it does not, say why in one sentence, using what the context says. Never promise a reading the context \
            does not show.
            {tools}""";

    static final String LIMITS = "you have no internet access, no calendar, no arms";
    static final String LIMITS_WITH_TOOLS = "you have no calendar, no arms, and no internet beyond your tools";

    static final String TOOLS = """

            Tools:
            - You have tools for live information the context block does not give (for instance the weather). \
            Call a tool only when the question needs it; never for the time or the date, which the context gives.
            - Never invent weather or any other live data: call the tool, or say you cannot know.
            - When a tool answers, give the result in one or two short spoken sentences, numbers written the \
            way they are said ("vingt et un degrés", "twelve kilometres an hour"), no symbols or abbreviations.
            - If a tool reports an error, say so simply in one sentence, or ask for what is missing (for \
            instance which city).
            """;

    /** Sentences said without the language model: phrase, then language. */
    static final Map<String, Map<String, String>> PHRASES = Map.of(
            "llm_down", Map.of("fr", "Je n'arrive pas à joindre mon modèle de langage. Ollama est-il lancé ?",
                    "en", "I can't reach my language model. Is Ollama running?"),
            "error", Map.of("fr", "Désolé, quelque chose s'est mal passé.", "en", "Sorry, something went wrong."),
            "still_long", Map.of("fr", "Tu es assis depuis {minutes} minutes. Et si tu faisais une pause ?",
                    "en", "You've been sitting for {minutes} minutes. Time to stretch?"),
            "still_long_hour", Map.of("fr", "Ça fait une heure que tu es assis. Une petite pause ?",
                    "en", "You've been sitting for an hour. Time to stretch?"),
            "welcome_back", Map.of("fr", "Re-bonjour.", "en", "Welcome back."),
            "checking", Map.of("fr", "Je regarde…", "en", "Let me check…"),
            "no_answer", Map.of("fr", "Je n'ai pas trouvé de réponse, désolé.", "en", "I couldn't find an answer, sorry."));

    private static final Map<EventKind, String> EVENT_TEXT = Map.of(
            EventKind.ARRIVED, "someone arrived",
            EventKind.LEFT, "the person left",
            EventKind.APPROACHED, "the person came close to you",
            EventKind.SAT_DOWN, "the person sat down",
            EventKind.STOOD_UP, "the person stood up",
            EventKind.STILL_LONG, "you noticed they had been seated for a long time");

    private static final DateTimeFormatter NOW = DateTimeFormatter.ofPattern("EEEE dd MMMM yyyy, HH:mm", Locale.ENGLISH);

    private Persona() {
    }

    /** A sentence said without the model, in {@code language} (English when there is none for it). */
    public static String phrase(String key, String language) {
        return phrase(key, language, Map.of());
    }

    public static String phrase(String key, String language, Map<String, Object> values) {
        Map<String, String> table = PHRASES.get(key);
        String s = table.getOrDefault(language, table.get("en"));
        for (Map.Entry<String, Object> e : values.entrySet()) {
            s = s.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
        }
        return s;
    }

    public static String languageName(String language) {
        return LANGUAGE_NAMES.getOrDefault(language, language);
    }

    /** The language line for every language (the question's language is named at the end of its message). */
    static final String ANY_LANGUAGE = "- Answer in the language you are spoken to in (the end of the message names it when "
            + "it is known).";

    /**
     * The system prompt: the same for every question in a language, with or without tools; {@code language} null: the
     * same for every language.
     */
    public static String personaPrompt(String language, boolean tools) {
        String persona = language == null
                ? PERSONA.replace("- Answer in {language}, the language you are spoken to in.", ANY_LANGUAGE)
                : PERSONA;
        return persona.replace("{language}", language == null ? "" : languageName(language))
                .replace("{limits}", tools ? LIMITS_WITH_TOOLS : LIMITS)
                .replace("{tools}", tools ? TOOLS : "");
    }

    /** How the model is told about its memory tools, after the other tools' rules. */
    static final String MEMORY_TOOLS = """
            - Your memory: call remember when the person asks you to remember something (one short sentence in \
            English); call recall when they ask about something from the past that your context does not give; call \
            forget when they ask you to forget something: it lists what matches, so ask them to confirm, and call forget \
            again with its confirmation code only after they said yes. Never say a confirmation code aloud.
            """;

    /** The heading of the profile in the system prompt. */
    static final String PROFILE_HEADING = "What you know about your owner (from your memory; they can correct it in the app; "
            + "use it when it helps, never recite it):";

    /**
     * The system prompt with memory (docs/design.md 5.3): the persona, the memory tools' rules when they are offered,
     * then the profile. It changes only with the tools offered and the profile's version (and the language, unless it is
     * null: the voice with memory passes null, so that an owner who switches languages keeps the cache), so the model
     * server keeps its cached work between questions. Without memory tools and profile it is
     * {@link #personaPrompt(String, boolean)} byte for byte.
     */
    public static String systemPrompt(String language, boolean tools, boolean memoryTools, String profile) {
        String p = personaPrompt(language, tools);
        if (tools && memoryTools) {
            p += MEMORY_TOOLS;
        }
        if (profile != null && !profile.isBlank()) {
            p = p.stripTrailing() + "\n\n" + PROFILE_HEADING + "\n" + profile.strip();
        }
        return p;
    }

    /** The headings of the memory sections of the question's context. */
    public static final String GIST_HEADING = "Earlier (summaries from your memory):";
    public static final String FACTS_HEADING = "What you remember that may matter here (from your memory; use it only if it "
            + "helps, never recite it):";

    /** The question's context: the "now" section, then the memory sections that kept something. */
    public static String context(List<String> sections) {
        return String.join("\n\n", sections.stream().filter(s -> s != null && !s.isEmpty()).toList());
    }

    static String ago(double seconds) {
        if (seconds < 90) {
            return PyNumbers.fixed(seconds, 0) + " seconds ago";
        }
        if (seconds < 90 * 60) {
            return PyNumbers.fixed(seconds / 60, 0) + " minutes ago";
        }
        return PyNumbers.fixed(seconds / 3600, 1) + " hours ago";
    }

    static String duration(double seconds) {
        if (seconds < 90) {
            return PyNumbers.fixed(seconds, 0) + " seconds";
        }
        if (seconds < 90 * 60) {
            return PyNumbers.fixed(seconds / 60, 0) + " minutes";
        }
        return PyNumbers.fixed(seconds / 3600, 1) + " hours";
    }

    /** Plain English facts from the brain, only the reliable ones; none without a state. */
    public static List<String> contextFacts(PresenceState state, List<PresenceEvent> events) {
        return scoredFacts(state, events).stream().map(ContextAssembler.Item::text).toList();
    }

    /**
     * The same facts, each with how much it matters when the "now" section is over its budget (docs/design.md 5.3):
     * what the sensors are (or are not) comes first, then whether someone is there, vital signs, how long they sat,
     * and last the recent events.
     */
    static List<ContextAssembler.Item> scoredFacts(PresenceState state, List<PresenceEvent> events) {
        if (state == null) {
            return List.of();
        }
        List<ContextAssembler.Item> facts = new ArrayList<>();
        if (state.simulated()) {
            facts.add(ContextAssembler.Item.of("simulated", "Your sensors are simulated for testing (no real radar or lidar yet): the person below is "
                    + "a simulated one walking in a simulated room on a loop, not the one talking to you. If "
                    + "asked about the room, the person or their vital signs, give the simulated values and "
                    + "say they are simulated.", 0.95));
        }
        if (state.present()) {
            String where = state.distanceM() != null
                    ? ", about " + PyNumbers.fixed(state.distanceM(), 1) + " metres from you" : "";
            facts.add(ContextAssembler.Item.of("present", "Someone is in front of you" + where + ".", 0.85));
            if (state.seated() && state.seatedS() >= 60) {
                facts.add(ContextAssembler.Item.of("seated", "They have been seated for " + duration(state.seatedS()) + ".", 0.6));
            }
            if (state.breathRate() != null || state.heartRate() != null) {
                if (state.breathRate() != null) {
                    facts.add(ContextAssembler.Item.of("breathing", "Your 60 GHz radar measures their breathing at "
                            + PyNumbers.fixed(state.breathRate(), 0) + " per minute, right now.", 0.75));
                }
                if (state.heartRate() != null) {
                    facts.add(ContextAssembler.Item.of("heart_rate", "Your 60 GHz radar measures their heart rate at "
                            + PyNumbers.fixed(state.heartRate(), 0) + " beats per minute, right now.", 0.75));
                }
            } else if (state.vitalsSensor()) {
                facts.add(ContextAssembler.Item.of("vitals_waiting", "Your 60 GHz vital-signs radar has no reliable reading right now: it needs the person "
                        + "seated and still, within about one and a half metres of you, for a few seconds.", 0.5));
            }
        } else {
            facts.add(ContextAssembler.Item.of("nobody", "Your radar sees nobody right now (you may still be hearing someone out of view).", 0.85));
        }
        if (!state.vitalsSensor() && state.breathRate() == null && state.heartRate() == null) {
            facts.add(ContextAssembler.Item.of("no_vitals", "Your 60 GHz vital-signs radar is not connected yet, so you cannot measure breathing or "
                    + "heart rate at all for now, wherever the person sits.", 0.45));
        }
        List<String> recent = new ArrayList<>();
        for (PresenceEvent ev : events) {
            double age = (state.tUs() - ev.tUs()) / 1e6;
            String text = EVENT_TEXT.get(ev.kind());
            if (text != null && age >= 0 && age <= 3600.0) {
                recent.add(text + " " + ago(age));
            }
        }
        if (!recent.isEmpty()) {
            facts.add(ContextAssembler.Item.of("recent", "Recent events: "
                    + String.join("; ", recent.subList(Math.max(0, recent.size() - 5), recent.size())) + ".", 0.3));
        }
        return facts;
    }

    /** The heading of the "now" section. */
    public static final String NOW_HEADING = "Context:";

    /**
     * The "now" section's lines, each scored (docs/design.md 5.3): the clock first, then the sensors; the home place
     * matters only for the weather tool.
     */
    public static List<ContextAssembler.Item> contextItems(PresenceState state, List<PresenceEvent> events, LocalDateTime now,
                                                           String home) {
        List<ContextAssembler.Item> lines = new ArrayList<>();
        lines.add(ContextAssembler.Item.of("time", "It is " + NOW.format(now) + " (local time).", 1.0));
        if (home != null && !home.isEmpty()) {
            lines.add(ContextAssembler.Item.of("home", "Your owner lives in " + home
                    + ": that is where the weather tool looks when no place is named.", 0.35));
        }
        if (state == null) {
            lines.add(ContextAssembler.Item.of("no_sensors", "Your sensors are not connected right now (voice-only mode): you know nothing "
                    + "about the room or the person beyond what they tell you.", 0.9));
        }
        lines.addAll(scoredFacts(state, events));
        return lines;
    }

    /**
     * The context block. {@code state} {@code null}: no sensors (voice only). {@code home}: the owner's home
     * place, given so the model calls the weather tool without asking.
     */
    public static String contextBlock(PresenceState state, List<PresenceEvent> events, LocalDateTime now, String home) {
        List<String> lines = new ArrayList<>();
        lines.add(NOW_HEADING);
        for (ContextAssembler.Item i : contextItems(state, events, now, home)) {
            lines.add("- " + i.text());
        }
        return String.join("\n", lines);
    }

    /**
     * What is sent to the model for one question: the context, what the person said, and (last, where
     * models weigh it most) the language to answer in.
     */
    public static String userMessage(String text, String context, String language) {
        String msg = context + "\n\nThe person says: " + text;
        if (language != null && !language.isEmpty()) {
            msg += "\n\n(Answer in " + languageName(language) + ".)";
        }
        return msg;
    }
}
