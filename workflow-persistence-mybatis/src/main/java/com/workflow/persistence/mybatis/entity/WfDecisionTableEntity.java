package com.workflow.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * DMN 决策表实体（MyBatis 版）
 */
@TableName("wf_decision_table")
public class WfDecisionTableEntity {
    
    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    
    @TableField("name")
    private String name;
    
    @TableField("inputs_json")
    private String inputsJson;
    
    @TableField("outputs_json")
    private String outputsJson;
    
    @TableField("rules_json")
    private String rulesJson;
    
    @TableField("hit_policy")
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
