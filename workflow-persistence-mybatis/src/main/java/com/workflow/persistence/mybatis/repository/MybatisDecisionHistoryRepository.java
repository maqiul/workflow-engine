package com.workflow.persistence.mybatis.repository;

import com.alibaba.fastjson2.JSON;
import com.workflow.dmn.DecisionHistory;
import com.workflow.dmn.DecisionHistoryRepository;
import com.workflow.persistence.mybatis.MybatisPersistence;
import com.workflow.persistence.mybatis.entity.WfDecisionHistoryEntity;
import com.workflow.persistence.mybatis.mapper.WfDecisionHistoryMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MyBatis 版决策历史仓储实现
 */
public class MybatisDecisionHistoryRepository implements DecisionHistoryRepository {
    
    private final MybatisPersistence mybatis;
    
    public MybatisDecisionHistoryRepository(MybatisPersistence mybatis) {
        this.mybatis = mybatis;
    }
    
    @Override
    public void save(DecisionHistory history) {
        mybatis.inSession(session -> {
            WfDecisionHistoryMapper mapper = session.getMapper(WfDecisionHistoryMapper.class);
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
            mapper.insert(entity);
            return null;
        });
    }
    
    @Override
    public List<DecisionHistory> findByInstanceId(String instanceId) {
        return mybatis.inSession(session -> {
            WfDecisionHistoryMapper mapper = session.getMapper(WfDecisionHistoryMapper.class);
            List<WfDecisionHistoryEntity> entities = mapper.selectByInstanceId(instanceId);
            return entities.stream()
                    .map(this::toDecisionHistory)
                    .collect(Collectors.toList());
        });
    }
    
    @Override
    public List<DecisionHistory> findByNodeId(String nodeId) {
        return mybatis.inSession(session -> {
            WfDecisionHistoryMapper mapper = session.getMapper(WfDecisionHistoryMapper.class);
            List<WfDecisionHistoryEntity> entities = mapper.selectByNodeId(nodeId);
            return entities.stream()
                    .map(this::toDecisionHistory)
                    .collect(Collectors.toList());
        });
    }
    
    @Override
    public List<DecisionHistory> findAll() {
        return mybatis.inSession(session -> {
            WfDecisionHistoryMapper mapper = session.getMapper(WfDecisionHistoryMapper.class);
            List<WfDecisionHistoryEntity> entities = mapper.selectList(null);
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
