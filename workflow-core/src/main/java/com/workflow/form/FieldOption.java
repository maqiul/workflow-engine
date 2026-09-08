package com.workflow.form;

/**
 * 表单字段选项（用于 select/radio/checkbox 类型）
 */
public class FieldOption {
    
    /** 选项值 */
    private final Object value;
    
    /** 选项标签 */
    private final String label;
    
    /** 是否默认选中 */
    private final boolean selected;
    
    /** 是否禁用 */
    private final boolean disabled;
    
    public FieldOption(Object value, String label, boolean selected, boolean disabled) {
        this.value = value;
        this.label = label;
        this.selected = selected;
        this.disabled = disabled;
    }
    
    public FieldOption(Object value, String label) {
        this(value, label, false, false);
    }
    
    public Object getValue() {
        return value;
    }
    
    public String getLabel() {
        return label;
    }
    
    public boolean isSelected() {
        return selected;
    }
    
    public boolean isDisabled() {
        return disabled;
    }
}
