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
        
        // 设置当前输入定义（供 matchesEntry 访问）
        this.currentInputs = decisionTable.getInputs();
        
        List<Map<String, Object>> matchedOutputs = new ArrayList<>();
        DecisionTable.DecisionRule matchedRule = null;
        
        for (DecisionTable.DecisionRule rule : decisionTable.getRules()) {
            if (matchesRule(rule, decisionTable.getInputs(), context)) {
                Map<String, Object> output = extractOutput(rule, decisionTable.getOutputs());
                matchedOutputs.add(output);
                if (matchedRule == null) {
                    matchedRule = rule;
                }
                
                // 根据命中策略决定是否继续
                if (decisionTable.getHitPolicy() == DecisionTable.HitPolicy.FIRST) {
                    log.debug("FIRST hit policy, stopping after first match");
                    break;
                }
            }
        }
        
        // 根据命中策略返回结果
        DecisionResult result = applyHitPolicy(decisionTable.getHitPolicy(), matchedOutputs);
        
        // 记录匹配的规则 ID（供决策历史使用）
        if (matchedRule != null) {
            result.matchedRuleId = matchedRule.getId();
        }
        
        return result;
    }

    /** 当前正在执行的决策表的输入定义（供 matchesEntry 访问） */
    private List<DecisionTable.InputClause> currentInputs;
    
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
            boolean matches = matchesEntry(entry, inputValue, context);
            log.debug("Rule {} input '{}' = {} matches entry '{}': {}", 
                    rule.getId(), input.getExpression(), inputValue, entry, matches);
            if (!matches) {
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
     * 
     * <p>支持三种匹配模式：
     * <ol>
     *   <li>简单值匹配：如 "vip"，直接比较字符串</li>
     *   <li>范围比较：如 "100..500"，检查输入值是否在范围内</li>
     *   <li>FEEL 表达式：如 "${amount > 1000}"，使用 ConditionEvaluator 评估</li>
     * </ol>
     */
    private boolean matchesEntry(String entry, Object inputValue, Map<String, Object> context) {
        if (inputValue == null) {
            return "null".equals(entry) || "nil".equals(entry);
        }
        
        String trimmed = entry.trim();
        
        // 范围表达式：如 "100..500"、"..100"、"100.."
        if (trimmed.contains("..")) {
            return matchesRange(trimmed, inputValue);
        }
        
        // 简单字符串比较
        if (!trimmed.startsWith("${") && !trimmed.startsWith("(")) {
            String strValue = String.valueOf(inputValue);
            return trimmed.equals(strValue);
        }
        
        // FEEL 表达式：将 _input 替换为字面值后评估
        try {
            String expr = trimmed;
            if (expr.startsWith("${") && expr.endsWith("}")) {
                expr = expr.substring(2, expr.length() - 1).trim();
            }
            // 构造评估上下文，将 _input 设为实际输入值
            Map<String, Object> evalContext = new HashMap<>(context);
            evalContext.put("_input", inputValue);
            // 同时将输入变量也放入上下文（支持直接引用 amount 而非 _input）
            for (DecisionTable.InputClause input : currentInputs) {
                evalContext.put(input.getExpression(), evaluateInputExpression(input.getExpression(), context));
            }
            return ConditionEvaluator.eval(expr, evalContext);
        } catch (Exception e) {
            // 如果评估失败，尝试字符串比较
            return String.valueOf(inputValue).equals(trimmed);
        }
    }

    /**
     * 范围匹配：如 "100..500"、"..100"、"100.."
     */
    private boolean matchesRange(String range, Object inputValue) {
        // 使用 limit=-1 保留末尾的空字符串，否则 "100.." 会被拆成 ["100"]
        String[] parts = range.split("\\.\\.", -1);
        double value = toDouble(inputValue);
        if (parts.length != 2) {
            return false;
        }
        try {
            if (parts[0].isEmpty()) {
                // ..上限
                double upper = Double.parseDouble(parts[1]);
                return value < upper;
            } else if (parts[1].isEmpty()) {
                // 下限..
                double lower = Double.parseDouble(parts[0]);
                return value >= lower;
            } else {
                // 下限..上限
                double lower = Double.parseDouble(parts[0]);
                double upper = Double.parseDouble(parts[1]);
                return value >= lower && value < upper;
            }
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 将对象转为 double
     */
    private double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }
    
    /**
     * 提取规则的输出
     * 
     * <p>输出条目支持：
     * <ul>
     *   <li>字面量字符串："manager" → "manager"</li>
     *   <li>字面量数字：0.2 → 0.2</li>
     *   <li>布尔字面量：true / false</li>
     *   <li>变量引用：${varName}</li>
     * </ul>
     */
    private Map<String, Object> extractOutput(DecisionTable.DecisionRule rule,
                                             List<DecisionTable.OutputClause> outputs) {
        Map<String, Object> result = new HashMap<>();
        List<String> outputEntries = rule.getOutputEntries();
        
        for (int i = 0; i < outputs.size(); i++) {
            DecisionTable.OutputClause output = outputs.get(i);
            String entry = outputEntries.get(i);
            
            result.put(output.getName(), parseOutputValue(entry));
        }
        
        return result;
    }
    
    /**
     * 解析输出值
     * 
     * <p>去除 ${} 包装后按字面量解析：引号字符串、数字、布尔
     */
    private Object parseOutputValue(String entry) {
        if (entry == null) {
            return null;
        }
        
        String trimmed = entry.trim();
        
        // 去除 ${} 包装
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            trimmed = trimmed.substring(2, trimmed.length() - -1).trim();
            if (trimmed.endsWith("}")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
            }
        }
        
        // 字符串字面量：双引号或单引号包裹
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        
        // 布尔字面量
        if (trimmed.equals("true")) return true;
        if (trimmed.equals("false")) return false;
        if (trimmed.equals("null")) return null;
        
        // 数字字面量
        try {
            if (trimmed.contains(".")) {
                return Double.parseDouble(trimmed);
            } else {
                return Long.parseLong(trimmed);
            }
        } catch (NumberFormatException e) {
            // 不是数字，作为字符串返回
            return trimmed;
        }
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
        /** 命中的规则 ID（由 execute 方法填充） */
        String matchedRuleId;
        
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
        
        /** 命中的规则 ID，可能为 null（无匹配时） */
        public String getMatchedRuleId() {
            return matchedRuleId;
        }
    }
}
