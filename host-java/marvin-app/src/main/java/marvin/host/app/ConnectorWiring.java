// SPDX-License-Identifier: MIT
package marvin.host.app;

import java.net.URI;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import marvin.host.adapter.connectors.GoogleClientFile;
import marvin.host.adapter.connectors.GoogleEndpoints;
import marvin.host.adapter.connectors.GoogleHttp;
import marvin.host.adapter.connectors.GoogleOAuth;
import marvin.host.adapter.connectors.GoogleTools;
import marvin.host.adapter.connectors.SecretStores;
import marvin.host.adapter.persistence.JdbcApprovalStore;
import marvin.host.adapter.web.EventHub;
import marvin.host.application.connector.ConnectorService;
import marvin.host.application.connector.port.out.SecretStore;
import marvin.host.application.conversation.VoiceService;
import marvin.host.application.conversation.task.ApprovalService;
import marvin.host.application.conversation.task.TaskService;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.shared.LocalDays;

/**
 * What reaches outside this computer, and the owner's say on it (docs/design.md 6.3, 7): the approvals a background task
 * waits for before a call that writes outside (published on the app's stream as {@code approval}); the connectors
 * (Google Calendar, Gmail) with the owner's own OAuth client, their tokens in the secret store, and their tools given to
 * the conversation as a tool source (the tool filter and the approvals apply to them as to any tool).
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

    /**
     * Where tokens are kept: the macOS Keychain on a Mac, else a 0600 file in the data folder; memory only in the demo
     * (it never touches the owner's Keychain).
     */
    @Bean
    public SecretStore secretStore(@Value("${marvin.mode:live}") String mode,
                                   @Value("${marvin.connectors.secrets:auto}") String kind,
                                   @Value("${marvin.data-dir}") String dataDir) {
        return SecretStores.choose("demo".equals(mode) ? "memory" : kind, Path.of(dataDir, "secrets.json"),
                System.getProperty("os.name"));
    }

    /** Google's endpoints; {@code marvin.connectors.google.base} points them at a stand-in (tests, the e2e). */
    @Bean
    public GoogleHttp googleHttp(@Value("${marvin.connectors.google.base:}") String base) {
        return new GoogleHttp(base.isBlank() ? GoogleEndpoints.GOOGLE : GoogleEndpoints.at(URI.create(base)));
    }

    @Bean
    public ConnectorService connectorService(GoogleHttp http, SecretStore secrets, Clocks clocks,
                                             @Value("${marvin.connectors.google.client-file:${marvin.data-dir}/google-oauth-client.json}")
                                             String clientFile) {
        return new ConnectorService(new GoogleOAuth(new GoogleClientFile(Path.of(clientFile)), http, clocks::wallSeconds),
                secrets, clocks);
    }

    /** The connectors' tools, offered to the conversation and the tasks while their connector is connected. */
    @Bean
    public GoogleTools googleTools(ConnectorService connectors, GoogleHttp http, LocalDays days, Clocks clocks,
                                   VoiceService voice) {
        GoogleTools tools = new GoogleTools(connectors, http, days.zone(), clocks::wallSeconds);
        voice.addToolSource(tools);
        return tools;
    }
}
