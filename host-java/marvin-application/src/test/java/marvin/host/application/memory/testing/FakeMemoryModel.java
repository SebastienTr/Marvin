// SPDX-License-Identifier: MIT
package marvin.host.application.memory.testing;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import marvin.host.application.memory.port.out.MemoryModel;
import marvin.host.domain.memory.FactCandidate;

/** A scripted model for the memory jobs; every request is kept. */
public final class FakeMemoryModel implements MemoryModel {
    public volatile Function<ExtractRequest, List<FactCandidate.Raw>> extract = r -> List.of();
    public volatile Function<ReconcileRequest, Decision> reconcile = r -> decision("ADD", 0);
    public volatile Function<SummaryRequest, String> summarize = r -> "Summary of " + r.items().size() + " items.";
    public volatile Function<ProfileRequest, String> profile = r -> String.join("\n", r.learned());
    /** Runs before every call (a test can make the voice busy here). */
    public volatile Runnable beforeCall = () -> { };
    public final List<Object> requests = new CopyOnWriteArrayList<>();

    public static Decision decision(String op, int target) {
        return new Decision(op, target == 0 ? null : target, "", "", Usage.NONE);
    }

    public static FactCandidate.Raw raw(String subject, String statement, int importance) {
        return new FactCandidate.Raw(subject, statement, "biographical", "", "", importance, "normal", 0.9);
    }

    private void check(Object request, BooleanSupplier cancelled) {
        requests.add(request);
        beforeCall.run();
        if (cancelled.getAsBoolean()) {
            throw new Cancelled();
        }
    }

    @Override
    public Extraction extract(Target target, ExtractRequest request, BooleanSupplier cancelled) {
        check(request, cancelled);
        return new Extraction(extract.apply(request), Usage.NONE);
    }

    @Override
    public Decision reconcile(Target target, ReconcileRequest request, BooleanSupplier cancelled) {
        check(request, cancelled);
        return reconcile.apply(request);
    }

    @Override
    public Text summarize(Target target, SummaryRequest request, BooleanSupplier cancelled) {
        check(request, cancelled);
        return new Text(summarize.apply(request), Usage.NONE);
    }

    @Override
    public Text rewriteProfile(Target target, ProfileRequest request, BooleanSupplier cancelled) {
        check(request, cancelled);
        return new Text(profile.apply(request), Usage.NONE);
    }

    @Override
    public String version() {
        return "fake/1";
    }

    public long count(Class<?> type) {
        return requests.stream().filter(type::isInstance).count();
    }
}
