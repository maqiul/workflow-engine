package com.workflow.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.workflow.enums.InstanceStatus;

/**
 * 流程实例实体(MyBatis-Plus 版)
 *
 * 表结构:
 *   wf_instance
 *     id               VARCHAR(64)  PRIMARY KEY
 *     process_key      VARCHAR(64)
 *     process_version  INT          -- 实例固化版本,0=取最新
 *     status           VARCHAR(16)  -- InstanceStatus 枚举
 *     create_time      BIGINT
 *     end_time         BIGINT
 *     variables_json   CLOB         -- fastjson2 序列化的 Map<String,Object>
 */
@TableName("wf_instance")
public class WfInstanceEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("process_key")
    private String processKey;

    @TableField("process_version")
    private int processVersion;

    @TableField("status")
    private InstanceStatus status;

    @TableField("create_time")
    private long createTime;

    @TableField("end_time")
    private Long endTime;

    @TableField("variables_json")
    private String variablesJson;

    /** 父流程实例 id - 子流程实例有值 */
    @TableField("parent_instance_id")
    private String parentInstanceId;

    /** 父流程中等待该子流程的 Token id */
    @TableField("parent_token_id")
    private String parentTokenId;

    /** 父流程中发起该子流程的 SUB_PROCESS 节点 id */
    @TableField("parent_node_id")
    private String parentNodeId;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProcessKey() { return processKey; }
    public void setProcessKey(String processKey) { this.processKey = processKey; }
    public int getProcessVersion() { return processVersion; }
    public void setProcessVersion(int processVersion) { this.processVersion = processVersion; }
    public InstanceStatus getStatus() { return status; }
    public void setStatus(InstanceStatus status) { this.status = status; }
    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }
    public Long getEndTime() { return endTime; }
    public void setEndTime(Long endTime) { this.endTime = endTime; }
    public String getVariablesJson() { return variablesJson; }
    public void setVariablesJson(String variablesJson) { this.variablesJson = variablesJson; }
    public String getParentInstanceId() { return parentInstanceId; }
    public void setParentInstanceId(String parentInstanceId) { this.parentInstanceId = parentInstanceId; }
    public String getParentTokenId() { return parentTokenId; }
    public void setParentTokenId(String parentTokenId) { this.parentTokenId = parentTokenId; }
    public String getParentNodeId() { return parentNodeId; }
    public void setParentNodeId(String parentNodeId) { this.parentNodeId = parentNodeId; }
}
