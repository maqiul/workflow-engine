-- =====================================================================
-- V4: 历史任务表
--
-- 与 wf_hist_activity 的分工：活动回答「流程走到哪、哪一步慢」，
-- 任务回答「这张单由谁批的、以什么方式结束」。绩效与责任追溯只能靠后者。
--
-- 设计说明：
--   * 主键直接取 task_id：一个任务只该有一条历史，重复写在数据层就不可能成立。
--   * 只记录已落定的任务（end_reason 非 PENDING），因此没有 end_time IS NULL 的中间态。
--   * candidate_users / completed_by 采用「前后带逗号」的存储形式：,u1,u2,
--     这样按人检索可用 LIKE '%,u1,%' 精确命中，不会把 u1 误配到 u11 上。
--     代价是这两列不能被规范化查询，但按人统计本就是历史表的主要用途。
--   * 不存 duration：end_time - start_time 即可算出。
-- =====================================================================

CREATE TABLE IF NOT EXISTS wf_hist_task (
    task_id          VARCHAR(64)  PRIMARY KEY,
    instance_id      VARCHAR(64)  NOT NULL,
    process_key      VARCHAR(64)  NOT NULL,
    process_version  INT          NOT NULL,
    node_id          VARCHAR(64)  NOT NULL,
    candidate_users  VARCHAR(512) NOT NULL,
    completed_by     VARCHAR(512),
    start_time       BIGINT       NOT NULL,
    end_time         BIGINT       NOT NULL,
    seq              BIGINT       NOT NULL,
    end_reason       VARCHAR(16)  NOT NULL
);

CREATE INDEX idx_hist_task_inst ON wf_hist_task (instance_id);
CREATE INDEX idx_hist_task_node ON wf_hist_task (process_key, node_id);
CREATE INDEX idx_hist_task_time ON wf_hist_task (end_time);
-- 按实例还原审批链顺序时用它，避免 filesort
CREATE INDEX idx_hist_task_order ON wf_hist_task (instance_id, end_time, seq);
