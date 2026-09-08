package com.workflow.form;

import java.util.Map;

/**
 * 表单绑定
 * 
 * <p>描述表单与流程节点的绑定关系，以及表单数据与流程变量的映射。
 */
public class FormBinding {
    
    /** 绑定 ID */
    private final String id;
    
    /** 流程定义 key */
    private final String processKey;
    
    /** 节点 ID */
    private final String nodeId;
    
    /** 表单 ID */
    private final String formId;
    
    /** 字段到流程变量的映射（fieldId -> variableName） */
    private final Map<String, String> fieldToVariableMapping;
    
    /** 流程变量到字段的映射（variableName -> fieldId） */
    private final Map<String, String> variableToFieldMapping;
    
    /** 表单提交时的操作类型 */
    private final SubmitAction submitAction;
    
    public FormBinding(String id, String processKey, String nodeId, String formId,
                      Map<String, String> fieldToVariableMapping,
                      Map<String, String> variableToFieldMapping,
                      SubmitAction submitAction) {
        this.id = id;
        this.processKey = processKey;
        this.nodeId = nodeId;
        this.formId = formId;
        this.fieldToVariableMapping = fieldToVariableMapping;
        this.variableToFieldMapping = variableToFieldMapping;
        this.submitAction = submitAction;
    }
    
    public String getId() {
        return id;
    }
    
    public String getProcessKey() {
        return processKey;
    }
    
    public String getNodeId() {
        return nodeId;
    }
    
    public String getFormId() {
        return formId;
    }
    
    public Map<String, String> getFieldToVariableMapping() {
        return fieldToVariableMapping;
    }
    
    public Map<String, String> getVariableToFieldMapping() {
        return variableToFieldMapping;
    }
    
    public SubmitAction getSubmitAction() {
        return submitAction;
    }
    
    /**
     * 表单提交时的操作类型
     */
    public enum SubmitAction {
        COMPLETE_TASK,    // 完成任务
        SAVE_DRAFT,       // 保存草稿
        REJECT_TASK       // 驳回任务
    }
}
