package com.workflow.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * DMN 决策历史实体
 */
@Entity
@Table(name = "wf_decision_history")
public class WfDecisionHistoryEntity {
    
    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;
    
    @Column(name = "instance_id", length = 64, nullable = false)
    private String instanceId;
    
    @Column(name = "token_id", length = 64)
    private String tokenId;
    
    @Column(name = "node_id", length = 64, nullable = false)
    private String nodeId;
    
    @Column(name = "decision_table_id", length = 64, nullable = false)
    private String decisionTableId;
    
    @Column(name = "inputs_json", columnDefinition = "TEXT")
    private String inputsJson;
    
    @Column(name = "outputs_json", columnDefinition = "TEXT")
    private String outputsJson;
    
    @Column(name = "matched_rule_id", length = 64)
    private String matchedRuleId;
    
    @Column(name = "executed_at")
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
