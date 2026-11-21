package io.github.msbtff.toolguard.spring;

import io.github.msbtff.toolguard.core.AuditLogFormatter;
import io.github.msbtff.toolguard.core.InMemoryToolExecutionStore;
import io.github.msbtff.toolguard.core.ToolExecutionListener;
import io.github.msbtff.toolguard.core.ToolExecutionStore;
import io.github.msbtff.toolguard.spring.store.JdbcToolExecutionStore;
import io.github.msbtff.toolguard.spring.store.RedisToolExecutionStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@AutoConfiguration(afterName = {
    "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
    "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
    "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
    "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
    "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration",
    "org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration",
    "org.springframework.boot.micrometer.metrics.autoconfigure.export.prometheus.PrometheusMetricsExportAutoConfiguration",
    "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
    "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration"
})
@EnableConfigurationProperties(ToolGuardProperties.class)
@ConditionalOnProperty(prefix = "toolguard", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ToolGuardAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AuditLogFormatter auditLogFormatter(ToolGuardProperties properties) {
        return new AuditLogFormatter(properties.getAudit().getRedactKeys());
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolSessionFactory toolSessionFactory(
        ToolGuardProperties properties,
        ToolExecutionStore executionStore,
        ObjectProvider<ToolExecutionListener> listeners
    ) {
        return new ToolSessionFactory(properties, executionStore, listeners.orderedStream().toList());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "toolguard.store", name = "type", havingValue = "in-memory", matchIfMissing = true)
    static class InMemoryStoreConfiguration {
        @Bean
        @ConditionalOnMissingBean(ToolExecutionStore.class)
        public ToolExecutionStore inMemoryToolExecutionStore(ToolGuardProperties properties) {
            ToolGuardProperties.InMemoryProperties inMemory = properties.getStore().getInMemory();
            return new InMemoryToolExecutionStore(
                inMemory.getMaxEntries(),
                inMemory.getEntryTtl(),
                Clock.systemUTC()
            );
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(DataSource.class)
    @ConditionalOnProperty(prefix = "toolguard.store", name = "type", havingValue = "jdbc")
    static class JdbcStoreConfiguration {
        @Bean
        @ConditionalOnBean(DataSource.class)
        @ConditionalOnMissingBean(ToolExecutionStore.class)
        public ToolExecutionStore jdbcToolExecutionStore(DataSource dataSource, ToolGuardProperties properties) {
            ToolGuardProperties.JdbcProperties jdbc = properties.getStore().getJdbc();
            return new JdbcToolExecutionStore(
                dataSource,
                jdbc.getTableName(),
                Clock.systemUTC(),
                jdbc.isInitializeSchema(),
                jdbc.getLockTimeout()
            );
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.data.redis.core.StringRedisTemplate")
    @ConditionalOnProperty(prefix = "toolguard.store", name = "type", havingValue = "redis")
    static class RedisStoreConfiguration {
        @Bean
        @ConditionalOnBean(StringRedisTemplate.class)
        @ConditionalOnMissingBean(ToolExecutionStore.class)
        public ToolExecutionStore redisToolExecutionStore(
            StringRedisTemplate redisTemplate,
            ToolGuardProperties properties
        ) {
            ToolGuardProperties.RedisProperties redis = properties.getStore().getRedis();
            return new RedisToolExecutionStore(
                redisTemplate,
                redis.getKeyPrefix(),
                redis.getLockTtl(),
                redis.getLockAcquireTimeout(),
                redis.getStateTtl()
            );
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.slf4j.Logger")
    @ConditionalOnProperty(prefix = "toolguard.audit", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class AuditLoggingConfiguration {
        @Bean
        @ConditionalOnMissingBean(Slf4jAuditToolExecutionListener.class)
        public Slf4jAuditToolExecutionListener slf4jAuditToolExecutionListener(AuditLogFormatter formatter) {
            return new Slf4jAuditToolExecutionListener(formatter);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    static class MicrometerConfiguration {
        @Bean
        @ConditionalOnBean(MeterRegistry.class)
        @ConditionalOnMissingBean(MicrometerToolExecutionListener.class)
        public MicrometerToolExecutionListener micrometerToolExecutionListener(MeterRegistry meterRegistry) {
            return new MicrometerToolExecutionListener(meterRegistry);
        }
    }
}
