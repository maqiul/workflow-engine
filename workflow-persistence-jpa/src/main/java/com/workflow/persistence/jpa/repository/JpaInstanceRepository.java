package com.workflow.persistence.jpa.repository;

import com.alibaba.fastjson2.JSON;
import com.workflow.persistence.jpa.JpaPersistence;
import com.workflow.persistence.jpa.entity.WfInstanceEntity;
import com.workflow.persistence.jpa.entity.WfTaskEntity;
import com.workflow.persistence.jpa.entity.WfTokenEntity;
import com.workflow.repository.InstanceRepository;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import com.workflow.runtime.Token;
import jakarta.persistence.EntityManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * JPA 版 InstanceRepository
 *
 * 策略:
 *  - 用反射写 final 字段(测试已验证 JDK 17 + Hibernate 6.4 + H2 可用)
 *  - findById 单事务内加载 instance + token + task 全部转成 Domain 对象
 *  - save 是 delete-all + persist Token 的全量同步策略
 *  - 优先复用当前线程绑定的 EM (JpaPersistence.bindCurrentEm),让引擎在外部事务下
 *    instanceRepo.save 与 taskRepo.save 共用同一事务,避免"实例保存但 task 表查询为空"
 */
public class JpaInstanceRepository implements InstanceRepository {

    private final JpaPersistence jpa;

