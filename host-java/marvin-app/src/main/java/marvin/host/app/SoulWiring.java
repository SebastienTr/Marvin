// SPDX-License-Identifier: MIT
package marvin.host.app;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import marvin.host.adapter.llm.OllamaLanguageModel;
import marvin.host.adapter.persistence.JdbcEpisodeStore;
import marvin.host.adapter.persistence.JdbcEventLog;
import marvin.host.adapter.persistence.JdbcSoulStore;
import marvin.host.adapter.persistence.VoiceSettingsFile;
import marvin.host.application.conversation.port.out.LanguageModel;
import marvin.host.application.conversation.task.ModelLane;
import marvin.host.application.conversation.task.TaskService;
import marvin.host.application.memory.port.in.RecordMemory;
import marvin.host.application.memory.port.out.EpisodeStore;
import marvin.host.application.memory.port.out.EventLog;
import marvin.host.application.soul.SoulService;
import marvin.host.application.soul.port.out.ReflectionModel;
import marvin.host.application.soul.port.out.ReflectionSource;
import marvin.host.domain.conversation.ChatMessage;
import marvin.host.domain.conversation.ReasoningSettings;
import marvin.host.domain.conversation.ToolCall;
import marvin.host.domain.conversation.VoiceSettings;
import marvin.host.domain.memory.Episode;
import marvin.host.domain.memory.EpisodeLevel;
import marvin.host.domain.memory.MemoryEvent;
import marvin.host.domain.memory.MemorySources;
import marvin.host.domain.memory.Sensitivity;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.shared.LocalDays;
import marvin.host.domain.soul.Feedback;
import marvin.host.domain.soul.ReflectionPrompt;

/**
 * The long-term brain's wiring (docs/design.md 6.1, 8): the soul with its store, the week it reflects on (memory's day
 * summaries and the owner's feedback), the model it reflects with (the conversation's, giving way to the voice like a
 * task), the weekly reflection's timer; and what background tasks found, in memory's event log.
 */
@Configuration(proxyBeanMethods = false)
public class SoulWiring {
    private static final Logger log = Logger.getLogger("marvin.soul");

    @Bean
    public SoulService soulService(JdbcSoulStore store, ReflectionModel reflectionModel, ReflectionSource reflectionSource,
                                   Clocks clocks, LocalDays days) {
        return new SoulService(store, reflectionModel, reflectionSource, clocks, days);
    }

    /** The reflection asks the conversation's model, as a task would: it waits while the voice needs the model. */
    @Bean
    public ReflectionModel reflectionModel(OllamaLanguageModel model, ModelLane lane, VoiceSettingsFile settings) {
        return new LaneReflectionModel(model, lane, settings);
    }

    /** The week as memory has it: its day summaries, and what the owner said that reads as feedback. */
    @Bean
    public ReflectionSource reflectionSource(JdbcEpisodeStore episodes, JdbcEventLog events, LocalDays days) {
        return new MemoryWeek(episodes, events, days.zone());
    }

