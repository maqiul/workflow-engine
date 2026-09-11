package com.workflow.delegate;

import com.workflow.definition.ProcessDefinition;

import java.util.Collections;
import java.util.Map;

/**
 * 服务任务执行上下文 - 提供 delegate 执行时的流程实例信息
 * 
 * <p>这是 delegate 执行时的"只读视图"，提供：
 * <ul>
 *   <li>流程实例 ID</li>
 *   <li>当前节点 ID</li>
 *   <li>流程变量（只读）</li>
 *   <li>流程定义</li>
 * </ul>
 * 
 * <p>设计原则：
 * <ul>
 *   <li>只读：delegate 不能修改流程变量，避免副作用</li>
 *   <li>轻量：只暴露必要信息，不暴露引擎内部状态</li>
 *   <li>不可变：所有字段 final，构造后不可修改</li>
 * </ul>
 */
public final class DelegateExecution {
    
    private final String instanceId;
    private final String currentNodeId;
    private final Map<String, Object> variables;
    private final ProcessDefinition processDefinition;
    
    public DelegateExecution(String instanceId, String currentNodeId, 
                            Map<String, Object> variables, ProcessDefinition processDefinition) {
        this.instanceId = instanceId;
        this.currentNodeId = currentNodeId;
        this.variables = variables != null ? Collections.unmodifiableMap(variables) : Collections.emptyMap();
        this.processDefinition = processDefinition;
    }
    
    /**
     * 获取流程实例 ID
     */
    public String getInstanceId() {
        return instanceId;
    }
    
    /**
     * 获取当前节点 ID
     */
    public String getCurrentNodeId() {
        return currentNodeId;
    }
    
    /**
     * 获取流程变量（只读）
     * 
     * @return 不可变的变量 Map
     */
    public Map<String, Object> getVariables() {
        return variables;
    }
    
    /**
     * 获取单个流程变量
     * 
     * @param name 变量名
     * @return 变量值，不存在返回 null
     */
    public Object getVariable(String name) {
        return variables.get(name);
    }
    
    /**
     * 获取流程定义
     */
    public ProcessDefinition getProcessDefinition() {
        return processDefinition;
    }
    
    @Override
    public String toString() {
        return "DelegateExecution{" +
                "instanceId='" + instanceId + '\'' +
                ", currentNodeId='" + currentNodeId + '\'' +
                ", variables=" + variables +
                '}';
    }
}
