package com.workflow.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 流程定义实体(MyBatis-Plus 版) - 复合主键 (key, version)
 *
 * 注意:MyBatis-Plus 的 BaseMapper 不支持复合主键,所以 wf_process_def 的
 * 增删查全部走手写注解 SQL(见 WfProcessDefMapper),这里仅做字段映射。
 *
 * 表结构:
 *   wf_process_def
 *     key              VARCHAR(64)  PK
 *     version          INT          PK
 *     name             VARCHAR(128)
 *     start_node_id    VARCHAR(64)
 *     nodes_json       CLOB         -- fastjson2 序列化的 Map<String,NodeDefinition>
 *     outgoing_json    CLOB         -- fastjson2 序列化的 Map<String,List<Transition>>
 */
@TableName("wf_process_def")
public class WfProcessDefEntity {

    /** key 映射到 key_ 列(H2 保留字) */
    @TableId(value = "key_", type = IdType.INPUT)
    private String key;

    @TableField("version")
    private int version;

    @TableField("name")
    private String name;

    @TableField("start_node_id")
    private String startNodeId;

    @TableField("nodes_json")
    private String nodesJson;

    @TableField("outgoing_json")
    private String outgoingJson;

    @TableField("variable_definitions_json")
    private String variableDefinitionsJson;

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getStartNodeId() { return startNodeId; }
    public void setStartNodeId(String startNodeId) { this.startNodeId = startNodeId; }
    public String getNodesJson() { return nodesJson; }
    public void setNodesJson(String nodesJson) { this.nodesJson = nodesJson; }
    public String getOutgoingJson() { return outgoingJson; }
    public void setOutgoingJson(String outgoingJson) { this.outgoingJson = outgoingJson; }
    public String getVariableDefinitionsJson() { return variableDefinitionsJson; }
    public void setVariableDefinitionsJson(String variableDefinitionsJson) { this.variableDefinitionsJson = variableDefinitionsJson; }
}
