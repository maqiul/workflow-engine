-- =====================================================================
-- workflow-engine 统一 Schema 迁移 V1
-- 目标:同一份 DDL 在 H2 / MySQL / PostgreSQL 三库均可执行
-- 兼容性说明:
--   * TEXT 三库通用(H2 2.x 支持 TEXT,MySQL/PG 原生 TEXT)
--   * VARCHAR / INT / BIGINT 三库通用
--   * 复合主键 (key_, version) 三库通用
--   * IF NOT EXISTS 三库通用
-- =====================================================================

CREATE TABLE IF NOT EXISTS wf_process_def (
    key_            VARCHAR(64)  NOT NULL,
    version         INT          NOT NULL,
    name            VARCHAR(128),
    start_node_id   VARCHAR(64)  NOT NULL,
    nodes_json      TEXT         NOT NULL,
    outgoing_json   TEXT         NOT NULL,
    variable_definitions_json TEXT,
    PRIMARY KEY (key_, version)
);

CREATE TABLE IF NOT EXISTS wf_instance (
    id                VARCHAR(64) PRIMARY KEY,
    process_key       VARCHAR(64) NOT NULL,
    process_version   INT         NOT NULL,
    status            VARCHAR(16) NOT NULL,
    create_time       BIGINT      NOT NULL,
    end_time          BIGINT,
    variables_json    TEXT,
    parent_instance_id VARCHAR(64),
    parent_token_id    VARCHAR(64),
    parent_node_id     VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS wf_token (
    id              VARCHAR(64) PRIMARY KEY,
    instance_id     VARCHAR(64) NOT NULL,
    current_node_id VARCHAR(64) NOT NULL,
    status          VARCHAR(16) NOT NULL
);

CREATE TABLE IF NOT EXISTS wf_task (
    id                       VARCHAR(64) PRIMARY KEY,
    instance_id              VARCHAR(64) NOT NULL,
    token_id                 VARCHAR(64) NOT NULL,
    node_id                  VARCHAR(64) NOT NULL,
    candidate_json           TEXT        NOT NULL,
    completed_approvers_json TEXT        NOT NULL,
    status                   VARCHAR(16) NOT NULL,
    create_time              BIGINT      NOT NULL
);

CREATE TABLE IF NOT EXISTS wf_audit_log (
    id           VARCHAR(64) PRIMARY KEY,
    instance_id  VARCHAR(64) NOT NULL,
    task_id      VARCHAR(64),
    event_type   VARCHAR(32) NOT NULL,
    operator     VARCHAR(64) NOT NULL,
    timestamp    BIGINT      NOT NULL,
    detail       TEXT
);
