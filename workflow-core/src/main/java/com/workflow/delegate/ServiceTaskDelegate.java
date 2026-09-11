package com.workflow.delegate;

/**
 * 服务任务委托接口 - 自动节点的执行逻辑
 * 
 * <p>用于实现流程中的自动执行节点，如：
 * <ul>
 *   <li>自动抄送通知</li>
 *   <li>调用外部系统接口</li>
 *   <li>数据转换处理</li>
 *   <li>定时任务触发</li>
 * </ul>
 * 
 * <p>使用方式：
 * <pre>
 * // 1. 实现接口
 * ServiceTaskDelegate sendNotification = execution -> {
 *     String assignee = (String) execution.getVariable("assignee");
 *     System.out.println("发送通知给: " + assignee);
 * };
 * 
 * // 2. 注册到引擎
 * engine.registerDelegate("sendNotification", sendNotification);
 * 
 * // 3. 在流程定义中使用
 * ProcessBuilder.create("leave-flow")
 *     .serviceTask("notify", "发送通知", "sendNotification")
 *     ...
 * </pre>
 * 
 * <p>异常处理：
 * <ul>
 *   <li>delegate 执行成功 → 自动推进到下一节点</li>
 *   <li>delegate 抛异常 → 流程挂起（SUSPENDED），记录错误日志，需人工干预</li>
 *   <li>delegate 未注册 → 抛 IllegalStateException，流程挂起</li>
 * </ul>
 */
@FunctionalInterface
public interface ServiceTaskDelegate {
    
    /**
     * 执行服务任务逻辑
     * 
     * @param execution 执行上下文，提供流程实例信息
     * @throws Exception 执行失败时抛出异常，流程将挂起
     */
    void execute(DelegateExecution execution) throws Exception;
}
