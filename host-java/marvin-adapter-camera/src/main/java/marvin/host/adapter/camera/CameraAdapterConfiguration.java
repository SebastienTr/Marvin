// SPDX-License-Identifier: MIT
package marvin.host.adapter.camera;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@code marvin.camera.*}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CameraProperties.class)
public class CameraAdapterConfiguration {
}
