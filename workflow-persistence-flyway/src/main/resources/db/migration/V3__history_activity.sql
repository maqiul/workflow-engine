-- =====================================================================
-- V3: 历史活动实例宽表
--
-- 用途：效能分析（某节点平均耗时、瓶颈在哪个环节、实际走过的路径）。
-- 与 wf_audit_log 的分工：审计是 append-only 事件流水，历史是「区间」——
-- 平均耗时这类指标要靠区间直算，用事件流水差分既脆弱又昂贵。
--
-- 设计说明：
--   * 冗余 process_key / process_version：让「按流程 key + 节点聚合」不必 join 实例表；
--     实例表会被归档清理，历史表要能独立长存。
--   * 不存 duration 列：end_time - start_time 即可算出，多存一份就有不一致风险。
--   * end_time IS NULL 表示活动仍在进行 —— UserTask / SUB_PROCESS 的记录会跨越
--     两次引擎推进（进入时开、审批完成后闭），这样才能覆盖真实的人类等待时长。
--   * seq 是次级排序键：start_time 只到毫秒，同一毫秒内推进的 START 与 USER_TASK
--     分不出先后，而还原路径必须有确定顺序。当前取进程内单调递增，
--     <b>多实例部署须改为数据库序列（或自增列）</b>，否则跨节点写入的 seq 会重叠。
--   * 历史行必须与产生它的业务写入在同一事务提交，否则业务回滚后
--     库里会留下「发生过但从没发生」的幽灵活动。
-- =====================================================================

CREATE TABLE IF NOT EXISTS wf_hist_activity (
    id               VARCHAR(64) PRIMARY KEY,
    instance_id      VARCHAR(64) NOT NULL,
    process_key      VARCHAR(64) NOT NULL,
    process_version  INT         NOT NULL,
    activity_id      VARCHAR(64) NOT NULL,
    activity_type    VARCHAR(32) NOT NULL,
    token_id         VARCHAR(64) NOT NULL,
    task_id          VARCHAR(64),
    start_time       BIGINT      NOT NULL,
    seq              BIGINT      NOT NULL,
    end_time         BIGINT,
    performer        VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_hist_act_inst ON wf_hist_activity (instance_id);
CREATE INDEX IF NOT EXISTS idx_hist_act_key  ON wf_hist_activity (process_key, activity_id);
-- findOpen 走这条：按实例+令牌+节点定位未闭合记录
CREATE INDEX IF NOT EXISTS idx_hist_act_open ON wf_hist_activity (instance_id, token_id, activity_id);
-- 报表按实例还原路径时用它，避免大表 filesort
CREATE INDEX IF NOT EXISTS idx_hist_act_order ON wf_hist_activity (instance_id, start_time, seq);
