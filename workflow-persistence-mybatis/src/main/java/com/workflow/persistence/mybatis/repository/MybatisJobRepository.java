package com.workflow.persistence.mybatis.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.workflow.enums.JobState;
import com.workflow.persistence.mybatis.MybatisPersistence;
import com.workflow.persistence.mybatis.entity.WfJobEntity;
import com.workflow.persistence.mybatis.mapper.WfJobMapper;
import com.workflow.repository.JobRepository;
import com.workflow.runtime.Job;

import java.util.List;
import java.util.Objects;

/**
 * MyBatis-Plus 版作业仓储。
 *
 * <p>领取走带条件的 UPDATE（{@code WfJobMapper.claim}），受影响行数 &gt; 0 才算成功 ——
 * 与 JPA 版语义一致，保证多节点下同一作业只被一个节点执行。
 */
public class MybatisJobRepository implements JobRepository {

    private final MybatisPersistence mb;

    public MybatisJobRepository(MybatisPersistence mb) {
        this.mb = Objects.requireNonNull(mb);
    }

    @Override
    public void save(Job job) {
        Objects.requireNonNull(job);
        mb.inSession(session -> {
            WfJobMapper mapper = session.getMapper(WfJobMapper.class);
            WfJobEntity e = mapper.selectById(job.getId());
            boolean isNew = (e == null);
            if (isNew) {
                e = new WfJobEntity();
                e.setId(job.getId());
            }
            fill(e, job);
            if (isNew) {
                mapper.insert(e);
            } else {
                mapper.updateById(e);
            }
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
        return mb.inSession(session -> {
            WfJobEntity e = session.getMapper(WfJobMapper.class).selectById(id);
            return e == null ? null : toDomain(e);
        });
    }

    @Override
    public List<Job> findDue(long now, int limit) {
        return mb.inSession(session -> session.getMapper(WfJobMapper.class)
                .findDue(now, limit).stream().map(MybatisJobRepository::toDomain).toList());
    }

    @Override
    public boolean claim(String jobId, String owner, long now, long leaseUntil) {
        return mb.inSession(session ->
                session.getMapper(WfJobMapper.class).claim(jobId, owner, now, leaseUntil) > 0);
    }

    @Override
    public void delete(String jobId) {
        mb.inSession(session -> {
            session.getMapper(WfJobMapper.class).deleteById(jobId);
            return null;
        });
    }

    @Override
    public void markRetry(String jobId, String error, long nextDueAt) {
        mb.inSession(session -> {
            session.getMapper(WfJobMapper.class)
                    .markRetry(jobId, error, nextDueAt, System.currentTimeMillis());
            return null;
        });
    }

    @Override
    public void markFailed(String jobId, String error) {
        mb.inSession(session -> {
            session.getMapper(WfJobMapper.class).markFailed(jobId, error, System.currentTimeMillis());
            return null;
        });
    }

    @Override
    public List<Job> findByState(JobState state, int limit) {
        return mb.inSession(session -> session.getMapper(WfJobMapper.class)
                .selectList(new QueryWrapper<WfJobEntity>()
                        .eq("state", state.name())
                        .orderByAsc("due_at")
                        .last("LIMIT " + limit))
                .stream().map(MybatisJobRepository::toDomain).toList());
    }

    @Override
    public List<Job> findByInstanceId(String instanceId) {
        return mb.inSession(session -> session.getMapper(WfJobMapper.class)
                .findByInstanceId(instanceId).stream().map(MybatisJobRepository::toDomain).toList());
    }

    @Override
    public int deleteByInstanceId(String instanceId) {
        return mb.inSession(session ->
                session.getMapper(WfJobMapper.class).deleteByInstanceId(instanceId));
    }

    @Override
    public long countByState(JobState state) {
        return mb.inSession(session ->
                session.getMapper(WfJobMapper.class).countByState(state.name()));
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
}
