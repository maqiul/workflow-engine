package com.workflow.form;

import java.util.List;

/**
 * 表单仓储接口
 * 
 * <p>管理表单定义的存储和检索。
 */
public interface FormRepository {
    
    /**
     * 保存表单定义
     */
    void save(FormDefinition form);
    
    /**
     * 根据 ID 查找表单定义
     */
    FormDefinition findById(String id);
    
    /**
     * 根据名称查找表单定义
     */
    FormDefinition findByName(String name);
    
    /**
     * 查找所有表单定义
     */
    List<FormDefinition> findAll();
    
    /**
     * 删除表单定义
     */
    void delete(String id);
    
    /**
     * 检查表单是否存在
     */
    boolean exists(String id);
}
