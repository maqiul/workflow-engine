package com.workflow.engine;

import com.workflow.concurrency.InstanceLockProvider;
import com.workflow.concurrency.LocalInstanceLocks;
import com.workflow.concurrency.WorkflowConflictException;
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
import com.workflow.tx.TransactionRunner;
import com.workflow.tx.UndoLogTransactionRunner;
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
    /** 历史活动仓储，可为 null（不记录历史）。埋点须在同一事务内写入，见 closeHistoryIfSettled。 */
    private final com.workflow.repository.HistoryRepository historyRepo;
    /**
     * 记录哪些类别的历史。空集或 null 仓储等价于关闭历史。
     * 活动与任务之间没有递进关系，故用集合而非"级别"枚举。
     */
    private final java.util.EnumSet<com.workflow.enums.HistoryKind> historyKinds;
    /** 事件仓储，可为 null（不启用事件网关）。 */
    private final com.workflow.repository.EventRepository eventRepo;

    /** 监听器列表 */
    private final List<ExecutionListener> executionListeners = new ArrayList<>();
    private final List<TaskListener> taskListeners = new ArrayList<>();

    /**
     * 并发控制：同一棵流程树的引擎动作串行化。
     * 默认启用（单 JVM 内的正确性底线），多 JVM 部署另需仓储层乐观锁。
     */
    private final InstanceLockProvider locks;
    /**
     * 事务边界：一次业务动作跨 instance/task/token/audit 多仓储写入时整体生效或整体不生效。
     */
    private final TransactionRunner tx;
    /** 乐观锁冲突时的重试次数（不含首次尝试）。 */
    private final int conflictRetries;
    /** 冲突重试间隔毫秒。 */
    private final long retryBackoffMillis;

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

    /** Token 推进器 - 负责流程路由逻辑 */
    private final TokenAdvancer tokenAdvancer;

    /**
     * 最简构造器 - 仅注入三个必填仓储
     *
     * <p>推荐新代码使用 {@link WorkflowEngineBuilder} 以获得更好的可读性。
     */
    public WorkflowEngine(ProcessRepository processRepo,
                          InstanceRepository instanceRepo,
                          TaskRepository taskRepo) {
        this(processRepo, instanceRepo, taskRepo, null, null, null, null, null,
                null, null, null, null, null, 3, 20L);
    }

    /**
     * 完整构造器 - 供 {@link WorkflowEngineBuilder} 使用
     */
    WorkflowEngine(ProcessRepository processRepo,
                   InstanceRepository instanceRepo,
                   TaskRepository taskRepo,
                   TimeoutScheduler scheduler,
                   AuditLogRepository auditLogRepo,
                   DelegationRepository delegationRepo,
                   NotificationService notificationService,
                   CarbonCopyRepository carbonCopyRepo,
                   com.workflow.repository.HistoryRepository historyRepo,
                   java.util.EnumSet<com.workflow.enums.HistoryKind> historyKinds,
                   com.workflow.repository.EventRepository eventRepo,
                   InstanceLockProvider locks,
                   TransactionRunner tx,
                   int conflictRetries,
                   long retryBackoffMillis) {
        this.processRepo = Objects.requireNonNull(processRepo);
        this.instanceRepo = Objects.requireNonNull(instanceRepo);
        this.taskRepo = Objects.requireNonNull(taskRepo);
        this.scheduler = scheduler != null ? scheduler : createDefaultScheduler();
        this.auditLogRepo = auditLogRepo;
        this.delegationRepo = delegationRepo;
        this.notificationService = notificationService;
        this.carbonCopyRepo = carbonCopyRepo;
        this.historyRepo = historyRepo;
        this.historyKinds = (historyKinds == null || historyKinds.isEmpty())
                ? java.util.EnumSet.noneOf(com.workflow.enums.HistoryKind.class)
                : java.util.EnumSet.copyOf(historyKinds);
        this.eventRepo = eventRepo;
        this.locks = locks != null ? locks : new LocalInstanceLocks();
        this.tx = tx != null ? tx : new UndoLogTransactionRunner();
        this.conflictRetries = Math.max(0, conflictRetries);
        this.retryBackoffMillis = Math.max(0, retryBackoffMillis);
        
        // 初始化 Token 推进器
        this.tokenAdvancer = new TokenAdvancer(
            taskRepo,
            instanceRepo,
            eventRepo,
            historyRepo,
            this.scheduler,  // 使用已初始化的 scheduler，而不是可能为 null 的参数
            this::fireTaskCreated,
            this::startSubProcessInternal,
            this::onSubProcessCompletedInternal,
            this::afterCommitScheduleInternal,
            this::fireExecutionCompleted
        );
    }

    /**
     * 并发改动开关 —— 供压测或特殊嵌入场景关闭锁与事务，退回 v3.6 的裸执行语义。
     * 返回一个新引擎实例，不修改当前实例。
     */
    public WorkflowEngine withoutConcurrencyControl() {
        return new WorkflowEngine(processRepo, instanceRepo, taskRepo, scheduler,
                auditLogRepo, delegationRepo, notificationService, carbonCopyRepo, historyRepo,
                historyKinds, eventRepo, passthroughLocks(), TransactionRunner.noop(), 0, 0L);
    }

    /**
     * 返回一个只记录指定类别历史的新引擎（共享同一套仓储与锁）。
     *
     * <p>典型用法是高吞吐场景下砍掉活动历史、只留任务历史做绩效：
     * {@code engine.withHistoryKinds(EnumSet.of(HistoryKind.TASK))}。
     * 传空集等于彻底关闭历史写入。
     */
    public WorkflowEngine withHistoryKinds(
            java.util.EnumSet<com.workflow.enums.HistoryKind> kinds) {
        return new WorkflowEngine(processRepo, instanceRepo, taskRepo, scheduler,
                auditLogRepo, delegationRepo, notificationService, carbonCopyRepo,
                historyRepo, kinds, eventRepo, locks, tx, conflictRetries, retryBackoffMillis);
    }

    /** 是否该写活动历史。 */
    private boolean recordsActivity() {
        return historyRepo != null
                && historyKinds.contains(com.workflow.enums.HistoryKind.ACTIVITY);
    }

    /** 是否该写任务历史。 */
    private boolean recordsTask() {
        return historyRepo != null
                && historyKinds.contains(com.workflow.enums.HistoryKind.TASK);
    }

    private static InstanceLockProvider passthroughLocks() {
        return new InstanceLockProvider() {
            @Override
            public <T> T executeLocked(String rootInstanceId, java.util.function.Supplier<T> action) {
                return action.get();
            }
        };
    }

    private TimeoutScheduler createDefaultScheduler() {
        return new ScheduledTimeoutScheduler(this::onTaskTimeout);
    }

    // ========== 并发与事务模板 ==========

    /**
     * 引擎所有实例级写动作的统一入口：<b>流程树锁 → 事务 → 业务体</b>。
     *
     * <p>三层各自解决一个问题，缺一不可：
     * <ol>
     *   <li><b>锁</b> —— 同树串行。杜绝「两个审批人同时点通过」导致的丢失更新、
     *       以及 {@code HashSet}/{@code LinkedHashMap} 被并发结构性修改。</li>
     *   <li><b>事务</b> —— 一次动作内跨 instance / task / token / audit 的写入整体生效
     *       或整体撤销，不留「任务已 COMPLETED 但 Token 未推进」的半完成尸体。</li>
     *   <li><b>乐观锁重试</b> —— 跨 JVM 场景下 CAS 失败时重读最新状态再试，
     *       次数耗尽才向调用方透出 {@link WorkflowConflictException}。</li>
     * </ol>
     *
     * <p>注意锁与事务的<b>先后顺序</b>：必须先拿锁再开事务。反过来会出现
     * 「事务已提交但锁已释放」的窗口，让并发者读到中间态。
     *
     * @param instanceId 目标实例 id（子流程传自身 id 即可，内部会解析到根）
     * @param op         操作名，仅用于日志与异常定位
     * @param body       业务体
     */
    private <T> T exclusive(String instanceId, String op, java.util.function.Supplier<T> body) {
        WorkflowConflictException last = null;
        for (int attempt = 0; attempt <= conflictRetries; attempt++) {
            try {
                String root = resolveRootInstanceId(instanceId);
                return locks.executeLocked(root, () -> tx.execute(body));
            } catch (WorkflowConflictException conflict) {
                last = conflict;
                if (attempt < conflictRetries) {
                    log.warn("[并发] op={} instance={} 第 {} 次尝试冲突，退避 {}ms 后重试: {}",
                            op, instanceId, attempt + 1, retryBackoffMillis, conflict.getMessage());
                    sleepBeforeRetry();
                }
            }
        }
        throw new WorkflowConflictException(
                "操作 " + op + " 在 " + (conflictRetries + 1) + " 次尝试后仍并发冲突: instance=" + instanceId,
                instanceId, last);
    }

    /** 无返回值的 {@link #exclusive} 变体。 */
    private void exclusiveVoid(String instanceId, String op, Runnable body) {
        exclusive(instanceId, op, () -> {
            body.run();
            return null;
        });
    }

    /**
     * 以 taskId 为入口时，先定位其所属实例再进临界区。
     *
     * <p>这次定位读<b>故意放在锁外</b>：它只用于选锁，不参与状态判定；
     * 进入临界区后业务体会重新读取任务，不信任此处快照。
     */
    private void exclusiveVoidByTask(String taskId, String op, Runnable body) {
        exclusiveVoid(locateInstanceOfTask(taskId), op, body);
    }

    /** taskId → instanceId 定位（任务不存在时直接向上抛，不进入锁）。 */
    private String locateInstanceOfTask(String taskId) {
        return taskRepo.findById(taskId).getInstanceId();
    }

    /**
     * 把任务最新状态对齐进实例视图。
     *
     * <p>仓储采用拷贝语义，{@code instance.tasks} 与 {@code taskRepo} 各持一份副本；
     * 落库实例前必须显式对齐，让「实例里看到的任务」和「待办列表里查到的任务」一致。
     * 所有对齐统一走这里 —— 取代此前散落在 5 处的 {@code setAccessible} 反射。
     */
    private void syncTaskInInstance(ProcessInstance instance, TaskInstance task) {
        instance.replaceTask(task.copy());
        recordTaskHistoryIfFinal(instance, task);
    }

    /**
     * 任务进入终态时落一条历史。
     *
     * <p>埋点选在这里，而不是散到 complete / reject / transfer / terminate / withdraw /
     * 四种超时策略等八来个位置：{@code syncTaskInInstance} 是本引擎<b>所有</b>任务状态变更
     * 的必经漏斗（上一轮为消灭反射双写而收敛出的单点），一处埋点即覆盖全部路径 ——
     * 漏埋概率从"每个分支都得记得"降到零。
     *
     * <p>会签部分完成时状态仍是 PENDING，不写；只有真正落定才写。
     * 幂等由 taskId 作主键天然保证：同一任务重复同步只会覆盖同一行。
     */
    private void recordTaskHistoryIfFinal(ProcessInstance instance, TaskInstance task) {
        if (!recordsTask()) {
            return;
        }
        TaskStatus st = task.getStatus();
        if (st == null || st == TaskStatus.PENDING) {
            return;
        }
        historyRepo.saveTask(com.workflow.runtime.HistoricTaskInstance.of(
                task, instance, System.currentTimeMillis()));
    }

    /**
     * 查询某 token 在某节点上的当前任务 —— 以 {@code taskRepo} 为唯一真相。
     *
     * <p>不再翻 {@code instance.getTasks()}：那份列表只有在有人记得同步时才正确，
     * 而「记得同步」正是过去所有状态错乱 bug 的来源。
     */
    private TaskInstance currentTaskOf(ProcessInstance instance, String tokenId, String nodeId) {
        return taskRepo.findByInstanceId(instance.getId()).stream()
                .filter(t -> t.getTokenId().equals(tokenId)
                        && t.getNodeId().equals(nodeId)
                        && t.getStatus() != TaskStatus.TRANSFERRED
                        && t.getStatus() != TaskStatus.TERMINATED)
                .findFirst()
                .orElse(null);
    }

    /**
     * 解析流程树根 —— 锁的单位。
     *
     * <p>读不到实例（新建流程、已删除）时退化为自身 id：此时它必然就是根。
     */
    private String resolveRootInstanceId(String instanceId) {
        if (instanceId == null) {
            return null;
        }
        try {
            return instanceRepo.findById(instanceId).getRootInstanceId();
        } catch (RuntimeException notYetStored) {
            return instanceId;
        }
    }

    private void sleepBeforeRetry() {
        if (retryBackoffMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(retryBackoffMillis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发冲突重试等待被中断", ex);
        }
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

        // 实例 id 此刻尚未落库，resolveRoot 会退化为「自身即根」——正是我们要的锁粒度
        return exclusive(instance.getId(), "start", () -> {
            instanceRepo.save(instance);

            log.info("[引擎] 发起流程 instance={} key={} v{} initiator={}", instance.getId(), def.getKey(), def.getVersion(), initiator);
            audit(AuditEventType.PROCESS_STARTED, instance.getId(), null, initiator != null ? initiator : "system",
                    "发起流程 key=" + def.getKey() + " v" + def.getVersion());
            fireExecutionStarted(instance);
            // 推进第一个 Token
            advanceToken(instance, def, token.getId());
            return instance.getId();
        });
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
        exclusiveVoidByTask(taskId, "completeTask", () -> {
            if (approved) {
                completeAndAdvance(taskId, userId);
            } else {
                // approved=false 等同于 reject,但保留 rejectTask API 接收 reason
                rejectTaskInternal(taskId, userId, "未提供理由");
            }
        });
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

        boolean taskCompleted = task.recordCompletion(actualApprover);
        taskRepo.save(task);
        // 把最新状态对齐进实例视图 —— 取代原先「两份对象 + setAccessible 反射双写」的做法
        syncTaskInInstance(instance, task);
        
        String auditDetail = "任务整体完成=" + taskCompleted;
        if (delegatedBy != null) {
            auditDetail += " (代理人=" + delegatedBy + ")";
        }
        log.info("[引擎] 用户 {} 完成审批 task={} (任务整体完成={})", actualApprover, taskId, taskCompleted);
        audit(AuditEventType.TASK_COMPLETED, instance.getId(), taskId, actualApprover, auditDetail);
        fireTaskCompleted(task, actualApprover);

        if (taskCompleted) {
            // 任务整体完成 -> 取消超时调度(会签场景:部分完成不取消,继续等待剩余人)
            // 放到提交后执行：本次事务若回滚，任务仍是 PENDING，调度必须原样保留
            afterCommitSchedule(() -> scheduler.cancel(taskId));
            // 推进该 Token 到下一个节点
            advanceToken(instance, def, task.getTokenId());
        }
    }

    @Override
    public void rejectTask(String taskId, String userId, String reason) {
        exclusiveVoidByTask(taskId, "rejectTask", () -> rejectTaskInternal(taskId, userId, reason));
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
        syncTaskInInstance(instance, task);

        // 取消超时调度 —— 放到提交后：事务回滚时任务仍是 PENDING，调度必须保留
        afterCommitSchedule(() -> scheduler.cancel(taskId));

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
        exclusiveVoidByTask(taskId, "transferTask",
                () -> transferTaskInternal(taskId, fromUserId, toUserId));
    }

    /** 转办实现 —— 必须在 {@link #exclusiveVoidByTask} 内调用。 */
    private void transferTaskInternal(String taskId, String fromUserId, String toUserId) {
        TaskInstance task = taskRepo.findById(taskId);
        ensureRunning(task);

        if (!task.getCandidate().getUserIds().contains(fromUserId)) {
            throw new IllegalArgumentException("用户 " + fromUserId + " 不是本任务候选人");
        }
        ProcessInstance instance = instanceRepo.findById(task.getInstanceId());

        // 简化:把原任务置为 TRANSFERRED,在同一节点上新建一个 toUserId 的任务
        task.setStatus(TaskStatus.TRANSFERRED);
        taskRepo.save(task);

        // 同步 instance 视图：instance 与 task 是两份独立对象，转办后待办列表必须反映 TRANSFERRED
        syncTaskInInstance(instance, task);

        // 取消原任务的超时调度（调度器活在 JVM 内存，回滚时不该生效 → 登记到提交后）
        afterCommitSchedule(() -> scheduler.cancel(taskId));

        ProcessDefinition def = defOf(instance);
        NodeDefinition nodeDef = def.getNode(task.getNodeId());
        Candidate newCand = Candidate.ofAny(toUserId);
        TaskInstance newTask = new TaskInstance(instance.getId(), task.getTokenId(),
                nodeDef.getId(), newCand);
        instance.addTask(newTask);
        taskRepo.save(newTask);
        // 实例视图与任务列表必须落库：仓储采用拷贝语义后，不再存在「改引用即改库」的便利
        instanceRepo.save(instance);

        // 新任务继承原节点的超时配置(若有)
        if (nodeDef.hasTimeout()) {
            String newTaskId = newTask.getId();
            String instanceId = instance.getId();
            long timeout = nodeDef.getTimeoutMillis();
            TimeoutPolicy policy = nodeDef.getTimeoutPolicy();
            String target = nodeDef.getTimeoutTargetUserId();
            afterCommitSchedule(() -> scheduler.schedule(newTaskId, instanceId, timeout, policy, target));
        }

        log.info("[引擎] 转办 task={} from={} to={}", taskId, fromUserId, toUserId);
        audit(AuditEventType.TASK_TRANSFERRED, instance.getId(), taskId, fromUserId,
                "转办给 toUser=" + toUserId + " newTaskId=" + newTask.getId());
        fireTaskTransferred(task, fromUserId, toUserId);
    }

    /** 把调度器等不受事务保护的副作用推迟到事务提交后执行。 */
    private void afterCommitSchedule(Runnable action) {
        com.workflow.tx.TransactionContext.afterCommit(action);
    }

    // ========== 实例级操作 ==========

    @Override
    public void suspend(String instanceId) {
        exclusiveVoid(instanceId, "suspend", () -> {
            ProcessInstance instance = instanceRepo.findById(instanceId);
            instance.suspend();
            instanceRepo.save(instance);
            log.info("[引擎] 暂停实例 {}", instanceId);
            audit(AuditEventType.PROCESS_SUSPENDED, instanceId, null, "system", "流程挂起");
            fireExecutionSuspended(instance);
        });
    }

    @Override
    public void resume(String instanceId) {
        exclusiveVoid(instanceId, "resume", () -> {
            ProcessInstance instance = instanceRepo.findById(instanceId);
            instance.resume();
            instanceRepo.save(instance);
            log.info("[引擎] 恢复实例 {}", instanceId);
            audit(AuditEventType.PROCESS_RESUMED, instanceId, null, "system", "流程恢复");
            fireExecutionResumed(instance);
        });
    }

    @Override
    public void terminate(String instanceId) {
        exclusiveVoid(instanceId, "terminate", () -> {
            ProcessInstance instance = instanceRepo.findById(instanceId);
            instance.markTerminated();
            // 关闭所有 PENDING 任务并取消超时调度
            List<String> cancelledTaskIds = new ArrayList<>();
            for (TaskInstance t : taskRepo.findByInstanceId(instanceId)) {
                if (t.getStatus() == TaskStatus.PENDING) {
                    t.setStatus(TaskStatus.TERMINATED);
                    taskRepo.save(t);
                    syncTaskInInstance(instance, t);
                    cancelledTaskIds.add(t.getId());
                }
            }
            instanceRepo.save(instance);
            afterCommitSchedule(() -> cancelledTaskIds.forEach(scheduler::cancel));
            
            // 取消该实例的所有事件（消息/信号/定时器）
            if (eventRepo != null) {
                eventRepo.cancelEvents(instanceId);
            }
            
            log.info("[引擎] 终止实例 {}", instanceId);
            audit(AuditEventType.PROCESS_TERMINATED, instanceId, null, "system", "流程终止");
            fireExecutionTerminated(instance);
        });
    }

    @Override
    public void withdraw(String instanceId, String initiator) {
        exclusiveVoid(instanceId, "withdraw", () -> {
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
        List<String> withdrawnTaskIds = new ArrayList<>();
        for (TaskInstance t : tasks) {
            if (t.getStatus() == TaskStatus.PENDING) {
                t.setStatus(TaskStatus.WITHDRAWN);
                taskRepo.save(t);
                syncTaskInInstance(instance, t);
                withdrawnTaskIds.add(t.getId());
            }
        }
        instanceRepo.save(instance);
        afterCommitSchedule(() -> withdrawnTaskIds.forEach(scheduler::cancel));

        log.info("[引擎] 发起人 {} 撤回流程 {}", initiator, instanceId);
        audit(AuditEventType.PROCESS_WITHDRAWN, instanceId, null, initiator, "发起人撤回流程");
        fireExecutionTerminated(instance);
        // 触发任务撤回事件
        for (TaskInstance t : tasks) {
            if (t.getStatus() == TaskStatus.WITHDRAWN) {
                fireTaskWithdrawn(t);
            }
        }
        });
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

    // ========== 事件网关 ==========

    /**
     * 发送消息 - 触发等待中的消息事件
     * 
     * @param messageName 消息名称
     * @param correlationKey 关联键（用于匹配到具体的流程实例）
     */
    public void sendMessage(String messageName, String correlationKey) {
        if (eventRepo == null) {
            throw new IllegalStateException("未启用事件网关，请注入 EventRepository");
        }
        
        List<String> instanceIds = eventRepo.triggerMessageEvent(messageName, correlationKey);
        if (instanceIds.isEmpty()) {
            log.warn("[引擎] 消息事件未匹配到任何实例 messageName={} correlationKey={}", messageName, correlationKey);
            return;
        }
        
        for (String instanceId : instanceIds) {
            exclusiveVoid(instanceId, "sendMessage", () -> {
                ProcessInstance instance = instanceRepo.findById(instanceId);
                ProcessDefinition def = defOf(instance);
                
                // 找到等待该消息的节点
                for (var entry : instance.getActiveTokens().entrySet()) {
                    Token token = entry.getValue();
                    NodeDefinition node = def.getNode(token.getCurrentNodeId());
                    if (node.isMessageEvent() && 
                        node.getMessageEvent().messageName().equals(messageName)) {
                        // 消耗当前 Token，推进到下一节点
                        instance.consumeToken(token.getId());
                        List<Transition> outs = def.getOutgoing(node.getId());
                        if (!outs.isEmpty()) {
                            Token nextToken = new Token(instance.getId(), outs.get(0).getTo());
                            instance.addToken(nextToken);
                            advanceToken(instance, def, nextToken.getId());
                        }
                        log.info("[引擎] 消息事件触发 instanceId={} nodeId={} messageName={}", 
                                instanceId, node.getId(), messageName);
                        break;
                    }
                }
                instanceRepo.save(instance);
            });
        }
    }

    /**
     * 发送信号 - 触发所有等待该信号的流程实例
     * 
     * @param signalName 信号名称
     */
    public void sendSignal(String signalName) {
        if (eventRepo == null) {
            throw new IllegalStateException("未启用事件网关，请注入 EventRepository");
        }
        
        List<String> instanceIds = eventRepo.triggerSignalEvent(signalName);
        if (instanceIds.isEmpty()) {
            log.warn("[引擎] 信号事件未匹配到任何实例 signalName={}", signalName);
            return;
        }
        
        for (String instanceId : instanceIds) {
            exclusiveVoid(instanceId, "sendSignal", () -> {
                ProcessInstance instance = instanceRepo.findById(instanceId);
                ProcessDefinition def = defOf(instance);
                
                // 找到等待该信号的节点
                for (var entry : instance.getActiveTokens().entrySet()) {
                    Token token = entry.getValue();
                    NodeDefinition node = def.getNode(token.getCurrentNodeId());
                    if (node.isSignalEvent() && 
                        node.getSignalEvent().signalName().equals(signalName)) {
                        // 消耗当前 Token，推进到下一节点
                        instance.consumeToken(token.getId());
                        List<Transition> outs = def.getOutgoing(node.getId());
                        if (!outs.isEmpty()) {
                            Token nextToken = new Token(instance.getId(), outs.get(0).getTo());
                            instance.addToken(nextToken);
                            advanceToken(instance, def, nextToken.getId());
                        }
                        log.info("[引擎] 信号事件触发 instanceId={} nodeId={} signalName={}", 
                                instanceId, node.getId(), signalName);
                        break;
                    }
                }
                instanceRepo.save(instance);
            });
        }
    }

    /**
     * 检查并触发到期的定时器事件
     * 
     * 应由外部调度器定期调用（如每分钟一次）
     */
    public void checkAndTriggerTimers() {
        if (eventRepo == null) {
            log.warn("[引擎] checkAndTriggerTimers 被调用但 eventRepo 为空");
            return;
        }
        
        List<com.workflow.repository.EventRepository.TimerEvent> expiredTimers = 
                eventRepo.getExpiredTimers(java.time.Instant.now());
        log.info("[引擎] checkAndTriggerTimers 发现 {} 个到期定时器", expiredTimers.size());
        
        for (var timer : expiredTimers) {
            exclusiveVoid(timer.instanceId(), "checkAndTriggerTimers", () -> {
                ProcessInstance instance = instanceRepo.findById(timer.instanceId());
                
                // 实例可能已经完成或终止，跳过
                if (instance.getStatus() != com.workflow.enums.InstanceStatus.RUNNING) {
                    return;
                }
                
                ProcessDefinition def = defOf(instance);
                
                // 查找 timer 节点
                NodeDefinition timerNode = def.getNode(timer.nodeId());
                if (timerNode == null || !timerNode.isTimerBoundary()) {
                    return;
                }
                
                // 找到 timer 节点附加到的 USER_TASK 节点
                String attachedToNodeId = timerNode.getTimerBoundaryEvent().attachedToNodeId();
                
                // 找到 waiting 在 attachedToNodeId 上的 Token
                Token waitingToken = null;
                for (Token t : instance.getActiveTokens().values()) {
                    if (attachedToNodeId.equals(t.getCurrentNodeId())) {
                        waitingToken = t;
                        break;
                    }
                }
                if (waitingToken == null) {
                    return;
                }
                
                if (timer.interrupting()) {
                    // 中断模式：取消当前任务，推进到定时器出口
                    log.info("[引擎] 定时器触发（中断模式）instanceId={} nodeId={}", 
                            timer.instanceId(), timer.nodeId());
                    
                    // 找到关联的任务并取消
                    for (TaskInstance task : taskRepo.findByInstanceId(timer.instanceId())) {
                        if (task.getNodeId().equals(attachedToNodeId) &&
                            task.getStatus() == TaskStatus.PENDING) {
                            task.setStatus(TaskStatus.TERMINATED);
                            taskRepo.save(task);
                            syncTaskInInstance(instance, task);
                        }
                    }
                    
                    // 消耗当前 Token，推进到定时器出口
                    instance.consumeToken(waitingToken.getId());
                    List<Transition> outs = def.getOutgoing(timer.nodeId());
                    if (!outs.isEmpty()) {
                        Token nextToken = new Token(instance.getId(), outs.get(0).getTo());
                        instance.addToken(nextToken);
                        advanceToken(instance, def, nextToken.getId());
                    }
                } else {
                    // 非中断模式：创建新 Token 走定时器分支，原任务继续
                    log.info("[引擎] 定时器触发（非中断模式）instanceId={} nodeId={}", 
                            timer.instanceId(), timer.nodeId());
                    List<Transition> outs = def.getOutgoing(timer.nodeId());
                    if (!outs.isEmpty()) {
                        Token nextToken = new Token(instance.getId(), outs.get(0).getTo());
                        instance.addToken(nextToken);
                        advanceToken(instance, def, nextToken.getId());
                    }
                }
                
                // 触发后移除该定时器事件
                eventRepo.cancelTimer(timer.instanceId(), timer.nodeId());
                instanceRepo.save(instance);
            });
        }
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

    /**
     * 全量任务 —— 供 {@link com.workflow.query.TaskQuery} 做无实例约束的查询。
     * 直接委托仓储，不在引擎内拼装。
     */
    @Override
    public List<TaskInstance> allTasks() {
        return taskRepo.findAll();
    }

    /** 全量实例 —— 任务与流程定义的关联（key / version / 变量）由此补齐。 */
    @Override
    public List<ProcessInstance> allInstances() {
        return instanceRepo.findAll();
    }

    // ========== Token 推进核心 ==========

    /**
     * 推进指定 Token 到下一个状态 —— 委托给 TokenAdvancer 处理。
     *
     * <p>TokenAdvancer 负责所有节点类型的路由逻辑，WorkflowEngine 只提供回调方法。
     */
    private void advanceToken(ProcessInstance instance, ProcessDefinition def, String tokenId) {
        tokenAdvancer.advanceToken(instance, def, tokenId, recordsActivity());
    }

    /** 回调：启动子流程 */
    private void startSubProcessInternal(ProcessInstance parent, ProcessDefinition parentDef,
                                        Token parentToken, NodeDefinition subNode) {
        startSubProcess(parent, parentDef, parentToken, subNode);
    }

    /** 回调：子流程完成后推进父流程 */
    private void onSubProcessCompletedInternal(ProcessInstance child) {
        onSubProcessCompleted(child);
    }

    /** 回调：事务提交后调度 */
    private void afterCommitScheduleInternal(Runnable action) {
        afterCommitSchedule(action);
    }

    /**
     * 检查实例是否可以结束(无活跃 Token 且无 PENDING 任务)
     */
    private void checkAndFinalize(ProcessInstance instance) {
        if (instance.isAllTokensConsumed() && instance.getStatus() == InstanceStatus.RUNNING) {
            // 若还有 PENDING 任务,说明是 UserTask 完成的瞬态,不算结束
            // 以 taskRepo 为唯一真相：instance.getTasks() 是副本视图，不可用于判定
            boolean hasPending = taskRepo.findByInstanceId(instance.getId()).stream()
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
        // 子实例继承父树的根 —— 引擎按「流程树根」加锁，父子共享同一把锁，
        // 从而杜绝 startSubProcess(父→子) 与 onSubProcessCompleted(子→父) 构成 ABBA 死锁
        child.assignRootInstanceId(parent.getRootInstanceId());
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
     * 超时回调入口 —— 由调度线程触发，属于<b>独立的并发入口</b>，
     * 必须与用户手动操作走同一把流程树锁，否则会出现
     * 「超时自动通过与人工审批同时生效」的双写。
     */
    private void onTaskTimeout(String taskId, String instanceId,
                               TimeoutPolicy policy, String targetUserId) {
        exclusiveVoid(instanceId, "onTaskTimeout",
                () -> doTaskTimeout(taskId, instanceId, policy, targetUserId));
    }

    /**
     * 超时回调 - 由 ScheduledTimeoutScheduler 触发
     * 按节点配置的 TimeoutPolicy 执行自动动作
     *
     * 幂等保证:回调前重查任务状态,非 PENDING 则忽略(避免与用户手动操作竞态)
     */
    private void doTaskTimeout(String taskId, String instanceId,
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
                // 自动通过:记录 SYSTEM_USER 并直接完成 —— 候选人校验在 domain 内部豁免
                task.recordSystemApproval(SYSTEM_USER);
                taskRepo.save(task);
                syncTaskInInstance(instance, task);
                ProcessDefinition def = defOf(instance);
                advanceToken(instance, def, task.getTokenId());
                audit(AuditEventType.TIMEOUT_AUTO_APPROVED, instanceId, taskId, SYSTEM_USER,
                        "超时自动通过");
            }
            case AUTO_REJECT -> {
                // 自动驳回:退回上一 UserTask
                task.setStatus(TaskStatus.REJECTED);
                taskRepo.save(task);
                syncTaskInInstance(instance, task);
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
                List<String> terminatedIds = new ArrayList<>();
                for (TaskInstance t : taskRepo.findByInstanceId(instanceId)) {
                    if (t.getStatus() == TaskStatus.PENDING) {
                        t.setStatus(TaskStatus.TERMINATED);
                        taskRepo.save(t);
                        syncTaskInInstance(instance, t);
                        terminatedIds.add(t.getId());
                    }
                }
                instanceRepo.save(instance);
                afterCommitSchedule(() -> terminatedIds.forEach(scheduler::cancel));
                audit(AuditEventType.TIMEOUT_AUTO_TERMINATED, instanceId, taskId, SYSTEM_USER,
                        "超时自动终止");
            }
            case AUTO_TRANSFER -> {
                // 自动转办:原任务置为 TRANSFERRED,新建目标用户任务
                task.setStatus(TaskStatus.TRANSFERRED);
                taskRepo.save(task);
                syncTaskInInstance(instance, task);
                ProcessDefinition def = defOf(instance);
                NodeDefinition nodeDef = def.getNode(task.getNodeId());
                Candidate newCand = Candidate.ofAny(targetUserId);
                TaskInstance newTask = new TaskInstance(instance.getId(), task.getTokenId(),
                        nodeDef.getId(), newCand);
                taskRepo.save(newTask);
                syncTaskInInstance(instance, newTask);
                // 新任务继承原节点超时配置 —— 提交后才登记
                if (nodeDef.hasTimeout()) {
                    String newTaskId = newTask.getId();
                    String instId = instance.getId();
                    long timeout = nodeDef.getTimeoutMillis();
                    TimeoutPolicy autoPolicy = nodeDef.getTimeoutPolicy();
                    String autoTarget = nodeDef.getTimeoutTargetUserId();
                    afterCommitSchedule(() -> scheduler.schedule(newTaskId, instId, timeout, autoPolicy, autoTarget));
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