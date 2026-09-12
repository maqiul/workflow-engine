-- =====================================================================
-- V10: 审批意见表
--
-- 与 wf_hist_task 的分工：历史任务记「谁批的、以什么方式结束」，
-- 意见记「他为什么这么批」—— 人说的话本身。
--
-- 设计说明：
--   * 独立成表，而不是往审计日志的 detail 列里塞：审计受保留策略清理
--     （HistoryRetention.purgeBefore），而意见是归档导出要长期保留的证据，
--     放在一个会过期的地方等于迟早丢失。本表不参与 purgeBefore。
--   * create_time + seq 双键排序：时间戳只到毫秒，同一毫秒内的多条意见
--     必须靠 seq 定序，否则审批链顺序由随机 UUID 决定（历史活动表踩过这个坑）。
--   * task_id / node_id 可空：流程级意见（如发起人附言）不挂具体待办。
--   * message 可空：「同意」这类动作本身即信息，不必强求文本。
--   * 不建外键：与其余 wf_ 表一致，级联删除的连锁反应留到应用层显式处理。
-- =====================================================================

CREATE TABLE IF NOT EXISTS wf_comment (
    id           VARCHAR(64)   PRIMARY KEY,
    instance_id  VARCHAR(64)   NOT NULL,
    task_id      VARCHAR(64),
    node_id      VARCHAR(64),
    user_id      VARCHAR(64)   NOT NULL,
    comment_type VARCHAR(16)   NOT NULL,
    message      VARCHAR(2000),
    create_time  BIGINT        NOT NULL,
    seq          BIGINT        NOT NULL
);

-- 按实例还原审批链顺序（与 hist_task 同一套索引思路，避免 filesort）
CREATE INDEX idx_comment_inst ON wf_comment (instance_id, create_time, seq);
-- 按待办查意见
CREATE INDEX idx_comment_task ON wf_comment (task_id);
-- 「某人都说过什么」
CREATE INDEX idx_comment_user ON wf_comment (user_id);