    /**
     * Every 15 minutes: the weekly reflection when it is due (from Sunday night) and the model is free. Off in the demo
     * (its week is simulated) and with {@code marvin.soul.reflection=false}.
     */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService soulReflectionTimer(SoulService soul, ModelLane lane,
                                                        @Value("${marvin.mode:live}") String mode,
                                                        @Value("${marvin.soul.reflection:true}") boolean on,
                                                        @Value("${marvin.soul.check-minutes:15}") long minutes) {
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual()
                .name("marvin-soul").factory());
        if (on && !"demo".equals(mode)) {
            timer.scheduleWithFixedDelay(() -> soul.maybeReflect(() -> !lane.free() || lane.voiceBusy()), minutes,
                    Math.max(1, minutes), TimeUnit.MINUTES);
        }
        return timer;
    }

    /** A finished task's result, in memory's log. */
    @Bean
    public TaskResultsInMemory taskResultsInMemory(TaskService tasks, RecordMemory memory) {
        tasks.addListener(new TaskMemoryFeed(tasks, memory));
        return new TaskResultsInMemory();
    }

    /** A marker: task results reach memory. */
    public record TaskResultsInMemory() {
    }

    // ------------------------------------------------------------------ bridges

    /** The reflection's model call, on the lane the voice and the tasks share. */
    static final class LaneReflectionModel implements ReflectionModel {
        /** How long the model may take to answer a reflection (a 27B model reading a week, thinking). */
        static final double TIMEOUT_S = 600;
        private final LanguageModel model;
        private final ModelLane lane;
        private final VoiceSettingsFile settings;

        LaneReflectionModel(LanguageModel model, ModelLane lane, VoiceSettingsFile settings) {
            this.model = model;
            this.lane = lane;
            this.settings = settings;
        }

        @Override
        public String answer(String system, String user, BooleanSupplier cancelled) {
            ModelLane.Lease lease;
            try {
                lease = lane.acquire(cancelled, null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Unavailable("the host is stopping");
            }
            if (lease == null) {
                throw new Unavailable("the voice needed the model");
            }
            StringBuilder text = new StringBuilder();
            try {
                var s = settings.load();
                var c = VoiceSettings.config(s);
                LanguageModel.Thinking thinking = new LanguageModel.Thinking(ReasoningSettings.of(s).tasks(),
                        LanguageModel.Thinking.TASK_TOKENS);
                model.streamChat(c.ollamaHost(), c.llmModel(), List.of(ChatMessage.system(system), ChatMessage.user(user)),
                        null, TIMEOUT_S, thinking, new LanguageModel.Stream() {
                            @Override
                            public void text(String piece) {
                                text.append(piece);
                            }

                            @Override
                            public void toolCall(ToolCall call) {
                                // no tools offered
                            }

                            @Override
                            public boolean cancelled() {
                                return lease.preempted() || cancelled.getAsBoolean();
                            }
                        });
            } catch (LanguageModel.Unavailable e) {
                throw new Unavailable(e.getMessage());
            } finally {
                lease.close();
            }
            if (lease.preempted() || cancelled.getAsBoolean()) {
                throw new Unavailable("the voice needed the model");
            }
            return text.toString();
        }
    }

    /** The week from memory's ports: day summaries, and the owner's lines that read as feedback. */
    static final class MemoryWeek implements ReflectionSource {
        static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH);
        static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEEE HH:mm", Locale.ENGLISH);
        static final int LINE_CHARS = 300;
        private final EpisodeStore episodes;
        private final EventLog events;
        private final ZoneId zone;

        MemoryWeek(EpisodeStore episodes, EventLog events, ZoneId zone) {
            this.episodes = episodes;
            this.events = events;
            this.zone = zone;
        }

        @Override
        public List<ReflectionPrompt.Evidence> days(Instant from, Instant to) {
            List<ReflectionPrompt.Evidence> out = new ArrayList<>();
            try {
                LocalDate first = from.atZone(zone).toLocalDate();
                LocalDate last = to.atZone(zone).toLocalDate();
                for (Episode e : episodes.list(EpisodeLevel.DAY, first, last)) {
                    if (!e.summary().isBlank()) {
                        out.add(new ReflectionPrompt.Evidence(0, DAY.format(e.day()), cut(e.summary())));
                    }
                }
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "soul: could not read the week's summaries", e);
            }
            return out;
        }

        @Override
        public List<ReflectionPrompt.Evidence> feedback(Instant from, Instant to) {
            List<ReflectionPrompt.Evidence> out = new ArrayList<>();
            try {
                for (MemoryEvent e : events.between(from, to, 5000)) {
                    if (MemorySources.CONVERSATION.equals(e.source()) && "heard".equals(e.kind())
                            && e.sensitivity().ordinal() < Sensitivity.SENSITIVE.ordinal()
                            && Feedback.looksLikeFeedback(e.body())) {
                        out.add(new ReflectionPrompt.Evidence(e.id(), WHEN.format(e.ts().atZone(zone)), cut(e.body())));
                    }
                }
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "soul: could not read the week's conversation", e);
            }
            return out;
        }

        private static String cut(String s) {
            String t = s.strip();
            return t.length() <= LINE_CHARS ? t : t.substring(0, LINE_CHARS) + "…";
        }
    }
}
