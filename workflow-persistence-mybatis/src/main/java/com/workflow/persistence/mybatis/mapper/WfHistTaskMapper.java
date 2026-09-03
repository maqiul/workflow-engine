package com.workflow.persistence.mybatis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.workflow.persistence.mybatis.entity.WfHistTaskEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 历史任务 Mapper —— 单主键，走 BaseMapper 通用 CRUD。
 *
 * <p>仅平均办理时长这一条用手写 SQL：返回标量而非实体，
 * 因此不存在「SELECT 漏了新列、读回时字段静默丢失」的风险。
 */
public interface WfHistTaskMapper extends BaseMapper<WfHistTaskEntity> {

    /** 某流程某节点的平均办理时长（毫秒）。历史表只存已落定任务，无需过滤未闭合。 */
    @Select("SELECT AVG(end_time - start_time) FROM wf_hist_task"
            + " WHERE process_key = #{processKey} AND node_id = #{nodeId}")
    Double avgDuration(@Param("processKey") String processKey, @Param("nodeId") String nodeId);
}
