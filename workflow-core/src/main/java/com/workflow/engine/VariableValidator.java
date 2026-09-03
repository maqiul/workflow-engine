package com.workflow.engine;

import com.workflow.definition.ProcessDefinition;
import com.workflow.definition.VariableDefinition;
import com.workflow.definition.VariableType;

import java.util.List;
import java.util.Map;

/**
 * 变量校验器 - 校验流程变量是否符合定义
 */
public class VariableValidator {

    /**
     * 校验流程变量
     *
     * @param def 流程定义
     * @param variables 实际传入的变量
     * @throws IllegalArgumentException 如果变量不符合定义
     */
    public static void validate(ProcessDefinition def, Map<String, Object> variables) {
        List<VariableDefinition> varDefs = def.getVariableDefinitions();
        if (varDefs == null || varDefs.isEmpty()) {
            return;  // 没有定义变量 schema，跳过校验
        }

        for (VariableDefinition varDef : varDefs) {
            Object value = variables != null ? variables.get(varDef.getName()) : null;

            // 1. 必填校验
            if (varDef.isRequired() && value == null && varDef.getDefaultValue() == null) {
                throw new IllegalArgumentException(
                        String.format("流程变量 [%s] 是必填项，但未提供且无默认值", varDef.getName()));
            }

            // 2. 如果有默认值且未提供，使用默认值
            if (value == null && varDef.getDefaultValue() != null) {
                if (variables != null) {
                    variables.put(varDef.getName(), varDef.getDefaultValue());
                }
                continue;
            }

            // 3. 类型校验
            if (value != null) {
                validateType(varDef.getName(), value, varDef.getType());
            }
        }
    }

    /**
     * 校验单个变量的类型
     */
    private static void validateType(String name, Object value, VariableType expectedType) {
        boolean valid = false;

        switch (expectedType) {
            case STRING:
                valid = value instanceof String;
                break;
            case INTEGER:
                valid = value instanceof Integer || value instanceof Long;
                break;
            case LONG:
                valid = value instanceof Long || value instanceof Integer;
                break;
            case DOUBLE:
                valid = value instanceof Double || value instanceof Float ||
                        value instanceof Integer || value instanceof Long;
                break;
            case BOOLEAN:
                valid = value instanceof Boolean;
                break;
            case OBJECT:
                valid = true;  // 任意对象都接受
                break;
        }

        if (!valid) {
            throw new IllegalArgumentException(
                    String.format("流程变量 [%s] 类型不匹配：期望 %s，实际 %s",
                            name, expectedType, value.getClass().getSimpleName()));
        }
    }
}
