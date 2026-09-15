-- V8: 添加 arrival 字段支持循环回边
-- Token 到达代次：区分同一节点多轮到达，支持排他网关回边式循环

-- 方言约束（勿改回）：不使用 ADD COLUMN IF NOT EXISTS，也不使用 COMMENT ON COLUMN。
--   * `ADD COLUMN IF NOT EXISTS` 是 MariaDB 扩展语法，MySQL 会直接抛
--     SQLSyntaxErrorException（cross-db 实测踩过）；
--   * `COMMENT ON COLUMN` 仅 PostgreSQL 支持，MySQL 无此语法。
--   Flyway 保证每个版本只执行一次，本就不需要 IF NOT EXISTS 兜重入。
--   与 V2 的注释说明、V9 的写法保持一致。
--
-- 列语义（原用 COMMENT ON COLUMN 声明，因 MySQL 不支持而降为 SQL 注释）：
--   wf_token.arrival —— Token 到达代次：每移动到一个节点自增，支持循环回边
--   wf_task.arrival  —— 任务所属 Token 的到达代次：区分同一节点多轮到达

ALTER TABLE wf_token ADD COLUMN arrival INT DEFAULT 0;
ALTER TABLE wf_task ADD COLUMN arrival INT DEFAULT 0;
