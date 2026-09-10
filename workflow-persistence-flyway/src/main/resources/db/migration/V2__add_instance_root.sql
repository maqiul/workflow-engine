-- =====================================================================
-- V2: 为流程实例补 root_instance_id 列
--
-- 动机：引擎的并发锁粒度是「流程树根」（见 README §17.2）——子实例必须继承父树的根 id，
-- 父子才能共用一把锁。该字段此前只存在于内存对象，没有落库，导致 JPA / MyBatis 路径
-- 重建实例后 rootInstanceId 丢失、退化为「自身即根」：父子各持一把锁，
-- startSubProcess(父→子) 与 onSubProcessCompleted(子→父) 的 ABBA 死锁防护形同虚设。
--
-- 兼容性：三库均支持 ALTER TABLE ADD COLUMN；由 Flyway 保证只执行一次，
-- 故不使用 IF NOT EXISTS（PostgreSQL 低版本不支持该写法）。
-- 历史数据：留 NULL 即可 —— 运行时读回 NULL 会回退为「以自身为根」，
-- 对非子流程实例语义正确；旧子流程实例属开发期数据，不做回填。
-- =====================================================================

ALTER TABLE wf_instance ADD COLUMN root_instance_id VARCHAR(64);

CREATE INDEX idx_wf_instance_root ON wf_instance (root_instance_id);
