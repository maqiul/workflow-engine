package com.workflow.engine;

import com.workflow.form.FormValidator;

import java.util.List;
import java.util.Map;

/**
 * 表单提交结果
 */
public class FormSubmitResult {
    
    /** 是否成功 */
    private final boolean success;
    
    /** 验证错误列表（如果有） */
    private final List<FormValidator.ValidationError> validationErrors;
    
    /** 映射到流程变量的数据 */
    private final Map<String, Object> mappedVariables;
    
    /** 错误消息（如果有） */
    private final String errorMessage;
    
    private FormSubmitResult(boolean success, List<FormValidator.ValidationError> validationErrors,
                            Map<String, Object> mappedVariables, String errorMessage) {
        this.success = success;
        this.validationErrors = validationErrors;
        this.mappedVariables = mappedVariables;
        this.errorMessage = errorMessage;
    }
    
    /**
     * 创建成功结果
     */
    public static FormSubmitResult success(Map<String, Object> mappedVariables) {
        return new FormSubmitResult(true, null, mappedVariables, null);
    }
    
    /**
     * 创建验证失败结果
     */
    public static FormSubmitResult validationFailed(List<FormValidator.ValidationError> errors) {
        return new FormSubmitResult(false, errors, null, "表单验证失败");
    }
    
    /**
     * 创建错误结果
     */
    public static FormSubmitResult error(String message) {
        return new FormSubmitResult(false, null, null, message);
    }
    
    public boolean isSuccess() {
        return success;
    }
    
    public List<FormValidator.ValidationError> getValidationErrors() {
        return validationErrors;
    }
    
    public Map<String, Object> getMappedVariables() {
        return mappedVariables;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
}
