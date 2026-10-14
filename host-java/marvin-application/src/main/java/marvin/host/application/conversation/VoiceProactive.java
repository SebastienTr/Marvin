// SPDX-License-Identifier: MIT
package marvin.host.application.conversation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

import marvin.host.application.conversation.port.out.VoiceSidecar;
import marvin.host.application.conversation.task.TaskService;
import marvin.host.application.conversation.tools.ToolRegistry;
import marvin.host.domain.conversation.Greeting;
import marvin.host.domain.conversation.ProactiveSpeech;
import marvin.host.domain.conversation.SentenceSplitter;
import marvin.host.domain.conversation.SpeechLanguage;
import marvin.host.domain.conversation.SpeechText;
import marvin.host.domain.conversation.VoiceSettings;
import marvin.host.domain.conversation.VoiceSettings.VoiceConfig;
import marvin.host.domain.presence.event.EventKind;
import marvin.host.domain.presence.event.PresenceEvent;
import marvin.host.domain.presence.event.PresenceState;

/**
 * Marvin speaking first (a collaborator of {@link VoiceService}): from the brain's events (a break reminder, "welcome
 * back", the owner greeted by name when they sit down after an absence), and for background tasks (a finished task said
 * aloud); never during a conversation, and nothing from the brain's events in quiet hours. What it says is written down
 * once said, as a proactive reply ({@code greeting: true} for the greeting).
 */
final class VoiceProactive {
    private static final Logger log = Logger.getLogger("marvin.voice");

    private final VoiceService v;
    private volatile ProactiveSpeech proactive;
    /** The greeting by name ({@code null}: off, or the voice is off). */
    private volatile Greeting greeting;
    /** What Marvin said on its own and the voice has not reported yet, by reply id. */
    private final Map<Long, Said> replies = new ConcurrentHashMap<>();
    private volatile BooleanSupplier quiet = () -> false;
    private volatile Supplier<Greeting.Owner> owner = () -> Greeting.Owner.NONE;

    /** Something Marvin said on its own: its language, and whether it was the greeting. */
    private record Said(String language, boolean greeting) {
    }

    VoiceProactive(VoiceService v) {
        this.v = v;
    }

    /** The voice starts with these settings. */
    void start(VoiceConfig c) {
        // the greeting on sitting down replaces "welcome back" on arriving: one hello per return
        proactive = new ProactiveSpeech(c.reminders(), c.welcomeBack() && !c.greetOnSit());
        Greeting before = greeting;
        greeting = !c.greetOnSit() ? null : before != null ? before : new Greeting();
    }

    /** The voice stops: nothing more on its own. */
    void stop() {
        proactive = null;
        greeting = null;
    }

    void setQuiet(BooleanSupplier q) {
        quiet = q;
    }

    void setOwner(Supplier<Greeting.Owner> o) {
        owner = o;
    }

