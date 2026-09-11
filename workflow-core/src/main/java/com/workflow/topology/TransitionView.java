package com.workflow.topology;

import java.util.Objects;

/**
 * 连线（转移）只读视图
 */
public final class TransitionView {
    
    private final String from;
    private final String to;
    private final String condition;  // 条件表达式（可为 null）
    
    public TransitionView(String from, String to, String condition) {
        this.from = Objects.requireNonNull(from);
        this.to = Objects.requireNonNull(to);
        this.condition = condition;
    }
    
    public String getFrom() { return from; }
    public String getTo() { return to; }
    public String getCondition() { return condition; }
    
    @Override
    public String toString() {
        return "TransitionView{" + from + " -> " + to + 
               (condition != null ? " [" + condition + "]" : "") + "}";
    }
}
