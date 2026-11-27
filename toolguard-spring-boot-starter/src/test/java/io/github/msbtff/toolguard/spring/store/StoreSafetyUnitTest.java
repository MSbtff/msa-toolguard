package io.github.msbtff.toolguard.spring.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.msbtff.toolguard.spring.ToolGuardProperties;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class StoreSafetyUnitTest {

    @Test
    void redisCompositeKeyHashAvoidsDelimiterCollisions() {
        String key1 = RedisToolExecutionStore.compositeKeyHash("a::b", "c", "req-1");
        String key2 = RedisToolExecutionStore.compositeKeyHash("a", "b::c", "req-1");
        assertNotEquals(key1, key2);
        assertEquals(64, key1.length());
        assertEquals(key1, RedisToolExecutionStore.compositeKeyHash("a::b", "c", "req-1"));
    }

    @Test
    void jdbcLockTimeoutDetectionRecognizesStandardPostgresMariaDbAndH2Timeouts() {
        assertTrue(JdbcToolExecutionStore.isLockTimeout(new SQLTimeoutException("timeout")));
        assertTrue(JdbcToolExecutionStore.isLockTimeout(new SQLException("canceling statement", "57014")));
        assertTrue(JdbcToolExecutionStore.isLockTimeout(new SQLException("lock not available", "55P03")));
        assertTrue(JdbcToolExecutionStore.isLockTimeout(new SQLException("h2 timeout", "HYT00")));
        assertTrue(JdbcToolExecutionStore.isLockTimeout(new SQLException("lock wait timeout", "41000", 1205)));
        assertTrue(JdbcToolExecutionStore.isLockTimeout(new SQLException("statement timeout", "70100", 1969)));
        assertTrue(JdbcToolExecutionStore.isLockTimeout(new SQLException("query timeout", "HY000", 3024)));
        assertTrue(JdbcToolExecutionStore.isLockTimeout(new SQLException("lock nowait", "HY000", 3572)));
        assertFalse(JdbcToolExecutionStore.isLockTimeout(new SQLException("syntax error", "42601")));
    }

    @Test
    void jdbcPurgeIndexNameRespectsMaxIdentifierLengthAndDetectsDuplicateIndexErrors() {
        assertEquals(
            "idx_toolguard_executions_status_updated",
            JdbcToolExecutionStore.purgeIndexName("toolguard_executions")
        );
        String longTable = "a".repeat(60);
        String longIndex = JdbcToolExecutionStore.purgeIndexName(longTable);
        assertEquals(63, longIndex.length());
        assertTrue(longIndex.endsWith("_status_updated"));

        assertTrue(JdbcToolExecutionStore.isDuplicateIndex(new SQLException("already exists", "42P07")));
        assertTrue(JdbcToolExecutionStore.isDuplicateIndex(new SQLException("already exists", "42S11")));
        assertTrue(JdbcToolExecutionStore.isDuplicateIndex(new SQLException("dup key name", "42000", 1061)));
        assertFalse(JdbcToolExecutionStore.isDuplicateIndex(new SQLException("syntax error", "42601")));
    }

    @Test
    void storePropertiesExposeBoundedInMemoryAndJdbcLockTimeoutDefaults() {
        ToolGuardProperties props = new ToolGuardProperties();
        assertEquals(10_000, props.getStore().getInMemory().getMaxEntries());
        assertEquals(Duration.ofHours(24), props.getStore().getInMemory().getEntryTtl());
        assertEquals(Duration.ofSeconds(10), props.getStore().getJdbc().getLockTimeout());

        props.getStore().getInMemory().setMaxEntries(500);
        props.getStore().getInMemory().setEntryTtl(Duration.ofMinutes(30));
        props.getStore().getJdbc().setLockTimeout(Duration.ofSeconds(3));

        assertEquals(500, props.getStore().getInMemory().getMaxEntries());
        assertEquals(Duration.ofMinutes(30), props.getStore().getInMemory().getEntryTtl());
        assertEquals(Duration.ofSeconds(3), props.getStore().getJdbc().getLockTimeout());
    }
}
