package com.workflow.persistence.mybatis.repository;

import com.alibaba.fastjson2.JSON;
import com.workflow.dmn.DecisionRepository;
import com.workflow.dmn.DecisionTable;
import com.workflow.persistence.mybatis.MybatisPersistence;
import com.workflow.persistence.mybatis.entity.WfDecisionTableEntity;
import com.workflow.persistence.mybatis.mapper.WfDecisionTableMapper;

import java.util.List;
import java.util.stream.Collectors;

/**
 * MyBatis 版决策表仓储实现
 */
public class MybatisDecisionRepository implements DecisionRepository {
    
    private final MybatisPersistence mybatis;
    
    public MybatisDecisionRepository(MybatisPersistence mybatis) {
        this.mybatis = mybatis;
    }
    
    @Override
    public void save(DecisionTable decisionTable) {
        mybatis.inSession(session -> {
            WfDecisionTableMapper mapper = session.getMapper(WfDecisionTableMapper.class);
            WfDecisionTableEntity entity = mapper.selectById(decisionTable.getId());
            if (entity == null) {
                entity = new WfDecisionTableEntity();
                entity.setId(decisionTable.getId());
            }
            entity.setName(decisionTable.getName());
            entity.setInputsJson(JSON.toJSONString(decisionTable.getInputs()));
            entity.setOutputsJson(JSON.toJSONString(decisionTable.getOutputs()));
            entity.setRulesJson(JSON.toJSONString(decisionTable.getRules()));
            entity.setHitPolicy(decisionTable.getHitPolicy().name());
            
            if (mapper.selectById(decisionTable.getId()) == null) {
                mapper.insert(entity);
            } else {
                mapper.updateById(entity);
            }
            return null;
        });
    }
    
    @Override
    public DecisionTable findById(String id) {
        return mybatis.inSession(session -> {
            WfDecisionTableMapper mapper = session.getMapper(WfDecisionTableMapper.class);
            WfDecisionTableEntity entity = mapper.selectById(id);
            return entity != null ? toDecisionTable(entity) : null;
        });
    }
    
    @Override
    public DecisionTable findByName(String name) {
        return mybatis.inSession(session -> {
            WfDecisionTableMapper mapper = session.getMapper(WfDecisionTableMapper.class);
            List<WfDecisionTableEntity> entities = mapper.selectByName(name);
            return entities.isEmpty() ? null : toDecisionTable(entities.get(0));
        });
    }
    
    @Override
    public List<DecisionTable> findAll() {
        return mybatis.inSession(session -> {
            WfDecisionTableMapper mapper = session.getMapper(WfDecisionTableMapper.class);
            List<WfDecisionTableEntity> entities = mapper.selectList(null);
            return entities.stream()
                    .map(this::toDecisionTable)
                    .collect(Collectors.toList());
        });
    }
    
    @Override
    public void delete(String id) {
        mybatis.inSession(session -> {
            WfDecisionTableMapper mapper = session.getMapper(WfDecisionTableMapper.class);
            mapper.deleteById(id);
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
