-- V11: 为任务引入 assignee 一等公民（claim / setAssignee 支持）
-- assignee = 当前办理人；NULL 表示未指派/未认领，此时按 candidate 候选池决定谁能办。
-- 方言约束：不使用 ADD COLUMN IF NOT EXISTS（MariaDB 扩展，MySQL 会抛语法错误）；
-- Flyway 保证每版本仅执行一次，无需 IF NOT EXISTS。与 V9 写法一致。
ALTER TABLE wf_task ADD COLUMN assignee VARCHAR(64);
