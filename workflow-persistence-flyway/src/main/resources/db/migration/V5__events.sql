-- =====================================================================
-- V5: 事件表 - 消息/信号/定时器事件的统一存储
--
-- 设计说明：
--   * 主键 (instance_id, node_id)：一个节点只会有一个事件
--   * event_type：区分 MESSAGE / SIGNAL / TIMER
--   * MESSAGE 事件按 message_key (messageName:correlationKey) 索引
--   * SIGNAL 事件按 signal_name 索引
--   * TIMER 事件按 trigger_time 索引（定时器调度器扫描）
-- =====================================================================

CREATE TABLE IF NOT EXISTS wf_event (
    instance_id   VARCHAR(64)  NOT NULL,
    node_id       VARCHAR(64)  NOT NULL,
    event_type    VARCHAR(16)  NOT NULL,
    message_key   VARCHAR(256),
    signal_name   VARCHAR(128),
    trigger_time  BIGINT,
    interrupting  BOOLEAN,
    PRIMARY KEY (instance_id, node_id)
);

CREATE INDEX IF NOT EXISTS idx_event_message_key ON wf_event (message_key);
CREATE INDEX IF NOT EXISTS idx_event_signal_name ON wf_event (signal_name);
CREATE INDEX IF NOT EXISTS idx_event_trigger_time ON wf_event (trigger_time);