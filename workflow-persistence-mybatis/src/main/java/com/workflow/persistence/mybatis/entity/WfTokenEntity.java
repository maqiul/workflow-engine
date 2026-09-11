package com.workflow.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.workflow.enums.TokenStatus;

/**
 * Token 实体(MyBatis-Plus 版)
 *
 * 表结构:
 *   wf_token
 *     id              VARCHAR(64)  PRIMARY KEY
 *     instance_id     VARCHAR(64)  -- 冗余字段,便于单表查询
 *     current_node_id VARCHAR(64)
 *     status          VARCHAR(16)  -- TokenStatus
 */
@TableName("wf_token")
public class WfTokenEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("instance_id")
    private String instanceId;

    @TableField("current_node_id")
    private String currentNodeId;

    @TableField("arrival")
    private int arrival;

    @TableField("status")
    private TokenStatus status;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public String getCurrentNodeId() { return currentNodeId; }
    public void setCurrentNodeId(String currentNodeId) { this.currentNodeId = currentNodeId; }
    public int getArrival() { return arrival; }
    public void setArrival(int arrival) { this.arrival = arrival; }
    public TokenStatus getStatus() { return status; }
    public void setStatus(TokenStatus status) { this.status = status; }
}
