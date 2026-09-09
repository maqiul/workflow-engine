package com.workflow.persistence.jpa.repository;

import com.workflow.enums.AuditEventType;
import com.workflow.persistence.jpa.JpaPersistence;
import com.workflow.persistence.jpa.entity.WfAuditLogEntity;
import com.workflow.repository.AuditLogRepository;
import com.workflow.runtime.AuditLog;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * JPA 版审计日志仓储
 */
public class JpaAuditLogRepository implements AuditLogRepository {

    private final JpaPersistence jpa;

    public JpaAuditLogRepository(JpaPersistence jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(AuditLog log) {
        runInOrOpenTx(em -> {
            WfAuditLogEntity entity = new WfAuditLogEntity();
            entity.setId(log.getId());
            entity.setInstanceId(log.getInstanceId());
            entity.setTaskId(log.getTaskId());
            entity.setEventType(log.getEventType());
            entity.setOperator(log.getOperator());
            entity.setTimestamp(log.getTimestamp().toEpochMilli());
            entity.setDetail(log.getDetail());
            em.persist(entity);
            em.flush();
            return null;
        });
    }

    @Override
    public List<AuditLog> findByInstanceId(String instanceId) {
        return runInOrOpenTx(em -> {
            List<WfAuditLogEntity> list = em.createQuery(
                    "SELECT a FROM WfAuditLogEntity a WHERE a.instanceId = :iid ORDER BY a.timestamp ASC",
                    WfAuditLogEntity.class)
                    .setParameter("iid", instanceId)
                    .getResultList();
            return list.stream().map(JpaAuditLogRepository::toDomain).collect(Collectors.toList());
        });
    }

    @Override
    public List<AuditLog> findByTaskId(String taskId) {
        return runInOrOpenTx(em -> {
            List<WfAuditLogEntity> list = em.createQuery(
                    "SELECT a FROM WfAuditLogEntity a WHERE a.taskId = :tid ORDER BY a.timestamp ASC",
                    WfAuditLogEntity.class)
                    .setParameter("tid", taskId)
                    .getResultList();
            return list.stream().map(JpaAuditLogRepository::toDomain).collect(Collectors.toList());
        });
    }

    @Override
    public List<AuditLog> findByTimeRange(Instant from, Instant to) {
        return runInOrOpenTx(em -> {
            List<WfAuditLogEntity> list = em.createQuery(
                    "SELECT a FROM WfAuditLogEntity a WHERE a.timestamp >= :from AND a.timestamp <= :to ORDER BY a.timestamp ASC",
                    WfAuditLogEntity.class)
                    .setParameter("from", from.toEpochMilli())
                    .setParameter("to", to.toEpochMilli())
                    .getResultList();
            return list.stream().map(JpaAuditLogRepository::toDomain).collect(Collectors.toList());
        });
    }

    @Override
    public void clear() {
        runInOrOpenTx(em -> {
            em.createQuery("DELETE FROM WfAuditLogEntity").executeUpdate();
            return null;
        });
    }

    @Override
    public java.util.List<com.workflow.repository.EventTypeCount> countGroupByEventTypePrefix(String prefix) {
        return runInOrOpenTx(em -> {
            // eventType 是枚举字段，不能直接 LIKE 字符串，需要列出匹配的枚举值
            java.util.List<AuditEventType> matchedTypes = new java.util.ArrayList<>();
            for (AuditEventType t : AuditEventType.values()) {
                if (t.name().startsWith(prefix)) {
                    matchedTypes.add(t);
                }
            }
            if (matchedTypes.isEmpty()) {
                return java.util.List.of();
            }
            @SuppressWarnings("unchecked")
            java.util.List<Object[]> rows = em.createQuery(
                    "SELECT a.eventType, COUNT(a) FROM WfAuditLogEntity a"
                            + " WHERE a.eventType IN :types"
                            + " GROUP BY a.eventType")
                    .setParameter("types", matchedTypes)
                    .getResultList();
            java.util.List<com.workflow.repository.EventTypeCount> out = new java.util.ArrayList<>();
            for (Object[] row : rows) {
                out.add(new com.workflow.repository.EventTypeCount(
                        (AuditEventType) row[0],
                        ((Number) row[1]).longValue()));
            }
            return out;
        });
    }

    private static AuditLog toDomain(WfAuditLogEntity e) {
        AuditLog log = new AuditLog(
                e.getInstanceId(),
                e.getTaskId(),
                e.getEventType(),
                e.getOperator(),
                e.getDetail()
        );
        // 反射设置 id 和 timestamp
        try {
            java.lang.reflect.Field idField = AuditLog.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(log, e.getId());

            java.lang.reflect.Field tsField = AuditLog.class.getDeclaredField("timestamp");
            tsField.setAccessible(true);
            tsField.set(log, Instant.ofEpochMilli(e.getTimestamp()));
        } catch (Exception ex) {
            throw new RuntimeException("重建 AuditLog 失败: " + ex.getMessage(), ex);
        }
        return log;
    }

    private <R> R runInOrOpenTx(Function<EntityManager, R> action) {
        EntityManager bound = jpa.currentEmOrNull();
        if (bound != null) {
            return action.apply(bound);
        }
        return jpa.inTransaction(action);
    }
}
