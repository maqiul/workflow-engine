package com.workflow.persistence.jpa.repository;

import com.alibaba.fastjson2.JSON;
import com.workflow.dmn.DecisionRepository;
import com.workflow.dmn.DecisionTable;
import com.workflow.persistence.jpa.JpaPersistence;
import com.workflow.persistence.jpa.entity.WfDecisionTableEntity;

import java.util.List;
import java.util.stream.Collectors;

/**
 * JPA 版决策表仓储实现
 */
public class JpaDecisionRepository implements DecisionRepository {
    
    private final JpaPersistence jpa;
    
    public JpaDecisionRepository(JpaPersistence jpa) {
        this.jpa = jpa;
    }
    
    @Override
    public void save(DecisionTable decisionTable) {
        jpa.inTransaction(em -> {
            WfDecisionTableEntity entity = em.find(WfDecisionTableEntity.class, decisionTable.getId());
            if (entity == null) {
                entity = new WfDecisionTableEntity();
                entity.setId(decisionTable.getId());
            }
            entity.setName(decisionTable.getName());
            entity.setInputsJson(JSON.toJSONString(decisionTable.getInputs()));
            entity.setOutputsJson(JSON.toJSONString(decisionTable.getOutputs()));
            entity.setRulesJson(JSON.toJSONString(decisionTable.getRules()));
            entity.setHitPolicy(decisionTable.getHitPolicy().name());
            em.merge(entity);
            return null;
        });
    }
    
    @Override
    public DecisionTable findById(String id) {
        return jpa.inTransaction(em -> {
            WfDecisionTableEntity entity = em.find(WfDecisionTableEntity.class, id);
            return entity != null ? toDecisionTable(entity) : null;
        });
    }
    
    @Override
    public DecisionTable findByName(String name) {
        return jpa.inTransaction(em -> {
            List<WfDecisionTableEntity> entities = em.createQuery(
                    "SELECT e FROM WfDecisionTableEntity e WHERE e.name = :name",
                    WfDecisionTableEntity.class)
                    .setParameter("name", name)
                    .getResultList();
            return entities.isEmpty() ? null : toDecisionTable(entities.get(0));
        });
    }
    
    @Override
    public List<DecisionTable> findAll() {
        return jpa.inTransaction(em -> {
            List<WfDecisionTableEntity> entities = em.createQuery(
                    "SELECT e FROM WfDecisionTableEntity e",
                    WfDecisionTableEntity.class)
                    .getResultList();
            return entities.stream()
                    .map(this::toDecisionTable)
                    .collect(Collectors.toList());
        });
    }
    
    @Override
    public void delete(String id) {
        jpa.inTransaction(em -> {
            WfDecisionTableEntity entity = em.find(WfDecisionTableEntity.class, id);
            if (entity != null) {
                em.remove(entity);
            }
            return null;
        });
    }
    
    private DecisionTable toDecisionTable(WfDecisionTableEntity entity) {
        List<DecisionTable.InputClause> inputs = JSON.parseArray(
                entity.getInputsJson(), DecisionTable.InputClause.class);
        List<DecisionTable.OutputClause> outputs = JSON.parseArray(
                entity.getOutputsJson(), DecisionTable.OutputClause.class);
        List<DecisionTable.DecisionRule> rules = JSON.parseArray(
                entity.getRulesJson(), DecisionTable.DecisionRule.class);
        DecisionTable.HitPolicy hitPolicy = DecisionTable.HitPolicy.valueOf(entity.getHitPolicy());
        
        return new DecisionTable(
                entity.getId(),
                entity.getName(),
                inputs,
                outputs,
                rules,
                hitPolicy
        );
    }
}
