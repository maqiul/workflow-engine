package com.workflow.persistence.mybatis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.workflow.persistence.mybatis.entity.WfTokenEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Token Mapper - 单主键 BaseMapper + 实例级查询/删除
 */
public interface WfTokenMapper extends BaseMapper<WfTokenEntity> {

    @Select("SELECT * FROM wf_token WHERE instance_id = #{iid}")
    List<WfTokenEntity> findByInstanceId(@Param("iid") String instanceId);

    @Delete("DELETE FROM wf_token WHERE instance_id = #{iid}")
    int deleteByInstanceId(@Param("iid") String instanceId);
}
