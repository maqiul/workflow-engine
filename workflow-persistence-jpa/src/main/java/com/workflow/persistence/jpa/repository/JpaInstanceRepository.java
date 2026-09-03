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
        runInOrOpenTx(em -> {
            WfInstanceEntity entity = em.find(WfInstanceEntity.class, instance.getId());
            if (entity == null) {
                entity = new WfInstanceEntity();
                entity.setId(instance.getId());
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
            em.merge(entity);

            // Token 全量同步:delete-all + persist
            em.createQuery("DELETE FROM WfTokenEntity t WHERE t.instanceId = :iid")
                    .setParameter("iid", instance.getId())
                    .executeUpdate();
            for (Token t : instance.getActiveTokens().values()) {
                WfTokenEntity te = em.find(WfTokenEntity.class, t.getId());
                if (te == null) {
                    te = new WfTokenEntity();
                    te.setId(t.getId());
                    te.setInstanceId(instance.getId());
                    te.setCurrentNodeId(t.getCurrentNodeId());
                    te.setStatus(t.getStatus());
                    em.persist(te);
                } else {
                    te.setInstanceId(instance.getId());
                    te.setCurrentNodeId(t.getCurrentNodeId());
                    te.setStatus(t.getStatus());
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

    /** 复用 ThreadLocal EM,否则开新事务(JpaTaskRepository 同款) */
    private <R> R runInOrOpenTx(Function<EntityManager, R> action) {
        EntityManager bound = jpa.currentEmOrNull();
        if (bound != null) {
            return action.apply(bound);
        }
        return jpa.inTransaction(action);
    }
}