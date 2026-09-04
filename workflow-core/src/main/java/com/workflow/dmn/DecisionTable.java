package com.workflow.dmn;

import java.util.List;
import java.util.Map;

/**
 * DMN 决策表定义
 * 
 * <p>决策表由输入列、输出列和规则组成。
 * 执行时根据输入值匹配规则，返回对应的输出值。
 */
public class DecisionTable {
    
    /** 决策表 ID */
    private final String id;
    
    /** 决策表名称 */
    private final String name;
    
    /** 输入列定义 */
    private final List<InputClause> inputs;
    
    /** 输出列定义 */
    private final List<OutputClause> outputs;
    
    /** 规则列表 */
    private final List<DecisionRule> rules;
    
    /** 命中策略 */
    private final HitPolicy hitPolicy;
    
    public DecisionTable(String id, String name, List<InputClause> inputs,
                        List<OutputClause> outputs, List<DecisionRule> rules,
                        HitPolicy hitPolicy) {
        this.id = id;
        this.name = name;
        this.inputs = inputs;
        this.outputs = outputs;
        this.rules = rules;
        this.hitPolicy = hitPolicy;
    }
    
    public String getId() {
        return id;
    }
    
    public String getName() {
        return name;
    }
    
    public List<InputClause> getInputs() {
        return inputs;
    }
    
    public List<OutputClause> getOutputs() {
        return outputs;
    }
    
    public List<DecisionRule> getRules() {
        return rules;
    }
    
    public HitPolicy getHitPolicy() {
        return hitPolicy;
    }
    
    /**
     * 输入列定义
     */
    public static class InputClause {
        private final String name;
        private final String expression;
        
        public InputClause(String name, String expression) {
            this.name = name;
            this.expression = expression;
        }
        
        public String getName() {
            return name;
        }
        
        public String getExpression() {
            return expression;
        }
    }
    
    /**
     * 输出列定义
     */
    public static class OutputClause {
        private final String name;
        private final String typeRef;
        
        public OutputClause(String name, String typeRef) {
            this.name = name;
            this.typeRef = typeRef;
        }
        
        public String getName() {
            return name;
        }
        
        public String getTypeRef() {
            return typeRef;
        }
    }
    
    /**
     * 决策规则
     */
    public static class DecisionRule {
        private final String id;
        private final List<String> inputEntries;
        private final List<String> outputEntries;
        private final int priority;
        
        public DecisionRule(String id, List<String> inputEntries,
                          List<String> outputEntries, int priority) {
            this.id = id;
            this.inputEntries = inputEntries;
            this.outputEntries = outputEntries;
            this.priority = priority;
        }
        
        public String getId() {
            return id;
        }
        
        public List<String> getInputEntries() {
            return inputEntries;
        }
        
        public List<String> getOutputEntries() {
            return outputEntries;
        }
        
        public int getPriority() {
            return priority;
        }
    }
    
    /**
     * 命中策略
     */
    public enum HitPolicy {
        /** 唯一命中 - 只有一条规则匹配 */
        UNIQUE,
        /** 首先命中 - 返回第一条匹配的规则 */
        FIRST,
        /** 优先级 - 返回优先级最高的匹配规则 */
        PRIORITY,
        /** 所有命中 - 返回所有匹配的规则 */
        ALL,
        /** 收集 - 收集所有匹配规则的输出 */
        COLLECT
    }
}
