package com.workflow.dmn;

import java.util.Map;

/**
 * 决策历史记录
 * 
 * <p>记录每次决策节点执行的结果，用于审计和回溯。
 */
public class DecisionHistory {
    
    private final String id;
    private final String instanceId;
    private final String tokenId;
    private final String nodeId;
    private final String decisionTableId;
    private final Map<String, Object> inputs;
    private final Map<String, Object> outputs;
    private final String matchedRuleId;
    private final long executedAt;
    
    public DecisionHistory(String id, String instanceId, String tokenId, String nodeId,
                          String decisionTableId, Map<String, Object> inputs,
                          Map<String, Object> outputs, String matchedRuleId,
                          long executedAt) {
        this.id = id;
        this.instanceId = instanceId;
        this.tokenId = tokenId;
        this.nodeId = nodeId;
        this.decisionTableId = decisionTableId;
        this.inputs = inputs;
        this.outputs = outputs;
        this.matchedRuleId = matchedRuleId;
        this.executedAt = executedAt;
    }
    
    public String getId() { return id; }
    public String getInstanceId() { return instanceId; }
    public String getTokenId() { return tokenId; }
    public String getNodeId() { return nodeId; }
    public String getDecisionTableId() { return decisionTableId; }
    public Map<String, Object> getInputs() { return inputs; }
    public Map<String, Object> getOutputs() { return outputs; }
    public String getMatchedRuleId() { return matchedRuleId; }
    public long getExecutedAt() { return executedAt; }
}