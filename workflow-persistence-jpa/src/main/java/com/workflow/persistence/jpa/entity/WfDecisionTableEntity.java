package com.workflow.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * DMN 决策表实体
 */
@Entity
@Table(name = "wf_decision_table")
public class WfDecisionTableEntity {
    
    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;
    
    @Column(name = "name", length = 256)
    private String name;
    
    @Column(name = "inputs_json", columnDefinition = "TEXT")
    private String inputsJson;
    
    @Column(name = "outputs_json", columnDefinition = "TEXT")
    private String outputsJson;
    
    @Column(name = "rules_json", columnDefinition = "TEXT")
    private String rulesJson;
    
    @Column(name = "hit_policy", length = 32)
    private String hitPolicy;
    
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getInputsJson() { return inputsJson; }
    public void setInputsJson(String inputsJson) { this.inputsJson = inputsJson; }
    
    public String getOutputsJson() { return outputsJson; }
    public void setOutputsJson(String outputsJson) { this.outputsJson = outputsJson; }
    
    public String getRulesJson() { return rulesJson; }
    public void setRulesJson(String rulesJson) { this.rulesJson = rulesJson; }
    
    public String getHitPolicy() { return hitPolicy; }
    public void setHitPolicy(String hitPolicy) { this.hitPolicy = hitPolicy; }
}