    public JpaInstanceRepository(JpaPersistence jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(ProcessInstance instance) {
        try {
            doSave(instance);
        } catch (RuntimeException ex) {
            throw JpaPersistence.asConflictIfOptimisticLock(ex, instance.getId());
        }
    }

    /**
     * CAS 的实现体。
     *
     * <p>版本条件由实体上的 {@code @Version} 生成：Hibernate 在 UPDATE 时自动补
     * {@code WHERE revision = <读取到的值>} 并自增。版本不匹配表现为「影响 0 行」，
     * 由 Hibernate 转成乐观锁异常，通常在 flush 或事务提交时才抛出 ——
     * 所以必须在调用 {@code runInOrOpenTx} 的外层捕获。
     */
    private void doSave(ProcessInstance instance) {
        runInOrOpenTx(em -> {
            WfInstanceEntity entity = em.find(WfInstanceEntity.class, instance.getId());
            if (entity == null) {
                entity = new WfInstanceEntity();
                entity.setId(instance.getId());
                // 插入行从 1 起算，与内存仓储 save 后的版本号对齐
                entity.setRevision(FIRST_REVISION);
                // 不要在 set 完所有字段前 persist!Hibernate 会立刻 INSERT 触发 NOT NULL 验证
                // 先 set 所有字段,再用 merge upsert
            }
            entity.setProcessKey(instance.getProcessKey());
            entity.setProcessVersion(instance.getProcessVersion());
            entity.setStatus(instance.getStatus());
            entity.setCreateTime(instance.getCreateTime());
            entity.setEndTime(instance.getEndTime() > 0 ? instance.getEndTime() : null);
            entity.setVariablesJson(JSON.toJSONString(instance.getVariables()));
            entity.setParentInstanceId(instance.getParentInstanceId());
            entity.setParentTokenId(instance.getParentTokenId());
            entity.setParentNodeId(instance.getParentNodeId());
            // getRootInstanceId() 永不为 null（未设置时回退自身 id），库里因此总有可用值
            entity.setRootInstanceId(instance.getRootInstanceId());
            em.merge(entity);

            // Token 差量同步。
            //
            // 不能再用「bulk DELETE 全部 + 重插」：bulk 语句绕过 persistence context，
            // 而共享事务下这些实体往往已经是托管状态，em.find 会拿回那个已被自己
            // DELETE 掉的 stale 实例，于是提交时变成 UPDATE 一行不存在的记录，
            // 抛 OptimisticLockException。旧代码每次新建 EntityManager，
            // 缓存是干净的，恰好把这个问题掩盖掉了。
            Map<String, WfTokenEntity> existing = new LinkedHashMap<>();
            em.createQuery("SELECT t FROM WfTokenEntity t WHERE t.instanceId = :iid",
                            WfTokenEntity.class)
                    .setParameter("iid", instance.getId())
                    .getResultList()
                    .forEach(te -> existing.put(te.getId(), te));

            for (Token t : instance.getActiveTokens().values()) {
                WfTokenEntity te = existing.remove(t.getId());
                if (te == null) {
                    te = new WfTokenEntity();
                    te.setId(t.getId());
                    te.setInstanceId(instance.getId());
                    te.setCurrentNodeId(t.getCurrentNodeId());
                    te.setArrival(t.getArrival());
                    te.setStatus(t.getStatus());
                    em.persist(te);
                } else {
                    // 查询结果本就是托管实体，直接改字段即可，无需 merge
                    te.setCurrentNodeId(t.getCurrentNodeId());
                    te.setArrival(t.getArrival());
                    te.setStatus(t.getStatus());
                }
            }
            // 剩余的是本次已不存在的 Token（已消耗）—— 走实体生命周期删除
            for (WfTokenEntity stale : existing.values()) {
                em.remove(stale);
            }
            return null;
        });
    }

    @Override
    public void saveBatch(List<ProcessInstance> instances) {
        if (instances == null || instances.isEmpty()) {
            return;
        }
        try {
            doSaveBatch(instances);
        } catch (RuntimeException ex) {
            throw JpaPersistence.asConflictIfOptimisticLock(ex, instances.get(0).getId());
        }
    }

    private void doSaveBatch(List<ProcessInstance> instances) {
        // 批量优化：单事务内批量插入，减少事务开销
        runInOrOpenTx(em -> {
            for (ProcessInstance instance : instances) {
                WfInstanceEntity entity = em.find(WfInstanceEntity.class, instance.getId());
                if (entity == null) {
                    entity = new WfInstanceEntity();
                    entity.setId(instance.getId());
                    entity.setRevision(FIRST_REVISION);
                }
                entity.setProcessKey(instance.getProcessKey());
                entity.setProcessVersion(instance.getProcessVersion());
                entity.setStatus(instance.getStatus());
                entity.setCreateTime(instance.getCreateTime());
                entity.setEndTime(instance.getEndTime() > 0 ? instance.getEndTime() : null);
                entity.setVariablesJson(JSON.toJSONString(instance.getVariables()));
                entity.setParentInstanceId(instance.getParentInstanceId());
                entity.setParentTokenId(instance.getParentTokenId());
                entity.setParentNodeId(instance.getParentNodeId());
                entity.setRootInstanceId(instance.getRootInstanceId());
                em.merge(entity);

                // Token 批量插入
                for (Token t : instance.getActiveTokens().values()) {
                    WfTokenEntity te = new WfTokenEntity();
                    te.setId(t.getId());
                    te.setInstanceId(instance.getId());
                    te.setCurrentNodeId(t.getCurrentNodeId());
                    te.setStatus(t.getStatus());
                    em.persist(te);
                }
            }
            return null;
        });
    }

    @Override
    public ProcessInstance findById(String instanceId) {
        return runInOrOpenTx(em -> {
            WfInstanceEntity e = em.find(WfInstanceEntity.class, instanceId);
            if (e == null) {
                throw new IllegalArgumentException("流程实例不存在: " + instanceId);
            }
            return rebuild(e, em);
        });
    }

    // ========== 列表查询 ==========
    //
    // 此前这四者全部落到 InstanceRepository 的 default 实现，一调用就抛
    // UnsupportedOperationException —— 即「历史查询 / 复杂查询」只在内存版可用，
    // 接上真实数据库即失效。补在这里，并靠下面的跨仓储一致性用例守住了。

    @Override
    public java.util.List<ProcessInstance> findByProcessKey(String processKey) {
        return runInOrOpenTx(em -> em.createQuery(
                        "SELECT e FROM WfInstanceEntity e WHERE e.processKey = :pk ORDER BY e.createTime",
                        WfInstanceEntity.class)
                .setParameter("pk", processKey)
                .getResultList().stream()
                .map(e -> rebuild(e, em))
                .collect(java.util.stream.Collectors.toList()));
    }

    @Override
    public java.util.List<ProcessInstance> findByStatus(com.workflow.enums.InstanceStatus status) {
        return runInOrOpenTx(em -> em.createQuery(
                        "SELECT e FROM WfInstanceEntity e WHERE e.status = :st ORDER BY e.createTime",
                        WfInstanceEntity.class)
                .setParameter("st", status)
                .getResultList().stream()
                .map(e -> rebuild(e, em))
                .collect(java.util.stream.Collectors.toList()));
    }

    @Override
    public java.util.List<ProcessInstance> findAll() {
        return runInOrOpenTx(em -> em.createQuery(
                        "SELECT e FROM WfInstanceEntity e ORDER BY e.createTime",
                        WfInstanceEntity.class)
                .getResultList().stream()
                .map(e -> rebuild(e, em))
                .collect(java.util.stream.Collectors.toList()));
    }

    @Override
    public java.util.List<ProcessInstance> findByProcessKeyAndVersion(String processKey, int version) {
        return runInOrOpenTx(em -> em.createQuery(
                        "SELECT e FROM WfInstanceEntity e WHERE e.processKey = :pk"
                                + " AND e.processVersion = :v ORDER BY e.createTime",
                        WfInstanceEntity.class)
                .setParameter("pk", processKey)
                .setParameter("v", version)
                .getResultList().stream()
                .map(e -> rebuild(e, em))
                .collect(java.util.stream.Collectors.toList()));
    }

    @Override
    public java.util.List<com.workflow.repository.ProcessStatusCount> countGroupByProcessAndStatus() {
        return countGroupByProcessAndStatus(null);
    }

    @Override
    public java.util.List<com.workflow.repository.ProcessStatusCount> countGroupByProcessAndStatus(String tenantId) {
        return runInOrOpenTx(em -> {
            String jpql = "SELECT e.processKey, e.status, COUNT(e) FROM WfInstanceEntity e";
            if (tenantId != null) {
                jpql += " WHERE e.tenantId = :tenantId";
            }
            jpql += " GROUP BY e.processKey, e.status";
            jakarta.persistence.TypedQuery<Object[]> query = em.createQuery(jpql, Object[].class);
            if (tenantId != null) {
                query.setParameter("tenantId", tenantId);
            }
            @SuppressWarnings("unchecked")
            java.util.List<Object[]> rows = query.getResultList();
            java.util.List<com.workflow.repository.ProcessStatusCount> out = new java.util.ArrayList<>();
            for (Object[] a : rows) {
                out.add(new com.workflow.repository.ProcessStatusCount(
                        (String) a[0],
                        (com.workflow.enums.InstanceStatus) a[1],
                        ((Number) a[2]).longValue()));
            }
            return out;
        });
    }

    private ProcessInstance rebuild(WfInstanceEntity e, EntityManager em) {
        // 1) 加载 Token 和 Task
        List<WfTokenEntity> tokens = em.createQuery(
                "SELECT t FROM WfTokenEntity t WHERE t.instanceId = :iid", WfTokenEntity.class)
                .setParameter("iid", e.getId()).getResultList();

        List<WfTaskEntity> tasks = em.createQuery(
                "SELECT t FROM WfTaskEntity t WHERE t.instanceId = :iid ORDER BY t.createTime",
                WfTaskEntity.class)
                .setParameter("iid", e.getId()).getResultList();

        // 2) 重建 ProcessInstance(反射写 final 字段)
        ProcessInstance instance = new ProcessInstance(e.getProcessKey(), e.getProcessVersion());
        setFinal(instance, "id", e.getId());
        setFinal(instance, "createTime", e.getCreateTime());
        setFinal(instance, "endTime", e.getEndTime() != null ? e.getEndTime() : 0L);
        setFinal(instance, "parentInstanceId", e.getParentInstanceId());
        setFinal(instance, "parentTokenId", e.getParentTokenId());
        setFinal(instance, "parentNodeId", e.getParentNodeId());
        // 流程树根必须原样读回：丢了会让父子各持一把锁，ABBA 防护失效。
        // 该行为由 InstanceRootPersistenceTest 守着，并经变异校验确认抽掉即红。
        instance.assignRootInstanceId(e.getRootInstanceId());
        // 乐观锁版本必须读回：重试路径要靠它判断"本次写入基于哪一版"
        instance.setRevision(e.getRevision());
        // status 是非 final 字段,用反射或直接赋值都行
        try {
            java.lang.reflect.Field statusField = ProcessInstance.class.getDeclaredField("status");
            statusField.setAccessible(true);
            statusField.set(instance, e.getStatus());
        } catch (Exception ex) {
            throw new RuntimeException("设置 status 失败", ex);
        }

        // 3) Token 列表
        Map<String, Token> tokenMap = new LinkedHashMap<>();
        for (WfTokenEntity te : tokens) {
            Token token = new Token(e.getId(), te.getCurrentNodeId());
            setFinal(token, "id", te.getId());
            token.setStatus(te.getStatus());
            token.setArrival(te.getArrival());
            tokenMap.put(token.getId(), token);
        }
        // 覆盖 activeTokens(用反射)
        setFinal(instance, "activeTokens", tokenMap);

        // 4) Task 列表
        List<TaskInstance> taskList = new ArrayList<>();
        for (WfTaskEntity te : tasks) {
            taskList.add(JpaTaskRepository.rebuildFromEntity(te));
        }
        setFinal(instance, "tasks", taskList);

        // 5) 变量
        Map<String, Object> variables = new LinkedHashMap<>();
        if (e.getVariablesJson() != null && !e.getVariablesJson().isEmpty()) {
            Map<String, Object> parsed = JSON.parseObject(e.getVariablesJson(),
                    new com.alibaba.fastjson2.TypeReference<Map<String, Object>>() {});
            if (parsed != null) variables.putAll(parsed);
        }
        setFinal(instance, "variables", variables);

        return instance;
    }

    @Override
    public void delete(String instanceId) {
        runInOrOpenTx(em -> {
            WfInstanceEntity e = em.find(WfInstanceEntity.class, instanceId);
            if (e != null) {
                em.createQuery("DELETE FROM WfTokenEntity t WHERE t.instanceId = :iid")
                        .setParameter("iid", instanceId).executeUpdate();
                em.createQuery("DELETE FROM WfTaskEntity t WHERE t.instanceId = :iid")
                        .setParameter("iid", instanceId).executeUpdate();
                em.remove(e);
            }
            return null;
        });
    }

    /** 反射写 final 字段 - JDK 17 setAccessible + set 可用 */
    private static void setFinal(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception ex) {
            throw new RuntimeException("反射设置字段失败: " + fieldName, ex);
        }
    }

    /** 插入行的初始版本号（与内存仓储 save 后的版本号对齐） */
    private static final long FIRST_REVISION = 1L;

    /** 复用 ThreadLocal EM,否则开新事务(JpaTaskRepository 同款) */
    private <R> R runInOrOpenTx(Function<EntityManager, R> action) {
        EntityManager bound = jpa.currentEmOrNull();
        if (bound != null) {
            return action.apply(bound);
        }
        return jpa.inTransaction(action);
    }
}