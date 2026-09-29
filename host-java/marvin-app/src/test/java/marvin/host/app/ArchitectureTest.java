// SPDX-License-Identifier: MIT
package marvin.host.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.onionArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * The architecture rules of docs/design.md 3.4, plus the bounded-context rules of 3.3 and 10.4.
 * The Maven module split already makes most violations impossible to compile; these catch the rest.
 */
@AnalyzeClasses(packages = "marvin.host", importOptions = {ImportOption.DoNotIncludeTests.class, ArchitectureTest.NoTestJars.class})
class ArchitectureTest {

    /** Test helpers shared as test jars (the stub Ollama, the in-memory stores) are not production code. */
    static final class NoTestJars implements ImportOption {
        @Override
        public boolean includes(com.tngtech.archunit.core.importer.Location location) {
            return !location.contains("-tests.jar");
        }
    }

    /** Generated code (the sidecar contract) is not ours to shape. */
    private static final String CONTRACTS = "marvin.host.contracts..";

    // ------------------------------------------------------------------ hexagon

    @ArchTest
    static final ArchRule hexagon = onionArchitecture()
            .domainModels("marvin.host.domain..")
            .domainServices("marvin.host.domain..")
            .applicationServices("marvin.host.application..")
            .adapter("web", "marvin.host.adapter.web..")
            .adapter("robot", "marvin.host.adapter.robot..")
            .adapter("persistence", "marvin.host.adapter.persistence..")
            .adapter("llm", "marvin.host.adapter.llm..")
            .adapter("sidecar", "marvin.host.adapter.sidecar..")
            .withOptionalLayers(true)
            .ignoreDependency(resideIn("marvin.host.app.."), DescribedPredicate.alwaysTrue());

    @ArchTest
    static final ArchRule adaptersDoNotDependOnEachOther = slices()
            .matching("marvin.host.adapter.(*)..")
            .should().notDependOnEachOther()
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule nothingDependsOnTheBootModule = noClasses()
            .that().resideOutsideOfPackage("marvin.host.app..")
            .should().dependOnClassesThat().resideInAPackage("marvin.host.app..");

    /** Adapters reach the application through its ports only, never a use case's implementation. */
    @ArchTest
    static final ArchRule adaptersUseApplicationPortsOnly = noClasses()
            .that().resideInAPackage("marvin.host.adapter..")
            .should().dependOnClassesThat(DescribedPredicate.describe("application classes outside port.in / port.out",
                    c -> c.getPackageName().startsWith("marvin.host.application")
                            && !c.getPackageName().matches("marvin\\.host\\.application\\.[a-z0-9]+\\.port\\.(in|out)(\\..*)?")))
            .because("an adapter swaps behind a port; it must not depend on how a use case is built");

    /**
     * Glue in the boot module may only build adapters in {@code @Configuration} classes (bean factories):
     * behaviour between two adapters belongs in an application service behind ports. The lifecycle classes
     * listed here predate the rule (start and stop of adapters, a health probe of the UDP link).
     */
    static final Set<String> BOOT_GLUE_KNOWN = Set.of("DemoRobot", "DeviceNotices", "RobotLinkProbe", "StartupImport");

    @ArchTest
    static final ArchRule bootGlueBuildsAdaptersOnlyInConfigurations = noClasses()
            .that().resideInAPackage("marvin.host.app..")
            .and(DescribedPredicate.describe("not a @Configuration (or inside one)", c -> !inConfiguration(c)))
            .and(DescribedPredicate.describe("not known lifecycle glue", c -> !BOOT_GLUE_KNOWN.contains(topLevel(c).getSimpleName())))
            .should().dependOnClassesThat().resideInAPackage("marvin.host.adapter..");

    private static JavaClass topLevel(JavaClass c) {
        JavaClass t = c;
        while (t.getEnclosingClass().isPresent()) {
            t = t.getEnclosingClass().get();
        }
        return t;
    }

    private static boolean inConfiguration(JavaClass c) {
        return topLevel(c).isAnnotatedWith("org.springframework.context.annotation.Configuration")
                || topLevel(c).isAnnotatedWith("org.springframework.boot.autoconfigure.SpringBootApplication");
    }

    // ------------------------------------------------------------------ plain core

