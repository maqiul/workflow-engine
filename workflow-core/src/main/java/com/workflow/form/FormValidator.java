package com.workflow.form;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 表单验证器
 * 
 * <p>根据表单定义验证提交的数据。
 */
public class FormValidator {
    
    /**
     * 验证表单数据
     * 
     * @param form 表单定义
     * @param data 提交的数据
     * @return 验证结果
     */
    public ValidationResult validate(FormDefinition form, Map<String, Object> data) {
        List<ValidationError> errors = new ArrayList<>();
        
        for (FormField field : form.getFields()) {
            Object value = data.get(field.getId());
            
            // 必填验证
            if (field.isRequired() && (value == null || "".equals(value.toString().trim()))) {
                errors.add(new ValidationError(field.getId(), "该字段为必填项"));
                continue;
            }
            
            // 如果有值，进行其他验证
            if (value != null && !"".equals(value.toString().trim())) {
                // 类型验证
                if (!validateType(field.getType(), value)) {
                    errors.add(new ValidationError(field.getId(), 
                            "字段类型不匹配，期望: " + field.getType()));
                    continue;
                }
                
                // 自定义验证规则
                if (field.getValidations() != null) {
                    for (ValidationRule rule : field.getValidations()) {
                        if (!validateRule(rule, value)) {
                            errors.add(new ValidationError(field.getId(), rule.getErrorMessage()));
                        }
                    }
                }
            }
        }
        
        return new ValidationResult(errors.isEmpty(), errors);
    }
    
    /**
     * 验证字段类型
     */
    private boolean validateType(FormField.FieldType type, Object value) {
        String strValue = value.toString();
        
        switch (type) {
            case NUMBER:
                try {
                    Double.parseDouble(strValue);
                    return true;
                } catch (NumberFormatException e) {
                    return false;
                }
            case DATE:
                // 简单日期格式验证 YYYY-MM-DD
                return Pattern.matches("\\d{4}-\\d{2}-\\d{2}", strValue);
            case DATETIME:
                // 简单日期时间格式验证 YYYY-MM-DD HH:mm:ss
                return Pattern.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}", strValue);
            default:
                return true;
        }
    }
    
    /**
     * 验证单条规则
     */
    private boolean validateRule(ValidationRule rule, Object value) {
        String strValue = value.toString();
        
        switch (rule.getType()) {
            case REQUIRED:
                return value != null && !"".equals(strValue.trim());
            case MIN_LENGTH:
                int minLength = (Integer) rule.getParameter();
                return strValue.length() >= minLength;
            case MAX_LENGTH:
                int maxLength = (Integer) rule.getParameter();
                return strValue.length() <= maxLength;
            case MIN_VALUE:
                double minValue = ((Number) rule.getParameter()).doubleValue();
                try {
                    return Double.parseDouble(strValue) >= minValue;
                } catch (NumberFormatException e) {
                    return false;
                }
            case MAX_VALUE:
                double maxValue = ((Number) rule.getParameter()).doubleValue();
                try {
                    return Double.parseDouble(strValue) <= maxValue;
                } catch (NumberFormatException e) {
                    return false;
                }
            case PATTERN:
                String pattern = (String) rule.getParameter();
                return Pattern.matches(pattern, strValue);
            case EMAIL:
                return Pattern.matches("^[A-Za-z0-9+_.-]+@(.+)$", strValue);
            case PHONE:
                return Pattern.matches("^1[3-9]\\d{9}$", strValue);
            case URL:
                return Pattern.matches("^(https?|ftp)://[^\\s/$.?#].[^\\s]*$", strValue);
            default:
                return true;
        }
    }
    
    /**
     * 验证结果
     */
    public static class ValidationResult {
        private final boolean valid;
        private final List<ValidationError> errors;
        
        public ValidationResult(boolean valid, List<ValidationError> errors) {
            this.valid = valid;
            this.errors = errors;
        }
        
        public boolean isValid() {
            return valid;
        }
        
        public List<ValidationError> getErrors() {
            return errors;
        }
    }
    
    /**
     * 验证错误
     */
    public static class ValidationError {
        private final String fieldId;
        private final String message;
        
        public ValidationError(String fieldId, String message) {
            this.fieldId = fieldId;
            this.message = message;
        }
        
        public String getFieldId() {
            return fieldId;
        }
        
        public String getMessage() {
            return message;
        }
    }
}
