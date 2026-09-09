-- =====================================================================
-- V7: 多租户隔离 —— 为运行态表加租户列
--
-- 设计说明：
--   * wf_instance.tenant_id / wf_task.tenant_id 可空：
--       null = 全局(兼容老数据)，非空 = 归属某租户。
--   * 只给 instance/task 加列：实体(JPA + MyBatis 的 WfInstanceEntity /
--     WfTaskEntity)已声明 tenant_id 字段并在 SELECT 中引用，缺列即报
--     "Column TENANT_ID not found"。流程定义表的租户语义暂未落库，故不加。
--   * 用普通 ALTER/CREATE：Flyway 保证每个版本脚本仅执行一次。
-- =====================================================================

ALTER TABLE wf_instance ADD COLUMN tenant_id VARCHAR(64);
ALTER TABLE wf_task     ADD COLUMN tenant_id VARCHAR(64);

CREATE INDEX idx_instance_tenant ON wf_instance (tenant_id);
CREATE INDEX idx_task_tenant     ON wf_task (tenant_id);
