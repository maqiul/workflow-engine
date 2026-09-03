package com.workflow.persistence.jpa.repository;

import com.workflow.persistence.jpa.JpaPersistence;
import com.workflow.persistence.jpa.entity.WfHistActivityEntity;
import com.workflow.repository.HistoryRepository;
import com.workflow.runtime.HistoricActivityInstance;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * JPA 版历史活动仓储。
 *
 * <p>与其余 JPA 仓储共用 {@link JpaPersistence#inTransaction}，因此自动继承
 * 「一个引擎动作一个事务」的语义 —— 历史写入与业务写入同生同灭，
 * 业务回滚不会留下"发生过但从没发生"的幽灵活动。
 *
 * <p>注意这里<b>不</b>复制各仓储里那份 {@code runInOrOpenTx}：它判断的
 * {@code jpa.currentEmOrNull()} 属于旧的 ThreadLocal 绑定机制，现在没人调用，
 * 恒为 null，实际生效的分支只有 {@code inTransaction} 里的事务感知逻辑。
 * 复用死代码只会让第四份副本继续流传。
 */
public class JpaHistoryRepository implements HistoryRepository {

    private final JpaPersistence jpa;

    public JpaHistoryRepository(JpaPersistence jpa) {
        this.jpa = Objects.requireNonNull(jpa);
    }

    @Override
    public void save(HistoricActivityInstance activity) {
        Objects.requireNonNull(activity);
        jpa.inTransaction(em -> {
            WfHistActivityEntity e = em.find(WfHistActivityEntity.class, activity.getId());
            if (e == null) {
                e = new WfHistActivityEntity();
                e.setId(activity.getId());
            }
            e.setInstanceId(activity.getInstanceId());
            e.setProcessKey(activity.getProcessKey());
            e.setProcessVersion(activity.getProcessVersion());
            e.setActivityId(activity.getActivityId());
            e.setActivityType(activity.getActivityType());
            e.setTokenId(activity.getTokenId());
            e.setTaskId(activity.getTaskId());
            e.setStartTime(activity.getStartTime());
            e.setSeq(activity.getSeq());
            e.setEndTime(activity.getEndTime());
            e.setPerformer(activity.getPerformer());
            em.merge(e);
            return null;
        });
    }

    @Override
    public HistoricActivityInstance findOpen(String instanceId, String tokenId, String activityId) {
        return jpa.inTransaction(em -> em.createQuery(
                        "SELECT e FROM WfHistActivityEntity e WHERE e.instanceId = :iid"
                                + " AND e.tokenId = :tid AND e.activityId = :aid AND e.endTime IS NULL",
                        WfHistActivityEntity.class)
                .setParameter("iid", instanceId)
                .setParameter("tid", tokenId)
                .setParameter("aid", activityId)
                .setMaxResults(1)
                .getResultList().stream()
                .findFirst()
                .map(JpaHistoryRepository::toDomain)
                .orElse(null));
    }

    @Override
    public List<HistoricActivityInstance> findByInstanceId(String instanceId) {
        return query(em -> em.createQuery(
                        "SELECT e FROM WfHistActivityEntity e WHERE e.instanceId = :iid"
                                + " ORDER BY e.startTime, e.seq", WfHistActivityEntity.class)
                .setParameter("iid", instanceId));
    }

    @Override
    public List<HistoricActivityInstance> findByActivity(String processKey, String activityId) {
        return query(em -> em.createQuery(
                        "SELECT e FROM WfHistActivityEntity e WHERE e.processKey = :pk"
                                + " AND e.activityId = :aid ORDER BY e.startTime, e.seq",
                        WfHistActivityEntity.class)
                .setParameter("pk", processKey)
                .setParameter("aid", activityId));
    }

    /**
     * 平均值交给数据库算，不把整批活动捞进内存。
     *
     * <p>未闭合活动（{@code endTime IS NULL}）天然被 WHERE 排除 ——
     * 它们的时间随查询时刻漂移，混进来会让同一报表每次刷新结果不同。
     */
    @Override
    public OptionalDouble averageClosedDuration(String processKey, String activityId) {
        Number avg = jpa.inTransaction(em -> (Number) em.createQuery(
                        "SELECT AVG(e.endTime - e.startTime) FROM WfHistActivityEntity e"
                                + " WHERE e.processKey = :pk AND e.activityId = :aid"
                                + " AND e.endTime IS NOT NULL")
                .setParameter("pk", processKey)
                .setParameter("aid", activityId)
                .getSingleResult());
        // 无样本时 SQL 返回 NULL，getSingleResult 给出 null
        return avg == null ? OptionalDouble.empty() : OptionalDouble.of(avg.doubleValue());
    }

    @Override
    public int deleteClosedBefore(long cutoffMillis) {
        return jpa.inTransaction(em -> em.createQuery(
                        "DELETE FROM WfHistActivityEntity e"
                                + " WHERE e.endTime IS NOT NULL AND e.startTime < :cutoff")
                .setParameter("cutoff", cutoffMillis)
                .executeUpdate());
    }

    private List<HistoricActivityInstance> query(
            java.util.function.Function<EntityManager,
                    jakarta.persistence.TypedQuery<WfHistActivityEntity>> q) {
        return jpa.inTransaction(em -> q.apply(em).getResultList().stream()
                .map(JpaHistoryRepository::toDomain)
                .toList());
    }

    /** 查询结果为托管实体，这里一律转成独立 domain 对象，仓库内部状态不外泄。 */
    private static HistoricActivityInstance toDomain(WfHistActivityEntity e) {
        return HistoricActivityInstance.reconstruct(
                e.getId(), e.getInstanceId(), e.getProcessKey(), e.getProcessVersion(),
                e.getActivityId(), e.getActivityType(), e.getTokenId(), e.getTaskId(),
                e.getStartTime(), e.getEndTime(), e.getPerformer(), e.getSeq());
    }

    /** 便于按实例清理（测试隔离用）。 */
    public int deleteByInstanceId(String instanceId) {
        return jpa.inTransaction(em -> em.createQuery(
                        "DELETE FROM WfHistActivityEntity e WHERE e.instanceId = :iid")
                .setParameter("iid", instanceId)
                .executeUpdate());
    }
}
