package com.workflow.repository;

import com.workflow.enums.InstanceStatus;
import com.workflow.runtime.ProcessInstance;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存版 InstanceRepository
 */
public class InMemoryInstanceRepository implements InstanceRepository {

    private final ConcurrentHashMap<String, ProcessInstance> store = new ConcurrentHashMap<>();

    @Override
    public void save(ProcessInstance instance) {
        Objects.requireNonNull(instance);
        store.put(instance.getId(), instance);
    }

    @Override
    public ProcessInstance findById(String instanceId) {
        ProcessInstance i = store.get(instanceId);
        if (i == null) {
            throw new IllegalArgumentException("流程实例不存在: " + instanceId);
        }
        return i;
    }

    @Override
    public void delete(String instanceId) {
        store.remove(instanceId);
    }

    @Override
    public List<ProcessInstance> findByProcessKey(String processKey) {
        return store.values().stream()
                .filter(i -> processKey.equals(i.getProcessKey()))
                .collect(Collectors.toList());
    }

    @Override
    public List<ProcessInstance> findByStatus(InstanceStatus status) {
        return store.values().stream()
                .filter(i -> i.getStatus() == status)
                .collect(Collectors.toList());
    }

    @Override
    public List<ProcessInstance> findAll() {
        return List.copyOf(store.values());
    }

    @Override
    public List<ProcessInstance> findByProcessKeyAndVersion(String processKey, int version) {
        return store.values().stream()
                .filter(i -> processKey.equals(i.getProcessKey()) && i.getProcessVersion() == version)
                .collect(Collectors.toList());
    }
}