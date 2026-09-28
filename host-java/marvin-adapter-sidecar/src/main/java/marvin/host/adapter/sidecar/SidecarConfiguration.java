// SPDX-License-Identifier: MIT
package marvin.host.adapter.sidecar;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@code marvin.sidecar.*}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SidecarProperties.class)
public class SidecarConfiguration {
}
