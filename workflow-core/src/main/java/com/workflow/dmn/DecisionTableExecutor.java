package com.workflow.dmn;

import com.workflow.engine.ConditionEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DMN 决策表执行器
 * 
 * <p>根据输入上下文执行决策表，返回匹配的输出结果。
 */
public class DecisionTableExecutor {
    
    private static final Logger log = LoggerFactory.getLogger(DecisionTableExecutor.class);
    
    /**
     * 执行决策表
     * 
     * @param decisionTable 决策表定义
     * @param context 输入上下文（流程变量）
     * @return 决策结果
     */
    public DecisionResult execute(DecisionTable decisionTable, Map<String, Object> context) {
        log.debug("Executing decision table: {}", decisionTable.getName());
        
        List<Map<String, Object>> matchedOutputs = new ArrayList<>();
        
        // 遍历所有规则
        for (DecisionTable.DecisionRule rule : decisionTable.getRules()) {
            if (matchesRule(rule, decisionTable.getInputs(), context)) {
                Map<String, Object> output = extractOutput(rule, decisionTable.getOutputs());
                matchedOutputs.add(output);
                
                // 根据命中策略决定是否继续
                if (decisionTable.getHitPolicy() == DecisionTable.HitPolicy.FIRST) {
                    log.debug("FIRST hit policy, stopping after first match");
                    break;
                }
            }
        }
        
        // 根据命中策略返回结果
        return applyHitPolicy(decisionTable.getHitPolicy(), matchedOutputs);
    }
    
    /**
     * 检查规则是否匹配
     */
    private boolean matchesRule(DecisionTable.DecisionRule rule,
                               List<DecisionTable.InputClause> inputs,
                               Map<String, Object> context) {
        List<String> inputEntries = rule.getInputEntries();
        
        for (int i = 0; i < inputs.size(); i++) {
            DecisionTable.InputClause input = inputs.get(i);
            String entry = inputEntries.get(i);
            
            // "-" 表示通配符，匹配任何值
            if ("-".equals(entry) || entry == null || entry.isEmpty()) {
                continue;
            }
            
            // 获取输入值
            Object inputValue = evaluateInputExpression(input.getExpression(), context);
            
            // 检查是否匹配
            if (!matchesEntry(entry, inputValue, context)) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * 评估输入表达式
     */
    private Object evaluateInputExpression(String expression, Map<String, Object> context) {
        if (expression == null || expression.isEmpty()) {
            return null;
        }
        
        // 简单的变量引用
        if (context.containsKey(expression)) {
            return context.get(expression);
        }
        
        // 尝试作为 FEEL 表达式评估
        try {
            return ConditionEvaluator.eval(expression, context);
        } catch (Exception e) {
            log.warn("Failed to evaluate input expression: {}", expression, e);
            return null;
        }
    }
    
    /**
     * 检查输入值是否匹配条目
     */
    private boolean matchesEntry(String entry, Object inputValue, Map<String, Object> context) {
        if (inputValue == null) {
            return "null".equals(entry) || "nil".equals(entry);
        }
        
        // 尝试作为 FEEL 表达式评估
        try {
            // 创建评估上下文，包含输入值
            Map<String, Object> evalContext = new HashMap<>(context);
            evalContext.put("_input", inputValue);
            
            // 如果条目是简单的比较表达式
            Object result = ConditionEvaluator.eval(entry, evalContext);
            if (result instanceof Boolean) {
                return (Boolean) result;
            }
            
            // 否则尝试直接比较
            return String.valueOf(inputValue).equals(entry);
        } catch (Exception e) {
            // 如果评估失败，尝试字符串比较
            return String.valueOf(inputValue).equals(entry);
        }
    }
    
    /**
     * 提取规则的输出
     */
    private Map<String, Object> extractOutput(DecisionTable.DecisionRule rule,
                                             List<DecisionTable.OutputClause> outputs) {
        Map<String, Object> result = new HashMap<>();
        List<String> outputEntries = rule.getOutputEntries();
        
        for (int i = 0; i < outputs.size(); i++) {
            DecisionTable.OutputClause output = outputs.get(i);
            String entry = outputEntries.get(i);
            
            // 尝试评估表达式
            try {
                Object value = ConditionEvaluator.eval(entry, new HashMap<>());
                result.put(output.getName(), value);
            } catch (Exception e) {
                // 如果评估失败，使用字符串值
                result.put(output.getName(), entry);
            }
        }
        
        return result;
    }
    
    /**
     * 应用命中策略
     */
    private DecisionResult applyHitPolicy(DecisionTable.HitPolicy hitPolicy,
                                         List<Map<String, Object>> matchedOutputs) {
        if (matchedOutputs.isEmpty()) {
            return DecisionResult.noMatch();
        }
        
        switch (hitPolicy) {
            case UNIQUE:
                if (matchedOutputs.size() > 1) {
                    throw new IllegalStateException(
                        "UNIQUE hit policy violated: multiple rules matched");
                }
                return DecisionResult.single(matchedOutputs.get(0));
                
            case FIRST:
            case PRIORITY:
                return DecisionResult.single(matchedOutputs.get(0));
                
            case ALL:
            case COLLECT:
                return DecisionResult.multiple(matchedOutputs);
                
            default:
                throw new IllegalStateException("Unknown hit policy: " + hitPolicy);
        }
    }
    
    /**
     * 决策结果
     */
    public static class DecisionResult {
        private final boolean matched;
        private final List<Map<String, Object>> outputs;
        
        private DecisionResult(boolean matched, List<Map<String, Object>> outputs) {
            this.matched = matched;
            this.outputs = outputs;
        }
        
        public static DecisionResult noMatch() {
            return new DecisionResult(false, new ArrayList<>());
        }
        
        public static DecisionResult single(Map<String, Object> output) {
            List<Map<String, Object>> outputs = new ArrayList<>();
            outputs.add(output);
            return new DecisionResult(true, outputs);
        }
        
        public static DecisionResult multiple(List<Map<String, Object>> outputs) {
            return new DecisionResult(true, outputs);
        }
        
        public boolean isMatched() {
            return matched;
        }
        
        public List<Map<String, Object>> getOutputs() {
            return outputs;
        }
        
        public Map<String, Object> getSingleOutput() {
            if (outputs.isEmpty()) {
                return null;
            }
            return outputs.get(0);
        }
    }
}
