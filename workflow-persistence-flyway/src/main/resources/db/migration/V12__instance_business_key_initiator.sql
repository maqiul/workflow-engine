-- V12: wf_instance 加 business_key + initiator 一等公民列（G-01 / G-02）
-- business_key：关联外部业务单据号（幂等 / 按单号定位实例）；
-- initiator：发起人（与 __initiator 变量双写，独立列供「我发起的」按发起人查）。
-- 方言约束：不使用 ADD COLUMN IF NOT EXISTS（MariaDB 扩展，MySQL 不支持）；
-- CREATE INDEX 不加 IF NOT EXISTS（MySQL 不支持），Flyway 保证每版本仅执行一次。与 V9 写法一致。
ALTER TABLE wf_instance ADD COLUMN business_key VARCHAR(255);
ALTER TABLE wf_instance ADD COLUMN initiator VARCHAR(64);
CREATE INDEX idx_wf_instance_business_key ON wf_instance(business_key);
CREATE INDEX idx_wf_instance_initiator ON wf_instance(initiator);
