package com.workflow.persistence.mybatis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.workflow.enums.TaskStatus;
import com.workflow.persistence.mybatis.entity.WfTaskEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 任务 Mapper - 单主键 BaseMapper + 实例级查询/删除
 */
public interface WfTaskMapper extends BaseMapper<WfTaskEntity> {

    @Select("SELECT * FROM wf_task WHERE instance_id = #{iid} ORDER BY create_time")
    List<WfTaskEntity> findByInstanceId(@Param("iid") String instanceId);

    @Select("SELECT * FROM wf_task WHERE status = #{status}")
    List<WfTaskEntity> findByStatus(@Param("status") TaskStatus status);

    @Delete("DELETE FROM wf_task WHERE instance_id = #{iid}")
    int deleteByInstanceId(@Param("iid") String instanceId);
}
