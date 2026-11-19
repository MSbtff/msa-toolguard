package io.github.msbtff.toolguard.spring;

import io.github.msbtff.toolguard.core.AccessLevel;
import io.github.msbtff.toolguard.core.AuditLogFormatter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "toolguard")
public class ToolGuardProperties {
    private boolean enabled = true;
    private int defaultMaxTotalCalls = 50;
    private AccessLevel defaultAccessLevel = AccessLevel.READ_ONLY;
    private StoreProperties store = new StoreProperties();
    private AuditProperties audit = new AuditProperties();

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

    public StoreProperties getStore() {
        return store;
    }

    public void setStore(StoreProperties store) {
        this.store = store;
    }

    public AuditProperties getAudit() {
        return audit;
    }

    public void setAudit(AuditProperties audit) {
        this.audit = audit;
    }

    public enum StoreType {
        IN_MEMORY,
        JDBC,
        REDIS
    }

    public static class StoreProperties {
        private StoreType type = StoreType.IN_MEMORY;
        private JdbcProperties jdbc = new JdbcProperties();
        private RedisProperties redis = new RedisProperties();

        public StoreType getType() {
            return type;
        }

        public void setType(StoreType type) {
            this.type = type;
        }

        public JdbcProperties getJdbc() {
            return jdbc;
        }

        public void setJdbc(JdbcProperties jdbc) {
            this.jdbc = jdbc;
        }

        public RedisProperties getRedis() {
            return redis;
        }

        public void setRedis(RedisProperties redis) {
            this.redis = redis;
        }
    }

    public static class JdbcProperties {
        private String tableName = "toolguard_executions";
        private boolean initializeSchema = true;

        public String getTableName() {
            return tableName;
        }

        public void setTableName(String tableName) {
            this.tableName = tableName;
        }

        public boolean isInitializeSchema() {
            return initializeSchema;
        }

        public void setInitializeSchema(boolean initializeSchema) {
            this.initializeSchema = initializeSchema;
        }
    }

    public static class RedisProperties {
        private String keyPrefix = "toolguard:exec:";
        private Duration lockTtl = Duration.ofSeconds(30);
        private Duration lockAcquireTimeout = Duration.ofSeconds(10);
        private Duration stateTtl = Duration.ofHours(24);

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public Duration getLockTtl() {
            return lockTtl;
        }

        public void setLockTtl(Duration lockTtl) {
            this.lockTtl = lockTtl;
        }

        public Duration getLockAcquireTimeout() {
            return lockAcquireTimeout;
        }

        public void setLockAcquireTimeout(Duration lockAcquireTimeout) {
            this.lockAcquireTimeout = lockAcquireTimeout;
        }

        public Duration getStateTtl() {
            return stateTtl;
        }

        public void setStateTtl(Duration stateTtl) {
            this.stateTtl = stateTtl;
        }
    }

    public static class AuditProperties {
        private boolean enabled = true;
        private List<String> redactKeys = new ArrayList<>(AuditLogFormatter.DEFAULT_REDACTED_KEYS);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getRedactKeys() {
            return redactKeys;
        }

        public void setRedactKeys(List<String> redactKeys) {
            this.redactKeys = redactKeys;
        }
    }
}
