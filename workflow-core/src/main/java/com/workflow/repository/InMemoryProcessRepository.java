package com.workflow.repository;

import com.workflow.definition.ProcessDefinition;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * 内存版 ProcessRepository - 线程安全,支持多版本
 *
 * 内部结构: key -> (version -> definition) 有序映射
 */
public class InMemoryProcessRepository implements ProcessRepository {

    private final ConcurrentHashMap<String, ConcurrentSkipListMap<Integer, ProcessDefinition>> store = new ConcurrentHashMap<>();

    @Override
    public void save(ProcessDefinition definition) {
        Objects.requireNonNull(definition);
        store.computeIfAbsent(definition.getKey(), k -> new ConcurrentSkipListMap<>())
                .put(definition.getVersion(), definition);
    }

    @Override
    public ProcessDefinition findByKey(String key) {
        ConcurrentSkipListMap<Integer, ProcessDefinition> versions = store.get(key);
        if (versions == null || versions.isEmpty()) {
            throw new IllegalArgumentException("流程定义不存在: " + key);
        }
        return versions.lastEntry().getValue();
    }

    @Override
    public ProcessDefinition findByKeyAndVersion(String key, int version) {
        ConcurrentSkipListMap<Integer, ProcessDefinition> versions = store.get(key);
        ProcessDefinition d = versions == null ? null : versions.get(version);
        if (d == null) {
            throw new IllegalArgumentException("流程定义不存在: " + key + " v" + version);
        }
        return d;
    }

    @Override
    public List<Integer> getVersions(String key) {
        ConcurrentSkipListMap<Integer, ProcessDefinition> versions = store.get(key);
        if (versions == null) {
            return List.of();
        }
        return List.copyOf(versions.keySet());
    }

    @Override
    public boolean exists(String key) {
        ConcurrentSkipListMap<Integer, ProcessDefinition> versions = store.get(key);
        return versions != null && !versions.isEmpty();
    }
}
