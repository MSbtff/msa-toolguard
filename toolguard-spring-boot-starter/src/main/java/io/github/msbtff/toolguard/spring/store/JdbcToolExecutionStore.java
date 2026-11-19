package io.github.msbtff.toolguard.spring.store;

import io.github.msbtff.toolguard.core.CanonicalJson;
import io.github.msbtff.toolguard.core.GuardrailException;
import io.github.msbtff.toolguard.core.GuardrailViolation;
import io.github.msbtff.toolguard.core.ToolExecutionOutcome;
import io.github.msbtff.toolguard.core.ToolExecutionStatus;
import io.github.msbtff.toolguard.core.ToolExecutionStore;
import io.github.msbtff.toolguard.core.UncertainExecutionException;
import io.github.msbtff.toolguard.core.ViolationCode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * JDBC-backed durable {@link ToolExecutionStore} with database row locking ({@code SELECT ... FOR UPDATE})
 * and restart-safe {@code PENDING}/{@code COMPLETED}/{@code REJECTED} state persistence.
 * Compatible with PostgreSQL, MariaDB/MySQL, and H2.
 */
public final class JdbcToolExecutionStore implements ToolExecutionStore {
    public static final String DEFAULT_TABLE_NAME = "toolguard_executions";
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private final DataSource dataSource;
    private final String tableName;
    private final Clock clock;

    public JdbcToolExecutionStore(DataSource dataSource) {
        this(dataSource, DEFAULT_TABLE_NAME, Clock.systemUTC(), true);
    }

    public JdbcToolExecutionStore(
        DataSource dataSource,
        String tableName,
        Clock clock,
        boolean initializeSchema
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.tableName = validateTableName(tableName);
        this.clock = Objects.requireNonNull(clock, "clock");
        if (initializeSchema) {
            initializeSchema();
        }
    }

