package com.workflow.repository;

import com.workflow.runtime.TaskInstance;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 内存版 TaskRepository
 */
public class InMemoryTaskRepository implements TaskRepository {

    /** 用 taskId 索引,O(1) 查询 */
    private final java.util.concurrent.ConcurrentHashMap<String, TaskInstance> byId = new java.util.concurrent.ConcurrentHashMap<>();
    /** 用 instanceId 索引,O(N) 但小数据集够用 */
    private final java.util.concurrent.ConcurrentHashMap<String, CopyOnWriteArrayList<TaskInstance>> byInstance = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void save(TaskInstance task) {
        Objects.requireNonNull(task);
        byId.put(task.getId(), task);
        byInstance.computeIfAbsent(task.getInstanceId(), k -> new CopyOnWriteArrayList<>()).add(task);
    }

    @Override
    public TaskInstance findById(String taskId) {
        TaskInstance t = byId.get(taskId);
        if (t == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
        return t;
    }

    @Override
    public List<TaskInstance> findByInstanceId(String instanceId) {
        return List.copyOf(byInstance.getOrDefault(instanceId, new CopyOnWriteArrayList<>()));
    }

    @Override
    public List<TaskInstance> findPendingByUser(String userId) {
        return byId.values().stream()
                .filter(t -> t.getStatus() == com.workflow.enums.TaskStatus.PENDING)
                .filter(t -> t.getCandidate().getUserIds().contains(userId))
                .collect(Collectors.toList());
    }

    @Override
    public List<TaskInstance> findAll() {
        return List.copyOf(byId.values());
    }

    @Override
    public List<TaskInstance> findByNodeId(String nodeId) {
        return byId.values().stream()
                .filter(t -> nodeId.equals(t.getNodeId()))
                .collect(Collectors.toList());
    }

    @Override
    public List<TaskInstance> findByStatus(com.workflow.enums.TaskStatus status) {
        return byId.values().stream()
                .filter(t -> t.getStatus() == status)
                .collect(Collectors.toList());
    }
}