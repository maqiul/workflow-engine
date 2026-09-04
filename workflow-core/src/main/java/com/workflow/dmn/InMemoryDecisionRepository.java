package com.workflow.dmn;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版决策仓储实现
 * 
 * <p>使用 ConcurrentHashMap 存储决策表，适用于测试和演示场景。
 */
public class InMemoryDecisionRepository implements DecisionRepository {
    
    private final Map<String, DecisionTable> store = new ConcurrentHashMap<>();
    
    @Override
    public void save(DecisionTable decisionTable) {
        if (decisionTable == null || decisionTable.getId() == null) {
            throw new IllegalArgumentException("DecisionTable and its ID cannot be null");
        }
        store.put(decisionTable.getId(), decisionTable);
    }
    
    @Override
    public DecisionTable findById(String id) {
        if (id == null) {
            return null;
        }
        return store.get(id);
    }
    
    @Override
    public DecisionTable findByName(String name) {
        if (name == null) {
            return null;
        }
        return store.values().stream()
                .filter(dt -> name.equals(dt.getName()))
                .findFirst()
                .orElse(null);
    }
    
    @Override
    public List<DecisionTable> findAll() {
        return new ArrayList<>(store.values());
    }
    
    @Override
    public void delete(String id) {
        if (id != null) {
            store.remove(id);
        }
    }
}
