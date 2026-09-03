package com.workflow.definition;

import java.util.Objects;

/**
 * 变量类型枚举
 */
public enum VariableType {
    STRING,
    INTEGER,
    LONG,
    DOUBLE,
    BOOLEAN,
    OBJECT  // 任意对象（JSON 序列化）
}
