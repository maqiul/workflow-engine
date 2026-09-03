package com.workflow.persistence.mybatis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.workflow.persistence.mybatis.entity.WfHistActivityEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 历史活动 Mapper —— 单主键，走 BaseMapper 通用 CRUD。
 *
 * <p>只有平均耗时这一条用了手写 SQL：它是聚合查询，返回标量而非实体，
 * 不存在「SELECT 漏了新列导致读回丢字段」的风险（那个坑针对的是实体全列映射）。
 */
public interface WfHistActivityMapper extends BaseMapper<WfHistActivityEntity> {

    /**
     * 已闭合活动的平均耗时（毫秒）。
     *
     * <p>{@code end_time IS NOT NULL} 把进行中的活动排除在外 —— 它们的时长随查询时刻
     * 漂移，计入会让同一份报表每次刷新都不一样。无样本时 SQL 返回 NULL，映射为 null。
     */
    @Select("SELECT AVG(end_time - start_time) FROM wf_hist_activity"
            + " WHERE process_key = #{processKey} AND activity_id = #{activityId}"
            + " AND end_time IS NOT NULL")
    Double avgClosedDuration(@Param("processKey") String processKey,
                            @Param("activityId") String activityId);
}
