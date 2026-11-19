CREATE TABLE IF NOT EXISTS toolguard_executions (
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
);
