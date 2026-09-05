package com.workflow.persistence.mybatis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.workflow.persistence.mybatis.entity.WfDecisionHistoryEntity;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * DMN 决策历史 Mapper
 */
public interface WfDecisionHistoryMapper extends BaseMapper<WfDecisionHistoryEntity> {
    
    @Select("SELECT * FROM wf_decision_history WHERE instance_id = #{instanceId}")
    List<WfDecisionHistoryEntity> selectByInstanceId(String instanceId);
    
    @Select("SELECT * FROM wf_decision_history WHERE node_id = #{nodeId}")
    List<WfDecisionHistoryEntity> selectByNodeId(String nodeId);
}