    @ArchTest
    static final ArchRule pureDomain = noClasses()
            .that().resideInAPackage("marvin.host.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta..", "javax..", "java.sql..", "java.net..", "java.nio.channels..",
                    "io.grpc..", "com.google.protobuf..", "org.slf4j..", "tools.jackson..", "com.fasterxml..",
                    CONTRACTS)
            .orShould().dependOnClassesThat(ioOtherThan("java.io.Serializable", "java.io.UncheckedIOException"))
            .because("the domain is plain Java with no framework and no I/O (docs/design.md 3.3)");

    @ArchTest
    static final ArchRule plainApplication = noClasses()
            .that().resideInAPackage("marvin.host.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta..", "java.sql..", "java.net..", "io.grpc..",
                    "com.google.protobuf..", "tools.jackson..", "com.fasterxml..", CONTRACTS)
            .because("use cases and ports are plain Java, wired by marvin-app");

    @ArchTest
    static final ArchRule noSpringAiOutsideAdapters = noClasses()
            .that().resideOutsideOfPackages("marvin.host.adapter.llm..", "marvin.host.adapter.mcp..", "marvin.host.app..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.ai..");

    @ArchTest
    static final ArchRule noSystemClock = noClasses()
            .that().resideInAnyPackage("marvin.host.domain..", "marvin.host.application..")
            .should().callMethod(System.class, "currentTimeMillis")
            .orShould().callMethod(System.class, "nanoTime")
            .orShould().callMethod(Instant.class, "now")
            .orShould().callMethod(LocalDateTime.class, "now")
            .orShould().callMethod(LocalDate.class, "now")
            .orShould().callMethod(ZonedDateTime.class, "now")
            .because("time comes from the Clock port, or from the robot's clock in the frames");

    @ArchTest
    static final ArchRule domainModulesDoNotCycle = slices()
            .matching("marvin.host.domain.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule applicationModulesDoNotCycle = slices()
            .matching("marvin.host.application.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule portsAreInterfaces = classes()
            .that().resideInAnyPackage("marvin.host.application.*.port.in..", "marvin.host.application.*.port.out..")
            .and().areTopLevelClasses()
            .and().doNotHaveSimpleName("package-info")
            .should().beInterfaces()
            .allowEmptyShould(true);

    // ------------------------------------------------------------------ bounded contexts

    /**
     * A context reaches another only through that context's application ports (in and out), its
     * domain events ({@code domain.<context>.event}), or the shared kernel ({@code domain.shared}).
     * Never its entities, services or adapters.
     */
    @ArchTest
    static final ArchRule contextsTalkThroughPortsAndEvents = classes()
            .that().resideInAnyPackage("marvin.host.domain..", "marvin.host.application..")
            .should(onlyReachOtherContextsThroughPortsAndEvents("marvin.host."));

    // ------------------------------------------------------------------ helpers

    private static final List<String> SHARED = List.of("shared");

    /** The context rule for code under {@code root} ({@code marvin.host.} here; a fixture in the tests). */
    static ArchCondition<JavaClass> onlyReachOtherContextsThroughPortsAndEvents(String root) {
        Pattern context = Pattern.compile("^" + Pattern.quote(root) + "(domain|application)\\.([a-z0-9]+)(\\..*)?$");
        return new ArchCondition<>("only reach other bounded contexts through their ports and events") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String from = contextOf(context, origin.getPackageName());
                if (from == null || SHARED.contains(from)) {
                    return;
                }
                for (Dependency d : origin.getDirectDependenciesFromSelf()) {
                    String pkg = d.getTargetClass().getPackageName();
                    String to = contextOf(context, pkg);
                    if (to == null || to.equals(from) || SHARED.contains(to)) {
                        continue;
                    }
                    boolean port = pkg.startsWith(root + "application." + to + ".port.");
                    String eventPackage = root + "domain." + to + ".event";
                    boolean event = pkg.equals(eventPackage) || pkg.startsWith(eventPackage + ".");
                    if (!port && !event) {
                        events.add(SimpleConditionEvent.violated(d, "context '" + from + "' reaches into context '"
                                + to + "': " + d.getDescription()));
                    }
                }
            }
        };
    }

    private static String contextOf(Pattern context, String pkg) {
        Matcher m = context.matcher(pkg);
        return m.matches() ? m.group(2) : null;
    }

    private static DescribedPredicate<JavaClass> resideIn(String pattern) {
        return JavaClass.Predicates.resideInAPackage(pattern);
    }

    private static DescribedPredicate<JavaClass> ioOtherThan(String... allowed) {
        List<String> ok = List.of(allowed);
        return DescribedPredicate.describe("java.io classes other than " + ok,
                c -> c.getPackageName().equals("java.io") && !ok.contains(c.getName()));
    }
}
