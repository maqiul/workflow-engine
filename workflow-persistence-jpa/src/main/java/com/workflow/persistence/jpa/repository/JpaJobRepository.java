package com.workflow.persistence.jpa.repository;

import com.workflow.enums.JobState;
import com.workflow.enums.JobType;
import com.workflow.persistence.jpa.JpaPersistence;
import com.workflow.persistence.jpa.entity.WfJobEntity;
import com.workflow.repository.JobRepository;
import com.workflow.runtime.Job;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Objects;

/**
 * JPA 版作业仓储。
 *
 * <p>领取用<b>带条件的 UPDATE</b> 实现原子性：只有当作业仍可领取时条件才命中，
 * 受影响行数 &gt; 0 才算领取成功。两个节点同时领同一作业，只有一个拿到 1 行。
 */
public class JpaJobRepository implements JobRepository {

    private final JpaPersistence jpa;

    public JpaJobRepository(JpaPersistence jpa) {
        this.jpa = Objects.requireNonNull(jpa);
    }

    @Override
    public void save(Job job) {
        Objects.requireNonNull(job);
        jpa.inTransaction(em -> {
            WfJobEntity e = em.find(WfJobEntity.class, job.getId());
            if (e == null) {
                e = new WfJobEntity();
                e.setId(job.getId());
            }
            fill(e, job);
            em.merge(e);
            return null;
        });
    }

    private static void fill(WfJobEntity e, Job job) {
        e.setType(job.getType());
        e.setInstanceId(job.getInstanceId());
        e.setPayload(job.getPayload());
        e.setDueAt(job.getDueAt());
        e.setState(job.getState());
        e.setRetries(job.getRetries());
        e.setMaxRetries(job.getMaxRetries());
        e.setLastError(job.getLastError());
        e.setLockOwner(job.getLockOwner());
        e.setLockExpiresAt(job.getLockExpiresAt());
        e.setCreateTime(job.getCreateTime());
        e.setUpdateTime(job.getUpdateTime());
    }

    @Override
    public Job findById(String id) {
        return jpa.inTransaction(em -> {
            WfJobEntity e = em.find(WfJobEntity.class, id);
            return e == null ? null : toDomain(e);
        });
    }

    @Override
    public List<Job> findDue(long now, int limit) {
        return jpa.inTransaction(em -> em.createQuery(
                        "SELECT e FROM WfJobEntity e WHERE e.state = :st AND e.dueAt <= :now"
                                + " AND (e.lockOwner IS NULL OR e.lockExpiresAt <= :now)"
                                + " ORDER BY e.dueAt",
                        WfJobEntity.class)
                .setParameter("st", JobState.READY)
                .setParameter("now", now)
                .setMaxResults(limit)
                .getResultList().stream().map(JpaJobRepository::toDomain).toList());
    }

    @Override
    public boolean claim(String jobId, String owner, long now, long leaseUntil) {
        return jpa.inTransaction(em -> {
            int rows = em.createQuery(
                            "UPDATE WfJobEntity e SET e.lockOwner = :owner,"
                                    + " e.lockExpiresAt = :lease, e.updateTime = :now"
                                    + " WHERE e.id = :id AND e.state = :st AND e.dueAt <= :now"
                                    + " AND (e.lockOwner IS NULL OR e.lockExpiresAt <= :now)")
                    .setParameter("owner", owner)
                    .setParameter("lease", leaseUntil)
                    .setParameter("now", now)
                    .setParameter("id", jobId)
                    .setParameter("st", JobState.READY)
                    .executeUpdate();
            return rows > 0;
        });
    }

    @Override
    public void delete(String jobId) {
        jpa.inTransaction(em -> {
            em.createQuery("DELETE FROM WfJobEntity e WHERE e.id = :id")
                    .setParameter("id", jobId).executeUpdate();
            return null;
        });
    }

    @Override
    public void markRetry(String jobId, String error, long nextDueAt) {
        jpa.inTransaction(em -> {
            em.createQuery("UPDATE WfJobEntity e SET e.state = :st, e.retries = e.retries + 1,"
                            + " e.lastError = :err, e.lockOwner = NULL, e.lockExpiresAt = 0,"
                            + " e.dueAt = :due, e.updateTime = :now WHERE e.id = :id")
                    .setParameter("st", JobState.READY)
                    .setParameter("err", error)
                    .setParameter("due", nextDueAt)
                    .setParameter("now", System.currentTimeMillis())
                    .setParameter("id", jobId)
                    .executeUpdate();
            return null;
        });
    }

    @Override
    public void markFailed(String jobId, String error) {
        jpa.inTransaction(em -> {
            em.createQuery("UPDATE WfJobEntity e SET e.state = :st, e.retries = e.retries + 1,"
                            + " e.lastError = :err, e.lockOwner = NULL, e.lockExpiresAt = 0,"
                            + " e.updateTime = :now WHERE e.id = :id")
                    .setParameter("st", JobState.FAILED)
                    .setParameter("err", error)
                    .setParameter("now", System.currentTimeMillis())
                    .setParameter("id", jobId)
                    .executeUpdate();
            return null;
        });
    }

    @Override
    public List<Job> findByState(JobState state, int limit) {
        return jpa.inTransaction(em -> em.createQuery(
                        "SELECT e FROM WfJobEntity e WHERE e.state = :st ORDER BY e.dueAt",
                        WfJobEntity.class)
                .setParameter("st", state)
                .setMaxResults(limit)
                .getResultList().stream().map(JpaJobRepository::toDomain).toList());
    }

    @Override
    public List<Job> findByInstanceId(String instanceId) {
        return jpa.inTransaction(em -> em.createQuery(
                        "SELECT e FROM WfJobEntity e WHERE e.instanceId = :iid ORDER BY e.dueAt",
                        WfJobEntity.class)
                .setParameter("iid", instanceId)
                .getResultList().stream().map(JpaJobRepository::toDomain).toList());
    }

    @Override
    public int deleteByInstanceId(String instanceId) {
        return jpa.inTransaction(em -> em.createQuery(
                        "DELETE FROM WfJobEntity e WHERE e.instanceId = :iid")
                .setParameter("iid", instanceId).executeUpdate());
    }

    @Override
    public long countByState(JobState state) {
        return jpa.inTransaction(em -> (Long) em.createQuery(
                        "SELECT COUNT(e) FROM WfJobEntity e WHERE e.state = :st")
                .setParameter("st", state).getSingleResult());
    }

    private static Job toDomain(WfJobEntity e) {
        Job job = new Job(e.getId(), e.getType(), e.getInstanceId(), e.getPayload(),
                e.getDueAt(), e.getCreateTime());
        job.setState(e.getState());
        job.setRetries(e.getRetries());
        job.setMaxRetries(e.getMaxRetries());
        job.setLastError(e.getLastError());
        job.setLockOwner(e.getLockOwner());
        job.setLockExpiresAt(e.getLockExpiresAt());
        job.setUpdateTime(e.getUpdateTime());
        return job;
    }

    /** 供 {@link JpaJobRepository} 之外的地方按类型过滤（观测用）。 */
    public List<Job> findByType(JobType type, int limit) {
        return jpa.inTransaction(em -> em.createQuery(
                        "SELECT e FROM WfJobEntity e WHERE e.type = :t ORDER BY e.dueAt",
                        WfJobEntity.class)
                .setParameter("t", type)
                .setMaxResults(limit)
                .getResultList().stream().map(JpaJobRepository::toDomain).toList());
    }
}
