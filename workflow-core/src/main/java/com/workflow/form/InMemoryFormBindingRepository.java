package com.workflow.form;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存版表单绑定仓储实现
 * 
 * <p>使用 ConcurrentHashMap 存储表单绑定，适用于测试和演示场景。
 */
public class InMemoryFormBindingRepository implements FormBindingRepository {
    
    private final Map<String, FormBinding> store = new ConcurrentHashMap<>();
    
    @Override
    public void save(FormBinding binding) {
        if (binding == null || binding.getId() == null) {
            throw new IllegalArgumentException("FormBinding and its ID cannot be null");
        }
        store.put(binding.getId(), binding);
    }
    
    @Override
    public FormBinding findById(String id) {
        if (id == null) {
            return null;
        }
        return store.get(id);
    }
    
    @Override
    public FormBinding findByProcessAndNode(String processKey, String nodeId) {
        if (processKey == null || nodeId == null) {
            return null;
        }
        return store.values().stream()
                .filter(b -> processKey.equals(b.getProcessKey()) && nodeId.equals(b.getNodeId()))
                .findFirst()
                .orElse(null);
    }
    
    @Override
    public List<FormBinding> findByProcessKey(String processKey) {
        if (processKey == null) {
            return new ArrayList<>();
        }
        return store.values().stream()
                .filter(b -> processKey.equals(b.getProcessKey()))
                .collect(Collectors.toList());
    }
    
    @Override
    public List<FormBinding> findByFormId(String formId) {
        if (formId == null) {
            return new ArrayList<>();
        }
        return store.values().stream()
                .filter(b -> formId.equals(b.getFormId()))
                .collect(Collectors.toList());
    }
    
    @Override
    public void delete(String id) {
        if (id != null) {
            store.remove(id);
        }
    }
    
    @Override
    public void deleteByProcessAndNode(String processKey, String nodeId) {
        if (processKey == null || nodeId == null) {
            return;
        }
        store.values().removeIf(b -> processKey.equals(b.getProcessKey()) && nodeId.equals(b.getNodeId()));
    }
}
