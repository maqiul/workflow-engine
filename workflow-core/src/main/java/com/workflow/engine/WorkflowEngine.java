package com.workflow.engine;

import com.workflow.definition.Candidate;
import com.workflow.definition.NodeDefinition;
import com.workflow.definition.ProcessDefinition;
import com.workflow.definition.Transition;
import com.workflow.enums.AuditEventType;
import com.workflow.enums.CandidateStrategy;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.NodeType;
import com.workflow.enums.TaskStatus;
import com.workflow.enums.TimeoutPolicy;
import com.workflow.listener.ExecutionListener;
import com.workflow.listener.TaskListener;
import com.workflow.repository.AuditLogRepository;
import com.workflow.repository.CarbonCopyRepository;
import com.workflow.repository.DelegationRepository;
import com.workflow.repository.InstanceRepository;
import com.workflow.repository.ProcessRepository;
import com.workflow.repository.TaskRepository;
import com.workflow.runtime.AuditLog;
import com.workflow.runtime.CarbonCopy;
import com.workflow.runtime.Delegation;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import com.workflow.runtime.Token;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 引擎核心实现
 *
 * 核心调度逻辑 (advanceToken):
 *  1. 根据 Token 当前所在节点,取该节点的出口转移
 *  2. 根据节点类型分派:
 *     - END: 实例完成
 *     - USER_TASK: 创建 TaskInstance(若已有则不重复)
 *     - EXCLUSIVE_GATEWAY: 按 condition 选一条(简化:取第一条)
 *     - PARALLEL_GATEWAY fork: 为每条出口生成新 Token
 *     - PARALLEL_GATEWAY join: 等待其他兄弟 Token,本 Token 消耗
 *
 * 任务调度 (completeTask / rejectTask):
 *  - complete: 调 recordCompletion,若任务整体完成则推进 Token
 *  - reject: 找上一个 USER_TASK,在该处重建 Token(驳回语义)
 *  - transfer: 把任务的 assignee 替换(本实现简化为状态置为 TRANSFERRED,
 *             并新建一个新候选人=toUserId 的任务实例)
 */
