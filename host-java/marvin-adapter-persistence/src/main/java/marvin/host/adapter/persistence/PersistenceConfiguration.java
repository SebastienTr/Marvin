// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@code marvin.db.*}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DatabaseProperties.class)
public class PersistenceConfiguration {
}
