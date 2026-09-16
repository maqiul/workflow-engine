-- V13: wf_task 加 delegated_from 列（G-09 委派回签）
-- 记录委派前的原办理人；resolveTask 后任务回到此人继续，不推进流程。
-- 不使用 ADD COLUMN IF NOT EXISTS（MySQL 不支持）；Flyway 保证仅执行一次。与 V9/V11 写法一致。
ALTER TABLE wf_task ADD COLUMN delegated_from VARCHAR(64);
