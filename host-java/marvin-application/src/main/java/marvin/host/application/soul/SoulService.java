// SPDX-License-Identifier: MIT
package marvin.host.application.soul;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import marvin.host.application.soul.port.in.SoulControl;
import marvin.host.application.soul.port.in.SoulPrompt;
import marvin.host.application.soul.port.out.ReflectionModel;
import marvin.host.application.soul.port.out.ReflectionSource;
import marvin.host.application.soul.port.out.SoulStore;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.shared.LocalDays;
import marvin.host.domain.soul.Guardrails;
import marvin.host.domain.soul.ReflectionPrompt;
import marvin.host.domain.soul.ReflectionSchedule;
import marvin.host.domain.soul.SoulDiff;
import marvin.host.domain.soul.SoulPart;
import marvin.host.domain.soul.SoulProposal;
import marvin.host.domain.soul.SoulText;
import marvin.host.domain.soul.SoulVersion;

/**
 * Marvin's soul (docs/design.md 8): the owner's core, the learned part, every version, and the weekly reflection that
 * proposes a new learned part from the week's summaries and the owner's feedback. Nothing changes until the owner
 * approves; a proposal or an owner edit that fails a guardrail is refused with its reasons (a proposal is shown as
 * rejected). The core starts as {@link SoulText#DEFAULT_CORE} until the owner writes their own.
 */
public final class SoulService implements SoulControl, SoulPrompt {
    private static final Logger log = Logger.getLogger("marvin.soul");
    /** Versions the app lists. */
    public static final int VERSIONS_SHOWN = 50;
    /** The days a reflection reads. */
    public static final int WEEK_DAYS = 7;
    /** Owner lines the reflection reads at most. */
    static final int MAX_FEEDBACK = 40;

    private final SoulStore store;
    private final ReflectionModel model;
    private final ReflectionSource source;
    private final Clocks clocks;
    private final LocalDays days;
    private volatile Text prompt;

