// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import marvin.host.application.conversation.port.in.ApprovalControl;
import marvin.host.application.conversation.port.out.VoiceSettingsStore;
import marvin.host.application.conversation.tools.ToolRegistry;
import marvin.host.domain.conversation.ChatMessage;
import marvin.host.domain.conversation.VoiceSettings;
import marvin.host.domain.conversation.VoiceSettings.VoiceConfig;
import marvin.host.domain.conversation.task.Approval;
import marvin.host.domain.conversation.task.ApprovalState;
import marvin.host.domain.conversation.task.Task;
import marvin.host.domain.conversation.task.TaskBudget;
import marvin.host.domain.conversation.task.TaskStatus;
import marvin.host.domain.conversation.tool.MemoryToolSpecs;
import marvin.host.domain.conversation.tool.TaskTools;
import marvin.host.domain.conversation.tool.ToolAccess;
import marvin.host.domain.conversation.tool.ToolSpec;
import marvin.host.domain.conversation.tool.ToolSurface;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.shared.LocalDays;

/**
 * Approvals end to end in the task service (docs/design.md 6.2, 6.3): a task that wants to create an email draft waits in
 * needs_approval with a preview; the draft is created only once the owner approves; rejected, expired or cancelled, it is
 * never created; a waiting task does not hold the others up, and its wait does not count against its time budget.
 */
class TaskApprovalTest {
    final InMemoryTaskStore store = new InMemoryTaskStore();
    final InMemoryApprovalStore approvalStore = new InMemoryApprovalStore();
    final List<TaskService> services = new ArrayList<>();
    final Map<String, Object> voiceJson = new LinkedHashMap<>(Map.of("llm_model", "qwen3.8:27b-mxfp8"));
    final List<String> statuses = new CopyOnWriteArrayList<>();
    final List<Map<String, Object>> approvalEvents = new CopyOnWriteArrayList<>();
    final List<Map<String, Object>> drafted = new CopyOnWriteArrayList<>();
    final List<String> said = new CopyOnWriteArrayList<>();
    volatile double wall = 1_790_000_000;
    volatile double mono = 500;
    volatile Boolean present = true;

    final Clocks clocks = new Clocks() {
        @Override
        public double wallSeconds() {
            return wall;
        }

        @Override
        public double monotonicSeconds() {
            return mono;
        }
    };

    final VoiceSettingsStore settings = new VoiceSettingsStore() {
        @Override
        public Map<String, Object> load() {
            return new LinkedHashMap<>(voiceJson);
        }

        @Override
        public void save(Map<String, Object> values) {
            voiceJson.putAll(values);
        }
    };

    static final Map<String, Object> DRAFT_SCHEMA = Map.of("type", "object", "properties", Map.of(
                    "to", Map.of("type", "array", "items", Map.of("type", "string")),
                    "subject", Map.of("type", "string"),
                    "body", Map.of("type", "string")),
            "required", List.of("to", "subject", "body"));

    /** The connector's draft tool: writes outside this computer, in tasks only, with its preview. */
    ToolRegistry.Tool draftTool() {
        return new ToolRegistry.Tool(new ToolSpec("gmail_create_draft", "Create a Gmail draft", DRAFT_SCHEMA, 5, true, false,
                false), (args, ctx) -> {
                    drafted.add(args);
                    return Map.of("created", true, "open_url", "https://mail.google.com/mail/u/0/#drafts?compose=r1");
                }, ToolAccess.EXTERNAL_WRITE, Set.of(ToolSurface.TASK),
                args -> Map.of("kind", "email_draft", "to", args.get("to"), "subject", args.get("subject"), "body",
                        args.get("body")));
    }

    final class Host implements TaskService.Host {
        @Override
        public VoiceConfig config() {
            return VoiceSettings.config(voiceJson);
        }

        @Override
        public ToolRegistry tools() {
            return new ToolRegistry(List.of(new ToolRegistry.Tool(MemoryToolSpecs.recall(), (a, c) -> Map.of("facts", List.of())),
                    draftTool()), true, true, () -> mono);
        }

        @Override
        public String profile() {
            return "";
        }

        @Override
        public boolean voiceOn() {
            return true;
        }

        @Override
        public boolean conversing() {
            return false;
        }

        @Override
        public boolean speak(String text, String language) {
            said.add(language + ": " + text);
            return true;
        }

        @Override
        public Boolean present() {
            return present;
        }

        @Override
        public double sinceTalkS() {
            return Double.POSITIVE_INFINITY;
        }

        @Override
        public boolean quiet() {
            return false;
        }
    }

    ApprovalService approvals;

