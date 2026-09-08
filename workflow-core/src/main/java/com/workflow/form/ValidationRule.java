package com.workflow.form;

/**
 * 表单字段验证规则
 */
public class ValidationRule {
    
    /** 验证类型 */
    private final ValidationType type;
    
    /** 验证参数（如最大长度、正则表达式等） */
    private final Object parameter;
    
    /** 验证失败时的错误消息 */
    private final String errorMessage;
    
    public ValidationRule(ValidationType type, Object parameter, String errorMessage) {
        this.type = type;
        this.parameter = parameter;
        this.errorMessage = errorMessage;
    }
    
    public ValidationType getType() {
        return type;
    }
    
    public Object getParameter() {
        return parameter;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    /**
     * 验证类型枚举
     */
    public enum ValidationType {
        REQUIRED,       // 必填
        MIN_LENGTH,     // 最小长度
        MAX_LENGTH,     // 最大长度
        MIN_VALUE,      // 最小值
        MAX_VALUE,      // 最大值
        PATTERN,        // 正则表达式
        EMAIL,          // 邮箱格式
        PHONE,          // 手机号格式
        URL,            // URL 格式
        CUSTOM          // 自定义验证
    }
}
