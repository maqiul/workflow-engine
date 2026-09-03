package com.workflow.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * 流程定义实体 - 复合主键 (key, version),支持多版本共存
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
@Entity
@IdClass(WfProcessDefPK.class)
@Table(name = "wf_process_def")
public class WfProcessDefEntity {

    @Id
    @Column(name = "key_", length = 64, nullable = false)
    private String key;

    @Id
    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "name", length = 128)
    private String name;

    @Column(name = "start_node_id", length = 64, nullable = false)
    private String startNodeId;

    @Lob
    @Column(name = "nodes_json", nullable = false)
    private String nodesJson;

    @Lob
    @Column(name = "outgoing_json", nullable = false)
    private String outgoingJson;

    @Lob
    @Column(name = "variable_definitions_json")
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