public class WorkflowEngine implements IWorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    private final ProcessRepository processRepo;
    private final InstanceRepository instanceRepo;
    private final TaskRepository taskRepo;
    private final AuditLogRepository auditLogRepo;  // 可为 null（不启用审计）
    private final DelegationRepository delegationRepo;  // 可为 null（不启用委托）
    private final NotificationService notificationService;  // 可为 null（不启用通知）
    private final CarbonCopyRepository carbonCopyRepo;  // 可为 null（不启用抄送）

    /** 监听器列表 */
    private final List<ExecutionListener> executionListeners = new ArrayList<>();
    private final List<TaskListener> taskListeners = new ArrayList<>();

    /** 子流程嵌套最大深度 - 防循环引用死递归 */
    private static final int MAX_SUB_PROCESS_DEPTH = 10;
    /** 子流程深度变量 key(内部使用) */
    private static final String SUB_DEPTH_VAR = "__sub_depth";
    /** 子流程发起标记变量前缀(内部使用):__sub_<tokenId> = childInstanceId */
    private static final String SUB_MARK_PREFIX = "__sub_";
    /** 系统用户 - 超时策略自动动作的操作用户(绕过候选人校验) */
    private static final String SYSTEM_USER = "__system__";
    /** 发起人变量 key(内部使用):用于撤回校验 */
    private static final String INITIATOR_VAR = "__initiator";

    private final TimeoutScheduler scheduler;

    public WorkflowEngine(ProcessRepository processRepo,
                          InstanceRepository instanceRepo,
                          TaskRepository taskRepo) {
        this(processRepo, instanceRepo, taskRepo, null, null, null, null, null);
    }

    public WorkflowEngine(ProcessRepository processRepo,
                          InstanceRepository instanceRepo,
                          TaskRepository taskRepo,
                          TimeoutScheduler scheduler) {
        this(processRepo, instanceRepo, taskRepo, scheduler, null, null, null, null);
    }

    public WorkflowEngine(ProcessRepository processRepo,
                          InstanceRepository instanceRepo,
                          TaskRepository taskRepo,
                          TimeoutScheduler scheduler,
                          AuditLogRepository auditLogRepo) {
        this(processRepo, instanceRepo, taskRepo, scheduler, auditLogRepo, null, null, null);
    }

    public WorkflowEngine(ProcessRepository processRepo,
                          InstanceRepository instanceRepo,
                          TaskRepository taskRepo,
                          TimeoutScheduler scheduler,
                          AuditLogRepository auditLogRepo,
                          DelegationRepository delegationRepo) {
        this(processRepo, instanceRepo, taskRepo, scheduler, auditLogRepo, delegationRepo, null, null);
    }

    public WorkflowEngine(ProcessRepository processRepo,
                          InstanceRepository instanceRepo,
                          TaskRepository taskRepo,
                          TimeoutScheduler scheduler,
                          AuditLogRepository auditLogRepo,
                          DelegationRepository delegationRepo,
                          NotificationService notificationService) {
        this(processRepo, instanceRepo, taskRepo, scheduler, auditLogRepo, delegationRepo, notificationService, null);
    }

    /**
     * 构造器 - 可注入自定义超时调度器、审计日志仓储、委托关系仓储、通知服务和抄送仓储
     *
     * @param scheduler           超时调度器,传 null 时使用默认 {@link ScheduledTimeoutScheduler}
     * @param auditLogRepo        审计日志仓储,传 null 时不记录审计日志
     * @param delegationRepo      委托关系仓储,传 null 时不启用委托功能
     * @param notificationService 通知服务,传 null 时不启用通知功能
     * @param carbonCopyRepo      抄送仓储,传 null 时不启用抄送功能
     */
    public WorkflowEngine(ProcessRepository processRepo,
                          InstanceRepository instanceRepo,
                          TaskRepository taskRepo,
                          TimeoutScheduler scheduler,
                          AuditLogRepository auditLogRepo,
                          DelegationRepository delegationRepo,
                          NotificationService notificationService,
                          CarbonCopyRepository carbonCopyRepo) {
        this.processRepo = Objects.requireNonNull(processRepo);
        this.instanceRepo = Objects.requireNonNull(instanceRepo);
        this.taskRepo = Objects.requireNonNull(taskRepo);
        this.scheduler = scheduler != null ? scheduler : createDefaultScheduler();
        this.auditLogRepo = auditLogRepo;
        this.delegationRepo = delegationRepo;
        this.notificationService = notificationService;
        this.carbonCopyRepo = carbonCopyRepo;
    }

    private TimeoutScheduler createDefaultScheduler() {
        return new ScheduledTimeoutScheduler(this::onTaskTimeout);
    }

    /**
     * 关闭引擎持有的超时调度器(释放调度线程)
     * 无超时调度器的场景下调用是安全的
     */
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    // ========== 监听器管理 ==========

    /**
     * 注册执行监听器
     */
    public void addExecutionListener(ExecutionListener listener) {
        executionListeners.add(listener);
    }

    /**
     * 注册任务监听器
     */
    public void addTaskListener(TaskListener listener) {
        taskListeners.add(listener);
    }

    /**
     * 移除执行监听器
     */
    public void removeExecutionListener(ExecutionListener listener) {
        executionListeners.remove(listener);
    }

    /**
     * 移除任务监听器
     */
    public void removeTaskListener(TaskListener listener) {
        taskListeners.remove(listener);
    }

    /** 触发流程启动事件 */
    private void fireExecutionStarted(ProcessInstance instance) {
        for (ExecutionListener l : executionListeners) {
            try { l.onStarted(instance); } catch (Exception e) { log.warn("[监听器] onStarted 异常", e); }
        }
    }

    /** 触发流程完成事件 */
    private void fireExecutionCompleted(ProcessInstance instance) {
        for (ExecutionListener l : executionListeners) {
            try { l.onCompleted(instance); } catch (Exception e) { log.warn("[监听器] onCompleted 异常", e); }
        }
    }

    /** 触发流程终止事件 */
    private void fireExecutionTerminated(ProcessInstance instance) {
        for (ExecutionListener l : executionListeners) {
            try { l.onTerminated(instance); } catch (Exception e) { log.warn("[监听器] onTerminated 异常", e); }
        }
    }

    /** 触发流程挂起事件 */
    private void fireExecutionSuspended(ProcessInstance instance) {
        for (ExecutionListener l : executionListeners) {
            try { l.onSuspended(instance); } catch (Exception e) { log.warn("[监听器] onSuspended 异常", e); }
        }
    }

    /** 触发流程恢复事件 */
    private void fireExecutionResumed(ProcessInstance instance) {
        for (ExecutionListener l : executionListeners) {
            try { l.onResumed(instance); } catch (Exception e) { log.warn("[监听器] onResumed 异常", e); }
        }
    }

    /** 触发任务创建事件 */
    private void fireTaskCreated(TaskInstance task) {
        for (TaskListener l : taskListeners) {
            try { l.onCreated(task); } catch (Exception e) { log.warn("[监听器] onCreated 异常", e); }
        }
    }

    /** 触发任务完成事件 */
    private void fireTaskCompleted(TaskInstance task, String userId) {
        for (TaskListener l : taskListeners) {
            try { l.onCompleted(task, userId); } catch (Exception e) { log.warn("[监听器] onCompleted 异常", e); }
        }
    }

    /** 触发任务驳回事件 */
    private void fireTaskRejected(TaskInstance task, String userId, String reason) {
        for (TaskListener l : taskListeners) {
            try { l.onRejected(task, userId, reason); } catch (Exception e) { log.warn("[监听器] onRejected 异常", e); }
        }
    }

    /** 触发任务转办事件 */
    private void fireTaskTransferred(TaskInstance task, String fromUser, String toUser) {
        for (TaskListener l : taskListeners) {
            try { l.onTransferred(task, fromUser, toUser); } catch (Exception e) { log.warn("[监听器] onTransferred 异常", e); }
        }
    }

    /** 触发任务撤回事件 */
    private void fireTaskWithdrawn(TaskInstance task) {
        for (TaskListener l : taskListeners) {
            try { l.onWithdrawn(task); } catch (Exception e) { log.warn("[监听器] onWithdrawn 异常", e); }
        }
    }

    // ========== 流程发起 ==========

    @Override
    public String start(String processKey, Map<String, Object> variables) {
        return start(processKey, null, variables);
    }

    @Override
    public String start(String processKey, String initiator, Map<String, Object> variables) {
        ProcessDefinition def = processRepo.findByKey(processKey);
        return startWithDefinition(def, initiator, variables);
    }

    @Override
    public String start(String processKey, int version, Map<String, Object> variables) {
        return start(processKey, version, null, variables);
    }

    @Override
    public String start(String processKey, int version, String initiator, Map<String, Object> variables) {
        ProcessDefinition def = processRepo.findByKeyAndVersion(processKey, version);
        return startWithDefinition(def, initiator, variables);
    }

    private String startWithDefinition(ProcessDefinition def, String initiator, Map<String, Object> variables) {
        // 变量校验（如果定义了变量 schema）
        VariableValidator.validate(def, variables);

        ProcessInstance instance = new ProcessInstance(def.getKey(), def.getVersion());
        if (variables != null) {
            variables.forEach(instance::setVariable);
        }
        // 记录发起人（用于撤回校验）
        if (initiator != null && !initiator.isBlank()) {
            instance.setVariable(INITIATOR_VAR, initiator);
        }

        Token token = new Token(instance.getId(), def.getStartNodeId());
        instance.addToken(token);
        instanceRepo.save(instance);

        log.info("[引擎] 发起流程 instance={} key={} v{} initiator={}", instance.getId(), def.getKey(), def.getVersion(), initiator);
        audit(AuditEventType.PROCESS_STARTED, instance.getId(), null, initiator != null ? initiator : "system",
                "发起流程 key=" + def.getKey() + " v" + def.getVersion());
        fireExecutionStarted(instance);
        // 推进第一个 Token
        advanceToken(instance, def, token.getId());
        return instance.getId();
    }

    /**
     * 按实例所属版本取流程定义:
     *  - processVersion > 0 取精确版本(实例发起时固化的版本)
     *  - 否则取最新版(兼容旧数据)
     */
    private ProcessDefinition defOf(ProcessInstance instance) {
        if (instance.getProcessVersion() > 0) {
            return processRepo.findByKeyAndVersion(instance.getProcessKey(), instance.getProcessVersion());
        }
        return processRepo.findByKey(instance.getProcessKey());
    }

    // ========== 任务操作 ==========

    @Override
    public void completeTask(String taskId, String userId, boolean approved) {
        if (approved) {
            completeAndAdvance(taskId, userId);
        } else {
            // approved=false 等同于 reject,但保留 rejectTask API 接收 reason
            rejectTaskInternal(taskId, userId, "未提供理由");
        }
    }

    private void completeAndAdvance(String taskId, String userId) {
        TaskInstance task = taskRepo.findById(taskId);
        ensureRunning(task);

        ProcessInstance instance = instanceRepo.findById(task.getInstanceId());
        ProcessDefinition def = defOf(instance);

        // 检查候选人或委托关系
        String actualApprover = userId;  // 实际审批人（可能是委托人）
        String delegatedBy = null;       // 如果是代理人审批，记录委托人
        
        if (task.getCandidate().getUserIds().contains(userId)) {
            // 用户是候选人，直接审批
            actualApprover = userId;
        } else {
            // 检查是否有委托关系（用户是代理人）
            Delegation delegation = findDelegation(userId, task.getNodeId(), instance.getProcessKey());
            if (delegation != null && task.getCandidate().getUserIds().contains(delegation.getDelegator())) {
                // 用户是委托人的代理人，且委托人在候选人列表中
                actualApprover = delegation.getDelegator();  // 实际审批人是委托人
                delegatedBy = userId;                         // 代理人是操作人
                log.info("[引擎] 代理人 {} 代 {} 审批 task={}", userId, actualApprover, taskId);
            } else {
                throw new IllegalArgumentException("用户 " + userId + " 不是本任务候选人，且无委托关系");
            }
        }
        
        if (task.getStatus() != TaskStatus.PENDING) {
            throw new IllegalStateException("任务非 PENDING 状态: " + task.getStatus());
        }

        // 同步 instance 视图里同一 task 的状态 - JPA 仓储下 task 与 instance.tasks 是不同对象,
        // 不更新 instance 视图里那份,advanceToken 会拿旧 PENDING 状态做判断。
        TaskInstance taskInInstance = instance.getTasks().stream()
                .filter(t -> t.getId().equals(taskId))
                .findFirst()
                .orElse(null);

        boolean taskCompleted = task.recordCompletion(actualApprover);
        taskRepo.save(task);
        if (taskInInstance != null) {
            taskInInstance.setStatus(task.getStatus());
            try {
                java.lang.reflect.Field f = TaskInstance.class.getDeclaredField("completedApprovers");
                f.setAccessible(true);
                f.set(taskInInstance, new java.util.HashSet<>(task.getCompletedApprovers()));
            } catch (Exception ex) {
                throw new RuntimeException("同步 instance 视图 task 状态失败", ex);
            }
        }
        
        String auditDetail = "任务整体完成=" + taskCompleted;
        if (delegatedBy != null) {
            auditDetail += " (代理人=" + delegatedBy + ")";
        }
        log.info("[引擎] 用户 {} 完成审批 task={} (任务整体完成={})", actualApprover, taskId, taskCompleted);
        audit(AuditEventType.TASK_COMPLETED, instance.getId(), taskId, actualApprover, auditDetail);
        fireTaskCompleted(task, actualApprover);

        if (taskCompleted) {
            // 任务整体完成 -> 取消超时调度(会签场景:部分完成不取消,继续等待剩余人)
            scheduler.cancel(taskId);
            // 推进该 Token 到下一个节点
            advanceToken(instance, def, task.getTokenId());
        }
    }

    @Override
    public void rejectTask(String taskId, String userId, String reason) {
        rejectTaskInternal(taskId, userId, reason);
    }

    private void rejectTaskInternal(String taskId, String userId, String reason) {
        TaskInstance task = taskRepo.findById(taskId);
        ensureRunning(task);

        ProcessInstance instance = instanceRepo.findById(task.getInstanceId());
        ProcessDefinition def = defOf(instance);

        if (!task.getCandidate().getUserIds().contains(userId)) {
            throw new IllegalArgumentException("用户 " + userId + " 不是本任务候选人");
        }
        task.setStatus(TaskStatus.REJECTED);
        taskRepo.save(task);

        // 取消超时调度
        scheduler.cancel(taskId);

        // 找上一个 USER_TASK
        String prevUserTask = PathNavigator.findPreviousUserTask(def, task.getNodeId());
        if (prevUserTask == null) {
            // 没有上一个 UserTask - 直接终止
            log.warn("[引擎] 驳回时找不到上一节点,实例终止 reason={}", reason);
            instance.markTerminated();
            instanceRepo.save(instance);
            audit(AuditEventType.TASK_REJECTED, instance.getId(), taskId, userId,
                    "驳回找不到上一节点,实例终止 reason=" + reason);
            return;
        }

        // 消耗当前 Token,在上一节点新建 Token
        instance.consumeToken(task.getTokenId());
        NodeDefinition prevDef = def.getNode(prevUserTask);
        Token newToken = new Token(instance.getId(), prevDef.getId());
        instance.addToken(newToken);
        instanceRepo.save(instance);

        log.info("[引擎] 用户 {} 驳回到 {} reason={}", userId, prevUserTask, reason);
        audit(AuditEventType.TASK_REJECTED, instance.getId(), taskId, userId,
                "驳回到 node=" + prevUserTask + " reason=" + reason);
        fireTaskRejected(task, userId, reason);
        // 在上一节点重新创建任务(新待办)
        advanceToken(instance, def, newToken.getId());
    }

    @Override
    public void transferTask(String taskId, String fromUserId, String toUserId) {
        TaskInstance task = taskRepo.findById(taskId);
        ensureRunning(task);

        if (!task.getCandidate().getUserIds().contains(fromUserId)) {
            throw new IllegalArgumentException("用户 " + fromUserId + " 不是本任务候选人");
        }
        ProcessInstance instance = instanceRepo.findById(task.getInstanceId());

        // 简化:把原任务置为 TRANSFERRED,在同一节点上新建一个 toUserId 的任务
        task.setStatus(TaskStatus.TRANSFERRED);
        taskRepo.save(task);

        // 取消原任务的超时调度
        scheduler.cancel(taskId);

        ProcessDefinition def = defOf(instance);
        NodeDefinition nodeDef = def.getNode(task.getNodeId());
        Candidate newCand = Candidate.ofAny(toUserId);
        TaskInstance newTask = new TaskInstance(instance.getId(), task.getTokenId(),
                nodeDef.getId(), newCand);
        instance.addTask(newTask);
        taskRepo.save(newTask);

        // 新任务继承原节点的超时配置(若有)
        if (nodeDef.hasTimeout()) {
            scheduler.schedule(newTask.getId(), instance.getId(),
                    nodeDef.getTimeoutMillis(), nodeDef.getTimeoutPolicy(),
                    nodeDef.getTimeoutTargetUserId());
        }

        log.info("[引擎] 转办 task={} from={} to={}", taskId, fromUserId, toUserId);
        audit(AuditEventType.TASK_TRANSFERRED, instance.getId(), taskId, fromUserId,
                "转办给 toUser=" + toUserId + " newTaskId=" + newTask.getId());
        fireTaskTransferred(task, fromUserId, toUserId);
    }

    // ========== 实例级操作 ==========

    @Override
    public void suspend(String instanceId) {
        ProcessInstance instance = instanceRepo.findById(instanceId);
        instance.suspend();
        instanceRepo.save(instance);
        log.info("[引擎] 暂停实例 {}", instanceId);
        audit(AuditEventType.PROCESS_SUSPENDED, instanceId, null, "system", "流程挂起");
        fireExecutionSuspended(instance);
    }

    @Override
    public void resume(String instanceId) {
        ProcessInstance instance = instanceRepo.findById(instanceId);
        instance.resume();
        instanceRepo.save(instance);
        log.info("[引擎] 恢复实例 {}", instanceId);
        audit(AuditEventType.PROCESS_RESUMED, instanceId, null, "system", "流程恢复");
        fireExecutionResumed(instance);
    }

    @Override
    public void terminate(String instanceId) {
        ProcessInstance instance = instanceRepo.findById(instanceId);
        instance.markTerminated();
        // 关闭所有 PENDING 任务并取消超时调度
        for (TaskInstance t : taskRepo.findByInstanceId(instanceId)) {
            if (t.getStatus() == TaskStatus.PENDING) {
                t.setStatus(TaskStatus.TERMINATED);
                taskRepo.save(t);
                scheduler.cancel(t.getId());
            }
        }
        instanceRepo.save(instance);
        log.info("[引擎] 终止实例 {}", instanceId);
        audit(AuditEventType.PROCESS_TERMINATED, instanceId, null, "system", "流程终止");
        fireExecutionTerminated(instance);
    }

    @Override
    public void withdraw(String instanceId, String initiator) {
        ProcessInstance instance = instanceRepo.findById(instanceId);
        
        // 1. 检查实例状态
        if (instance.getStatus() != InstanceStatus.RUNNING) {
            throw new IllegalStateException("仅 RUNNING 状态的流程可撤回,当前: " + instance.getStatus());
        }
        
        // 2. 检查发起人
        Object recordedInitiator = instance.getVariable(INITIATOR_VAR);
        if (recordedInitiator == null) {
            throw new IllegalArgumentException("流程未记录发起人,无法撤回");
        }
        if (!recordedInitiator.toString().equals(initiator)) {
            throw new IllegalArgumentException("发起人 " + initiator + " 不是流程发起人(实际发起人: " + recordedInitiator + ")");
        }
        
        // 3. 检查是否有已审批的任务（如果有，不能撤回）
        List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
        boolean hasCompletedTask = tasks.stream()
                .anyMatch(t -> t.getStatus() == TaskStatus.COMPLETED);
        if (hasCompletedTask) {
            throw new IllegalStateException("流程已有审批人通过,无法撤回");
        }
        
        // 4. 终止流程，把所有 PENDING 任务置为 WITHDRAWN
        instance.markTerminated();
        for (TaskInstance t : tasks) {
            if (t.getStatus() == TaskStatus.PENDING) {
                t.setStatus(TaskStatus.WITHDRAWN);
                taskRepo.save(t);
                scheduler.cancel(t.getId());
            }
        }
        instanceRepo.save(instance);
        
        log.info("[引擎] 发起人 {} 撤回流程 {}", initiator, instanceId);
        audit(AuditEventType.PROCESS_WITHDRAWN, instanceId, null, initiator, "发起人撤回流程");
        fireExecutionTerminated(instance);
        // 触发任务撤回事件
        for (TaskInstance t : tasks) {
            if (t.getStatus() == TaskStatus.WITHDRAWN) {
                fireTaskWithdrawn(t);
            }
        }
    }

    // ========== 委托管理 ==========

    @Override
    public void delegate(String delegator, String delegate) {
        delegate(delegator, delegate, null, null);
    }

    @Override
    public void delegate(String delegator, String delegate, String nodeId, String processKey) {
        if (delegationRepo == null) {
            throw new IllegalStateException("未启用委托功能，请注入 DelegationRepository");
        }
        Delegation d = new Delegation(delegator, delegate, nodeId, processKey);
        delegationRepo.save(d);
        log.info("[引擎] 设置委托 {} -> {} (node={}, process={})", delegator, delegate, nodeId, processKey);
    }

    @Override
    public void revokeDelegate(String delegator, String delegate) {
        if (delegationRepo == null) {
            throw new IllegalStateException("未启用委托功能，请注入 DelegationRepository");
        }
        List<Delegation> delegations = delegationRepo.findByDelegator(delegator);
        for (Delegation d : delegations) {
            if (d.getDelegate().equals(delegate)) {
                delegationRepo.remove(d);
            }
        }
        log.info("[引擎] 撤销委托 {} -> {}", delegator, delegate);
    }

    /**
     * 检查用户是否有委托关系（作为代理人）
     * @return 匹配的委托关系，null 表示无委托
     */
    private Delegation findDelegation(String userId, String nodeId, String processKey) {
        if (delegationRepo == null) {
            return null;
        }
        List<Delegation> delegations = delegationRepo.findByDelegate(userId);
        for (Delegation d : delegations) {
            if (d.matches(nodeId, processKey)) {
                return d;
            }
        }
        return null;
    }

    // ========== 催办 ==========

    @Override
    public void urge(String taskId, String operator, String reason) {
        if (notificationService == null) {
            throw new IllegalStateException("未启用通知功能，请注入 NotificationService");
        }
        TaskInstance task = taskRepo.findById(taskId);
        if (task.getStatus() != TaskStatus.PENDING) {
            throw new IllegalStateException("任务非 PENDING 状态，无法催办: " + task.getStatus());
        }
        
        // 向所有候选人发送催办通知
        for (String userId : task.getCandidate().getUserIds()) {
            notificationService.urge(task, userId, operator, reason);
        }
        log.info("[引擎] 催办任务 {} 操作人={} 原因={}", taskId, operator, reason);
    }

    // ========== 抄送 ==========

    @Override
    public void carbonCopy(String instanceId, String taskId, String nodeId,
                           List<String> recipients, String operator, String message) {
        if (carbonCopyRepo == null) {
            throw new IllegalStateException("未启用抄送功能，请注入 CarbonCopyRepository");
        }
        if (recipients == null || recipients.isEmpty()) {
            return;  // 无抄送人，静默返回
        }
        for (String recipient : recipients) {
            CarbonCopy cc = new CarbonCopy(instanceId, taskId, nodeId, recipient, operator, message);
            carbonCopyRepo.save(cc);
        }
        log.info("[引擎] 抄送 instance={} node={} recipients={} operator={}", 
                instanceId, nodeId, recipients, operator);
    }

    @Override
    public List<CarbonCopy> getCarbonCopies(String recipient) {
        if (carbonCopyRepo == null) {
            return List.of();
        }
        return carbonCopyRepo.findByRecipient(recipient);
    }

    @Override
    public List<CarbonCopy> getUnreadCarbonCopies(String recipient) {
        if (carbonCopyRepo == null) {
            return List.of();
        }
        return carbonCopyRepo.findUnreadByRecipient(recipient);
    }

    @Override
    public void markCarbonCopyRead(String ccId) {
        if (carbonCopyRepo == null) {
            return;
        }
        carbonCopyRepo.markRead(ccId);
    }

    // ========== 查询 ==========

    @Override
    public ProcessInstance getInstance(String instanceId) {
        return instanceRepo.findById(instanceId);
    }

    @Override
    public TaskInstance getTask(String taskId) {
        return taskRepo.findById(taskId);
    }

    // ========== Token 推进核心 ==========

    /**
     * 推进指定 Token 到下一个状态
     */
    private void advanceToken(ProcessInstance instance, ProcessDefinition def, String tokenId) {
        Token token = instance.getActiveTokens().get(tokenId);
        if (token == null) {
            log.debug("[引擎] Token 已不存在,跳过推进 id={}", tokenId);
            checkAndFinalize(instance);
            return;
        }
        NodeDefinition current = def.getNode(token.getCurrentNodeId());

        switch (current.getType()) {
            case END -> {
                instance.consumeToken(tokenId);
                log.info("[引擎] Token {} 到达 END", tokenId);
            }
            case USER_TASK -> {
                // 1) 找出该 token+node 上已有的非终止任务
                TaskInstance existing = instance.getTasks().stream()
                        .filter(t -> t.getTokenId().equals(tokenId)
                                && t.getNodeId().equals(current.getId())
                                && t.getStatus() != TaskStatus.TRANSFERRED
                                && t.getStatus() != TaskStatus.TERMINATED)
                        .findFirst().orElse(null);
                log.debug("[引擎] advanceToken USER_TASK node={} existing={}",
                        current.getId(),
                        existing == null ? "null" : ("taskId=" + existing.getId() + " status=" + existing.getStatus()));

                if (existing == null) {
                    TaskInstance task = new TaskInstance(instance.getId(), tokenId,
                            current.getId(), current.getCandidate());
                    instance.addTask(task);
                    log.info("[引擎] 创建任务 node={} candidate={} taskId={}", current.getId(), current.getCandidate(), task.getId());
                    taskRepo.save(task);
                    fireTaskCreated(task);
                    // JPA 关键:同时 save instance 让 wf_token 同步(applyToken 已推进到 review 节点)
                    instanceRepo.save(instance);
                    log.info("[引擎] 创建任务并保存 instance 完成");
                    // 注册超时调度(若节点配置了超时)
                    if (current.hasTimeout()) {
                        scheduler.schedule(task.getId(), instance.getId(),
                                current.getTimeoutMillis(), current.getTimeoutPolicy(),
                                current.getTimeoutTargetUserId());
                    }
                } else if (existing.getStatus() == TaskStatus.COMPLETED) {
                    // 任务已完成 -> 把 Token 推进到下一节点
                    log.info("[引擎] advanceToken USER_TASK node={} existing.status=COMPLETED -> 推进 Token", current.getId());
                    List<Transition> outs = def.getOutgoing(current.getId());
                    if (outs.isEmpty()) {
                        instance.consumeToken(tokenId);
                        instanceRepo.save(instance);
                    } else if (outs.size() == 1) {
                        token.setCurrentNodeId(outs.get(0).getTo());
                        instanceRepo.save(instance);
                        advanceToken(instance, def, tokenId);
                    } else {
                        // 多出口 - 不支持(应该用排他网关)
                        throw new IllegalStateException("USER_TASK 节点 " + current.getId() + " 有多条出口");
                    }
                } else {
                    // PENDING 状态被再次推进(异常路径),直接忽略
                    log.debug("[引擎] Token {} 节点 {} 上任务仍 PENDING,跳过", tokenId, current.getId());
                }
            }
            case EXCLUSIVE_GATEWAY -> {
                List<Transition> outs = def.getOutgoing(current.getId());
                // 简化:取第一个出口(实际应按 condition 评估)
                if (outs.isEmpty()) {
                    throw new IllegalStateException("排他网关 " + current.getId() + " 无出口");
                }
                Transition chosen = null;
                Map<String, Object> vars = instance.getVariables();
                for (Transition t : outs) {
                    if (ConditionEvaluator.eval(t.getCondition(), vars)) {
                        chosen = t;
                        break;
                    }
                }
                if (chosen == null) {
                    // 所有条件都不满足且无默认出口 -> 终止(避免卡死)
                    log.warn("[引擎] 排他网关 {} 无匹配出口,Token 终止", current.getId());
                    instance.consumeToken(tokenId);
                    instanceRepo.save(instance);
                    break;
                }
                log.info("[引擎] 排他网关 {} 选择出口 {}", current.getId(), chosen.getTo());
                token.setCurrentNodeId(chosen.getTo());
                instanceRepo.save(instance);
                advanceToken(instance, def, tokenId);
            }
            case PARALLEL_GATEWAY -> {
                List<Transition> outs = def.getOutgoing(current.getId());
                if (outs.isEmpty()) {
                    instance.consumeToken(tokenId);
                    break;
                }
                if (GatewayKind.isJoin(def, current.getId())) {
                    // 汇聚:消耗本 Token,但要等其他兄弟
                    instance.consumeToken(tokenId);
                    log.info("[引擎] Token {} 到达并行汇聚 {}", tokenId, current.getId());
                    // 检查是否所有到达此网关的 Token 都已消耗
                    if (allJoinArrived(def, current.getId(), instance)) {
                        // 创建后续 Token(从 join 节点的唯一出口)
                        Transition out = outs.get(0);
                        Token next = new Token(instance.getId(), out.getTo());
                        instance.addToken(next);
                        instanceRepo.save(instance);
                        advanceToken(instance, def, next.getId());
                    } else {
                        instanceRepo.save(instance);
                    }
                } else {
                    // fork: 分裂
                    instance.consumeToken(tokenId);
                    List<Token> forked = new ArrayList<>();
                    for (Transition out : outs) {
                        Token t = new Token(instance.getId(), out.getTo());
                        instance.addToken(t);
                        forked.add(t);
                    }
                    instanceRepo.save(instance);
                    log.info("[引擎] 并行分裂出 {} 条 Token", forked.size());
                    for (Token t : forked) {
                        advanceToken(instance, def, t.getId());
                    }
                }
            }
            case START -> {
                // 起始节点:取其唯一出口
                List<Transition> outs = def.getOutgoing(current.getId());
                if (outs.isEmpty()) {
                    throw new IllegalStateException("START 节点 " + current.getId() + " 无出口");
                }
                token.setCurrentNodeId(outs.get(0).getTo());
                instanceRepo.save(instance);
                advanceToken(instance, def, tokenId);
            }
            case SUB_PROCESS -> {
                // 子流程节点:Token 停留等待子流程完成
                String markKey = SUB_MARK_PREFIX + tokenId;
                Object childId = instance.getVariable(markKey);
                if (childId == null) {
                    // 尚未发起 -> 创建子流程实例
                    startSubProcess(instance, def, token, current);
                } else {
                    // 已发起 -> 检查子流程是否完成
                    ProcessInstance child = instanceRepo.findById(childId.toString());
                    if (child.getStatus() == InstanceStatus.COMPLETED) {
                        // 子流程已完成 -> 把 Token 推进到出口
                        log.info("[引擎] 子流程 {} 已完成,推进父 Token 到出口 node={}",
                                child.getId(), current.getId());
                        List<Transition> outs = def.getOutgoing(current.getId());
                        if (outs.isEmpty()) {
                            instance.consumeToken(tokenId);
                            instanceRepo.save(instance);
                        } else if (outs.size() == 1) {
                            token.setCurrentNodeId(outs.get(0).getTo());
                            instanceRepo.save(instance);
                            advanceToken(instance, def, tokenId);
                        } else {
                            throw new IllegalStateException(
                                    "SUB_PROCESS 节点 " + current.getId() + " 有多条出口");
                        }
                    } else {
                        // 子流程仍在执行,继续等待
                        log.info("[引擎] 子流程 {} 仍执行中,父 Token 等待 node={}",
                                child.getId(), current.getId());
                    }
                }
            }
            case DYNAMIC_PARALLEL -> {
                // 动态多实例节点:从变量获取候选人列表,动态创建多个任务
                String variable = current.getDynamicParallelVariable();
                CandidateStrategy strategy = current.getDynamicParallelStrategy();
                
                // 检查是否已经创建过任务
                String markKey = "__dynamic_" + tokenId;
                Object created = instance.getVariable(markKey);
                
                if (created == null) {
                    // 首次进入:从变量获取候选人列表
                    Object varValue = instance.getVariable(variable);
                    if (varValue == null) {
                        throw new IllegalStateException(
                                "DYNAMIC_PARALLEL 节点 " + current.getId() + 
                                " 需要的变量 " + variable + " 不存在");
                    }
                    
                    List<String> candidates;
                    if (varValue instanceof List<?> list) {
                        candidates = new ArrayList<>();
                        for (Object item : list) {
                            if (item instanceof String s) {
                                candidates.add(s);
                            } else {
                                candidates.add(item.toString());
                            }
                        }
                    } else {
                        throw new IllegalStateException(
                                "DYNAMIC_PARALLEL 节点 " + current.getId() + 
                                " 的变量 " + variable + " 必须是 List 类型");
                    }
                    
                    if (candidates.isEmpty()) {
                        // 候选人列表为空:直接推进到出口
                        log.info("[引擎] DYNAMIC_PARALLEL 节点 {} 候选人列表为空,直接推进", current.getId());
                        instance.setVariable(markKey, "done");
                        List<Transition> outs = def.getOutgoing(current.getId());
                        if (outs.isEmpty()) {
                            instance.consumeToken(tokenId);
                            instanceRepo.save(instance);
                        } else if (outs.size() == 1) {
                            token.setCurrentNodeId(outs.get(0).getTo());
                            instanceRepo.save(instance);
                            advanceToken(instance, def, tokenId);
                        } else {
                            throw new IllegalStateException(
                                    "DYNAMIC_PARALLEL 节点 " + current.getId() + " 有多条出口");
                        }
                    } else {
                        // 为每个候选人创建任务
                        Candidate candidate = strategy == CandidateStrategy.ANY 
                                ? Candidate.ofAny(candidates.toArray(new String[0]))
                                : Candidate.ofAll(candidates.toArray(new String[0]));
                        
                        TaskInstance task = new TaskInstance(instance.getId(), tokenId,
                                current.getId(), candidate);
                        instance.addTask(task);
                        instance.setVariable(markKey, "created");
                        log.info("[引擎] DYNAMIC_PARALLEL 节点 {} 创建动态任务,候选人={},策略={},taskId={}", 
                                current.getId(), candidates, strategy, task.getId());
                        taskRepo.save(task);
                        instanceRepo.save(instance);
                    }
                } else if ("created".equals(created)) {
                    // 任务已创建,检查是否完成
                    TaskInstance existing = instance.getTasks().stream()
                            .filter(t -> t.getTokenId().equals(tokenId)
                                    && t.getNodeId().equals(current.getId())
                                    && t.getStatus() != TaskStatus.TRANSFERRED
                                    && t.getStatus() != TaskStatus.TERMINATED)
                            .findFirst().orElse(null);
                    
                    if (existing != null && existing.getStatus() == TaskStatus.COMPLETED) {
                        // 任务完成:推进到出口
                        log.info("[引擎] DYNAMIC_PARALLEL 节点 {} 动态任务完成,推进 Token", current.getId());
                        instance.setVariable(markKey, "done");
                        List<Transition> outs = def.getOutgoing(current.getId());
                        if (outs.isEmpty()) {
                            instance.consumeToken(tokenId);
                            instanceRepo.save(instance);
                        } else if (outs.size() == 1) {
                            token.setCurrentNodeId(outs.get(0).getTo());
                            instanceRepo.save(instance);
                            advanceToken(instance, def, tokenId);
                        } else {
                            throw new IllegalStateException(
                                    "DYNAMIC_PARALLEL 节点 " + current.getId() + " 有多条出口");
                        }
                    } else {
                        // 任务仍在执行,继续等待
                        log.debug("[引擎] DYNAMIC_PARALLEL 节点 {} 动态任务仍执行中,继续等待", current.getId());
                    }
                }
            }
        }

        checkAndFinalize(instance);
    }

    /**
     * 检查并行汇聚是否所有分支都已到齐
     */
    private boolean allJoinArrived(ProcessDefinition def, String joinNodeId, ProcessInstance instance) {        // 统计到达 joinNodeId 的所有 Transition,来自哪些 Token
        // 简化:由于我们 consumeToken 时立即移除,本方法被调用时已经 consume 了当前 Token
        // 所以剩下要判断的是:还有没有其他 Token 还停在 joinNodeId 的前驱上
        Set<String> expectedSources = new HashSet<>();
        for (NodeDefinition n : def.getNodes().values()) {
            for (Transition t : def.getOutgoing(n.getId())) {
                if (t.getTo().equals(joinNodeId)) {
                    expectedSources.add(n.getId());
                }
            }
        }
        for (Token t : instance.getActiveTokens().values()) {
            if (expectedSources.contains(t.getCurrentNodeId())) {
                return false;  // 还有分支没到达
            }
        }
        return true;
    }

    /**
     * 检查实例是否可以结束(无活跃 Token 且无 PENDING 任务)
     */
    private void checkAndFinalize(ProcessInstance instance) {
        if (instance.isAllTokensConsumed() && instance.getStatus() == InstanceStatus.RUNNING) {
            // 若还有 PENDING 任务,说明是 UserTask 完成的瞬态,不算结束
            boolean hasPending = instance.getTasks().stream()
                    .anyMatch(t -> t.getStatus() == TaskStatus.PENDING);
            if (!hasPending) {
                instance.markCompleted();
                instanceRepo.save(instance);
                log.info("[引擎] 实例 {} 流程完成", instance.getId());
                fireExecutionCompleted(instance);
                // 子流程完成 -> 回调父流程推进
                if (instance.isSubProcess()) {
                    onSubProcessCompleted(instance);
                }
            }
        }
    }

    // ========== 子流程 ==========

    /**
     * 在父流程的 SUB_PROCESS 节点发起子流程实例
     * Token 停留在 SUB_PROCESS 节点,子流程完成后由 onSubProcessCompleted 推进
     */
    private void startSubProcess(ProcessInstance parent, ProcessDefinition parentDef,
                                 Token token, NodeDefinition nodeDef) {
        String subKey = nodeDef.getSubProcessKey();
        ProcessDefinition subDef = processRepo.findByKey(subKey);

        // 深度检查 - 沿 __sub_depth 变量,防循环引用
        int depth = 1;
        Object d = parent.getVariable(SUB_DEPTH_VAR);
        if (d instanceof Number n) {
            depth = n.intValue() + 1;
        }
        if (depth > MAX_SUB_PROCESS_DEPTH) {
            throw new IllegalStateException(
                    "子流程嵌套超过最大深度 " + MAX_SUB_PROCESS_DEPTH + ",疑似循环引用: " + subKey);
        }

        // 创建子实例(携带父上下文)
        ProcessInstance child = new ProcessInstance(subDef.getKey(), subDef.getVersion(),
                parent.getId(), token.getId(), nodeDef.getId());
        // 继承父流程变量(浅拷贝)
        parent.getVariables().forEach(child::setVariable);
        child.setVariable(SUB_DEPTH_VAR, depth);

        Token childToken = new Token(child.getId(), subDef.getStartNodeId());
        child.addToken(childToken);
        instanceRepo.save(child);

        // 父实例打标记:该 token 的子流程已发起,避免重复发起
        parent.setVariable(SUB_MARK_PREFIX + token.getId(), child.getId());
        instanceRepo.save(parent);

        log.info("[引擎] 发起子流程 parent={} node={} subKey={} child={} depth={}",
                parent.getId(), nodeDef.getId(), subKey, child.getId(), depth);
        advanceToken(child, subDef, childToken.getId());
    }

    /**
     * 子流程实例完成 -> 回调父流程,推进停在 SUB_PROCESS 节点上的 Token
     */
    private void onSubProcessCompleted(ProcessInstance child) {
        String parentId = child.getParentInstanceId();
        String parentTokenId = child.getParentTokenId();
        if (parentId == null || parentTokenId == null) {
            return;
        }
        ProcessInstance parent = instanceRepo.findById(parentId);
        ProcessDefinition parentDef = defOf(parent);
        Token token = parent.getActiveTokens().get(parentTokenId);
        if (token == null) {
            // 父 Token 已不存在(父流程可能被终止)
            log.warn("[引擎] 子流程完成但父 Token 已不存在 parent={} token={}", parentId, parentTokenId);
            return;
        }
        log.info("[引擎] 子流程 {} 完成,推进父流程 token={} node={}",
                child.getId(), parentTokenId, token.getCurrentNodeId());
        advanceToken(parent, parentDef, parentTokenId);
    }

    private void ensureRunning(TaskInstance task) {
        if (task.getStatus() != TaskStatus.PENDING) {
            throw new IllegalStateException("任务非 PENDING 状态: " + task.getStatus());
        }
    }

    // ========== 超时回调 ==========

    /**
     * 超时回调 - 由 ScheduledTimeoutScheduler 触发
     * 按节点配置的 TimeoutPolicy 执行自动动作
     *
     * 幂等保证:回调前重查任务状态,非 PENDING 则忽略(避免与用户手动操作竞态)
     */
    private void onTaskTimeout(String taskId, String instanceId,
                               TimeoutPolicy policy, String targetUserId) {
        TaskInstance task = taskRepo.findById(taskId);
        if (task == null || task.getStatus() != TaskStatus.PENDING) {
            log.debug("[超时] 任务 {} 已非 PENDING 状态,忽略超时回调", taskId);
            return;
        }

        ProcessInstance instance = instanceRepo.findById(instanceId);
        if (instance.getStatus() != InstanceStatus.RUNNING) {
            log.debug("[超时] 实例 {} 已非 RUNNING 状态,忽略超时回调", instanceId);
            return;
        }

        log.info("[超时] 任务 {} 超时触发策略 policy={} target={}", taskId, policy, targetUserId);

        // 超时通知（如果启用了通知服务）
        if (notificationService != null) {
            for (String userId : task.getCandidate().getUserIds()) {
                notificationService.timeoutReminder(task, userId, 0);
            }
        }

        switch (policy) {
            case AUTO_APPROVE -> {
                // 自动通过:直接设置状态并记录 SYSTEM_USER,绕过候选人校验
                try {
                    java.lang.reflect.Field f = TaskInstance.class.getDeclaredField("completedApprovers");
                    f.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Set<String> approvers = (Set<String>) f.get(task);
                    approvers.add(SYSTEM_USER);
                } catch (Exception e) {
                    throw new RuntimeException("反射设置 completedApprovers 失败", e);
                }
                task.setStatus(TaskStatus.COMPLETED);
                taskRepo.save(task);
                // 同步 instance 视图里同一 task 的状态(与 completeAndAdvance 同理)
                TaskInstance taskInInstance = instance.getTasks().stream()
                        .filter(t -> t.getId().equals(taskId))
                        .findFirst().orElse(null);
                if (taskInInstance != null) {
                    taskInInstance.setStatus(TaskStatus.COMPLETED);
                    try {
                        java.lang.reflect.Field f = TaskInstance.class.getDeclaredField("completedApprovers");
                        f.setAccessible(true);
                        f.set(taskInInstance, new java.util.HashSet<>(task.getCompletedApprovers()));
                    } catch (Exception ex) {
                        throw new RuntimeException("同步 instance 视图 task 状态失败", ex);
                    }
                }
                ProcessDefinition def = defOf(instance);
                advanceToken(instance, def, task.getTokenId());
                audit(AuditEventType.TIMEOUT_AUTO_APPROVED, instanceId, taskId, SYSTEM_USER,
                        "超时自动通过");
            }
            case AUTO_REJECT -> {
                // 自动驳回:退回上一 UserTask
                task.setStatus(TaskStatus.REJECTED);
                taskRepo.save(task);
                // 同步 instance 视图里同一 task 的状态
                TaskInstance taskInInstance = instance.getTasks().stream()
                        .filter(t -> t.getId().equals(taskId))
                        .findFirst().orElse(null);
                if (taskInInstance != null) {
                    taskInInstance.setStatus(TaskStatus.REJECTED);
                }
                ProcessDefinition def = defOf(instance);
                String prevUserTask = PathNavigator.findPreviousUserTask(def, task.getNodeId());
                if (prevUserTask == null) {
                    log.warn("[超时] 驳回时找不到上一节点,实例终止");
                    instance.markTerminated();
                    instanceRepo.save(instance);
                    audit(AuditEventType.TIMEOUT_AUTO_REJECTED, instanceId, taskId, SYSTEM_USER,
                            "超时自动驳回找不到上一节点,实例终止");
                    return;
                }
                instance.consumeToken(task.getTokenId());
                NodeDefinition prevDef = def.getNode(prevUserTask);
                Token newToken = new Token(instance.getId(), prevDef.getId());
                instance.addToken(newToken);
                instanceRepo.save(instance);
                advanceToken(instance, def, newToken.getId());
                audit(AuditEventType.TIMEOUT_AUTO_REJECTED, instanceId, taskId, SYSTEM_USER,
                        "超时自动驳回到 node=" + prevUserTask);
            }
            case AUTO_TERMINATE -> {
                // 自动终止:关闭实例和所有 PENDING 任务
                instance.markTerminated();
                for (TaskInstance t : taskRepo.findByInstanceId(instanceId)) {
                    if (t.getStatus() == TaskStatus.PENDING) {
                        t.setStatus(TaskStatus.TERMINATED);
                        taskRepo.save(t);
                        scheduler.cancel(t.getId());
                    }
                }
                instanceRepo.save(instance);
                audit(AuditEventType.TIMEOUT_AUTO_TERMINATED, instanceId, taskId, SYSTEM_USER,
                        "超时自动终止");
            }
            case AUTO_TRANSFER -> {
                // 自动转办:原任务置为 TRANSFERRED,新建目标用户任务
                task.setStatus(TaskStatus.TRANSFERRED);
                taskRepo.save(task);
                // 同步 instance 视图里同一 task 的状态
                TaskInstance taskInInstance = instance.getTasks().stream()
                        .filter(t -> t.getId().equals(taskId))
                        .findFirst().orElse(null);
                if (taskInInstance != null) {
                    taskInInstance.setStatus(TaskStatus.TRANSFERRED);
                }
                ProcessDefinition def = defOf(instance);
                NodeDefinition nodeDef = def.getNode(task.getNodeId());
                Candidate newCand = Candidate.ofAny(targetUserId);
                TaskInstance newTask = new TaskInstance(instance.getId(), task.getTokenId(),
                        nodeDef.getId(), newCand);
                instance.addTask(newTask);
                taskRepo.save(newTask);
                // 新任务继承原节点超时配置
                if (nodeDef.hasTimeout()) {
                    scheduler.schedule(newTask.getId(), instance.getId(),
                            nodeDef.getTimeoutMillis(), nodeDef.getTimeoutPolicy(),
                            nodeDef.getTimeoutTargetUserId());
                }
                audit(AuditEventType.TIMEOUT_AUTO_TRANSFERRED, instanceId, taskId, SYSTEM_USER,
                        "超时自动转办给 toUser=" + targetUserId + " newTaskId=" + newTask.getId());
            }
            default -> log.warn("[超时] 未知策略 policy={}", policy);
        }
    }

    /**
     * 记录审计日志（若配置了审计仓储）
     */
    private void audit(AuditEventType eventType, String instanceId, String taskId,
                       String operator, String detail) {
        if (auditLogRepo != null) {
            auditLogRepo.save(new AuditLog(instanceId, taskId, eventType, operator, detail));
        }
    }
}