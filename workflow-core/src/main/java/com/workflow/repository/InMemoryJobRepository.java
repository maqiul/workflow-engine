package com.workflow.repository;

import com.workflow.enums.JobState;
import com.workflow.runtime.Job;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版作业仓储 —— 供单进程/测试使用。
 *
 * <p>{@link #claim} 用 {@code compute} 保证「判定 + 写入」原子，与真库的行锁语义对齐：
 * 两个线程同时领同一作业，只有一个拿到锁，另一个拿到 false。
 */
public class InMemoryJobRepository implements JobRepository {

    private final Map<String, Job> store = new ConcurrentHashMap<>();

    @Override
    public void save(Job job) {
        store.put(job.getId(), job);
    }

    @Override
    public Job findById(String id) {
        return store.get(id);
    }

    @Override
    public List<Job> findDue(long now, int limit) {
        List<Job> due = new ArrayList<>();
        for (Job j : store.values()) {
            if (j.isClaimable(now)) {
                due.add(j);
            }
        }
        due.sort(Comparator.comparingLong(Job::getDueAt));
        return due.size() > limit ? due.subList(0, limit) : due;
    }

    @Override
    public boolean claim(String jobId, String owner, long now, long leaseUntil) {
        // compute 在 ConcurrentHashMap 上对同一 key 串行 —— 判定与写入不可分割
        boolean[] claimed = new boolean[1];
        store.compute(jobId, (k, existing) -> {
            if (existing == null || !existing.isClaimable(now)) {
                return existing;
            }
            existing.setLockOwner(owner);
            existing.setLockExpiresAt(leaseUntil);
            existing.setUpdateTime(now);
            claimed[0] = true;
            return existing;
        });
        return claimed[0];
    }

    @Override
    public void delete(String jobId) {
        store.remove(jobId);
    }

    @Override
    public void markRetry(String jobId, String error, long nextDueAt) {
        store.computeIfPresent(jobId, (k, j) -> {
            j.setState(JobState.READY);
            j.setRetries(j.getRetries() + 1);
            j.setLastError(error);
            j.setLockOwner(null);
            j.setLockExpiresAt(0L);
            j.setUpdateTime(System.currentTimeMillis());
            // 重排期：把到期时刻推到退避之后
            j.reschedule(nextDueAt);
            return j;
        });
    }

    @Override
    public void markFailed(String jobId, String error) {
        store.computeIfPresent(jobId, (k, j) -> {
            j.setState(JobState.FAILED);
            j.setRetries(j.getRetries() + 1);
            j.setLastError(error);
            j.setLockOwner(null);
            j.setLockExpiresAt(0L);
            j.setUpdateTime(System.currentTimeMillis());
            return j;
        });
    }

    @Override
    public List<Job> findByState(JobState state, int limit) {
        List<Job> hits = new ArrayList<>();
        for (Job j : store.values()) {
            if (j.getState() == state) {
                hits.add(j);
            }
        }
        hits.sort(Comparator.comparingLong(Job::getDueAt));
        return hits.size() > limit ? hits.subList(0, limit) : hits;
    }

    @Override
    public List<Job> findByInstanceId(String instanceId) {
        List<Job> hits = new ArrayList<>();
        for (Job j : store.values()) {
            if (instanceId != null && instanceId.equals(j.getInstanceId())) {
                hits.add(j);
            }
        }
        return hits;
    }

    @Override
    public int deleteByInstanceId(String instanceId) {
        int removed = 0;
        for (String id : new ArrayList<>(store.keySet())) {
            Job j = store.get(id);
            if (j != null && instanceId != null && instanceId.equals(j.getInstanceId())
                    && store.remove(id) != null) {
                removed++;
            }
        }
        return removed;
    }

    @Override
    public long countByState(JobState state) {
        return store.values().stream().filter(j -> j.getState() == state).count();
    }
}
