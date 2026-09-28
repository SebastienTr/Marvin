// SPDX-License-Identifier: MIT
package marvin.host.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;

/**
 * The bounded-context rule must fail on a real violation. It is checked on a small fixture laid out
 * like the real packages, under {@code marvin.host.app.archfixture} (test code: the real
 * ArchitectureTest never sees it).
 */
class ArchitectureRulesBiteTest {
    private static final String ROOT = "marvin.host.app.archfixture.";

    private static EvaluationResult check() {
        JavaClasses fixture = new ClassFileImporter().importPackages("marvin.host.app.archfixture");
        return classes().should(ArchitectureTest.onlyReachOtherContextsThroughPortsAndEvents(ROOT))
                .evaluate(fixture);
    }

    @Test
    void reachingIntoAnotherContextsDomainIsCaught() {
        assertThat(check().getFailureReport().getDetails())
                .anyMatch(v -> v.contains("context 'presence' reaches into context 'robot'")
                        && v.contains("archfixture.domain.robot.Device"));
    }

    @Test
    void portsAndEventsOfAnotherContextAreAllowed() {
        assertThat(check().getFailureReport().getDetails())
                .noneMatch(v -> v.contains("RobotLinkQuery") || v.contains("DeviceConnected"));
    }
}
