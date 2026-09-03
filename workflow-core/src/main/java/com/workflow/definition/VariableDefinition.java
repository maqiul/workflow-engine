package com.workflow.definition;

import com.alibaba.fastjson2.annotation.JSONCreator;
import com.alibaba.fastjson2.annotation.JSONField;

import java.util.Objects;

/**
 * 变量定义 - 描述流程变量的类型、默认值、是否必填等元数据
 *
 * 不可变对象，创建后字段不可修改。
 */
public final class VariableDefinition {
    private final String name;
    private final VariableType type;
    private final boolean required;
    private final Object defaultValue;
    private final String description;

    private VariableDefinition(Builder builder) {
        this.name = Objects.requireNonNull(builder.name, "变量名不能为空");
        this.type = Objects.requireNonNull(builder.type, "变量类型不能为空");
        this.required = builder.required;
        this.defaultValue = builder.defaultValue;
        this.description = builder.description;
    }

    /**
     * 反序列化构造器 - 供 fastjson2 使用
     */
    @JSONCreator
    private VariableDefinition(
            @JSONField(name = "name") String name,
            @JSONField(name = "type") VariableType type,
            @JSONField(name = "required") boolean required,
            @JSONField(name = "defaultValue") Object defaultValue,
            @JSONField(name = "description") String description) {
        this.name = Objects.requireNonNull(name, "变量名不能为空");
        this.type = Objects.requireNonNull(type, "变量类型不能为空");
        this.required = required;
        this.defaultValue = defaultValue;
        this.description = description;
    }

    public String getName() { return name; }
    public VariableType getType() { return type; }
    public boolean isRequired() { return required; }
    public Object getDefaultValue() { return defaultValue; }
    public String getDescription() { return description; }

    public static Builder builder(String name, VariableType type) {
        return new Builder(name, type);
    }

    public static class Builder {
        private final String name;
        private final VariableType type;
        private boolean required = false;
        private Object defaultValue = null;
        private String description = null;

        private Builder(String name, VariableType type) {
            this.name = name;
            this.type = type;
        }

        public Builder required(boolean required) {
            this.required = required;
            return this;
        }

        public Builder defaultValue(Object defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public VariableDefinition build() {
            return new VariableDefinition(this);
        }
    }

    @Override
    public String toString() {
        return String.format("VariableDefinition[name=%s, type=%s, required=%s]",
                name, type, required);
    }
}
