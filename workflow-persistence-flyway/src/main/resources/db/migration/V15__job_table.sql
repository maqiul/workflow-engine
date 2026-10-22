-- V15: 异步作业表（持久化作业执行器）
-- 作业落库 → 进程重启后仍在、执行失败可重试、最终进死信可查、多节点靠原子领取互斥。
-- 方言约束（勿改回）：不使用 ADD COLUMN IF NOT EXISTS（MariaDB 扩展，MySQL 会抛语法错误）；
--   CREATE INDEX 不加 IF NOT EXISTS（MySQL 不支持）。Flyway 保证每版本仅执行一次。
CREATE TABLE wf_job (
    id              VARCHAR(64)   NOT NULL,
    type            VARCHAR(32)   NOT NULL,
    instance_id     VARCHAR(64),
    payload         CLOB,
    due_at          BIGINT        NOT NULL,
    state           VARCHAR(16)   NOT NULL,
    retries         INT           NOT NULL DEFAULT 0,
    max_retries     INT           NOT NULL DEFAULT 3,
    last_error      VARCHAR(1024),
    lock_owner      VARCHAR(64),
    lock_expires_at BIGINT        NOT NULL DEFAULT 0,
    create_time     BIGINT        NOT NULL,
    update_time     BIGINT        NOT NULL,
    CONSTRAINT pk_wf_job PRIMARY KEY (id)
);
CREATE INDEX idx_wf_job_due ON wf_job(due_at);
CREATE INDEX idx_wf_job_state ON wf_job(state);
CREATE INDEX idx_wf_job_instance ON wf_job(instance_id);
