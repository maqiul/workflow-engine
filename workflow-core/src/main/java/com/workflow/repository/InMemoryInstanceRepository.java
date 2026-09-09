package com.workflow.repository;

import com.workflow.concurrency.WorkflowConflictException;
import com.workflow.enums.InstanceStatus;
import com.workflow.runtime.ProcessInstance;
import com.workflow.tx.TransactionContext;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存版 InstanceRepository。
 *
 * <p>与 {@link InMemoryTaskRepository} 同样采用<b>拷贝语义</b>：save 存副本、find 还副本，
 * 仓库持有的对象绝不外泄，事务 before-image 因此可信。
 *
 * <p>注意 {@code save} 会整体覆盖 tokens 与 tasks —— 这沿用了引擎
 * 「改内存对象再落库」的写模型。快照拷贝是 O(实例内任务数)，内存测试规模下无碍；
 * 生产规模请使用 JPA / MyBatis 仓储（它们按行增量写、由数据库负责事务与并发）。
 */
public class InMemoryInstanceRepository implements InstanceRepository {

    private static final String UNDO_PREFIX = "instance:";

    private final ConcurrentHashMap<String, ProcessInstance> store = new ConcurrentHashMap<>();
    private final boolean optimisticLock;

    public InMemoryInstanceRepository() {
        this(false);
    }

    public InMemoryInstanceRepository(boolean optimisticLock) {
        this.optimisticLock = optimisticLock;
    }

    @Override
    public void save(ProcessInstance instance) {
        Objects.requireNonNull(instance);
        String id = instance.getId();

        TransactionContext.recordUndo(UNDO_PREFIX + id, restoreAction(id, store.get(id)));

        if (optimisticLock) {
            ProcessInstance current = store.get(id);
            if (current != null && current.getRevision() != instance.getRevision()) {
                throw new WorkflowConflictException(
                        "流程实例并发冲突: instance=" + id + " 期望 revision=" + instance.getRevision()
                                + " 实际=" + current.getRevision(), id);
            }
        }
        ProcessInstance stored = instance.snapshot();
        stored.setRevision(optimisticLock ? stored.getRevision() + 1 : 0L);
        store.put(id, stored);
    }

    private Runnable restoreAction(String id, ProcessInstance before) {
        return () -> {
            if (before == null) {
                store.remove(id);
            } else {
                store.put(id, before);
            }
        };
    }

    @Override
    public ProcessInstance findById(String instanceId) {
        ProcessInstance i = store.get(instanceId);
        if (i == null) {
            throw new IllegalArgumentException("流程实例不存在: " + instanceId);
        }
        return i.snapshot();
    }

    @Override
    public void delete(String instanceId) {
        TransactionContext.recordUndo(UNDO_PREFIX + instanceId,
                restoreAction(instanceId, store.get(instanceId)));
        store.remove(instanceId);
    }

    @Override
    public List<ProcessInstance> findByProcessKey(String processKey) {
        return store.values().stream()
                .filter(i -> processKey.equals(i.getProcessKey()))
                .map(ProcessInstance::snapshot)
                .collect(Collectors.toList());
    }

    @Override
    public List<ProcessInstance> findByStatus(InstanceStatus status) {
        return store.values().stream()
                .filter(i -> i.getStatus() == status)
                .map(ProcessInstance::snapshot)
                .collect(Collectors.toList());
    }

    @Override
    public List<ProcessInstance> findAll() {
        return store.values().stream()
                .map(ProcessInstance::snapshot)
                .collect(Collectors.toList());
    }

    @Override
    public List<ProcessInstance> findByProcessKeyAndVersion(String processKey, int version) {
        return store.values().stream()
                .filter(i -> processKey.equals(i.getProcessKey()) && i.getProcessVersion() == version)
                .map(ProcessInstance::snapshot)
                .collect(Collectors.toList());
    }

    @Override
    public java.util.List<ProcessStatusCount> countGroupByProcessAndStatus() {
        java.util.Map<String, java.util.Map<InstanceStatus, Long>> g = new java.util.LinkedHashMap<>();
        for (ProcessInstance i : store.values()) {
            g.computeIfAbsent(i.getProcessKey(), k -> new java.util.EnumMap<>(InstanceStatus.class))
             .merge(i.getStatus(), 1L, Long::sum);
        }
        java.util.List<ProcessStatusCount> out = new java.util.ArrayList<>();
        g.forEach((k, m) -> m.forEach((s, c) -> out.add(new ProcessStatusCount(k, s, c))));
        return out;
    }
}
