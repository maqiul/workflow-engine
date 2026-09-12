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
     * 运行期校验单个变量 —— 只校类型，<b>不做必填校验</b>。
     *
     * <p>与 {@link #validate} 的区别：后者是<b>启动期</b>的整体校验，会检查必填。
     * 运行期改一个 key 时，定义里的其它必填项早已存在于实例中，拿整体 schema 再
     * 校一遍只会把「只想改金额」变成「必须把全部变量再传一次」。
     *
     * <p>未在定义中声明的变量<b>放行</b>：运行时变量本就允许临时出现 ——
     * 循环标记、动态办理人来源等都不在 schema 里。
     *
     * @param def   流程定义；null 表示无从校验，直接放行
     * @param name  变量名
     * @param value 变量值；null 放行（视为清除该变量）
     */
    public static void validateOne(ProcessDefinition def, String name, Object value) {
        if (def == null || name == null || value == null) {
            return;
        }
        List<VariableDefinition> varDefs = def.getVariableDefinitions();
        if (varDefs == null || varDefs.isEmpty()) {
            return;
        }
        for (VariableDefinition varDef : varDefs) {
            if (name.equals(varDef.getName())) {
                validateType(name, value, varDef.getType());
                return;
            }
        }
        // 定义里没声明这个变量 —— 放行
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