    public void initializeSchema() {
        String ddl = """
            CREATE TABLE IF NOT EXISTS %s (
                scope_key       VARCHAR(191) NOT NULL,
                tool_name       VARCHAR(191) NOT NULL,
                request_id      VARCHAR(191) NOT NULL,
                payload_digest  VARCHAR(64)  NOT NULL,
                status          VARCHAR(32)  NOT NULL,
                evidence_id     VARCHAR(64),
                rejection_code  VARCHAR(128),
                message         TEXT,
                facts_json      TEXT         NOT NULL,
                created_at_ms   BIGINT       NOT NULL,
                updated_at_ms   BIGINT       NOT NULL,
                PRIMARY KEY (scope_key, tool_name, request_id)
            )
            """.formatted(tableName);
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize toolguard JDBC table '" + tableName + "'", e);
        }
    }

    @Override
    public ToolExecutionOutcome executeIdempotent(
        String scopeKey,
        String toolName,
        String requestId,
        String payloadDigest,
        Supplier<ToolExecutionOutcome> action
    ) {
        Objects.requireNonNull(scopeKey, "scopeKey");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(payloadDigest, "payloadDigest");
        Objects.requireNonNull(action, "action");

        ensurePendingRowExists(scopeKey, toolName, requestId, payloadDigest);

        try (Connection conn = dataSource.getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                RowState row = lockRow(conn, scopeKey, toolName, requestId);
                if (!row.payloadDigest().equals(payloadDigest)) {
                    rollbackQuietly(conn);
                    throw new GuardrailException(new GuardrailViolation(
                        ViolationCode.IDEMPOTENCY_CONFLICT,
                        toolName,
                        "requestId '" + requestId + "' was already used with different arguments",
                        "Use the exact same arguments when retrying requestId '" + requestId + "', or generate a new requestId."
                    ));
                }
                if (row.status() != ToolExecutionStatus.PENDING) {
                    rollbackQuietly(conn);
                    return row.toReplayedOutcome(requestId);
                }

                try {
                    ToolExecutionOutcome result = Objects.requireNonNull(action.get(), "tool action returned null");
                    updateRow(conn, scopeKey, toolName, requestId, result);
                    conn.commit();
                    return result;
                } catch (UncertainExecutionException uncertain) {
                    ToolExecutionOutcome pending = ToolExecutionOutcome.pending(requestId, uncertain.getMessage());
                    updateRow(conn, scopeKey, toolName, requestId, pending);
                    conn.commit();
                    return pending;
                } catch (RuntimeException | Error failure) {
                    rollbackQuietly(conn);
                    throw failure;
                }
            } finally {
                restoreAutoCommitQuietly(conn, previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("JDBC idempotent execution failed for tool '" + toolName + "'", e);
        }
    }

    public Optional<ToolExecutionOutcome> find(String scopeKey, String toolName, String requestId) {
        Objects.requireNonNull(scopeKey, "scopeKey");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(requestId, "requestId");
        String sql = "SELECT payload_digest, status, evidence_id, rejection_code, message, facts_json FROM "
            + tableName + " WHERE scope_key = ? AND tool_name = ? AND request_id = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scopeKey);
            ps.setString(2, toolName);
            ps.setString(3, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(readRow(rs).toOutcome(requestId, false));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read toolguard execution row", e);
        }
    }

    private void ensurePendingRowExists(
        String scopeKey,
        String toolName,
        String requestId,
        String payloadDigest
    ) {
        String sql = "INSERT INTO " + tableName
            + " (scope_key, tool_name, request_id, payload_digest, status, evidence_id, rejection_code, message, facts_json, created_at_ms, updated_at_ms)"
            + " VALUES (?, ?, ?, ?, ?, NULL, NULL, ?, ?, ?, ?)";
        long now = clock.millis();
        try (Connection conn = dataSource.getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(true);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, scopeKey);
                ps.setString(2, toolName);
                ps.setString(3, requestId);
                ps.setString(4, payloadDigest);
                ps.setString(5, ToolExecutionStatus.PENDING.name());
                ps.setString(6, "Execution in progress");
                ps.setString(7, "{}");
                ps.setLong(8, now);
                ps.setLong(9, now);
                ps.executeUpdate();
            } catch (SQLException ex) {
                if (!isDuplicateKey(ex)) {
                    throw ex;
                }
            } finally {
                restoreAutoCommitQuietly(conn, previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to register pending toolguard execution row", e);
        }
    }

    private RowState lockRow(Connection conn, String scopeKey, String toolName, String requestId) throws SQLException {
        String sql = "SELECT payload_digest, status, evidence_id, rejection_code, message, facts_json FROM "
            + tableName + " WHERE scope_key = ? AND tool_name = ? AND request_id = ? FOR UPDATE";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scopeKey);
            ps.setString(2, toolName);
            ps.setString(3, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("Execution row not found after registration for requestId '" + requestId + "'");
                }
                return readRow(rs);
            }
        }
    }

    private void updateRow(
        Connection conn,
        String scopeKey,
        String toolName,
        String requestId,
        ToolExecutionOutcome outcome
    ) throws SQLException {
        String sql = "UPDATE " + tableName
            + " SET status = ?, evidence_id = ?, rejection_code = ?, message = ?, facts_json = ?, updated_at_ms = ?"
            + " WHERE scope_key = ? AND tool_name = ? AND request_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, outcome.status().name());
            ps.setString(2, outcome.evidenceId());
            ps.setString(3, outcome.rejectionCode());
            ps.setString(4, outcome.message());
            ps.setString(5, CanonicalJson.stringify(outcome.facts()));
            ps.setLong(6, clock.millis());
            ps.setString(7, scopeKey);
            ps.setString(8, toolName);
            ps.setString(9, requestId);
            ps.executeUpdate();
        }
    }

    private static RowState readRow(ResultSet rs) throws SQLException {
        String digest = rs.getString("payload_digest");
        ToolExecutionStatus status = ToolExecutionStatus.valueOf(rs.getString("status"));
        String evidenceId = rs.getString("evidence_id");
        String rejectionCode = rs.getString("rejection_code");
        String message = rs.getString("message");
        String factsJson = rs.getString("facts_json");
        Map<String, Object> facts = (factsJson == null || factsJson.isBlank())
            ? Map.of()
            : CanonicalJson.parseObject(factsJson);
        return new RowState(digest, status, evidenceId, rejectionCode, message, facts);
    }

    private static boolean isDuplicateKey(SQLException ex) {
        if (ex instanceof SQLIntegrityConstraintViolationException) {
            return true;
        }
        String state = ex.getSQLState();
        return state != null && state.startsWith("23");
    }

    private static String validateTableName(String tableName) {
        if (tableName == null || !SAFE_IDENTIFIER.matcher(tableName).matches()) {
            throw new IllegalArgumentException("Invalid SQL table identifier: " + tableName);
        }
        return tableName;
    }

    private static void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // Preserve original exception when rollback also fails.
        }
    }

    private static void restoreAutoCommitQuietly(Connection conn, boolean autoCommit) {
        try {
            conn.setAutoCommit(autoCommit);
        } catch (SQLException ignored) {
            // Connection pool handles broken connections on close.
        }
    }

    private record RowState(
        String payloadDigest,
        ToolExecutionStatus status,
        String evidenceId,
        String rejectionCode,
        String message,
        Map<String, Object> facts
    ) {
        ToolExecutionOutcome toReplayedOutcome(String requestId) {
            return toOutcome(requestId, true);
        }

        ToolExecutionOutcome toOutcome(String requestId, boolean replayed) {
            return new ToolExecutionOutcome(status, evidenceId, requestId, rejectionCode, message, replayed, facts);
        }
    }
}
