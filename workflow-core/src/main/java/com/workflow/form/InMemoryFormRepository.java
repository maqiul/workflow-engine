package com.workflow.form;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版表单仓储实现
 * 
 * <p>使用 ConcurrentHashMap 存储表单定义，适用于测试和演示场景。
 */
public class InMemoryFormRepository implements FormRepository {
    
    private final Map<String, FormDefinition> store = new ConcurrentHashMap<>();
    
    @Override
    public void save(FormDefinition form) {
        if (form == null || form.getId() == null) {
            throw new IllegalArgumentException("Form and its ID cannot be null");
        }
        store.put(form.getId(), form);
    }
    
    @Override
    public FormDefinition findById(String id) {
        if (id == null) {
            return null;
        }
        return store.get(id);
    }
    
    @Override
    public FormDefinition findByName(String name) {
        if (name == null) {
            return null;
        }
        return store.values().stream()
                .filter(f -> name.equals(f.getName()))
                .findFirst()
                .orElse(null);
    }
    
    @Override
    public List<FormDefinition> findAll() {
        return new ArrayList<>(store.values());
    }
    
    @Override
    public void delete(String id) {
        if (id != null) {
            store.remove(id);
        }
    }
    
    @Override
    public boolean exists(String id) {
        return id != null && store.containsKey(id);
    }
}
