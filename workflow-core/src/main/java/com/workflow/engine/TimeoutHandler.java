package com.workflow.engine;

import com.workflow.definition.Candidate;
import com.workflow.definition.NodeDefinition;
import com.workflow.definition.ProcessDefinition;
import com.workflow.enums.AuditEventType;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
import com.workflow.enums.TimeoutPolicy;
import com.workflow.repository.AuditLogRepository;
import com.workflow.repository.InstanceRepository;
import com.workflow.repository.TaskRepository;
import com.workflow.runtime.AuditLog;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import com.workflow.runtime.Token;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 超时处理器 - 负责处理任务超时回调
 *
 * <p>职责：
 * <ul>
 *   <li>处理任务超时事件</li>
 *   <li>根据 TimeoutPolicy 执行自动动作（AUTO_APPROVE/AUTO_REJECT/AUTO_TERMINATE/AUTO_TRANSFER）</li>
 *   <li>发送超时通知</li>
 *   <li>记录审计日志</li>
 * </ul>
 */
public class TimeoutHandler {

    private static final Logger log = LoggerFactory.getLogger(TimeoutHandler.class);

    private static final String SYSTEM_USER = "__system__";

    private final TaskRepository taskRepo;
    private final InstanceRepository instanceRepo;
    private final AuditLogRepository auditLogRepo;
    private final NotificationService notificationService;
    private TimeoutScheduler scheduler;  // 非 final，允许后续设置

    /** 回调：推进 Token */
    private final TokenAdvancer tokenAdvancer;

    /** 回调：获取流程定义 */
    private final Function<ProcessInstance, ProcessDefinition> defResolver;

    /** 回调：同步任务到实例 */
    private final BiConsumer<ProcessInstance, TaskInstance> taskSyncer;

    /** 回调：事务提交后调度 */
    private final Consumer<Runnable> afterCommitSchedule;

    /** 回调：排他锁执行 */
    private final ExclusiveVoidExecutor exclusiveVoidExecutor;

    public interface TokenAdvancer {
        void advanceToken(ProcessInstance instance, ProcessDefinition def, String tokenId);
    }

    public interface ExclusiveVoidExecutor {
        void execute(String instanceId, String op, Runnable body);
    }

    public TimeoutHandler(
            TaskRepository taskRepo,
            InstanceRepository instanceRepo,
            AuditLogRepository auditLogRepo,
            NotificationService notificationService,
            TimeoutScheduler scheduler,
            TokenAdvancer tokenAdvancer,
            Function<ProcessInstance, ProcessDefinition> defResolver,
            BiConsumer<ProcessInstance, TaskInstance> taskSyncer,
            Consumer<Runnable> afterCommitSchedule,
            ExclusiveVoidExecutor exclusiveVoidExecutor) {
        this.taskRepo = taskRepo;
        this.instanceRepo = instanceRepo;
        this.auditLogRepo = auditLogRepo;
        this.notificationService = notificationService;
        this.scheduler = scheduler;  // 可以为 null，稍后由 WorkflowEngine 设置
        this.tokenAdvancer = tokenAdvancer;
        this.defResolver = defResolver;
        this.taskSyncer = taskSyncer;
        this.afterCommitSchedule = afterCommitSchedule;
        this.exclusiveVoidExecutor = exclusiveVoidExecutor;
    }
    
