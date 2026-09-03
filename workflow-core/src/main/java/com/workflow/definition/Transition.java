package com.workflow.definition;

import java.util.Objects;

/**
 * 转移连线 - 节点间的有向边
 * 不可变,用于 DSL 编译期固化流程拓扑
 */
public final class Transition {
    private final String from;
    private final String to;
    /** 条件表达式键(spEL/OGNL/简单变量),null 表示无条件 */
    private final String condition;

    public Transition(String from, String to, String condition) {
        this.from = Objects.requireNonNull(from, "from 不能为空");
        this.to = Objects.requireNonNull(to, "to 不能为空");
        this.condition = condition;
    }

    public Transition(String from, String to) {
        this(from, to, null);
    }

    public String getFrom() { return from; }
    public String getTo() { return to; }
    public String getCondition() { return condition; }

    @Override
    public String toString() {
        return from + " -> " + to + (condition != null ? " [when " + condition + "]" : "");
    }
}