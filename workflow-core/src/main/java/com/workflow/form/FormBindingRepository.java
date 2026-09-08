package com.workflow.form;

import java.util.List;

/**
 * 表单绑定仓储接口
 * 
 * <p>管理表单与流程节点绑定关系的存储和检索。
 */
public interface FormBindingRepository {
    
    /**
     * 保存表单绑定
     */
    void save(FormBinding binding);
    
    /**
     * 根据 ID 查找表单绑定
     */
    FormBinding findById(String id);
    
    /**
     * 根据流程 key 和节点 ID 查找表单绑定
     */
    FormBinding findByProcessAndNode(String processKey, String nodeId);
    
    /**
     * 根据流程 key 查找所有表单绑定
     */
    List<FormBinding> findByProcessKey(String processKey);
    
    /**
     * 根据表单 ID 查找所有绑定
     */
    List<FormBinding> findByFormId(String formId);
    
    /**
     * 删除表单绑定
     */
    void delete(String id);
    
    /**
     * 删除流程节点的所有绑定
     */
    void deleteByProcessAndNode(String processKey, String nodeId);
}