    public SoulService(SoulStore store, ReflectionModel model, ReflectionSource source, Clocks clocks, LocalDays days) {
        this.store = store;
        this.model = model;
        this.source = source;
        this.clocks = clocks;
        this.days = days;
        try {
            if (store.current(SoulPart.CORE).isEmpty()) {
                store.addVersion(SoulPart.CORE, SoulText.DEFAULT_CORE, clocks.wallSeconds(), SoulVersion.OWNER,
                        "The core Marvin starts with");
            }
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "could not write the starting core", e);
        }
        refreshPrompt();
    }

    // ------------------------------------------------------------------ the prompt

    @Override
    public Text soulPrompt() {
        Text t = prompt;
        return t != null ? t : new Text("none", "");
    }

    private void refreshPrompt() {
        try {
            Optional<SoulVersion> c = store.current(SoulPart.CORE);
            Optional<SoulVersion> l = store.current(SoulPart.LEARNED);
            prompt = new Text("c" + c.map(SoulVersion::version).orElse(0) + ".l" + l.map(SoulVersion::version).orElse(0),
                    SoulText.prompt(c.map(SoulVersion::text).orElse(""), l.map(SoulVersion::text).orElse("")));
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "could not read the soul", e);
        }
    }

    // ------------------------------------------------------------------ the app

    @Override
    public synchronized Map<String, Object> view() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("core", part(SoulPart.CORE));
        m.put("learned", part(SoulPart.LEARNED));
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("core_tokens", SoulText.CORE_TOKENS);
        limits.put("learned_tokens", SoulText.LEARNED_TOKENS);
        m.put("limits", limits);
        List<SoulProposal> open = store.awaitingOwner();
        m.put("proposal", open.isEmpty() ? null : open.getFirst().toMap());
        m.put("versions", store.versions(VERSIONS_SHOWN).stream().map(SoulVersion::toListMap).toList());
        return m;
    }

    private Map<String, Object> part(SoulPart p) {
        Optional<SoulVersion> v = store.current(p);
        Map<String, Object> m = new LinkedHashMap<>();
        String text = v.map(SoulVersion::text).orElse("");
        m.put("text", text);
        m.put("version", v.map(SoulVersion::version).orElse(0));
        m.put("updated", v.map(SoulVersion::created).orElse(null));
        m.put("tokens", SoulText.tokens(text));
        return m;
    }

    @Override
    public synchronized SoulVersion setCore(String text) {
        String t = SoulText.normalise(text);
        if (t.isEmpty()) {
            throw new IllegalArgumentException("the core cannot be empty");
        }
        if (t.length() > SoulText.MAX_CHARS || SoulText.tokens(t) > SoulText.CORE_TOKENS) {
            throw new IllegalArgumentException("the core is at most " + SoulText.CORE_TOKENS + " tokens (about "
                    + SoulText.tokens(t) + " now)");
        }
        Optional<SoulVersion> cur = store.current(SoulPart.CORE);
        if (cur.isPresent() && cur.get().text().equals(t)) {
            return cur.get();
        }
        SoulVersion v = store.addVersion(SoulPart.CORE, t, clocks.wallSeconds(), SoulVersion.OWNER, "Edited by the owner");
        log.info("soul: the owner wrote core version " + v.version());
        refreshPrompt();
        return v;
    }

    @Override
    public synchronized SoulVersion setLearned(String text) {
        String t = SoulText.normalise(text);
        List<Guardrails.Check> checks = Guardrails.check(t, coreText());
        if (!Guardrails.passes(checks)) {
            throw new Refused(checks);
        }
        Optional<SoulVersion> cur = store.current(SoulPart.LEARNED);
        if (cur.isPresent() && cur.get().text().equals(t) || cur.isEmpty() && t.isEmpty()) {
            return cur.orElse(new SoulVersion(SoulPart.LEARNED, 0, "", clocks.wallSeconds(), SoulVersion.OWNER, null));
        }
        SoulVersion v = store.addVersion(SoulPart.LEARNED, t, clocks.wallSeconds(), SoulVersion.OWNER, "Edited by the owner");
        log.info("soul: the owner wrote learned version " + v.version());
        refreshPrompt();
        return v;
    }

    @Override
    public synchronized SoulVersion approve(long id) {
        SoulProposal p = store.proposal(id).orElseThrow(() -> new NotFound("no such proposal"));
        if (!p.awaitsOwner()) {
            throw new Conflict("this proposal is already " + p.state().id());
        }
        if (p.state() != SoulProposal.State.PENDING) {
            throw new Conflict("this proposal failed the guardrails: it can only be dismissed");
        }
        int learned = store.current(SoulPart.LEARNED).map(SoulVersion::version).orElse(0);
        if (learned != p.baseVersion()) {
            throw new Conflict("the learned part changed since this proposal was made: reject it, the next reflection "
                    + "starts from the new one");
        }
        List<Guardrails.Check> checks = Guardrails.check(p.text(), coreText());
        if (!Guardrails.passes(checks)) {
            throw new Refused(checks);
        }
        double now = clocks.wallSeconds();
        SoulVersion v = store.addVersion(SoulPart.LEARNED, p.text(), now, SoulVersion.REFLECTION, "Approved by the owner");
        store.updateProposal(p.decide(SoulProposal.State.APPROVED, now));
        log.info("soul: proposal " + id + " approved, learned version " + v.version());
        refreshPrompt();
        return v;
    }

    @Override
    public synchronized SoulProposal reject(long id) {
        SoulProposal p = store.proposal(id).orElseThrow(() -> new NotFound("no such proposal"));
        if (!p.awaitsOwner()) {
            throw new Conflict("this proposal is already " + p.state().id());
        }
        SoulProposal r = p.decide(SoulProposal.State.REJECTED, clocks.wallSeconds());
        store.updateProposal(r);
        log.info("soul: proposal " + id + " rejected by the owner");
        return r;
    }

    @Override
    public synchronized SoulVersion revert(SoulPart part, int version) {
        SoulVersion old = store.version(part, version).orElseThrow(() -> new NotFound("no " + part.id() + " version "
                + version));
        if (part == SoulPart.LEARNED) {
            List<Guardrails.Check> checks = Guardrails.check(old.text(), coreText());
            if (!Guardrails.passes(checks)) {
                throw new Refused(checks);
            }
        }
        SoulVersion v = store.addVersion(part, old.text(), clocks.wallSeconds(), SoulVersion.OWNER,
                "Reverted to version " + version);
        log.info("soul: " + part.id() + " reverted to version " + version + " (now version " + v.version() + ")");
        refreshPrompt();
        return v;
    }

    private String coreText() {
        return store.current(SoulPart.CORE).map(SoulVersion::text).orElse(SoulText.DEFAULT_CORE);
    }

    // ------------------------------------------------------------------ the weekly reflection

    /**
     * Runs the reflection when it is due (from Sunday night, once a week) and the model is free ({@code busy} false).
     * Never throws.
     *
     * @return whether it ran
     */
    public boolean maybeReflect(BooleanSupplier busy) {
        try {
            if (!ReflectionSchedule.due(store.lastReflection().orElse(null), now()) || busy.getAsBoolean()) {
                return false;
            }
            reflect(busy);
            return true;
        } catch (ReflectionModel.Unavailable e) {
            log.info("soul: the reflection waits for the model (" + e.getMessage() + ")");
            return false;
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "soul: the reflection failed", e);
            return false;
        }
    }

    @Override
    public Optional<SoulProposal> reflect() {
        return reflect(() -> false);
    }

    /**
     * Reads the week and asks the model for a learned part; stores the proposal when it changes something (pending when
     * every guardrail passes, rejected with its reasons otherwise). A newer proposal supersedes the ones the owner has
     * not decided on.
     *
     * @throws ReflectionModel.Unavailable the model could not answer (the reflection is not counted as run)
     */
    Optional<SoulProposal> reflect(BooleanSupplier cancelled) {
        double now = clocks.wallSeconds();
        Instant to = Instant.ofEpochMilli((long) (now * 1000));
        Instant from = to.minusSeconds(WEEK_DAYS * 86_400L);
        List<ReflectionPrompt.Evidence> week = source.days(from, to);
        List<ReflectionPrompt.Evidence> feedback = source.feedback(from, to);
        if (feedback.size() > MAX_FEEDBACK) {
            feedback = feedback.subList(feedback.size() - MAX_FEEDBACK, feedback.size());
        }
        if (week.isEmpty() && feedback.isEmpty()) {
            store.reflected(now);
            log.info("soul: nothing to reflect on this week");
            return Optional.empty();
        }
        String core;
        String learned;
        int base;
        synchronized (this) {
            core = coreText();
            Optional<SoulVersion> l = store.current(SoulPart.LEARNED);
            learned = l.map(SoulVersion::text).orElse("");
            base = l.map(SoulVersion::version).orElse(0);
        }
        String answer = model.answer(ReflectionPrompt.system(), ReflectionPrompt.user(core, learned, week, feedback), cancelled);
        Set<Long> known = new HashSet<>();
        feedback.forEach(f -> known.add(f.event()));
        ReflectionPrompt.Proposed proposed;
        try {
            proposed = ReflectionPrompt.parse(answer, known);
        } catch (IllegalArgumentException e) {
            store.reflected(now);
            log.warning("soul: the reflection's answer was not usable (" + e.getMessage() + ")");
            return Optional.empty();
        }
        synchronized (this) {
            store.reflected(now);
            if (!SoulDiff.changes(SoulDiff.of(learned, proposed.learned()))) {
                log.info("soul: the reflection changes nothing");
                return Optional.empty();
            }
            for (SoulProposal old : store.awaitingOwner()) {
                store.updateProposal(old.decide(SoulProposal.State.SUPERSEDED, now));
            }
            SoulProposal p = store.addProposal(SoulProposal.propose(now, proposed.learned(), proposed.rationale(),
                    proposed.events(), learned, base, core));
            log.info("soul: the reflection proposes a learned part (proposal " + p.id() + ", " + p.state().id()
                    + (p.state() == SoulProposal.State.PENDING ? "" : ": " + Guardrails.reasons(p.guardrails())) + ")");
            return Optional.of(p);
        }
    }

    private ZonedDateTime now() {
        return Instant.ofEpochMilli((long) (clocks.wallSeconds() * 1000)).atZone(days.zone());
    }
}
