package io.github.goldenpage.toolguard.spring;

import io.github.goldenpage.toolguard.core.AccessLevel;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "toolguard")
public class ToolGuardProperties {
    private boolean enabled = true;
    private int defaultMaxTotalCalls = 50;
    private AccessLevel defaultAccessLevel = AccessLevel.READ_ONLY;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDefaultMaxTotalCalls() {
        return defaultMaxTotalCalls;
    }

    public void setDefaultMaxTotalCalls(int defaultMaxTotalCalls) {
        this.defaultMaxTotalCalls = defaultMaxTotalCalls;
    }

    public AccessLevel getDefaultAccessLevel() {
        return defaultAccessLevel;
    }

    public void setDefaultAccessLevel(AccessLevel defaultAccessLevel) {
        this.defaultAccessLevel = defaultAccessLevel;
    }
}
