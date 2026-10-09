-- V14: 全局序列表，供历史活动/历史任务/意见的 seq 跨重启、跨节点单调
-- （替代进程内 AtomicLong —— 集群下同实例跨节点推进 + 同毫秒会撞，见 HistoricActivityInstance 注释）。
-- 取号：UPDATE next_val=next_val+1 后 SELECT，行锁保证原子；与业务写入同事务。
-- 跨库通用（H2/MySQL/PG 都支持 UPDATE...SET c=c+1 + SELECT），不依赖各库原生 SEQUENCE 语法。
CREATE TABLE IF NOT EXISTS wf_sequence (
    name      VARCHAR(64) PRIMARY KEY,
    next_val  BIGINT      NOT NULL
);
INSERT INTO wf_sequence (name, next_val) VALUES ('hist_activity', 0);
INSERT INTO wf_sequence (name, next_val) VALUES ('hist_task', 0);
INSERT INTO wf_sequence (name, next_val) VALUES ('comment', 0);
