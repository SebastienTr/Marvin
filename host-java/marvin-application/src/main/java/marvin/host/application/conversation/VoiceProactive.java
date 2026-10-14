// SPDX-License-Identifier: MIT
package marvin.host.application.conversation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

import marvin.host.application.conversation.port.out.VoiceSidecar;
import marvin.host.application.conversation.task.TaskService;
import marvin.host.application.conversation.tools.ToolRegistry;
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
 * back"), and for background tasks (a finished task said aloud); never during a conversation. What it says is written
 * down once said, as a proactive reply.
 */
final class VoiceProactive {
    private static final Logger log = Logger.getLogger("marvin.voice");

    private final VoiceService v;
    private volatile ProactiveSpeech proactive;
    /** What Marvin said on its own and the voice has not reported yet: the language, by reply id. */
    private final Map<Long, String> replies = new ConcurrentHashMap<>();
    private volatile BooleanSupplier quiet = () -> false;

    VoiceProactive(VoiceService v) {
        this.v = v;
    }

    /** The voice starts with these settings. */
    void start(VoiceConfig c) {
        proactive = new ProactiveSpeech(c.reminders(), c.welcomeBack());
    }

    /** The voice stops: nothing more on its own. */
    void stop() {
        proactive = null;
    }

    void setQuiet(BooleanSupplier q) {
        quiet = q;
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
        String lang = v.language;
        double now = v.clocks.monotonicSeconds();
        String text = p.onEvent(event, lang, now).orElse(null);
        if (text == null) {
            return;
        }
        VoiceSidecar.Status s = v.status;
        if (v.turns.active() || s != null && ("thinking".equals(s.state()) || "speaking".equals(s.state()))) {
            log.fine("proactive speech skipped during a conversation: " + text);
            return;
        }
        long id = v.replyIds.getAndIncrement();
        replies.put(id, lang);
        v.voice.send(new VoiceSidecar.Say(id, text, lang, false, true, clauses(text, lang)));
        p.spoken(now);
    }

    /** Says {@code text} on Marvin's own initiative, now: false when the voice is off or a conversation is going on. */
    boolean speak(String text, String lang) {
        if (!VoiceService.ON.equals(v.state) || !v.opened || v.conversing()) {
            return false;
        }
        long id = v.replyIds.getAndIncrement();
        replies.put(id, lang);
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
        String lang = replies.remove(r.replyId());
        if (lang != null && !r.interrupted()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("language", lang);
            d.put("latency", Map.of());
            d.put("first_word_s", null);
            d.put("interrupted", false);
            d.put("proactive", true);
            d.put("error", null);
            d.put("hint", "");
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
            try {
                return quiet.getAsBoolean();
            } catch (RuntimeException e) {
                return false;
            }
        }
    }
}
