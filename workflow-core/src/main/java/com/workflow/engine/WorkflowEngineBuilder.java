package com.workflow.engine;

import com.workflow.concurrency.InstanceLockProvider;
import com.workflow.concurrency.LocalInstanceLocks;
import com.workflow.repository.AuditLogRepository;
import com.workflow.repository.CarbonCopyRepository;
import com.workflow.repository.DelegationRepository;
import com.workflow.repository.EventRepository;
import com.workflow.repository.HistoryRepository;
import com.workflow.repository.InstanceRepository;
import com.workflow.repository.ProcessRepository;
import com.workflow.repository.TaskRepository;
import com.workflow.tx.TransactionRunner;
import com.workflow.tx.UndoLogTransactionRunner;

import java.util.EnumSet;

/**
 * WorkflowEngine 构建器
 *
 * <p>替代 WorkflowEngine 的多个构造器重载，提供链式 API 配置引擎。
 *
 * <p>用法示例：
 * <pre>{@code
 * // 最简用法
 * WorkflowEngine engine = WorkflowEngineBuilder.builder(procRepo, instRepo, taskRepo).build();
 *
 * // 完整配置
 * WorkflowEngine engine = WorkflowEngineBuilder.builder(procRepo, instRepo, taskRepo)
 *     .auditLogRepository(auditRepo)
 *     .historyRepository(historyRepo)
 *     .eventRepository(eventRepo)
 *     .delegationRepository(delegationRepo)
 *     .notificationService(notificationService)
 *     .carbonCopyRepository(carbonCopyRepo)
 *     .timeoutScheduler(scheduler)
 *     .conflictRetries(3)
 *     .retryBackoffMillis(20)
 *     .build();
 * }</pre>
 */
public final class WorkflowEngineBuilder {

    // 必填
    private final ProcessRepository processRepo;
    private final InstanceRepository instanceRepo;
    private final TaskRepository taskRepo;

    // 可选
    private TimeoutScheduler timeoutScheduler;
    private AuditLogRepository auditLogRepository;
    private DelegationRepository delegationRepository;
    private NotificationService notificationService;
    private CarbonCopyRepository carbonCopyRepository;
    private HistoryRepository historyRepository;
    private EnumSet<com.workflow.enums.HistoryKind> historyKinds = EnumSet.allOf(com.workflow.enums.HistoryKind.class);
    private EventRepository eventRepository;
    private com.workflow.dmn.DecisionRepository decisionRepository;
    private com.workflow.dmn.DecisionHistoryRepository decisionHistoryRepository;
    private com.workflow.form.FormRepository formRepository;
    private com.workflow.form.FormBindingRepository formBindingRepository;
    private com.workflow.attachment.AttachmentRepository attachmentRepository;
    private com.workflow.attachment.AttachmentStorage attachmentStorage;
    private InstanceLockProvider lockProvider;
    private TransactionRunner transactionRunner;
    private int conflictRetries = 3;
    private long retryBackoffMillis = 20;

    private WorkflowEngineBuilder(ProcessRepository processRepo,
                                   InstanceRepository instanceRepo,
                                   TaskRepository taskRepo) {
        this.processRepo = processRepo;
        this.instanceRepo = instanceRepo;
        this.taskRepo = taskRepo;
    }

    /**
     * 创建构建器
     *
     * @param processRepo 流程定义仓储（必填）
     * @param instanceRepo 流程实例仓储（必填）
     * @param taskRepo 任务仓储（必填）
     */
    public static WorkflowEngineBuilder builder(ProcessRepository processRepo,
                                                 InstanceRepository instanceRepo,
                                                 TaskRepository taskRepo) {
        return new WorkflowEngineBuilder(processRepo, instanceRepo, taskRepo);
    }

    /** 超时调度器；不设置时使用默认 {@link ScheduledTimeoutScheduler} */
    public WorkflowEngineBuilder timeoutScheduler(TimeoutScheduler scheduler) {
        this.timeoutScheduler = scheduler;
        return this;
    }

    /** 审计日志仓储；不设置时不记录审计日志 */
    public WorkflowEngineBuilder auditLogRepository(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
        return this;
    }

    /** 委托关系仓储；不设置时不启用委托功能 */
    public WorkflowEngineBuilder delegationRepository(DelegationRepository delegationRepository) {
        this.delegationRepository = delegationRepository;
        return this;
    }

