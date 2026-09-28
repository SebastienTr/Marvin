// SPDX-License-Identifier: MIT
package marvin.host.app;

import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import marvin.host.application.system.HealthService;
import marvin.host.application.system.port.in.ReportHealth;
import marvin.host.application.system.port.out.ComponentProbe;
import marvin.host.domain.system.RunMode;

/**
 * Wires the plain-Java use cases to their adapters. The application layer knows nothing of Spring:
 * every use case is built here.
 */
@Configuration(proxyBeanMethods = false)
public class HostWiring {

    @Bean
    public ReportHealth reportHealth(Environment env, ObjectProvider<BuildProperties> build,
                                     List<ComponentProbe> probes) {
        BuildProperties b = build.getIfAvailable();
        String version = b != null ? b.getVersion() : "dev";
        RunMode mode = RunMode.valueOf(env.getProperty("marvin.mode", "live").toUpperCase(Locale.ROOT));
        return new HealthService(version, mode, probes);
    }
}
