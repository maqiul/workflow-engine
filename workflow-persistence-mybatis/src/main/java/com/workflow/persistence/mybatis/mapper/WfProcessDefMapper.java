package com.workflow.persistence.mybatis.mapper;

import com.workflow.persistence.mybatis.entity.WfProcessDefEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 流程定义 Mapper
 *
 * 复合主键 (key, version):MyBatis-Plus BaseMapper 只支持单主键,
 * 所以这里全部手写注解 SQL,不继承 BaseMapper。
 */
public interface WfProcessDefMapper {

    @Insert("""
            INSERT INTO wf_process_def (key_, version, name, start_node_id, nodes_json, outgoing_json, variable_definitions_json)
            VALUES (#{key}, #{version}, #{name}, #{startNodeId}, #{nodesJson}, #{outgoingJson}, #{variableDefinitionsJson})
            """)
    int insert(WfProcessDefEntity entity);

    /** 更新已存在定义(逻辑 upsert 的一部分,跨库标准 SQL) */
    @Update("""
            UPDATE wf_process_def
            SET name = #{name}, start_node_id = #{startNodeId},
                nodes_json = #{nodesJson}, outgoing_json = #{outgoingJson},
                variable_definitions_json = #{variableDefinitionsJson}
            WHERE key_ = #{key} AND version = #{version}
            """)
    int update(WfProcessDefEntity entity);

    @Delete("DELETE FROM wf_process_def WHERE key_ = #{key} AND version = #{version}")
    int deleteByKeyVersion(@Param("key") String key, @Param("version") int version);

    @Select("SELECT * FROM wf_process_def WHERE key_ = #{key} AND version = #{version}")
    WfProcessDefEntity findByKeyVersion(@Param("key") String key, @Param("version") int version);

    /** 最新版本定义:version 最大的一行 */
    @Select("SELECT * FROM wf_process_def WHERE key_ = #{key} ORDER BY version DESC LIMIT 1")
    WfProcessDefEntity findLatestByKey(@Param("key") String key);

    @Select("SELECT version FROM wf_process_def WHERE key_ = #{key} ORDER BY version")
    List<Integer> findVersions(@Param("key") String key);

    @Select("SELECT COUNT(*) FROM wf_process_def WHERE key_ = #{key}")
    int countByKey(@Param("key") String key);
}
