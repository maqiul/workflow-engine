package com.workflow.engine;

import com.workflow.runtime.CarbonCopy;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;

import java.util.List;
import java.util.Map;

/**
 * 工作流引擎 - 对外统一入口
 *
 * 使用流程:
 *  1. registry.save(definition) - 注册流程定义
 *  2. start(processKey, variables) - 发起一次流程,返回 instanceId
 *  3. completeTask(taskId, userId, approved) - 候选人完成任务
 *  4. rejectTask(taskId, userId, reason) - 候选人驳回
 *  5. transferTask(taskId, fromUserId, toUserId) - 转办
 *  6. suspend/instanceId/resume/terminate - 实例级操作
 */
public interface IWorkflowEngine {

    /** 发起新流程,返回 instanceId */
    String start(String processKey, Map<String, Object> variables);

    /** 发起新流程 - 指定发起人(用于撤回校验) */
    String start(String processKey, String initiator, Map<String, Object> variables);

    /** 发起新流程 - 指定流程定义版本 */
    String start(String processKey, int version, Map<String, Object> variables);

    /** 发起新流程 - 指定流程定义版本 + 发起人 */
    String start(String processKey, int version, String initiator, Map<String, Object> variables);

    /** 查询实例 */
    ProcessInstance getInstance(String instanceId);

    /** 查询任务 */
    TaskInstance getTask(String taskId);

    /** 完成任务 - approved=true 通过;false 表示走驳回 */
    void completeTask(String taskId, String userId, boolean approved);

    /** 驳回任务到上一个 UserTask */
    void rejectTask(String taskId, String userId, String reason);

    /** 转办 */
    void transferTask(String taskId, String fromUserId, String toUserId);

    /** 暂停实例 */
    void suspend(String instanceId);

    /** 恢复实例 */
    void resume(String instanceId);

    /** 终止实例 */
    void terminate(String instanceId);

    /**
     * 撤回流程 - 发起人撤回未审批的申请
     *
     * @param instanceId 流程实例 ID
     * @param initiator  发起人（必须是流程发起人）
     * @throws IllegalArgumentException 如果发起人不是流程发起人，或流程已审批
     */
    void withdraw(String instanceId, String initiator);

    /**
     * 跳转到任意节点 - 将流程实例回退/跳转到指定历史节点
     *
     * <p>会消耗当前所有活跃 Token，终止所有 PENDING 任务，
     * 在目标节点创建新 Token 并推进（如果是 UserTask 则创建新任务）。
     *
     * @param instanceId   流程实例 ID
     * @param targetNodeId 目标节点 ID（必须存在于流程定义中）
     * @param operator     操作人
     * @param reason       跳转原因（可为 null）
     * @throws IllegalArgumentException 如果实例不存在、节点不存在、或实例非 RUNNING 状态
     */
    void jumpToNode(String instanceId, String targetNodeId, String operator, String reason);

    /**
     * 设置委托 - A 委托 B 代为审批（全局委托）
     *
     * @param delegator 委托人
     * @param delegate  代理人
     */
    void delegate(String delegator, String delegate);

    /**
     * 设置委托 - A 委托 B 代为审批（指定节点/流程）
     *
     * @param delegator  委托人
     * @param delegate   代理人
     * @param nodeId     节点 ID（null 表示全局）
     * @param processKey 流程 key（null 表示所有流程）
     */
    void delegate(String delegator, String delegate, String nodeId, String processKey);

    /**
     * 撤销委托
     *
     * @param delegator 委托人
     * @param delegate  代理人
     */
    void revokeDelegate(String delegator, String delegate);

    /**
     * 催办 - 向任务候选人发送催办通知
     *
     * @param taskId    任务 ID
     * @param operator  催办操作人
     * @param reason    催办原因（可为 null）
     */
    void urge(String taskId, String operator, String reason);

    /**
     * 抄送 - 向指定人员发送抄送通知（不需要审批）
     *
     * @param instanceId 流程实例 ID
     * @param taskId     触发抄送的任务 ID（可为 null，表示手动抄送）
     * @param nodeId     触发抄送的节点 ID（可为 null）
     * @param recipients 被抄送人列表
     * @param operator   抄送操作人
     * @param message    抄送附言（可为 null）
     */
    void carbonCopy(String instanceId, String taskId, String nodeId,
                    List<String> recipients, String operator, String message);

    /**
     * 查询指定接收人的抄送列表
     */
    List<CarbonCopy> getCarbonCopies(String recipient);

    /**
     * 查询指定接收人的未读抄送
     */
    List<CarbonCopy> getUnreadCarbonCopies(String recipient);

    /**
     * 标记抄送为已读
     */
    void markCarbonCopyRead(String ccId);

    // ========== 查询能力（供 TaskQuery 等构建器使用） ==========

    /**
     * 全部任务实例。
     *
     * <p>刻意声明为<b>抽象方法</b>，而非「{@code default} 抛
     * {@code UnsupportedOperationException}」—— 后者让缺失实现在编译期完全隐身：
     * 内存版测试全绿，真实仓储一调用就炸。强制实现才能把缺口暴露在编译阶段。
     */
    List<TaskInstance> allTasks();

    /** 全部流程实例（历史与效能查询的基础）。 */
    List<ProcessInstance> allInstances();

    // ========== 批处理 API ==========

    /**
     * 批量完成任务
     * 
     * <p>原子性操作：要么全部成功，要么全部失败。
     * 如果某个任务失败，整个批次回滚。
     *
     * @param taskIds  任务 ID 列表
     * @param userId   操作人
     * @param approved 是否批准
     * @return 批处理结果
     */
    BatchResult batchCompleteTasks(List<String> taskIds, String userId, boolean approved);

    /**
     * 批量终止流程实例
     * 
     * <p>原子性操作：要么全部成功，要么全部失败。
     *
     * @param instanceIds 流程实例 ID 列表
     * @param operator    操作人
     * @param reason      终止原因（可为 null）
     * @return 批处理结果
     */
    BatchResult batchTerminateInstances(List<String> instanceIds, String operator, String reason);

    // ========== 监控 ==========

    /**
     * 生成监控仪表盘快照(只读聚合)。
     *
     * @param bottleneckTopN 瓶颈节点取前 N 个(按平均耗时降序);传 &lt;0 表示不截断
     */
    com.workflow.monitor.DashboardMetrics dashboard(int bottleneckTopN);
}