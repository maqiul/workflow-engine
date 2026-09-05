package com.workflow.persistence.mybatis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.workflow.persistence.mybatis.entity.WfDecisionTableEntity;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * DMN 决策表 Mapper
 */
public interface WfDecisionTableMapper extends BaseMapper<WfDecisionTableEntity> {
    
    @Select("SELECT * FROM wf_decision_table WHERE name = #{name}")
    List<WfDecisionTableEntity> selectByName(String name);
}
