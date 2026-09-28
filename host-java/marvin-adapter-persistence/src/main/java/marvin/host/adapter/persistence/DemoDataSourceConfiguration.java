// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariDataSource;

/**
 * The demo on an external PostgreSQL: the same server, database {@code <name>_demo} (created on first
 * use), so the owner's history is never touched. Boot's data source is pointed there before its pool
 * starts. The embedded server does the same in {@link EmbeddedDatabaseConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'${marvin.mode:live}' == 'demo' and '${marvin.db.mode:external}' != 'embedded'")
public class DemoDataSourceConfiguration {
    private static final Logger log = LoggerFactory.getLogger(DemoDataSourceConfiguration.class);

    @Bean
    public static BeanPostProcessor demoDatabase() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String name) {
                if (bean instanceof HikariDataSource ds && ds.getJdbcUrl() != null
                        && !DemoDatabase.databaseOf(ds.getJdbcUrl()).endsWith(DemoDatabase.SUFFIX)) {
                    String url = ds.getJdbcUrl();
                    String demo = DemoDatabase.databaseOf(url) + DemoDatabase.SUFFIX;
                    try (Connection admin = DriverManager.getConnection(url, ds.getUsername(), ds.getPassword())) {
                        DemoDatabase.ensure(admin, demo);
                    } catch (SQLException e) {
                        throw new IllegalStateException("could not create the demo database " + demo + ": "
                                + e.getMessage(), e);
                    }
                    ds.setJdbcUrl(DemoDatabase.demoUrl(url));
                    log.info("demo: using database {} (emptied at start; the owner's data is not touched)", demo);
                }
                return bean;
            }
        };
    }
}
