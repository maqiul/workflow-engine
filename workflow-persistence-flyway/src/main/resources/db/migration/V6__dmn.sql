-- =====================================================================
-- V6: DMN 决策表和历史表
--
-- 设计说明：
--   * wf_decision_table: 存储 DMN 决策表定义
--   * wf_decision_history: 记录每次决策执行的历史
-- =====================================================================

CREATE TABLE IF NOT EXISTS wf_decision_table (
    id              VARCHAR(64)   NOT NULL PRIMARY KEY,
    name            VARCHAR(256),
    inputs_json     TEXT,
    outputs_json    TEXT,
    rules_json      TEXT,
    hit_policy      VARCHAR(32)
);

CREATE TABLE IF NOT EXISTS wf_decision_history (
    id                  VARCHAR(64)   NOT NULL PRIMARY KEY,
    instance_id         VARCHAR(64)   NOT NULL,
    token_id            VARCHAR(64),
    node_id             VARCHAR(64)   NOT NULL,
    decision_table_id   VARCHAR(64)   NOT NULL,
    inputs_json         TEXT,
    outputs_json        TEXT,
    matched_rule_id     VARCHAR(64),
    executed_at         BIGINT
);

CREATE INDEX idx_decision_history_instance ON wf_decision_history (instance_id);
CREATE INDEX idx_decision_history_node ON wf_decision_history (node_id);
CREATE INDEX idx_decision_history_table ON wf_decision_history (decision_table_id);
