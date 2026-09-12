package com.workflow.engine;

import com.workflow.concurrency.InstanceLockProvider;
import com.workflow.concurrency.LocalInstanceLocks;
import com.workflow.concurrency.WorkflowConflictException;
import com.workflow.delegate.DelegateExecution;
import com.workflow.delegate.ServiceTaskDelegate;
import com.workflow.definition.Candidate;
import com.workflow.definition.NodeDefinition;
import com.workflow.definition.ProcessDefinition;
import com.workflow.definition.Transition;
import com.workflow.dmn.DecisionHistoryRepository;
import com.workflow.dmn.DecisionRepository;
import com.workflow.dmn.DecisionTableExecutor;
import com.workflow.enums.AuditEventType;
import com.workflow.enums.CandidateStrategy;
import com.workflow.enums.CommentType;
import com.workflow.enums.HistoryKind;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.NodeType;
import com.workflow.enums.TaskStatus;
import com.workflow.enums.TimeoutPolicy;
import com.workflow.listener.ExecutionListener;
import com.workflow.listener.TaskListener;
import com.workflow.monitor.DashboardMetrics;
import com.workflow.monitor.MonitoringService;
import com.workflow.repository.AuditLogRepository;
import com.workflow.repository.CarbonCopyRepository;
import com.workflow.repository.CommentRepository;
import com.workflow.repository.DelegationRepository;
import com.workflow.repository.EventRepository;
import com.workflow.repository.HistoryRepository;
import com.workflow.repository.InstanceRepository;
import com.workflow.repository.ProcessRepository;
import com.workflow.repository.TaskFilter;
import com.workflow.repository.TaskRepository;
import com.workflow.runtime.AuditLog;
import com.workflow.runtime.CarbonCopy;
import com.workflow.runtime.Comment;
import com.workflow.runtime.Delegation;
import com.workflow.runtime.HistoricTaskInstance;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import com.workflow.runtime.Token;
import com.workflow.topology.InstanceTopologyView;
import com.workflow.topology.NodeView;
import com.workflow.topology.TopologyView;
import com.workflow.topology.TransitionView;
import com.workflow.tx.TransactionContext;
import com.workflow.tx.TransactionRunner;
import com.workflow.tx.UndoLogTransactionRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

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
    /**
     * 审批意见仓储，可为 null（不启用意见能力）。
     *
     * <p>这里的 null 只关掉「记录意见」本身：审批动作（完成 / 驳回）照常工作，
     * 只是不再额外留意见 —— 与审计、抄送同一套「可选副作用」定位。
     */
    private final CommentRepository commentRepo;
    /** 历史活动仓储，可为 null（不记录历史）。埋点须在同一事务内写入，见 closeHistoryIfSettled。 */
    private final HistoryRepository historyRepo;
    /**
     * 记录哪些类别的历史。空集或 null 仓储等价于关闭历史。
     * 活动与任务之间没有递进关系，故用集合而非"级别"枚举。
     */
    private final EnumSet<HistoryKind> historyKinds;
    /** 事件仓储，可为 null（不启用事件网关）。 */
    private final EventRepository eventRepo;
    /** 决策仓储，可为 null（不启用决策网关）。 */
    private final DecisionRepository decisionRepo;
    /** 决策历史仓储，可为 null（不记录决策历史）。 */
    private final DecisionHistoryRepository decisionHistoryRepo;

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
    /**
     * 引擎内部变量保留前缀 —— 调用方不得写入。
     *
     * <p>该前缀下是引擎自己的状态：{@code __initiator}(撤回鉴权)、
     * {@code __mi_<tokenId>}(多实例展开标记)、{@code __dynamic_<tokenId>}(动态并行标记)、
     * {@code __sub_<tokenId>}(子流程发起标记)。放进来的口子会直接变成漏洞，
     * 详见 {@link #ensureVariableWritable}。
     */
    private static final String RESERVED_VAR_PREFIX = "__";

    private final TimeoutScheduler scheduler;

    /** Token 推进器 - 负责流程路由逻辑 */
    private final TokenAdvancer tokenAdvancer;
    
    /** 子流程处理器 - 负责子流程的启动与完成回调 */
    private final SubProcessHandler subProcessHandler;
    
    /** 监听器支持 - 负责管理和触发执行监听器与任务监听器 */
    private final ListenerSupport listenerSupport;
    
    /** 超时处理器 - 负责处理任务超时回调 */
    private final TimeoutHandler timeoutHandler;

    /** 监控服务 - 只读聚合引擎各仓储数据为仪表盘快照 */
    private final MonitoringService monitoring;

    /** 服务任务委托注册表 - 运行时注册的 delegate */
    private final ConcurrentHashMap<String, ServiceTaskDelegate> delegates = new ConcurrentHashMap<>();

    /**
     * 最简构造器 - 仅注入三个必填仓储
     *
     * <p>推荐新代码使用 {@link WorkflowEngineBuilder} 以获得更好的可读性。
     */
    public WorkflowEngine(ProcessRepository processRepo,
                          InstanceRepository instanceRepo,
                          TaskRepository taskRepo) {
        this(processRepo, instanceRepo, taskRepo, null, null, null, null, null,
                null, null, null, null, null, null, null, null, 3, 20L);
    }

    /**
     * 完整构造器 - 供 {@link WorkflowEngineBuilder} 使用
     */
    public WorkflowEngine(ProcessRepository processRepo,
                   InstanceRepository instanceRepo,
                   TaskRepository taskRepo,
                   TimeoutScheduler scheduler,
                   AuditLogRepository auditLogRepo,
                   DelegationRepository delegationRepo,
                   NotificationService notificationService,
                   CarbonCopyRepository carbonCopyRepo,
                   CommentRepository commentRepo,
                   HistoryRepository historyRepo,
                   EnumSet<HistoryKind> historyKinds,
                   EventRepository eventRepo,
                   DecisionRepository decisionRepo,
                   DecisionHistoryRepository decisionHistoryRepo,
                   InstanceLockProvider locks,
                   TransactionRunner tx,
                   int conflictRetries,
                   long retryBackoffMillis) {
        this.processRepo = Objects.requireNonNull(processRepo);
        this.instanceRepo = Objects.requireNonNull(instanceRepo);
        this.taskRepo = Objects.requireNonNull(taskRepo);
        this.auditLogRepo = auditLogRepo;
        this.delegationRepo = delegationRepo;
        this.notificationService = notificationService;
        this.carbonCopyRepo = carbonCopyRepo;
        this.commentRepo = commentRepo;
        this.historyRepo = historyRepo;
        this.historyKinds = (historyKinds == null || historyKinds.isEmpty())
                ? EnumSet.noneOf(HistoryKind.class)
                : EnumSet.copyOf(historyKinds);
        this.eventRepo = eventRepo;
        this.decisionRepo = decisionRepo;
        this.decisionHistoryRepo = decisionHistoryRepo;
        this.locks = locks != null ? locks : new LocalInstanceLocks();
        this.tx = tx != null ? tx : new UndoLogTransactionRunner();
        this.conflictRetries = Math.max(0, conflictRetries);
        this.retryBackoffMillis = Math.max(0, retryBackoffMillis);
        
        // 初始化监听器支持
        this.listenerSupport = new ListenerSupport();
        
        // 初始化超时处理器（scheduler 稍后初始化）
        this.timeoutHandler = new TimeoutHandler(
            taskRepo,
            instanceRepo,
            auditLogRepo,
            notificationService,
            null,  // scheduler 稍后设置
            this::advanceToken,
            this::defOf,
            this::syncTaskInInstance,
            this::afterCommitScheduleInternal,
            this::exclusiveVoid
        );
        
        // 初始化 scheduler（使用 timeoutHandler 的回调）
        this.scheduler = scheduler != null ? scheduler : createDefaultScheduler();
        // 设置 scheduler 到 timeoutHandler
        this.timeoutHandler.setScheduler(this.scheduler);
        
        // 初始化 Token 推进器
        this.tokenAdvancer = new TokenAdvancer(
            taskRepo,
            instanceRepo,
            eventRepo,
            historyRepo,
            this.scheduler,
            listenerSupport::fireTaskCreated,
            this::startSubProcessInternal,
            this::onSubProcessCompletedInternal,
            this::afterCommitScheduleInternal,
            listenerSupport::fireExecutionCompleted,
            decisionRepo,
            decisionRepo != null ? new DecisionTableExecutor() : null,
            decisionHistoryRepo,
            this::getDelegate  // 传入 delegate provider
        );
        
        // 初始化子流程处理器
        this.subProcessHandler = new SubProcessHandler(
            processRepo,
            instanceRepo,
            this::advanceToken,
            this::defOf
        );

        // 初始化监控服务(只读聚合)
        this.monitoring = new MonitoringService(
            instanceRepo, taskRepo, historyRepo, auditLogRepo);
    }

    /**
     * 注册服务任务委托
     * 
     * @param key delegate 的唯一标识
     * @param delegate 要注册的 delegate
     */
    public void registerDelegate(String key, ServiceTaskDelegate delegate) {
        Objects.requireNonNull(key, "delegate key 不能为空");
        Objects.requireNonNull(delegate, "delegate 不能为空");
        delegates.put(key, delegate);
        log.info("注册服务任务委托: key={}", key);
    }

    /**
     * 获取已注册的 delegate
     * 
     * @param key delegate 的唯一标识
     * @return delegate 实例，未注册返回 null
     */
    public ServiceTaskDelegate getDelegate(String key) {
        return delegates.get(key);
    }

    /**
     * 并发改动开关 —— 供压测或特殊嵌入场景关闭锁与事务，退回 v3.6 的裸执行语义。
     * 返回一个新引擎实例，不修改当前实例。
     */
    public WorkflowEngine withoutConcurrencyControl() {
        return new WorkflowEngine(processRepo, instanceRepo, taskRepo, scheduler,
                auditLogRepo, delegationRepo, notificationService, carbonCopyRepo, commentRepo, historyRepo,
                historyKinds, eventRepo, decisionRepo, decisionHistoryRepo, passthroughLocks(), TransactionRunner.noop(), 0, 0L);
    }

    /**
     * 返回一个只记录指定类别历史的新引擎（共享同一套仓储与锁）。
     *
     * <p>典型用法是高吞吐场景下砍掉活动历史、只留任务历史做绩效：
     * {@code engine.withHistoryKinds(EnumSet.of(HistoryKind.TASK))}。
     * 传空集等于彻底关闭历史写入。
     */
    public WorkflowEngine withHistoryKinds(
            EnumSet<HistoryKind> kinds) {
        return new WorkflowEngine(processRepo, instanceRepo, taskRepo, scheduler,
                auditLogRepo, delegationRepo, notificationService, carbonCopyRepo, commentRepo,
                historyRepo, kinds, eventRepo, decisionRepo, decisionHistoryRepo, locks, tx, conflictRetries, retryBackoffMillis);
    }

    /** 是否该写活动历史。 */
    private boolean recordsActivity() {
        return historyRepo != null
                && historyKinds.contains(HistoryKind.ACTIVITY);
    }

    /** 是否该写任务历史。 */
    private boolean recordsTask() {
        return historyRepo != null
                && historyKinds.contains(HistoryKind.TASK);
    }

    private static InstanceLockProvider passthroughLocks() {
        return new InstanceLockProvider() {
            @Override
            public <T> T executeLocked(String rootInstanceId, Supplier<T> action) {
                return action.get();
            }
        };
    }

    private TimeoutScheduler createDefaultScheduler() {
        return new ScheduledTimeoutScheduler(timeoutHandler::onTaskTimeout);
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
    private <T> T exclusive(String instanceId, String op, Supplier<T> body) {
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
        historyRepo.saveTask(HistoricTaskInstance.of(
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

    // ========== 监听器管理 ==========

    /**
     * 添加执行监听器 - 委托给 ListenerSupport
     */
    public void addExecutionListener(ExecutionListener listener) {
        listenerSupport.addExecutionListener(listener);
    }

    /**
     * 添加任务监听器 - 委托给 ListenerSupport
     */
    public void addTaskListener(TaskListener listener) {
        listenerSupport.addTaskListener(listener);
    }

    /**
     * 移除执行监听器 - 委托给 ListenerSupport
     */
    public void removeExecutionListener(ExecutionListener listener) {
        listenerSupport.getExecutionListeners().remove(listener);
    }

    /**
     * 移除任务监听器 - 委托给 ListenerSupport
     */
    public void removeTaskListener(TaskListener listener) {
        listenerSupport.getTaskListeners().remove(listener);
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

        // 候选组预检：定义里用了候选组却没接组织架构，属部署配置错误。
        // 必须赶在实例落库之前拦下 —— 否则会留下"实例已存在、任务建不出来"的脏数据。
        requireGroupResolverIfNeeded(def);

        ProcessInstance instance = new ProcessInstance(def.getKey(), def.getVersion());
        if (variables != null) {
            variables.forEach(instance::setVariable);
        }
        // 记录发起人（用于撤回校验）
        if (initiator != null && !initiator.isBlank()) {
            instance.setVariable(INITIATOR_VAR, initiator);
        }
        // 设置租户 ID（从流程定义或上下文获取）
        String tenantId = def.getTenantId() != null ? def.getTenantId() : TenantContext.getTenantId();
        instance.setTenantId(tenantId);

        Token token = new Token(instance.getId(), def.getStartNodeId());
        instance.addToken(token);

        // 实例 id 此刻尚未落库，resolveRoot 会退化为「自身即根」——正是我们要的锁粒度
        return exclusive(instance.getId(), "start", () -> {
            instanceRepo.save(instance);

            log.info("[引擎] 发起流程 instance={} key={} v{} initiator={} tenant={}", 
                    instance.getId(), def.getKey(), def.getVersion(), initiator, tenantId);
            audit(AuditEventType.PROCESS_STARTED, instance.getId(), null, initiator != null ? initiator : "system",
                    "发起流程 key=" + def.getKey() + " v" + def.getVersion());
            listenerSupport.fireExecutionStarted(instance);
            // 推进第一个 Token
            advanceToken(instance, def, token.getId());
            return instance.getId();
        });
    }

    /**
     * 定义里含候选组却没配解析器 → 启动前直接失败。
     *
     * <p>只做零成本的配置检查（不调用解析器）：真正的组织数据问题留给建任务时暴露，
     * 那里能同时拿到具体节点与组名，错误信息更精确。
     */
    private void requireGroupResolverIfNeeded(ProcessDefinition def) {
        if (groupResolver != null) {
            return;
        }
        List<String> groupNodes = new ArrayList<>();
        for (NodeDefinition node : def.getNodes().values()) {
            Candidate nc = node.getCandidate();
            if (nc != null && nc.hasGroups()) {
                groupNodes.add(node.getId() + "=" + nc.getGroupIds());
            }
        }
        if (!groupNodes.isEmpty()) {
            throw new GroupResolutionException(
                    "流程 [" + def.getKey() + "] 含候选组节点 " + groupNodes
                            + "，但引擎未配置 GroupResolver，无法展开为具体用户。"
                            + "请先注入组织架构解析器（engine.setGroupResolver(...)）再启动",
                    null, null);
        }
    }

    @Override
    public List<String> batchStart(String processKey, List<Map<String, Object>> variablesList) {
        return batchStart(processKey, -1, variablesList);
    }

    @Override
    public List<String> batchStart(String processKey, int version, List<Map<String, Object>> variablesList) {
        if (variablesList == null || variablesList.isEmpty()) {
            return new ArrayList<>();
        }
        ProcessDefinition def = version > 0
                ? processRepo.findByKeyAndVersion(processKey, version)
                : processRepo.findByKey(processKey);
        if (def == null) {
            throw new IllegalArgumentException("流程定义不存在: " + processKey + (version > 0 ? " v" + version : ""));
        }

        // 批量优化：先创建所有实例，再单事务批量插入
        List<ProcessInstance> instances = new ArrayList<>();
        List<String> instanceIds = new ArrayList<>();
        List<Token> firstTokens = new ArrayList<>();

        // 租户 ID：流程定义优先，其次当前上下文
        String tenantId = def.getTenantId() != null ? def.getTenantId() : TenantContext.getTenantId();

        for (Map<String, Object> variables : variablesList) {
            // 变量校验
            VariableValidator.validate(def, variables);

            ProcessInstance instance = new ProcessInstance(def.getKey(), def.getVersion());
            if (variables != null) {
                variables.forEach(instance::setVariable);
            }
            instance.setTenantId(tenantId);  // 批量实例继承租户

            Token token = new Token(instance.getId(), def.getStartNodeId());
            instance.addToken(token);

            instances.add(instance);
            instanceIds.add(instance.getId());
            firstTokens.add(token);
        }

        // 批量保存（单事务）
        instanceRepo.saveBatch(instances);

        // 审计 + 监听器 + Token 推进（逐个实例）
        for (int i = 0; i < instances.size(); i++) {
            ProcessInstance instance = instances.get(i);
            Token token = firstTokens.get(i);

            log.info("[引擎] 批量发起流程 instance={} key={} v{}", instance.getId(), def.getKey(), def.getVersion());
            audit(AuditEventType.PROCESS_STARTED, instance.getId(), null, "system",
                    "批量发起流程 key=" + def.getKey() + " v" + def.getVersion());
            listenerSupport.fireExecutionStarted(instance);
            // 推进第一个 Token
            advanceToken(instance, def, token.getId());
        }

        return instanceIds;
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

    // ========== 审批意见 ==========
    //
    // 意见是一等数据：归档、导出、责任追溯都靠它，因此**不随历史保留策略清理**。
    // 与 AuditLog 的分工见 Comment 的类注释 —— 审计是系统流水，意见是人的表达。

    @Override
    public Comment addComment(String instanceId, String taskId, String userId, String message) {
        return addComment(instanceId, taskId, userId, CommentType.COMMENT, message);
    }

    @Override
    public Comment addComment(String instanceId, String taskId, String userId,
                              CommentType type, String message) {
        Objects.requireNonNull(instanceId, "instanceId 不能为空");
        Objects.requireNonNull(userId, "userId 不能为空");
        Objects.requireNonNull(type, "type 不能为空");
        CommentRepository repo = requireCommentRepo();
        return exclusive(instanceId, "addComment", () -> {
            if (instanceRepo.findById(instanceId) == null) {
                throw new IllegalArgumentException("流程实例不存在: " + instanceId);
            }
            String nodeId = null;
            if (taskId != null) {
                TaskInstance task = taskRepo.findById(taskId);
                if (task == null) {
                    throw new IllegalArgumentException("任务不存在: " + taskId);
                }
                nodeId = task.getNodeId();
            }
            Comment comment = new Comment(instanceId, taskId, nodeId, userId, type, message);
            repo.save(comment);
            log.info("[引擎] {} 在流程 {} 上留下意见 type={} task={}", userId, instanceId, type,
                    taskId == null ? "-" : taskId);
            return comment;
        });
    }

    @Override
    public List<Comment> getTaskComments(String taskId) {
        return requireCommentRepo().findByTaskId(taskId);
    }

    @Override
    public List<Comment> getInstanceComments(String instanceId) {
        return requireCommentRepo().findByInstanceId(instanceId);
    }

    @Override
    public boolean supportsComments() {
        return commentRepo != null;
    }

    /** 未注入意见仓储时快速失败 —— 显式报错比静默丢数据好。 */
    private CommentRepository requireCommentRepo() {
        if (commentRepo == null) {
            throw new IllegalStateException("未启用审批意见功能，请注入 CommentRepository");
        }
        return commentRepo;
    }

    /**
     * 记录一条随审批动作产生的意见 —— 必须在 {@link #exclusive} 临界区内调用。
     *
     * <p>未注入意见仓储时静默跳过：这类意见是审批动作的<b>副作用</b>，
     * 不该因为没配意见仓储就让完成 / 驳回本身失败（与审计、抄送的定位一致）。
     */
    private void recordApprovalComment(TaskInstance task, String userId,
                                       CommentType type, String message) {
        if (commentRepo == null) {
            return;
        }
        commentRepo.save(new Comment(task.getInstanceId(), task.getId(), task.getNodeId(),
                userId, type, message));
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

    /**
     * 完成任务并附意见 —— 与三参重载的唯一区别是同一次事务里多写一条意见，
     * 避免「意见存了流程没推进」这类靠补偿收拾的分裂状态。
     *
     * <p>{@code comment} 为 null / 空白时不写意见，行为与三参重载完全一致。
     */
    @Override
    public void completeTask(String taskId, String userId, boolean approved, String comment) {
        exclusiveVoidByTask(taskId, "completeTask", () -> {
            if (!approved) {
                rejectTaskInternal(taskId, userId, comment == null || comment.isBlank()
                        ? "未提供理由" : comment);
                return;
            }
            completeAndAdvance(taskId, userId);
            if (comment != null && !comment.isBlank()) {
                TaskInstance task = taskRepo.findById(taskId);
                if (task != null) {
                    recordApprovalComment(task, userId, CommentType.APPROVE, comment);
                }
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
                throw new IllegalArgumentException(task.getCandidate().explainRejection(userId) + "，且无委托关系");
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
        listenerSupport.fireTaskCompleted(task, actualApprover);

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
            throw new IllegalArgumentException(task.getCandidate().explainRejection(userId));
        }
        task.setStatus(TaskStatus.REJECTED);
        taskRepo.save(task);
        syncTaskInInstance(instance, task);
        // 驳回理由进一等存储 —— 不再只落在会被历史保留策略清理的 AuditLog.detail 里
        recordApprovalComment(task, userId, CommentType.REJECT, reason);

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
        listenerSupport.fireTaskRejected(task, userId, reason);
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
            throw new IllegalArgumentException(task.getCandidate().explainRejection(fromUserId));
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
        newTask.setArrival(task.getArrival());  // 转办继承原任务的到达代次
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
            afterCommitSchedule(() -> scheduler.schedule(newTaskId, instanceId,
                    newTask.getCreateTime() + timeout, policy, target));
        }

        log.info("[引擎] 转办 task={} from={} to={}", taskId, fromUserId, toUserId);
        audit(AuditEventType.TASK_TRANSFERRED, instance.getId(), taskId, fromUserId,
                "转办给 toUser=" + toUserId + " newTaskId=" + newTask.getId());
        listenerSupport.fireTaskTransferred(task, fromUserId, toUserId);
    }

    @Override
    public void adminTransferTask(String taskId, String toUserId, String operator) {
        exclusiveVoidByTask(taskId, "adminTransferTask",
                () -> adminTransferTaskInternal(taskId, toUserId, operator));
    }

    /**
     * 管理员强制改派实现 —— 必须在 {@link #exclusiveVoidByTask} 内调用。
     *
     * <p>与 {@link #transferTaskInternal} 的唯一差别是<b>不做候选人校验</b>：
     * 常规转办要求发起人本身就是候选人（防越权改派别人的活），而这条通道
     * 正是为"候选人离职、长期不在，或组织架构故障导致没人能接手"准备的。
     *
     * <p>引擎不判断 {@code operator} 的权限 —— 它不知道调用方的权限模型，
     * 这与不替调用方决定组织架构是同一条边界。调用方须自行鉴权。
     */
    private void adminTransferTaskInternal(String taskId, String toUserId, String operator) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("管理员改派必须记录操作人（operator）");
        }
        if (toUserId == null || toUserId.isBlank()) {
            throw new IllegalArgumentException("管理员改派必须指定目标用户（toUserId）");
        }
        TaskInstance task = taskRepo.findById(taskId);
        ensureRunning(task);
        ProcessInstance instance = instanceRepo.findById(task.getInstanceId());

        // 简化:与常规转办一致 —— 原任务置 TRANSFERRED，同节点新建一个给 toUserId 的任务
        task.setStatus(TaskStatus.TRANSFERRED);
        taskRepo.save(task);

        // 同步 instance 视图：转办后待办列表必须反映 TRANSFERRED
        syncTaskInInstance(instance, task);

        // 取消原任务的超时调度（调度器活在 JVM 内存，回滚时不该生效 → 登记到提交后）
        afterCommitSchedule(() -> scheduler.cancel(taskId));

        ProcessDefinition def = defOf(instance);
        NodeDefinition nodeDef = def.getNode(task.getNodeId());
        TaskInstance newTask = new TaskInstance(instance.getId(), task.getTokenId(),
                nodeDef.getId(), Candidate.ofAny(toUserId));
        newTask.setArrival(task.getArrival());  // 改派继承原任务的到达代次
        instance.addTask(newTask);
        taskRepo.save(newTask);
        instanceRepo.save(instance);

        // 新任务继承原节点的超时配置(若有)
        if (nodeDef.hasTimeout()) {
            String newTaskId = newTask.getId();
            String instanceId = instance.getId();
            long timeout = nodeDef.getTimeoutMillis();
            TimeoutPolicy policy = nodeDef.getTimeoutPolicy();
            String target = nodeDef.getTimeoutTargetUserId();
            afterCommitSchedule(() -> scheduler.schedule(newTaskId, instanceId,
                    newTask.getCreateTime() + timeout, policy, target));
        }

        log.info("[引擎] 管理员改派 task={} to={} operator={}", taskId, toUserId, operator);
        audit(AuditEventType.TASK_TRANSFERRED, instance.getId(), taskId, operator,
                "管理员强制改派给 toUser=" + toUserId + " newTaskId=" + newTask.getId()
                        + "（原候选人=" + task.getCandidate().getAllIds() + "）");
        listenerSupport.fireTaskTransferred(task, operator, toUserId);
    }

    /** 把调度器等不受事务保护的副作用推迟到事务提交后执行。 */
    private void afterCommitSchedule(Runnable action) {
        TransactionContext.afterCommit(action);
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
            listenerSupport.fireExecutionSuspended(instance);
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
            listenerSupport.fireExecutionResumed(instance);
        });
    }

    @Override
    public void terminate(String instanceId) {
        exclusiveVoid(instanceId, "terminate", () -> {
            ProcessInstance instance = instanceRepo.findById(instanceId);
            
            // 检查实例状态
            if (instance.getStatus() != InstanceStatus.RUNNING) {
                throw new IllegalStateException("仅 RUNNING 状态的流程可终止,当前: " + instance.getStatus());
            }
            
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
            listenerSupport.fireExecutionTerminated(instance);
        });
    }

    // ========== 运行期变量 ==========

    @Override
    public void setVariable(String instanceId, String key, Object value, String operator) {
        // 用 singletonMap 而非 Map.of：变量值允许为 null(清除该变量的写法)，Map.of 会直接 NPE
        setVariables(instanceId, Collections.singletonMap(key, value), operator);
    }

    @Override
    public void setVariables(String instanceId, Map<String, Object> variables, String operator) {
        if (variables == null || variables.isEmpty()) {
            throw new IllegalArgumentException("变量不能为空");
        }
        exclusiveVoid(instanceId, "setVariables", () -> {
            ProcessInstance instance = instanceRepo.findById(instanceId);
            if (instance == null) {
                throw new IllegalArgumentException("流程实例不存在: " + instanceId);
            }

            InstanceStatus status = instance.getStatus();
            // 挂起只暂停"推进"、不冻结"数据"：挂起 → 改数据 → 恢复 是常规运维动作。
            // 已结束的实例则一律拒绝 —— 事后改数据会污染审计与效能报表。
            if (status != InstanceStatus.RUNNING && status != InstanceStatus.SUSPENDED) {
                throw new IllegalStateException("仅 RUNNING / SUSPENDED 实例可修改变量,当前: " + status);
            }

            // 1. 全部校验通过才开始写 —— 批量要么全成、要么全不动
            ProcessDefinition def = defOf(instance);
            for (Map.Entry<String, Object> entry : variables.entrySet()) {
                ensureVariableWritable(def, entry.getKey(), entry.getValue());
            }

            // 2. 写入(顺带抓旧值 —— 审计要能回答"改前是什么")
            StringBuilder detail = new StringBuilder();
            for (Map.Entry<String, Object> entry : variables.entrySet()) {
                if (detail.length() > 0) {
                    detail.append(", ");
                }
                detail.append(entry.getKey()).append('=')
                      .append(entry.getValue())
                      .append("(原 ").append(instance.getVariable(entry.getKey())).append(')');
                if (entry.getValue() == null) {
                    // 值为 null 即"清除"。必须真删 key 而非存 null 占位 —— JSON 序列化会丢掉
                    // null 值，"存 null"在 InMemory 与 JPA/MyBatis 下会分叉成两种结果。
                    instance.removeVariable(entry.getKey());
                } else {
                    instance.setVariable(entry.getKey(), entry.getValue());
                }
            }
            instanceRepo.save(instance);

            // 3. 审计
            String op = operator != null && !operator.isBlank() ? operator : SYSTEM_USER;
            audit(AuditEventType.VARIABLE_UPDATED, instanceId, null, op, "变量更新: " + detail);
            log.info("[引擎] 实例 {} 变量更新: {}", instanceId, detail);
        });
    }

    /**
     * 变量可写性校验：非空 → 不占用保留前缀 → 类型符合定义(未声明则放行)。
     *
     * <p>保留前缀这关不是洁癖。放进来的后果：
     * <ul>
     *   <li>{@code __initiator} —— 伪造发起人，越权撤回他人流程；</li>
     *   <li>{@code __mi_<tokenId>} 写成 {@code expanded} —— 骗过多实例节点的幂等判定，
     *       会签节点的任务<b>根本不会展开</b>；</li>
     *   <li>{@code __dynamic_<tokenId>} 写成 {@code created} —— 同上，动态并行支被跳过；</li>
     *   <li>{@code __sub_<tokenId>} —— 子流程发起标记被改写，子流程重复发起。</li>
     * </ul>
     */
    private void ensureVariableWritable(ProcessDefinition def, String key, Object value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("变量名不能为空");
        }
        if (key.startsWith(RESERVED_VAR_PREFIX)) {
            throw new IllegalArgumentException(String.format(
                    "变量名 [%s] 占用引擎保留前缀 %s —— 该前缀下是发起人、子流程标记、多实例展开标记"
                            + "等内部状态，外部写入会破坏撤回鉴权与节点幂等判定",
                    key, RESERVED_VAR_PREFIX));
        }
        VariableValidator.validateOne(def, key, value);
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
        listenerSupport.fireExecutionTerminated(instance);
        // 触发任务撤回事件
        for (TaskInstance t : tasks) {
            if (t.getStatus() == TaskStatus.WITHDRAWN) {
                listenerSupport.fireTaskWithdrawn(t);
            }
        }
        });
    }

    @Override
    public void jumpToNode(String instanceId, String targetNodeId, String operator, String reason) {
        exclusiveVoid(instanceId, "jumpToNode", () -> {
            ProcessInstance instance = instanceRepo.findById(instanceId);
            
            // 1. 检查实例状态
            if (instance.getStatus() != InstanceStatus.RUNNING) {
                throw new IllegalStateException("仅 RUNNING 状态的流程可跳转,当前: " + instance.getStatus());
            }
            
            // 2. 获取流程定义，检查目标节点是否存在
            ProcessDefinition def = defOf(instance);
            if (def.getNode(targetNodeId) == null) {
                throw new IllegalArgumentException("目标节点 " + targetNodeId + " 不存在于流程定义中");
            }
            
            // 3. 消耗所有活跃 Token
            List<String> consumedTokenIds = new ArrayList<>(instance.getActiveTokens().keySet());
            for (String tokenId : consumedTokenIds) {
                instance.consumeToken(tokenId);
            }
            
            // 4. 终止所有 PENDING 任务
            List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
            List<String> terminatedTaskIds = new ArrayList<>();
            for (TaskInstance t : tasks) {
                if (t.getStatus() == TaskStatus.PENDING) {
                    t.setStatus(TaskStatus.TERMINATED);
                    taskRepo.save(t);
                    syncTaskInInstance(instance, t);
                    terminatedTaskIds.add(t.getId());
                }
            }
            
            // 5. 在目标节点创建新 Token
            Token newToken = new Token(instance.getId(), targetNodeId);
            instance.addToken(newToken);
            instanceRepo.save(instance);
            
            // 6. 推进 Token（如果是 UserTask 会创建新任务）
            tokenAdvancer.advanceToken(instance, def, newToken.getId(), recordsActivity());
            
            // 7. 取消旧任务的超时调度
            afterCommitSchedule(() -> terminatedTaskIds.forEach(scheduler::cancel));
            
            log.info("[引擎] 操作人 {} 跳转流程 {} 到节点 {} 原因: {}", 
                    operator, instanceId, targetNodeId, reason);
            audit(AuditEventType.PROCESS_JUMPED, instanceId, null, operator, 
                    "跳转到节点 " + targetNodeId + (reason != null ? " 原因: " + reason : ""));
        });
    }

    @Override
    public void jumpTokenToNode(String instanceId, String tokenId, String targetNodeId,
                                String operator, String reason) {
        exclusiveVoid(instanceId, "jumpTokenToNode", () -> {
            ProcessInstance instance = instanceRepo.findById(instanceId);
            if (instance.getStatus() != InstanceStatus.RUNNING) {
                throw new IllegalStateException("仅 RUNNING 状态的流程可逐支跳转,当前: " + instance.getStatus());
            }
            ProcessDefinition def = defOf(instance);
            if (def.getNode(targetNodeId) == null) {
                throw new IllegalArgumentException("目标节点 " + targetNodeId + " 不存在于流程定义中");
            }
            Token token = instance.getActiveTokens().get(tokenId);
            if (token == null) {
                throw new IllegalArgumentException("Token 不存在或已消耗: " + tokenId);
            }
            // 只终止「这一支(token)」当前节点的 PENDING 待办，其它并行支不受影响
            for (TaskInstance t : taskRepo.findByInstanceId(instanceId)) {
                if (tokenId.equals(t.getTokenId()) && t.getStatus() == TaskStatus.PENDING) {
                    t.setStatus(TaskStatus.TERMINATED);
                    taskRepo.save(t);
                    syncTaskInInstance(instance, t);
                }
            }
            token.moveTo(targetNodeId);
            instanceRepo.save(instance);
            advanceToken(instance, def, tokenId);  // 目标是 USER_TASK 会新建该支待办
            log.info("[引擎] 逐支跳转 instance={} token={} -> {} by={} reason={}",
                    instanceId, tokenId, targetNodeId, operator, reason);
            audit(AuditEventType.PROCESS_JUMPED, instanceId, null, operator,
                    "逐支跳转 token=" + tokenId + " -> " + targetNodeId
                            + (reason != null ? " 原因: " + reason : ""));
        });
    }

    // ========== 加签/减签（多实例会签） ==========

    @Override
    public void addSign(String instanceId, String nodeId, String assignee, String operator) {
        exclusiveVoid(instanceId, "addSign", () -> {
            ProcessInstance instance = instanceRepo.findById(instanceId);
            if (instance.getStatus() != InstanceStatus.RUNNING) {
                throw new IllegalStateException("仅 RUNNING 状态的流程可加签,当前: " + instance.getStatus());
            }
            Token token = instance.getActiveTokens().values().stream()
                    .filter(t -> nodeId.equals(t.getCurrentNodeId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("节点无进行中任务,无法加签: " + nodeId));
            TaskInstance t = new TaskInstance(instanceId, token.getId(), nodeId, Candidate.ofAny(assignee));
            t.setTenantId(instance.getTenantId());
            t.setArrival(token.getArrival());  // 加签继承当前任务的到达代次
            taskRepo.save(t);
            syncTaskInInstance(instance, t);
            instanceRepo.save(instance);
            log.info("[引擎] 加签 instance={} node={} assignee={} by={}", instanceId, nodeId, assignee, operator);
            audit(AuditEventType.SIGN_ADDED, instanceId, t.getId(), operator,
                    "加签 " + assignee + " @ " + nodeId);
        });
    }

    @Override
    public void removeSign(String instanceId, String nodeId, String assignee, String operator) {
        exclusiveVoid(instanceId, "removeSign", () -> {
            ProcessInstance instance = instanceRepo.findById(instanceId);
            if (instance.getStatus() != InstanceStatus.RUNNING) {
                throw new IllegalStateException("仅 RUNNING 状态的流程可减签,当前: " + instance.getStatus());
            }
            Token token = instance.getActiveTokens().values().stream()
                    .filter(t -> nodeId.equals(t.getCurrentNodeId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("节点无进行中任务,无法减签: " + nodeId));
            TaskInstance target = taskRepo.findByInstanceId(instanceId).stream()
                    .filter(t -> token.getId().equals(t.getTokenId()) && nodeId.equals(t.getNodeId())
                            && t.getStatus() == TaskStatus.PENDING
                            && t.getCandidate().getUserIds().contains(assignee))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("无可移除的待办: assignee=" + assignee + " node=" + nodeId));
            target.setStatus(TaskStatus.TERMINATED);
            taskRepo.save(target);
            syncTaskInInstance(instance, target);
            instanceRepo.save(instance);
            log.info("[引擎] 减签 instance={} node={} assignee={} by={}", instanceId, nodeId, assignee, operator);
            audit(AuditEventType.SIGN_REMOVED, instanceId, target.getId(), operator,
                    "减签 " + assignee + " @ " + nodeId);
            // 重判完成条件：若移除后该节点无 pending 且已有完成，ALL 将推进
            advanceToken(instance, defOf(instance), token.getId());
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
        
        List<EventRepository.TimerEvent> expiredTimers = 
                eventRepo.getExpiredTimers(Instant.now());
        log.info("[引擎] checkAndTriggerTimers 发现 {} 个到期定时器", expiredTimers.size());
        
        for (var timer : expiredTimers) {
            exclusiveVoid(timer.instanceId(), "checkAndTriggerTimers", () -> {
                ProcessInstance instance = instanceRepo.findById(timer.instanceId());
                
                // 实例可能已经完成或终止，跳过
                if (instance.getStatus() != InstanceStatus.RUNNING) {
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

    /**
     * 进程重启后恢复超时调度。
     *
     * <p>扫描仍处于 PENDING 的任务，按 {@code createTime + 节点超时配置} 重算到期时刻
     * 重新注册；已经过期的交给调度器立即触发（异步，不阻塞调用方）。
     *
     * <p>不调用它数据也不会坏，但重启前建立的待办会<b>静默地</b>永不超时。因此
     * {@link WorkflowEngineBuilder} 默认在 {@code build()} 时自动调用一次，
     * 可用 {@code autoRecoverTimeouts(false)} 关闭。
     *
     * @return 实际恢复的调度数量
     * @see TimeoutHandler#restoreTimeouts
     */
    public int recoverTimeouts() {
        return timeoutHandler.restoreTimeouts(taskRepo.findByStatus(TaskStatus.PENDING));
    }

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

    /**
     * 条件查询 —— 直接委托仓储，由它把条件下推到数据库（见 {@link TaskFilter}）。
     *
     * <p>引擎这一层刻意<b>不</b>掺和过滤：一旦在这里补一道内存过滤，
     * 就等于默认仓储给的是「可能多出来一批」的结果，下推的意义会被抹掉一半。
     * 精筛是仓储自己的职责（{@link TaskFilter#matches}）。
     */
    @Override
    public List<TaskInstance> findTasks(TaskFilter filter) {
        return taskRepo.findPaged(filter);
    }

    @Override
    public long countTasks(TaskFilter filter) {
        return taskRepo.countByFilter(filter);
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
    /** 候选组解析器；启动期预检用，建任务时的展开由 {@link TokenAdvancer} 共用同一引用。 */
    private GroupResolver groupResolver;

    /**
     * 注入候选组解析器（引擎与组织架构的接缝）。
     *
     * <p>不注入 → 定义里带候选组的流程会<b>拒绝启动</b>并抛
     * {@link GroupResolutionException}：部署期没接组织架构属于配置错误，
     * 要在实例落库之前暴露，而不是留下一个谁都办不了的任务。
     *
     * <p>展开结果是<b>快照</b>：只在任务创建时解析一次，此后组成员变动不影响在途任务。
     * 原始组名仍留在任务的候选信息里，供审计与「我所在组的待办」查询。
     * 紧急情况下可用 {@link #adminTransferTask} 强行改派。
     */
    public void setGroupResolver(GroupResolver groupResolver) {
        this.groupResolver = groupResolver;
        this.tokenAdvancer.setGroupResolver(groupResolver);
    }

    private void advanceToken(ProcessInstance instance, ProcessDefinition def, String tokenId) {
        tokenAdvancer.advanceToken(instance, def, tokenId, recordsActivity());
    }

    /** 回调：启动子流程 - 委托给 SubProcessHandler */
    private void startSubProcessInternal(ProcessInstance parent, ProcessDefinition parentDef,
                                        Token parentToken, NodeDefinition subNode) {
        subProcessHandler.startSubProcess(parent, parentDef, parentToken, subNode);
    }

    /** 回调：子流程完成后推进父流程 - 委托给 SubProcessHandler */
    private void onSubProcessCompletedInternal(ProcessInstance child) {
        subProcessHandler.onSubProcessCompleted(child);
    }

    /** 回调：事务提交后调度 */
    private void afterCommitScheduleInternal(Runnable action) {
        afterCommitSchedule(action);
    }

    private void ensureRunning(TaskInstance task) {
        if (task.getStatus() != TaskStatus.PENDING) {
            throw new IllegalStateException("任务非 PENDING 状态: " + task.getStatus());
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

    // ========== 批处理 API ==========

    @Override
    public BatchResult batchCompleteTasks(List<String> taskIds, String userId, boolean approved) {
        if (taskIds == null || taskIds.isEmpty()) {
            return BatchResult.allSuccess(0);
        }

        List<BatchResult.FailureDetail> failures = new ArrayList<>();
        AtomicInteger successCount = new AtomicInteger(0);

        // 使用事务保证原子性
        tx.execute(() -> {
            for (String taskId : taskIds) {
                try {
                    completeTask(taskId, userId, approved);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    log.warn("[批处理] 完成任务 {} 失败: {}", taskId, e.getMessage());
                    failures.add(new BatchResult.FailureDetail(taskId, e));
                }
            }
        });

        // 如果有失败，抛出异常让事务回滚
        if (!failures.isEmpty()) {
            throw new BatchPartialFailureException(
                    String.format("批处理部分失败：成功 %d，失败 %d", successCount.get(), failures.size()),
                    new BatchResult(taskIds.size(), successCount.get(), failures));
        }

        return BatchResult.allSuccess(taskIds.size());
    }

    @Override
    public BatchResult batchTerminateInstances(List<String> instanceIds, String operator, String reason) {
        if (instanceIds == null || instanceIds.isEmpty()) {
            return BatchResult.allSuccess(0);
        }

        List<BatchResult.FailureDetail> failures = new ArrayList<>();
        AtomicInteger successCount = new AtomicInteger(0);

        // 使用事务保证原子性
        tx.execute(() -> {
            for (String instanceId : instanceIds) {
                try {
                    terminate(instanceId);
                    successCount.incrementAndGet();
                    // 记录审计日志
                    audit(AuditEventType.PROCESS_TERMINATED, instanceId, null, operator,
                            reason != null ? "批量终止: " + reason : "批量终止");
                } catch (Exception e) {
                    log.warn("[批处理] 终止实例 {} 失败: {}", instanceId, e.getMessage());
                    failures.add(new BatchResult.FailureDetail(instanceId, e));
                }
            }
        });

        // 如果有失败，抛出异常让事务回滚
        if (!failures.isEmpty()) {
            throw new BatchPartialFailureException(
                    String.format("批处理部分失败：成功 %d，失败 %d", successCount.get(), failures.size()),
                    new BatchResult(instanceIds.size(), successCount.get(), failures));
        }

        return BatchResult.allSuccess(instanceIds.size());
    }

    @Override
    public void migrateInstance(String instanceId, String targetProcessKey,
                                int targetVersion, Map<String, String> nodeMapping, String operator) {
        exclusiveVoid(instanceId, "migrateInstance", () -> {
            // 1. 校验实例状态
            ProcessInstance instance = instanceRepo.findById(instanceId);
            if (instance.getStatus() != InstanceStatus.RUNNING) {
                throw new IllegalStateException("仅 RUNNING 实例可迁移，当前状态：" + instance.getStatus());
            }

            // 2. 获取目标流程定义
            ProcessDefinition targetDef;
            if (targetVersion < 0) {
                targetDef = processRepo.findByKey(targetProcessKey);
            } else {
                targetDef = processRepo.findByKeyAndVersion(targetProcessKey, targetVersion);
            }
            if (targetDef == null) {
                throw new IllegalStateException("目标流程定义不存在：" + targetProcessKey + " v" + targetVersion);
            }

            // 3. 校验节点映射
            Map<String, String> effectiveMapping = nodeMapping != null ? nodeMapping : Map.of();
            for (Token token : instance.getActiveTokens().values()) {
                String oldNodeId = token.getCurrentNodeId();
                String newNodeId = effectiveMapping.getOrDefault(oldNodeId, oldNodeId);
                NodeDefinition newNode = targetDef.getNode(newNodeId);
                if (newNode == null) {
                    throw new IllegalStateException("目标节点不存在：" + newNodeId);
                }
                // 类型兼容性校验
                NodeDefinition oldNode = defOf(instance).getNode(oldNodeId);
                if (oldNode != null && oldNode.getType() != newNode.getType()) {
                    throw new IllegalStateException("节点类型不兼容：" + oldNodeId + "(" + oldNode.getType() + ") → " + newNodeId + "(" + newNode.getType() + ")");
                }
            }

            // 4. 执行迁移
            setFinal(instance, "processKey", targetProcessKey);
            setFinal(instance, "processVersion", targetDef.getVersion());
            for (Token token : instance.getActiveTokens().values()) {
                String oldNodeId = token.getCurrentNodeId();
                String newNodeId = effectiveMapping.getOrDefault(oldNodeId, oldNodeId);
                if (!oldNodeId.equals(newNodeId)) {
                    setFinal(token, "currentNodeId", newNodeId);
                }
            }
            instanceRepo.save(instance);

            // 5. 记录审计
            if (auditLogRepo != null) {
                auditLogRepo.save(new AuditLog(
                        instanceId,
                        null,
                        AuditEventType.INSTANCE_MIGRATED,
                        operator != null ? operator : SYSTEM_USER,
                        "迁移到 " + targetProcessKey + " v" + targetDef.getVersion() +
                                (effectiveMapping.isEmpty() ? "" : " 节点映射：" + effectiveMapping)
                ));
            }
            log.info("[引擎] 实例 {} 迁移到 {} v{}，节点映射：{}", instanceId, targetProcessKey, targetDef.getVersion(), effectiveMapping);
        });
    }

    @Override
    public BatchResult migrateInstances(List<String> instanceIds, String targetProcessKey,
                                        int targetVersion, Map<String, String> nodeMapping,
                                        String operator) {
        List<BatchResult.FailureDetail> failures = new ArrayList<>();
        int successCount = 0;
        for (String instanceId : instanceIds) {
            try {
                // 逐个实例走一次 migrateInstance —— 它内部是 exclusive → tx.execute，
                // 天然构成一个独立事务。本方法自身刻意不开事务：若把 N 个实例塞进同一个
                // 事务，一个失败就会把已经成功的那些一起回滚，而这恰恰是批量迁移要避免的。
                migrateInstance(instanceId, targetProcessKey, targetVersion, nodeMapping, operator);
                successCount++;
            } catch (RuntimeException ex) {
                // 单个实例失败不中断整批：运维要的是"哪几个没成、各自为什么"，
                // 而不是拿到第一个异常、剩下的动没动全靠猜。
                failures.add(new BatchResult.FailureDetail(instanceId, ex));
                log.warn("[引擎] 实例 {} 批量迁移失败，跳过并继续后续实例：{}", instanceId, ex.getMessage());
            }
        }
        BatchResult result = BatchResult.partialFailure(instanceIds.size(), successCount, failures);
        log.info("[引擎] 批量迁移完成：{}", result);
        return result;
    }

    /** 反射设置 final 字段（用于迁移等场景） */
    private static void setFinal(Object target, String fieldName, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("设置字段失败：" + fieldName, ex);
        }
    }

    @Override
    public DashboardMetrics dashboard(int bottleneckTopN) {
        return monitoring.snapshot(bottleneckTopN);
    }

    @Override
    public DashboardMetrics dashboard(int bottleneckTopN, String tenantId) {
        return monitoring.snapshot(bottleneckTopN, tenantId);
    }

    // ========== 拓扑自省 ==========

    @Override
    public TopologyView getTopology(String processKey, int version) {
        ProcessDefinition def;
        if (version < 0) {
            def = processRepo.findByKey(processKey);
        } else {
            def = processRepo.findByKeyAndVersion(processKey, version);
        }
        if (def == null) {
            throw new IllegalArgumentException("流程定义不存在：" + processKey + " v" + version);
        }

        // 构建节点视图
        List<NodeView> nodes = new ArrayList<>();
        for (NodeDefinition nodeDef : def.getNodes().values()) {
            List<String> userIds = nodeDef.getCandidate() != null ? 
                    new ArrayList<>(nodeDef.getCandidate().getUserIds()) : null;
            List<String> groupIds = nodeDef.getCandidate() != null && nodeDef.getCandidate().hasGroups()
                    ? new ArrayList<>(nodeDef.getCandidate().getGroupIds()) : null;
            nodes.add(new NodeView(
                    nodeDef.getId(),
                    nodeDef.getName(),
                    nodeDef.getType(),
                    userIds,
                    groupIds,
                    nodeDef.getAssigneeVariable(),
                    nodeDef.getDelegateKey()
            ));
        }

        // 构建连线视图
        List<TransitionView> transitions = new ArrayList<>();
        for (NodeDefinition nodeDef : def.getNodes().values()) {
            List<Transition> outs = def.getOutgoing(nodeDef.getId());
            if (outs != null) {
                for (Transition t : outs) {
                    transitions.add(new TransitionView(
                            nodeDef.getId(),
                            t.getTo(),
                            t.getCondition()
                    ));
                }
            }
        }

        return new TopologyView(
                def.getKey(),
                def.getVersion(),
                def.getName(),
                nodes,
                transitions
        );
    }

    @Override
    public InstanceTopologyView getInstanceTopology(String instanceId) {
        ProcessInstance instance = instanceRepo.findById(instanceId);
        if (instance == null) {
            throw new IllegalArgumentException("实例不存在：" + instanceId);
        }

        // 获取基础拓扑
        TopologyView topology = getTopology(instance.getProcessKey(), instance.getProcessVersion());

        // 收集当前 Token 所在节点
        List<String> activeNodeIds = new ArrayList<>();
        for (Token token : instance.getActiveTokens().values()) {
            activeNodeIds.add(token.getCurrentNodeId());
        }

        // 收集已完成节点（从历史任务中提取）
        List<String> completedNodeIds = new ArrayList<>();
        for (TaskInstance task : instance.getTasks()) {
            if (task.getStatus() == TaskStatus.COMPLETED) {
                if (!completedNodeIds.contains(task.getNodeId())) {
                    completedNodeIds.add(task.getNodeId());
                }
            }
        }

        return new InstanceTopologyView(
                topology,
                activeNodeIds,
                completedNodeIds,
                instance.getStatus()
        );
    }
}