    /**
     * 设置 scheduler（由 WorkflowEngine 在初始化完成后调用）
     */
    public void setScheduler(TimeoutScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * 超时回调入口 - 由调度线程触发
     *
     * <p>必须与用户手动操作走同一把流程树锁，否则会出现
     * 「超时自动通过与人工审批同时生效」的双写。
     */
    public void onTaskTimeout(String taskId, String instanceId,
                              TimeoutPolicy policy, String targetUserId) {
        exclusiveVoidExecutor.execute(instanceId, "onTaskTimeout",
                () -> doTaskTimeout(taskId, instanceId, policy, targetUserId));
    }

    /**
     * 超时回调实现 - 由 ScheduledTimeoutScheduler 触发
     * 按节点配置的 TimeoutPolicy 执行自动动作
     *
     * <p>幂等保证:回调前重查任务状态,非 PENDING 则忽略(避免与用户手动操作竞态)
     */
    private void doTaskTimeout(String taskId, String instanceId,
                               TimeoutPolicy policy, String targetUserId) {
        TaskInstance task = taskRepo.findById(taskId);
        if (task == null || task.getStatus() != TaskStatus.PENDING) {
            log.debug("[TimeoutHandler] Task {} is not PENDING, ignore timeout callback", taskId);
            return;
        }

        ProcessInstance instance = instanceRepo.findById(instanceId);
        if (instance.getStatus() != InstanceStatus.RUNNING) {
            log.debug("[TimeoutHandler] Instance {} is not RUNNING, ignore timeout callback", instanceId);
            return;
        }

        log.info("[TimeoutHandler] Task {} timeout, policy={} target={}", taskId, policy, targetUserId);

        // 超时通知（如果启用了通知服务）
        if (notificationService != null) {
            for (String userId : task.getCandidate().getUserIds()) {
                notificationService.timeoutReminder(task, userId, 0);
            }
        }

        switch (policy) {
            case AUTO_APPROVE -> {
                // 自动通过:记录 SYSTEM_USER 并直接完成 - 候选人校验在 domain 内部豁免
                task.recordSystemApproval(SYSTEM_USER);
                taskRepo.save(task);
                taskSyncer.accept(instance, task);
                ProcessDefinition def = defResolver.apply(instance);
                tokenAdvancer.advanceToken(instance, def, task.getTokenId());
                audit(AuditEventType.TIMEOUT_AUTO_APPROVED, instanceId, taskId, SYSTEM_USER,
                        "超时自动通过");
            }
            case AUTO_REJECT -> {
                // 自动驳回:退回上一 UserTask
                task.setStatus(TaskStatus.REJECTED);
                taskRepo.save(task);
                taskSyncer.accept(instance, task);
                ProcessDefinition def = defResolver.apply(instance);
                String prevUserTask = PathNavigator.findPreviousUserTask(def, task.getNodeId());
                if (prevUserTask == null) {
                    log.warn("[TimeoutHandler] Cannot find previous node when rejecting, terminate instance");
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
                tokenAdvancer.advanceToken(instance, def, newToken.getId());
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
                        taskSyncer.accept(instance, t);
                        terminatedIds.add(t.getId());
                    }
                }
                instanceRepo.save(instance);
                afterCommitSchedule.accept(() -> terminatedIds.forEach(scheduler::cancel));
                audit(AuditEventType.TIMEOUT_AUTO_TERMINATED, instanceId, taskId, SYSTEM_USER,
                        "超时自动终止");
            }
            case AUTO_TRANSFER -> {
                // 自动转办:原任务置为 TRANSFERRED,新建目标用户任务
                task.setStatus(TaskStatus.TRANSFERRED);
                taskRepo.save(task);
                taskSyncer.accept(instance, task);
                ProcessDefinition def = defResolver.apply(instance);
                NodeDefinition nodeDef = def.getNode(task.getNodeId());
                Candidate newCand = Candidate.ofAny(targetUserId);
                TaskInstance newTask = new TaskInstance(instance.getId(), task.getTokenId(),
                        nodeDef.getId(), newCand);
                taskRepo.save(newTask);
                taskSyncer.accept(instance, newTask);
                // 新任务继承原节点超时配置 - 提交后才登记
                if (nodeDef.hasTimeout()) {
                    String newTaskId = newTask.getId();
                    String instId = instance.getId();
                    long timeout = nodeDef.getTimeoutMillis();
                    TimeoutPolicy autoPolicy = nodeDef.getTimeoutPolicy();
                    String autoTarget = nodeDef.getTimeoutTargetUserId();
                    afterCommitSchedule.accept(() -> scheduler.schedule(newTaskId, instId, timeout, autoPolicy, autoTarget));
                }
                audit(AuditEventType.TIMEOUT_AUTO_TRANSFERRED, instanceId, taskId, SYSTEM_USER,
                        "超时自动转办给 toUser=" + targetUserId + " newTaskId=" + newTask.getId());
            }
            default -> log.warn("[TimeoutHandler] Unknown policy={}", policy);
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
