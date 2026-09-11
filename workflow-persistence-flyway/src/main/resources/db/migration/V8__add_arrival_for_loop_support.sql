-- V8: 添加 arrival 字段支持循环回边
-- Token 到达代次：区分同一节点多轮到达，支持排他网关回边式循环

ALTER TABLE wf_token ADD COLUMN IF NOT EXISTS arrival INT DEFAULT 0;
ALTER TABLE wf_task ADD COLUMN IF NOT EXISTS arrival INT DEFAULT 0;

COMMENT ON COLUMN wf_token.arrival IS 'Token 到达代次：每移动到一个节点自增，支持循环回边';
COMMENT ON COLUMN wf_task.arrival IS '任务所属 Token 的到达代次：区分同一节点多轮到达';