    private boolean quietNow() {
        try {
            return quiet.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Greeting.Owner owner() {
        try {
            Greeting.Owner o = owner.get();
            return o != null ? o : Greeting.Owner.NONE;
        } catch (RuntimeException e) {
            return Greeting.Owner.NONE;
        }
    }

    /** Someone besides the owner is in the room: the greeting names nobody. */
    private boolean othersPresent() {
        try {
            PresenceState st = v.presence.state();
            return st != null && st.targets() > 1;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A brain event: maybe a break reminder or "welcome back" (never during a conversation). */
    void onPresenceEvent(PresenceEvent event) {
        TaskService ts = v.tasks;
        if (ts != null && event.kind() == EventKind.ARRIVED && !v.closed) {
            v.answers.execute(ts::tryAnnounce);           // a task finished while they were away
        }
        ProactiveSpeech p = proactive;
        if (p == null || !VoiceService.ON.equals(v.state) || !v.opened) {
            return;
        }
        double now = v.clocks.monotonicSeconds();
        Greeting g = greeting;
        if (g != null && g.onEvent(event, now)) {
            greet(g, now);
            return;
        }
        String lang = v.language;
        String text = p.onEvent(event, lang, now).orElse(null);
        if (text == null) {
            return;
        }
        if (quietNow()) {
            log.fine("proactive speech skipped in quiet hours: " + text);
            return;
        }
        if (inConversation()) {
            log.fine("proactive speech skipped during a conversation: " + text);
            return;
        }
        long id = v.replyIds.getAndIncrement();
        replies.put(id, new Said(lang, false));
        v.voice.send(new VoiceSidecar.Say(id, text, lang, false, true, clauses(text, lang)));
        p.spoken(now);
    }

    private boolean inConversation() {
        VoiceSidecar.Status s = v.status;
        return v.turns.active() || s != null && ("thinking".equals(s.state()) || "speaking".equals(s.state()));
    }

    /**
     * The owner sat down after an absence: "Bonjour Sébastien !" in the last language they spoke, a neutral hello when
     * someone else is there or the owner has no name yet; nothing in quiet hours or during a conversation.
     */
    private void greet(Greeting g, double now) {
        Greeting.Owner o = owner();
        String lang = Greeting.language(v.transcript.lastHeardLanguage(), o, v.language);
        String text = Greeting.text(othersPresent() ? "" : o.name(), lang);
        if (quietNow()) {
            log.fine("greeting skipped in quiet hours");
            return;
        }
        if (inConversation()) {
            log.fine("greeting skipped during a conversation");
            return;
        }
        long id = v.replyIds.getAndIncrement();
        replies.put(id, new Said(lang, true));
        v.voice.send(new VoiceSidecar.Say(id, text, lang, false, true, clauses(text, lang)));
        g.greeted(now);
        log.info("greeting: " + text);
    }

    /** Says {@code text} on Marvin's own initiative, now: false when the voice is off or a conversation is going on. */
    boolean speak(String text, String lang) {
        if (!VoiceService.ON.equals(v.state) || !v.opened || v.conversing()) {
            return false;
        }
        long id = v.replyIds.getAndIncrement();
        replies.put(id, new Said(lang, false));
        v.voice.send(new VoiceSidecar.Say(id, text, lang, false, true, clauses(text, lang)));
        return true;
    }

    /** The voice did not say {@code replyId} (a conversation began, it was stopped). */
    void onInterrupted(long replyId, String reason) {
        if (replies.remove(replyId) != null) {
            log.fine("proactive speech skipped: " + reason);
        }
    }

    /** The voice said {@code r}: written down when it was one of Marvin's own and was not cut. */
    void onSpoken(VoiceSidecar.ReplySpoken r) {
        Said said = replies.remove(r.replyId());
        if (said != null && !r.interrupted()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("language", said.language());
            d.put("latency", Map.of());
            d.put("first_word_s", null);
            d.put("interrupted", false);
            d.put("proactive", true);
            d.put("error", null);
            d.put("hint", "");
            if (said.greeting()) {
                d.put("greeting", true);        // the owner greeted on sitting down (the greeting event, C5)
            }
            v.transcript.entry("reply", v.clocks.wallSeconds(), r.text(), d);
        }
    }

    /** {@code text} as the voice says it: its sentences, each cut where it changes language. */
    static List<SpeechLanguage.Clause> clauses(String text, String language) {
        List<SpeechLanguage.Clause> out = new ArrayList<>();
        for (String sentence : SentenceSplitter.split(SpeechText.cleanForSpeech(text))) {
            out.addAll(SpeechLanguage.clauses(sentence, language));
        }
        return out;
    }

    // ------------------------------------------------------------------ what the tasks need of the conversation

    /** The conversation as background tasks see it: its model and tools, the profile, the voice, who is there. */
    final class TaskHost implements TaskService.Host {
        @Override
        public VoiceConfig config() {
            VoiceConfig c = v.config;
            return c != null ? c : VoiceSettings.config(v.settings());
        }

        @Override
        public ToolRegistry tools() {
            ToolRegistry reg = v.routing.currentTools();
            if (reg == null) {
                VoiceConfig c = config();
                reg = v.routing.registry(c.tools(), c.internet(), c.homePlace(), v.routing.cameraAvailable());
            }
            return reg;
        }

        @Override
        public String profile() {
            return v.routing.profile().text();
        }

        @Override
        public boolean voiceOn() {
            return VoiceService.ON.equals(v.state) && v.opened;
        }

        @Override
        public boolean conversing() {
            return v.conversing();
        }

        @Override
        public boolean speak(String text, String language) {
            return VoiceProactive.this.speak(text, language);
        }

        @Override
        public Boolean present() {
            try {
                PresenceState st = v.presence.state();
                if (st == null || st.tUs() == 0) {
                    return null;                    // no presence sensor has said anything
                }
                return st.present() || st.motionPresence();
            } catch (RuntimeException e) {
                return null;
            }
        }

        @Override
        public double sinceTalkS() {
            double t = v.turns.lastTalkAt();
            return t == Double.NEGATIVE_INFINITY ? Double.POSITIVE_INFINITY : v.clocks.monotonicSeconds() - t;
        }

        @Override
        public boolean quiet() {
            return quietNow();
        }
    }
}
