package com.workflow.engine;

import com.workflow.enums.CommentType;
import com.workflow.monitor.DashboardMetrics;
import com.workflow.runtime.CarbonCopy;
import com.workflow.runtime.Comment;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import com.workflow.topology.InstanceTopologyView;
import com.workflow.topology.TopologyView;

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

    /**
     * 批量发起流程 - 一次启动多个实例（优化：单事务内批量插入）
     *
     * @param processKey 流程 key
     * @param variablesList 每个实例的变量列表（列表大小即为实例数量）
     * @return 实例 ID 列表（与 variablesList 顺序一致）
     */
    List<String> batchStart(String processKey, List<Map<String, Object>> variablesList);

    /**
     * 批量发起流程 - 指定版本
     *
     * @param processKey 流程 key
     * @param version 流程版本
     * @param variablesList 每个实例的变量列表
     * @return 实例 ID 列表
     */
    List<String> batchStart(String processKey, int version, List<Map<String, Object>> variablesList);

    /** 查询实例 */
    ProcessInstance getInstance(String instanceId);

    /** 查询任务 */
    TaskInstance getTask(String taskId);

    /** 完成任务 - approved=true 通过;false 表示走驳回 */
    void completeTask(String taskId, String userId, boolean approved);

    /**
     * 完成任务并附意见 —— 意见写入与流程推进在<b>同一次引擎事务</b>内完成，
     * 不会出现「意见存了但流程没推进」这类需要补偿收拾的分裂状态。
     *
     * @param comment 审批意见；null 或空白表示不写意见（等同三参重载）
     */
    void completeTask(String taskId, String userId, boolean approved, String comment);

    /**
     * 添加审批意见（普通评论，类型 {@link CommentType#COMMENT}）。
     *
     * <p>意见<b>不随历史保留策略清理</b> —— 它是审批证据，归档导出要求长期保留。
     *
     * @param instanceId 流程实例 ID（必填）
     * @param taskId     关联的待办 ID；null 表示流程级意见（如发起人说明）
     * @param userId     发表人
     * @param message    意见正文；可为 null
     * @return 落库后的意见对象
     * @throws IllegalStateException 未注入 {@code CommentRepository} 时
     */
    Comment addComment(String instanceId, String taskId, String userId, String message);

    /**
     * 添加带类型的审批意见。
     *
     * <p>类型用于检索切片：只看驳回理由取 {@link CommentType#REJECT}，
     * 「这个流程上人说过什么」取全部。
     */
    Comment addComment(String instanceId, String taskId, String userId,
                       CommentType type, String message);

    /** 某张待办上的意见，按时间升序（同毫秒按写入顺序）。 */
    List<Comment> getTaskComments(String taskId);

    /** 某个实例上的全部意见（流程级 + 各任务），按时间升序。 */
    List<Comment> getInstanceComments(String instanceId);

    /**
     * 该部署是否启用了审批意见能力（取决于是否注入 {@code CommentRepository}）。
     *
     * <p>供接入层在调用前探测，把「这个部署没开这个功能」与「这次请求参数错了」
     * 区分成 501 与 400，而不是混在同一个异常里靠消息文本猜。
     */
    boolean supportsComments();

    /** 驳回任务到上一个 UserTask */
    void rejectTask(String taskId, String userId, String reason);

    /** 转办（要求 fromUserId 本身就是该任务的候选人） */
    void transferTask(String taskId, String fromUserId, String toUserId);

    /**
     * 管理员强制改派 —— 绕过候选人校验的兜底通道。
     *
     * <p>用于候选人离职/长期不在、或组织架构故障导致没人能接手的窘境。
     * 引擎<b>不做权限判断</b>（它不知道调用方的权限模型），
     * 调用方须自行确认 {@code operator} 具备管理员权限；{@code operator} 会进审计。
     */
    void adminTransferTask(String taskId, String toUserId, String operator);

    /** 暂停实例 */
    void suspend(String instanceId);

    /** 恢复实例 */
    void resume(String instanceId);

    /** 终止实例 */
    void terminate(String instanceId);

    // ========== 运行期变量 ==========

    /**
     * 修改单个流程变量（运行期）。
     *
     * <p>走引擎的实例锁与事务，改完<b>立即持久化</b>。
     *
     * <p><b>为什么必须有这个 API：</b>{@link #getInstance} 返回的是仓储的副本，
     * 直接对它调 {@code ProcessInstance.setVariable} 改的是副本 —— 看着像成功，
     * 重读就没了。而且 {@code getVariables()} 返回不可变视图，连"直接改 map"
     * 这条歪路也走不通。在拿到本方法之前，调用方要改变量只能绕开引擎直接写仓储，
     * 那是拿锁、事务、审计三者去换一次写操作。
     *
     * @param instanceId 流程实例 ID
     * @param key        变量名；<b>不可使用引擎保留前缀 {@code __}</b>
     * @param value      新值；null 表示清除该变量
     * @param operator   操作人；null/空白记为系统操作
     * @throws IllegalArgumentException 实例不存在、变量名为空、占用保留前缀、
     *                                  或类型不符流程定义中的声明
     * @throws IllegalStateException    实例非 RUNNING / SUSPENDED（已结束的实例不可改）
     */
    void setVariable(String instanceId, String key, Object value, String operator);

    /**
     * 批量修改变量 —— 一次锁、一次事务、一条审计，<b>要么全成、要么全不动</b>。
     *
     * <p>多个变量<b>共同决定一个网关分支</b>时必须用本方法：逐个调用会在中间态
     * 留下「只改了一半」的快照，此刻若有并发推进读到它，分支就走错了。
     *
     * @param variables 变量名 → 新值；不能为 null 或空（空调用是调用方的 bug，
     *                  快速失败好过静默 no-op）
     * @see #setVariable(String, String, Object, String)
     */
    void setVariables(String instanceId, Map<String, Object> variables, String operator);

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
     * 逐支跳转 - 只把「指定 Token 所在的那一条并行分支」移动到目标节点，
     * 其它并行分支的 Token 与待办不受影响。用于并行区内单支回退。
     *
     * <p>与 {@link #jumpToNode} 的区别：jumpToNode 消耗实例全部 Token（整实例回退）；
     * 本方法只处理 {@code tokenId} 这一支：终止该支当前待办、把该 Token 移到目标节点并推进。
     *
     * @param instanceId   流程实例 ID
     * @param tokenId      要跳转的 Token（须为该实例的活跃 Token）
     * @param targetNodeId 目标节点 ID
     * @param operator     操作人
     * @param reason       原因（可为 null）
     */
    void jumpTokenToNode(String instanceId, String tokenId, String targetNodeId, String operator, String reason);

    /**
     * 加签 - 给进行中的多实例会签节点临时增加一个审批人(新建一个独立待办任务)。
     *
     * @param instanceId 流程实例 ID
     * @param nodeId     多实例节点 ID(该节点须有进行中的 token)
     * @param assignee   新增审批人
     * @param operator   操作人
     */
    void addSign(String instanceId, String nodeId, String assignee, String operator);

    /**
     * 减签 - 移除多实例会签节点上某个审批人的待办任务。
     *
     * <p>移除后若该节点已无 pending 且已有完成,会重新判定完成条件(ALL 满足则推进)。
     *
     * @param instanceId 流程实例 ID
     * @param nodeId     多实例节点 ID
     * @param assignee   要移除的审批人
     * @param operator   操作人
     */
    void removeSign(String instanceId, String nodeId, String assignee, String operator);

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

    /**
     * 迁移运行中实例到新版本流程定义
     *
     * <p>迁移后实例的 processKey/processVersion 更新为目标版本，
     * 所有活跃 Token 的 currentNodeId 按 nodeMapping 更新。
     *
     * @param instanceId       要迁移的实例 ID
     * @param targetProcessKey 目标流程定义 key（通常与原实例相同）
     * @param targetVersion    目标版本号（-1 表示最新版）
     * @param nodeMapping      节点映射：旧节点 ID → 新节点 ID
     *                         未映射的节点必须在新版中仍存在且 ID 相同
     * @param operator         操作人（用于审计）
     */
    void migrateInstance(String instanceId, String targetProcessKey,
                         int targetVersion, Map<String, String> nodeMapping, String operator);

    /**
     * 批量迁移运行中实例到新版本流程定义
     *
     * <p><b>逐实例独立事务</b>：每个实例各自提交，某个实例失败<b>不会</b>回滚已经成功的那些。
     * 失败的实例仅记录在返回结果的 failures 中，不抛异常中断整批 ——
     * 批量迁移的语义是"尽量多迁成功"，而非"要么全成、要么全不成"。
     * 重试时直接把 failures 里的 id 再传一次即可。
     *
     * @param instanceIds      要迁移的实例 ID 列表（按给定顺序处理）
     * @param targetProcessKey 目标流程定义 key（通常与原实例相同）
     * @param targetVersion    目标版本号（-1 表示最新版）
     * @param nodeMapping      节点映射：旧节点 ID → 新节点 ID
     * @param operator         操作人（用于审计）
     * @return 批处理结果（成功数 + 逐条失败明细）
     */
    BatchResult migrateInstances(List<String> instanceIds, String targetProcessKey,
                                 int targetVersion, Map<String, String> nodeMapping, String operator);

    /**
     * 获取流程定义的拓扑只读视图
     *
     * @param processKey 流程定义 key
     * @param version 版本号（-1 表示最新版）
     * @return 拓扑视图
     */
    TopologyView getTopology(String processKey, int version);

    /**
     * 获取运行中实例的拓扑视图（含当前 Token 位置）
     *
     * @param instanceId 实例 ID
     * @return 实例拓扑视图（含高亮节点）
     */
    InstanceTopologyView getInstanceTopology(String instanceId);

    // ========== 监控 ==========

    /**
     * 生成监控仪表盘快照(只读聚合)。
     *
     * @param bottleneckTopN 瓶颈节点取前 N 个(按平均耗时降序);传 &lt;0 表示不截断
     */
    DashboardMetrics dashboard(int bottleneckTopN);

    /**
     * 生成指定租户的监控仪表盘快照(多租户隔离)。
     *
     * @param bottleneckTopN 瓶颈节点取前 N 个
     * @param tenantId 租户 ID；null 表示统计全部
     */
    default DashboardMetrics dashboard(int bottleneckTopN, String tenantId) {
        // 默认忽略租户(向后兼容)；WorkflowEngine 覆写为真正的租户过滤
        return dashboard(bottleneckTopN);
    }
}