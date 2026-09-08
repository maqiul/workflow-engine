package com.workflow.form;

import java.util.List;
import java.util.Map;

/**
 * 表单定义
 * 
 * <p>描述表单的结构，包括字段定义、布局、验证规则等。
 */
public class FormDefinition {
    
    /** 表单 ID */
    private final String id;
    
    /** 表单名称 */
    private final String name;
    
    /** 表单描述 */
    private final String description;
    
    /** 表单版本 */
    private final int version;
    
    /** 表单字段列表 */
    private final List<FormField> fields;
    
    /** 表单布局（可选） */
    private final FormLayout layout;
    
    /** 表单样式（可选） */
    private final Map<String, String> styles;
    
    public FormDefinition(String id, String name, String description, int version,
                         List<FormField> fields, FormLayout layout, Map<String, String> styles) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.version = version;
        this.fields = fields;
        this.layout = layout;
        this.styles = styles;
    }
    
    public String getId() {
        return id;
    }
    
    public String getName() {
        return name;
    }
    
    public String getDescription() {
        return description;
    }
    
    public int getVersion() {
        return version;
    }
    
    public List<FormField> getFields() {
        return fields;
    }
    
    public FormLayout getLayout() {
        return layout;
    }
    
    public Map<String, String> getStyles() {
        return styles;
    }
}
