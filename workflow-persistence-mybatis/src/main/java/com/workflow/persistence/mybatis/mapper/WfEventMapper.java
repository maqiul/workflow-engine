package com.workflow.persistence.mybatis.mapper;

import com.workflow.persistence.mybatis.entity.WfEventEntity;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 事件 Mapper - 复合主键，全部手写 SQL
 */
public interface WfEventMapper {

    @Select("SELECT * FROM wf_event WHERE instance_id = #{iid} AND node_id = #{nid}")
    WfEventEntity findByKey(@Param("iid") String instanceId, @Param("nid") String nodeId);

    @Select("SELECT * FROM wf_event WHERE event_type = #{type} AND message_key = #{key}")
    List<WfEventEntity> findByMessageKey(@Param("type") String type, @Param("key") String key);

    @Select("SELECT * FROM wf_event WHERE event_type = #{type} AND signal_name = #{name}")
    List<WfEventEntity> findBySignalName(@Param("type") String type, @Param("name") String signalName);

    @Select("SELECT * FROM wf_event WHERE event_type = #{type} AND trigger_time <= #{now} ORDER BY trigger_time")
    List<WfEventEntity> findExpiredTimers(@Param("type") String type, @Param("now") long now);

    @Insert("INSERT INTO wf_event (instance_id, node_id, event_type, message_key, signal_name, trigger_time, interrupting) " +
            "VALUES (#{instanceId}, #{nodeId}, #{eventType}, #{messageKey}, #{signalName}, #{triggerTime}, #{interrupting})")
    int insert(WfEventEntity entity);

    @Update("UPDATE wf_event SET event_type = #{eventType}, message_key = #{messageKey}, " +
            "signal_name = #{signalName}, trigger_time = #{triggerTime}, interrupting = #{interrupting} " +
            "WHERE instance_id = #{instanceId} AND node_id = #{nodeId}")
    int update(WfEventEntity entity);

    @Delete("DELETE FROM wf_event WHERE instance_id = #{iid} AND node_id = #{nid}")
    int deleteByKey(@Param("iid") String instanceId, @Param("nid") String nodeId);

    @Delete("DELETE FROM wf_event WHERE instance_id = #{iid}")
    int deleteByInstanceId(@Param("iid") String instanceId);

    /** H2 MERGE INTO 实现 upsert（复合主键） */
    @Insert("MERGE INTO wf_event KEY (instance_id, node_id) " +
            "VALUES (#{instanceId}, #{nodeId}, #{eventType}, #{messageKey}, #{signalName}, #{triggerTime}, #{interrupting})")
    int upsert(WfEventEntity entity);
}
