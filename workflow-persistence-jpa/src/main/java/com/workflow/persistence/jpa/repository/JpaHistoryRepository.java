package com.workflow.persistence.jpa.repository;

import com.workflow.persistence.jpa.JpaPersistence;
import com.workflow.persistence.jpa.entity.WfHistActivityEntity;
import com.workflow.persistence.jpa.entity.WfHistTaskEntity;
import com.workflow.repository.HistoryRepository;
import com.workflow.runtime.HistoricActivityInstance;
import com.workflow.runtime.HistoricTaskInstance;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.function.Function;

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
            boolean isNew = (e == null);
            if (isNew) {
                e = new WfHistActivityEntity();
                e.setId(activity.getId());
                e.setSeq(nextSeq(em, "hist_activity"));   // 新行：DB 序列，跨重启/节点单调
            }
            e.setInstanceId(activity.getInstanceId());
            e.setProcessKey(activity.getProcessKey());
            e.setProcessVersion(activity.getProcessVersion());
            e.setActivityId(activity.getActivityId());
            e.setActivityType(activity.getActivityType());
            e.setTokenId(activity.getTokenId());
            e.setTaskId(activity.getTaskId());
            e.setStartTime(activity.getStartTime());
            // update（如 UserTask 活动 close）不重设 seq，保留插入时的值，避免定序键漂移
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
            Function<EntityManager, TypedQuery<WfHistActivityEntity>> q) {
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

    // ========== 历史任务 ==========

    @Override
    public void saveTask(HistoricTaskInstance t) {
        Objects.requireNonNull(t);
        jpa.inTransaction(em -> {
            WfHistTaskEntity e = em.find(WfHistTaskEntity.class, t.getTaskId());
            boolean isNew = (e == null);
            if (isNew) {
                e = new WfHistTaskEntity();
                e.setTaskId(t.getTaskId());
                e.setSeq(nextSeq(em, "hist_task"));
            }
            e.setInstanceId(t.getInstanceId());
            e.setProcessKey(t.getProcessKey());
            e.setProcessVersion(t.getProcessVersion());
            e.setNodeId(t.getNodeId());
            e.setCandidateUsers(toCsv(t.getCandidateUsers()));
            e.setCompletedBy(toCsv(t.getCompletedBy()));
            e.setStartTime(t.getStartTime());
            e.setEndTime(t.getEndTime());
            // seq 仅新行分配；update 保留原值
            e.setEndReason(t.getEndReason());
            em.merge(e);
            return null;
        });
    }

    @Override
    public List<HistoricTaskInstance> findTasksByInstanceId(String instanceId) {
        return jpa.inTransaction(em -> em.createQuery(
                        "SELECT e FROM WfHistTaskEntity e WHERE e.instanceId = :iid"
                                + " ORDER BY e.endTime, e.seq", WfHistTaskEntity.class)
                .setParameter("iid", instanceId)
                .getResultList().stream().map(JpaHistoryRepository::toTaskDomain).toList());
    }

    @Override
    public List<HistoricTaskInstance> findTasksInvolving(String userId) {
        // 逗号包裹存储正是为了这一句：'%,u1,%' 不会把 u1 误配到 u11 上
        String pattern = "%," + userId + ",%";
        return jpa.inTransaction(em -> em.createQuery(
                        "SELECT e FROM WfHistTaskEntity e WHERE e.candidateUsers LIKE :pat"
                                + " OR e.completedBy LIKE :pat ORDER BY e.endTime, e.seq",
                        WfHistTaskEntity.class)
                .setParameter("pat", pattern)
                .getResultList().stream().map(JpaHistoryRepository::toTaskDomain).toList());
    }

    @Override
    public OptionalDouble averageClosedTaskDuration(String processKey, String nodeId) {
        Number avg = jpa.inTransaction(em -> (Number) em.createQuery(
                        "SELECT AVG(e.endTime - e.startTime) FROM WfHistTaskEntity e"
                                + " WHERE e.processKey = :pk AND e.nodeId = :aid")
                .setParameter("pk", processKey)
                .setParameter("aid", nodeId)
                .getSingleResult());
        return avg == null ? OptionalDouble.empty() : OptionalDouble.of(avg.doubleValue());
    }

    @Override
    public int deleteTasksBefore(long cutoffMillis) {
        return jpa.inTransaction(em -> em.createQuery(
                        "DELETE FROM WfHistTaskEntity e WHERE e.endTime < :cutoff")
                .setParameter("cutoff", cutoffMillis)
                .executeUpdate());
    }

    @Override
    public int deleteInstance(String instanceId) {
        return jpa.inTransaction(em -> {
            int activities = em.createQuery(
                            "DELETE FROM WfHistActivityEntity e WHERE e.instanceId = :iid")
                    .setParameter("iid", instanceId)
                    .executeUpdate();
            int tasks = em.createQuery(
                            "DELETE FROM WfHistTaskEntity e WHERE e.instanceId = :iid")
                    .setParameter("iid", instanceId)
                    .executeUpdate();
            return activities + tasks;
        });
    }

    /** 人员列表以 {@code ,u1,u2,} 形式落库，便于 LIKE 精确匹配。 */
    private static String toCsv(Collection<String> users) {
        if (users == null || users.isEmpty()) {
            return ",";
        }
        return "," + String.join(",", users) + ",";
    }

    private static List<String> fromCsv(String csv) {
        if (csv == null || csv.length() <= 1) {
            return List.of();
        }
        String body = csv;
        if (body.startsWith(",")) body = body.substring(1);
        if (body.endsWith(",")) body = body.substring(0, body.length() - 1);
        if (body.isEmpty()) return List.of();
        return Arrays.stream(body.split(",")).filter(s -> !s.isEmpty()).toList();
    }

    private static HistoricTaskInstance toTaskDomain(WfHistTaskEntity e) {
        return HistoricTaskInstance.reconstruct(
                e.getTaskId(), e.getInstanceId(), e.getProcessKey(), e.getProcessVersion(),
                e.getNodeId(), fromCsv(e.getCandidateUsers()), fromCsv(e.getCompletedBy()),
                e.getStartTime(), e.getEndTime(), e.getEndReason(), e.getSeq());
    }

    /** 从 wf_sequence 取全局单调序号；须在同一事务内调用（与业务写入原子）。 */
    private static long nextSeq(EntityManager em, String name) {
        em.createNativeQuery("UPDATE wf_sequence SET next_val = next_val + 1 WHERE name = ?1")
                .setParameter(1, name).executeUpdate();
        Number v = (Number) em.createNativeQuery("SELECT next_val FROM wf_sequence WHERE name = ?1")
                .setParameter(1, name).getSingleResult();
        return v.longValue();
    }
}
