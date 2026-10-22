package com.workflow.persistence.mybatis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.workflow.persistence.mybatis.entity.WfJobEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 作业 Mapper - 单主键 BaseMapper + 到期查询/原子领取
 */
public interface WfJobMapper extends BaseMapper<WfJobEntity> {

    @Select("SELECT * FROM wf_job WHERE state = 'READY' AND due_at <= #{now}"
            + " AND (lock_owner IS NULL OR lock_expires_at <= #{now})"
            + " ORDER BY due_at LIMIT #{limit}")
    List<WfJobEntity> findDue(@Param("now") long now, @Param("limit") int limit);

    /**
     * 原子领取：条件命中才更新，受影响行数 &gt; 0 才算领取成功。
     * 多个节点同时领同一作业，只有一个拿到 1 行。
     */
    @Update("UPDATE wf_job SET lock_owner = #{owner}, lock_expires_at = #{lease},"
            + " update_time = #{now} WHERE id = #{id} AND state = 'READY' AND due_at <= #{now}"
            + " AND (lock_owner IS NULL OR lock_expires_at <= #{now})")
    int claim(@Param("id") String id, @Param("owner") String owner,
              @Param("now") long now, @Param("lease") long leaseUntil);

    @Update("UPDATE wf_job SET state = 'READY', retries = retries + 1, last_error = #{err},"
            + " lock_owner = NULL, lock_expires_at = 0, due_at = #{due}, update_time = #{now}"
            + " WHERE id = #{id}")
    int markRetry(@Param("id") String id, @Param("err") String error,
                  @Param("due") long nextDueAt, @Param("now") long now);

    @Update("UPDATE wf_job SET state = 'FAILED', retries = retries + 1, last_error = #{err},"
            + " lock_owner = NULL, lock_expires_at = 0, update_time = #{now} WHERE id = #{id}")
    int markFailed(@Param("id") String id, @Param("err") String error, @Param("now") long now);

    @Select("SELECT * FROM wf_job WHERE instance_id = #{iid} ORDER BY due_at")
    List<WfJobEntity> findByInstanceId(@Param("iid") String instanceId);

    @Delete("DELETE FROM wf_job WHERE instance_id = #{iid}")
    int deleteByInstanceId(@Param("iid") String instanceId);

    @Select("SELECT COUNT(*) FROM wf_job WHERE state = #{st}")
    long countByState(@Param("st") String state);
}
