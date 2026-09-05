package com.workflow.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * DMN 决策历史实体（MyBatis 版）
 */
@TableName("wf_decision_history")
public class WfDecisionHistoryEntity {
    
    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    
    @TableField("instance_id")
    private String instanceId;
    
    @TableField("token_id")
    private String tokenId;
    
    @TableField("node_id")
    private String nodeId;
    
    @TableField("decision_table_id")
    private String decisionTableId;
    
    @TableField("inputs_json")
    private String inputsJson;
    
    @TableField("outputs_json")
    private String outputsJson;
    
    @TableField("matched_rule_id")
    private String matchedRuleId;
    
    @TableField("executed_at")
    private Long executedAt;
    
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    
    public String getTokenId() { return tokenId; }
    public void setTokenId(String tokenId) { this.tokenId = tokenId; }
    
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    
    public String getDecisionTableId() { return decisionTableId; }
    public void setDecisionTableId(String decisionTableId) { this.decisionTableId = decisionTableId; }
    
    public String getInputsJson() { return inputsJson; }
    public void setInputsJson(String inputsJson) { this.inputsJson = inputsJson; }
    
    public String getOutputsJson() { return outputsJson; }
    public void setOutputsJson(String outputsJson) { this.outputsJson = outputsJson; }
    
    public String getMatchedRuleId() { return matchedRuleId; }
    public void setMatchedRuleId(String matchedRuleId) { this.matchedRuleId = matchedRuleId; }
    
    public Long getExecutedAt() { return executedAt; }
    public void setExecutedAt(Long executedAt) { this.executedAt = executedAt; }
}
