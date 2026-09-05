package com.workflow.persistence.jpa.repository;

import com.alibaba.fastjson2.JSON;
import com.workflow.dmn.DecisionHistory;
import com.workflow.dmn.DecisionHistoryRepository;
import com.workflow.persistence.jpa.JpaPersistence;
import com.workflow.persistence.jpa.entity.WfDecisionHistoryEntity;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * JPA 版决策历史仓储实现
 */
public class JpaDecisionHistoryRepository implements DecisionHistoryRepository {
    
    private final JpaPersistence jpa;
    
    public JpaDecisionHistoryRepository(JpaPersistence jpa) {
        this.jpa = jpa;
    }
    
    @Override
    public void save(DecisionHistory history) {
        jpa.inTransaction(em -> {
            WfDecisionHistoryEntity entity = new WfDecisionHistoryEntity();
            entity.setId(history.getId());
            entity.setInstanceId(history.getInstanceId());
            entity.setTokenId(history.getTokenId());
            entity.setNodeId(history.getNodeId());
            entity.setDecisionTableId(history.getDecisionTableId());
            entity.setInputsJson(JSON.toJSONString(history.getInputs()));
            entity.setOutputsJson(JSON.toJSONString(history.getOutputs()));
            entity.setMatchedRuleId(history.getMatchedRuleId());
            entity.setExecutedAt(history.getExecutedAt());
            em.persist(entity);
            return null;
        });
    }
    
    @Override
    public List<DecisionHistory> findByInstanceId(String instanceId) {
        return jpa.inTransaction(em -> {
            List<WfDecisionHistoryEntity> entities = em.createQuery(
                    "SELECT e FROM WfDecisionHistoryEntity e WHERE e.instanceId = :instanceId",
                    WfDecisionHistoryEntity.class)
                    .setParameter("instanceId", instanceId)
                    .getResultList();
            return entities.stream()
                    .map(this::toDecisionHistory)
                    .collect(Collectors.toList());
        });
    }
    
    @Override
    public List<DecisionHistory> findByNodeId(String nodeId) {
        return jpa.inTransaction(em -> {
            List<WfDecisionHistoryEntity> entities = em.createQuery(
                    "SELECT e FROM WfDecisionHistoryEntity e WHERE e.nodeId = :nodeId",
                    WfDecisionHistoryEntity.class)
                    .setParameter("nodeId", nodeId)
                    .getResultList();
            return entities.stream()
                    .map(this::toDecisionHistory)
                    .collect(Collectors.toList());
        });
    }
    
    @Override
    public List<DecisionHistory> findAll() {
        return jpa.inTransaction(em -> {
            List<WfDecisionHistoryEntity> entities = em.createQuery(
                    "SELECT e FROM WfDecisionHistoryEntity e",
                    WfDecisionHistoryEntity.class)
                    .getResultList();
            return entities.stream()
                    .map(this::toDecisionHistory)
                    .collect(Collectors.toList());
        });
    }
    
    @SuppressWarnings("unchecked")
    private DecisionHistory toDecisionHistory(WfDecisionHistoryEntity entity) {
        Map<String, Object> inputs = JSON.parseObject(entity.getInputsJson(), Map.class);
        Map<String, Object> outputs = JSON.parseObject(entity.getOutputsJson(), Map.class);
        
        return new DecisionHistory(
                entity.getId(),
                entity.getInstanceId(),
                entity.getTokenId(),
                entity.getNodeId(),
                entity.getDecisionTableId(),
                inputs,
                outputs,
                entity.getMatchedRuleId(),
                entity.getExecutedAt()
        );
    }
}
