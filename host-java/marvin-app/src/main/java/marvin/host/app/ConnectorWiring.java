// SPDX-License-Identifier: MIT
package marvin.host.app;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import marvin.host.adapter.persistence.JdbcApprovalStore;
import marvin.host.adapter.web.EventHub;
import marvin.host.application.conversation.task.ApprovalService;
import marvin.host.application.conversation.task.TaskService;
import marvin.host.domain.shared.Clocks;

/**
 * What reaches outside this computer, and the owner's say on it (docs/design.md 6.3, 7): the approvals a background task
 * waits for before a call that writes outside (published on the app's stream as {@code approval}).
 */
@Configuration(proxyBeanMethods = false)
public class ConnectorWiring {

    @Bean
    public ApprovalService approvalService(JdbcApprovalStore store, Clocks clocks, EventHub hub, TaskService tasks,
                                           @Value("${marvin.approvals.expire-minutes:60}") double expireMinutes) {
        ApprovalService approvals = new ApprovalService(store, clocks, expireMinutes * 60);
        approvals.addListener(payload -> hub.publish("approval", payload));
        tasks.setApprovals(approvals);
        return approvals;
    }
}
