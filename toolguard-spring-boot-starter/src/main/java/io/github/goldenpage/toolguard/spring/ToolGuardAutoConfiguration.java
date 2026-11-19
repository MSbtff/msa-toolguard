package io.github.goldenpage.toolguard.spring;

import io.github.goldenpage.toolguard.core.InMemoryToolExecutionStore;
import io.github.goldenpage.toolguard.core.ToolExecutionListener;
import io.github.goldenpage.toolguard.core.ToolExecutionStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@AutoConfiguration
@EnableConfigurationProperties(ToolGuardProperties.class)
@ConditionalOnProperty(prefix = "toolguard", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ToolGuardAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ToolExecutionStore toolExecutionStore() {
        return new InMemoryToolExecutionStore();
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
    @ConditionalOnClass(MeterRegistry.class)
    static class MicrometerConfiguration {
        @Bean
        @ConditionalOnBean(MeterRegistry.class)
        @ConditionalOnMissingBean(MicrometerToolExecutionListener.class)
        public MicrometerToolExecutionListener micrometerToolExecutionListener(MeterRegistry meterRegistry) {
            return new MicrometerToolExecutionListener(meterRegistry);
        }
    }
}