    /** 通知服务；不设置时不启用通知功能 */
    public WorkflowEngineBuilder notificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
        return this;
    }

    /** 抄送仓储；不设置时不启用抄送功能 */
    public WorkflowEngineBuilder carbonCopyRepository(CarbonCopyRepository carbonCopyRepository) {
        this.carbonCopyRepository = carbonCopyRepository;
        return this;
    }

    /** 历史活动仓储；不设置时不记录历史 */
    public WorkflowEngineBuilder historyRepository(HistoryRepository historyRepository) {
        this.historyRepository = historyRepository;
        return this;
    }

    /** 历史类别；null 或空集表示不写任何历史 */
    public WorkflowEngineBuilder historyKinds(EnumSet<com.workflow.enums.HistoryKind> historyKinds) {
        this.historyKinds = historyKinds;
        return this;
    }

    /** 事件仓储；不设置时不启用事件网关 */
    public WorkflowEngineBuilder eventRepository(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
        return this;
    }

    /** 决策仓储；不设置时不启用决策网关 */
    public WorkflowEngineBuilder decisionRepository(com.workflow.dmn.DecisionRepository decisionRepository) {
        this.decisionRepository = decisionRepository;
        return this;
    }

    /** 决策历史仓储；不设置时不记录决策历史 */
    public WorkflowEngineBuilder decisionHistoryRepository(com.workflow.dmn.DecisionHistoryRepository decisionHistoryRepository) {
        this.decisionHistoryRepository = decisionHistoryRepository;
        return this;
    }

    /** 表单仓储；不设置时不启用表单集成 */
    public WorkflowEngineBuilder formRepository(com.workflow.form.FormRepository formRepository) {
        this.formRepository = formRepository;
        return this;
    }

    /** 表单绑定仓储；不设置时不启用表单集成 */
    public WorkflowEngineBuilder formBindingRepository(com.workflow.form.FormBindingRepository formBindingRepository) {
        this.formBindingRepository = formBindingRepository;
        return this;
    }

    /** 附件仓储；不设置时不启用附件管理 */
    public WorkflowEngineBuilder attachmentRepository(com.workflow.attachment.AttachmentRepository attachmentRepository) {
        this.attachmentRepository = attachmentRepository;
        return this;
    }

    /** 附件存储服务；不设置时不启用附件管理 */
    public WorkflowEngineBuilder attachmentStorage(com.workflow.attachment.AttachmentStorage attachmentStorage) {
        this.attachmentStorage = attachmentStorage;
        return this;
    }

    /** 流程树锁；不设置时使用 {@link LocalInstanceLocks} */
    public WorkflowEngineBuilder lockProvider(InstanceLockProvider lockProvider) {
        this.lockProvider = lockProvider;
        return this;
    }

    /** 事务边界；不设置时使用 {@link UndoLogTransactionRunner} */
    public WorkflowEngineBuilder transactionRunner(TransactionRunner transactionRunner) {
        this.transactionRunner = transactionRunner;
        return this;
    }

    /** 乐观锁冲突重试次数（0 表示不重试，直接向上抛） */
    public WorkflowEngineBuilder conflictRetries(int conflictRetries) {
        this.conflictRetries = Math.max(0, conflictRetries);
        return this;
    }

    /** 重试前的等待毫秒，给对手机会释放锁 */
    public WorkflowEngineBuilder retryBackoffMillis(long retryBackoffMillis) {
        this.retryBackoffMillis = Math.max(0, retryBackoffMillis);
        return this;
    }

    /** 构建 WorkflowEngine */
    public WorkflowEngine build() {
        WorkflowEngine engine = new WorkflowEngine(
                processRepo, instanceRepo, taskRepo,
                timeoutScheduler,
                auditLogRepository,
                delegationRepository,
                notificationService,
                carbonCopyRepository,
                historyRepository,
                historyKinds,
                eventRepository,
                decisionRepository,
                decisionHistoryRepository,
                lockProvider,
                transactionRunner,
                conflictRetries,
                retryBackoffMillis
        );
        
        // 设置表单仓储
        if (formRepository != null) {
            engine.setFormRepository(formRepository);
        }
        if (formBindingRepository != null) {
            engine.setFormBindingRepository(formBindingRepository);
        }
        
        // 设置附件仓储
        if (attachmentRepository != null) {
            engine.setAttachmentRepository(attachmentRepository);
        }
        if (attachmentStorage != null) {
            engine.setAttachmentStorage(attachmentStorage);
        }
        
        return engine;
    }
}
