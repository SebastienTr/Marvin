// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import marvin.host.application.face.port.in.FaceImage;
import marvin.host.application.presence.port.in.PresenceHistory;
import marvin.host.application.presence.port.in.PresenceQuery;
import marvin.host.application.robot.port.in.RobotLinkQuery;
import marvin.host.application.settings.port.in.ManageSettings;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.shared.LocalDays;

/** The web adapter's beans: the access key and its filter, the event hub, the live state, the ticker. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebProperties.class)
public class WebConfiguration {

    @Bean
    public AccessKey accessKey(WebProperties props) {
        return AccessKey.of(props);
    }

    @Bean
    public FilterRegistrationBean<AccessFilter> accessFilter(AccessKey key) {
        FilterRegistrationBean<AccessFilter> r = new FilterRegistrationBean<>(new AccessFilter(key, AccessFilter.localHostName()));
        r.setOrder(Ordered.HIGHEST_PRECEDENCE);
        r.addUrlPatterns("/*");
        return r;
    }

    @Bean
    public EventHub eventHub() {
        return new EventHub();
    }

    @Bean
    public LiveState liveState(PresenceQuery presence, PresenceHistory history, ManageSettings settings, FaceImage face,
                               Clocks clocks, LocalDays days) {
        return new LiveState(presence, history, settings, face, clocks, days);
    }

    @Bean
    public WebTicker webTicker(EventHub hub, PresenceHistory history, RobotLinkQuery robot) {
        return new WebTicker(hub, history, robot);
    }
}