    TaskService service(ScriptedModel model, boolean withApprovals) {
        TaskService s = new TaskService(store, model, new ModelLane(1, () -> mono), settings, clocks,
                new LocalDays(ZoneId.of("Europe/Paris")));
        s.setHost(new Host());
        s.addListener((kind, payload) -> {
            if ("task".equals(kind)) {
                statuses.add(payload.get("title") + ":" + payload.get("status"));
            }
        });
        if (withApprovals) {
            approvals = new ApprovalService(approvalStore, clocks, 600);
            approvals.addListener(approvalEvents::add);
            s.setApprovals(approvals);
        }
        services.add(s);
        return s;
    }

    @AfterEach
    void close() {
        services.forEach(TaskService::close);
    }

    static Object draftCall() {
        return ScriptedModel.call("gmail_create_draft", Map.of("to", List.of("plumber@example.com"), "subject", "Re: Thursday",
                "body", "Hello, Thursday works for me."));
    }

    static Object finish(String summary) {
        return ScriptedModel.call(TaskTools.FINAL, Map.of("summary", summary, "result", summary));
    }

    Task await(TaskService s, String id, TaskStatus status) throws InterruptedException {
        return awaitThat(s, id, () -> s.task(id).orElseThrow().status() == status, status.id());
    }

    Task awaitThat(TaskService s, String id, BooleanSupplier ok, String what) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) {
            if (ok.getAsBoolean()) {
                return s.task(id).orElseThrow();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("task " + id + " is " + s.task(id).orElseThrow().status() + ", not " + what);
    }

    Approval pending() throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) {
            for (Approval a : approvals.approvals()) {
                if (a.state() == ApprovalState.PENDING) {
                    return a;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("no pending approval");
    }

    @Test
    void theDraftIsCreatedOnlyOnceTheOwnerApproves() throws Exception {
        ScriptedModel model = ScriptedModel.of(List.of(draftCall()), List.of(finish("The draft is in Gmail.")));
        TaskService s = service(model, true);
        Task t = s.start("Draft a reply to the plumber saying Thursday works", "en", Task.VOICE);
        Approval a = pending();
        Task waiting = await(s, t.id(), TaskStatus.NEEDS_APPROVAL);
        assertThat(waiting.activity()).isEqualTo("Waiting for your approval (gmail_create_draft)");
        assertThat(drafted).isEmpty();
        assertThat(a.taskId()).isEqualTo(t.id());
        assertThat(a.summary()).isEqualTo(t.title());
        assertThat(a.preview()).containsEntry("kind", "email_draft").containsEntry("subject", "Re: Thursday")
                .containsEntry("to", List.of("plumber@example.com")).containsEntry("open_url", null);
        // said once, as the owner is there
        assertThat(said).containsExactly("en: For “" + t.title() + "”, I need your approval in the app.");

        approvals.approve(a.id());
        Task done = await(s, t.id(), TaskStatus.DONE);
        assertThat(drafted).hasSize(1);
        assertThat(drafted.getFirst()).containsEntry("subject", "Re: Thursday");
        Approval after = approvalStore.get(a.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(ApprovalState.APPROVED);
        assertThat(after.preview()).containsEntry("open_url", "https://mail.google.com/mail/u/0/#drafts?compose=r1");
        assertThat(statuses).contains(t.title() + ":needs_approval", t.title() + ":running", t.title() + ":done");
        assertThat(approvalEvents).extracting(e -> e.get("state")).containsSubsequence("pending", "approved");
        assertThat(done.summary()).isEqualTo("The draft is in Gmail.");
        assertThat(store.steps(t.id())).extracting(st -> String.valueOf(st.data().get("text")))
                .contains("Waiting for the owner's approval: gmail_create_draft", "Approved by the owner");
        assertThatThrownBy(() -> approvals.approve(a.id())).isInstanceOf(ApprovalControl.AlreadyDecided.class);
        assertThatThrownBy(() -> approvals.reject(999, "")).isInstanceOf(ApprovalControl.NotFound.class);
    }

    @Test
    void aRejectedDraftIsNeverCreatedAndTheModelIsTold() throws Exception {
        ScriptedModel model = ScriptedModel.of(List.of(draftCall()), List.of(finish("I did not create the draft.")));
        TaskService s = service(model, true);
        Task t = s.start("Draft a reply to the plumber", "en", Task.APP);
        Approval a = pending();
        approvals.reject(a.id(), "wrong tone");
        await(s, t.id(), TaskStatus.DONE);
        assertThat(drafted).isEmpty();
        List<ChatMessage> second = model.calls.get(1);
        assertThat(second.getLast().content()).contains("The owner did not approve this call (wrong tone)");
        assertThat(approvalStore.get(a.id()).orElseThrow().state()).isEqualTo(ApprovalState.REJECTED);
    }

    @Test
    void cancellingAWaitingTaskExpiresItsApproval() throws Exception {
        ScriptedModel model = ScriptedModel.of(List.of(draftCall()), List.of(finish("x")));
        TaskService s = service(model, true);
        Task t = s.start("Draft a reply", "en", Task.APP);
        Approval a = pending();
        s.cancel(t.id());
        await(s, t.id(), TaskStatus.CANCELLED);
        Approval after = approvalStore.get(a.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(ApprovalState.EXPIRED);
        assertThat(after.reason()).isEqualTo(ApprovalService.CANCELLED);
        assertThat(drafted).isEmpty();
        assertThatThrownBy(() -> approvals.approve(a.id())).isInstanceOf(ApprovalControl.AlreadyDecided.class);
        assertThat(drafted).isEmpty();
    }

    @Test
    void nobodyAnsweringExpiresItAndTheWaitDoesNotSpendTheTimeBudget() throws Exception {
        ScriptedModel model = ScriptedModel.of(List.of(draftCall()), List.of(finish("No approval, no draft.")));
        TaskService s = service(model, true);
        s.setBudget(new TaskBudget(12, 60, 40_000));
        present = false;
        Task t = s.start("Draft a reply", "en", Task.APP);
        Approval a = pending();
        assertThat(said).isEmpty();                     // nobody there: not said
        mono += 3600;                                   // an hour passes: far beyond the 60 s budget
        wall += 601;
        Task done = await(s, t.id(), TaskStatus.DONE);  // not failed for time: the wait did not count
        assertThat(done.summary()).isEqualTo("No approval, no draft.");
        assertThat(approvalStore.get(a.id()).orElseThrow().state()).isEqualTo(ApprovalState.EXPIRED);
        assertThat(model.calls.get(1).getLast().content()).contains("Nobody approved this call in time");
        assertThat(drafted).isEmpty();
    }

    @Test
    void aTaskWaitingForTheOwnerDoesNotHoldTheOthersUp() throws Exception {
        ScriptedModel model = new ScriptedModel(m -> {
            String goal = m.get(1).content();
            boolean toolDone = "tool".equals(m.getLast().role());
            if (goal.contains("plumber") && !toolDone) {
                return new ArrayList<>(List.of(draftCall()));
            }
            return new ArrayList<>(List.of(finish("Done.")));
        });
        TaskService s = service(model, true);
        Task a = s.start("Draft a reply to the plumber", "en", Task.APP);
        Approval ap = pending();
        Task b = s.start("Compare two laptops", "en", Task.APP);
        await(s, b.id(), TaskStatus.DONE);
        assertThat(s.task(a.id()).orElseThrow().status()).isEqualTo(TaskStatus.NEEDS_APPROVAL);
        approvals.approve(ap.id());
        await(s, a.id(), TaskStatus.DONE);
        assertThat(drafted).hasSize(1);
    }

    @Test
    void withoutApprovalsATaskIsNotOfferedTheDraftTool() throws Exception {
        ScriptedModel model = ScriptedModel.of(List.of(draftCall()), List.of(finish("I cannot draft emails.")));
        TaskService s = service(model, false);
        Task t = s.start("Draft a reply", "en", Task.APP);
        await(s, t.id(), TaskStatus.DONE);
        assertThat(model.tools.getFirst().toString()).doesNotContain("gmail_create_draft");
        assertThat(model.calls.get(1).getLast().content()).contains("unknown tool");
        assertThat(drafted).isEmpty();
    }

    @Test
    void aRestartExpiresWhatWasPendingAndInterruptsItsTask() {
        Task waiting = Task.queued("w", "Draft a reply", "en", Task.APP, wall - 100, TaskBudget.DEFAULT)
                .running(wall - 90, "m", "high", "xhigh").awaiting(true, "Waiting for your approval (gmail_create_draft)");
        store.save(waiting);
        Approval a = approvalStore.add(Approval.pending("w", "gmail_create_draft", wall - 80, "Draft a reply", null, Map.of()));
        TaskService s = service(ScriptedModel.of(List.of(finish("x"))), true);
        assertThat(s.task("w").orElseThrow().status()).isEqualTo(TaskStatus.INTERRUPTED);
        Approval after = approvalStore.get(a.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(ApprovalState.EXPIRED);
        assertThat(after.reason()).isEqualTo(ApprovalService.HOST_STOPPED);
    }
}
