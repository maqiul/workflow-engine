package com.workflow.dmn;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 内存版决策历史仓储实现
 */
public class InMemoryDecisionHistoryRepository implements DecisionHistoryRepository {
    
    private final List<DecisionHistory> store = new CopyOnWriteArrayList<>();
    
    @Override
    public void save(DecisionHistory history) {
        if (history == null) {
            throw new IllegalArgumentException("DecisionHistory cannot be null");
        }
        store.add(history);
    }
    
    @Override
    public List<DecisionHistory> findByInstanceId(String instanceId) {
        if (instanceId == null) {
            return new ArrayList<>();
        }
        return store.stream()
                .filter(h -> instanceId.equals(h.getInstanceId()))
                .collect(Collectors.toList());
    }
    
    @Override
    public List<DecisionHistory> findByNodeId(String nodeId) {
        if (nodeId == null) {
            return new ArrayList<>();
        }
        return store.stream()
                .filter(h -> nodeId.equals(h.getNodeId()))
                .collect(Collectors.toList());
    }
    
    @Override
    public List<DecisionHistory> findAll() {
        return new ArrayList<>(store);
    }
}