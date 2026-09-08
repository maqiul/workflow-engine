package com.workflow.form;

import java.util.List;
import java.util.Map;

/**
 * 表单字段定义
 * 
 * <p>描述单个表单字段的属性，包括类型、验证规则、默认值等。
 */
public class FormField {
    
    /** 字段 ID */
    private final String id;
    
    /** 字段标签 */
    private final String label;
    
    /** 字段类型 */
    private final FieldType type;
    
    /** 是否必填 */
    private final boolean required;
    
    /** 默认值 */
    private final Object defaultValue;
    
    /** 占位符文本 */
    private final String placeholder;
    
    /** 字段提示 */
    private final String hint;
    
    /** 验证规则 */
    private final List<ValidationRule> validations;
    
    /** 选项列表（用于 select/radio/checkbox 类型） */
    private final List<FieldOption> options;
    
    /** 字段属性（如最大长度、最小值等） */
    private final Map<String, Object> properties;
    
    /** 关联的流程变量名（用于数据映射） */
    private final String variableName;
    
    public FormField(String id, String label, FieldType type, boolean required,
                    Object defaultValue, String placeholder, String hint,
                    List<ValidationRule> validations, List<FieldOption> options,
                    Map<String, Object> properties, String variableName) {
        this.id = id;
        this.label = label;
        this.type = type;
        this.required = required;
        this.defaultValue = defaultValue;
        this.placeholder = placeholder;
        this.hint = hint;
        this.validations = validations;
        this.options = options;
        this.properties = properties;
        this.variableName = variableName;
    }
    
    public String getId() {
        return id;
    }
    
    public String getLabel() {
        return label;
    }
    
    public FieldType getType() {
        return type;
    }
    
    public boolean isRequired() {
        return required;
    }
    
    public Object getDefaultValue() {
        return defaultValue;
    }
    
    public String getPlaceholder() {
        return placeholder;
    }
    
    public String getHint() {
        return hint;
    }
    
    public List<ValidationRule> getValidations() {
        return validations;
    }
    
    public List<FieldOption> getOptions() {
        return options;
    }
    
    public Map<String, Object> getProperties() {
        return properties;
    }
    
    public String getVariableName() {
        return variableName;
    }
    
    /**
     * 字段类型枚举
     */
    public enum FieldType {
        TEXT,           // 文本输入
        TEXTAREA,       // 多行文本
        NUMBER,         // 数字
        DATE,           // 日期
        DATETIME,       // 日期时间
        SELECT,         // 下拉选择
        RADIO,          // 单选
        CHECKBOX,       // 复选框
        FILE,           // 文件上传
        IMAGE,          // 图片上传
        RICH_TEXT       // 富文本
    }
}
