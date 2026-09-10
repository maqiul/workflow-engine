package com.workflow.engine;

import com.workflow.definition.Candidate;
import com.workflow.definition.NodeDefinition;
import com.workflow.definition.ProcessDefinition;
import com.workflow.definition.Transition;
import com.workflow.enums.CandidateStrategy;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.NodeType;
import com.workflow.enums.TaskStatus;
import com.workflow.enums.TimeoutPolicy;
import com.workflow.repository.EventRepository;
import com.workflow.repository.HistoryRepository;
import com.workflow.repository.InstanceRepository;
import com.workflow.repository.TaskRepository;
import com.workflow.runtime.HistoricActivityInstance;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import com.workflow.runtime.Token;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Token 推进器 - 负责流程实例中 Token 的路由与状态转换
 * 
 * <p>职责：
 * <ul>
 *   <li>根据节点类型决定 Token 下一步动作</li>
 *   <li>处理并行网关的 fork/join 语义</li>
 *   <li>处理子流程的启动与回调</li>
 *   <li>处理事件节点</li>
 *   <li>历史埋点</li>
 * </ul>
 */
public class TokenAdvancer {
    
    private static final Logger log = LoggerFactory.getLogger(TokenAdvancer.class);
    
    private static final String SUB_MARK_PREFIX = "__sub_";
    
    private final TaskRepository taskRepo;
    private final InstanceRepository instanceRepo;
    private final EventRepository eventRepo;
    private final HistoryRepository historyRepo;
    private final TimeoutScheduler scheduler;
    private final com.workflow.dmn.DecisionRepository decisionRepo;
    private final com.workflow.dmn.DecisionTableExecutor decisionExecutor;
    private final com.workflow.dmn.DecisionHistoryRepository decisionHistoryRepo;
    
    private final Consumer<TaskInstance> onTaskCreated;
    private final SubProcessStarter subProcessStarter;
    private final Consumer<ProcessInstance> onSubProcessCompleted;
    private final Consumer<Runnable> afterCommitSchedule;
    private final Consumer<ProcessInstance> onProcessCompleted;
    
    public interface SubProcessStarter {
        void startSubProcess(ProcessInstance parent, ProcessDefinition parentDef, 
                           Token parentToken, NodeDefinition subNode);
    }
    
    public TokenAdvancer(
            TaskRepository taskRepo,
            InstanceRepository instanceRepo,
            EventRepository eventRepo,
            HistoryRepository historyRepo,
            TimeoutScheduler scheduler,
            Consumer<TaskInstance> onTaskCreated,
            SubProcessStarter subProcessStarter,
            Consumer<ProcessInstance> onSubProcessCompleted,
            Consumer<Runnable> afterCommitSchedule,
            Consumer<ProcessInstance> onProcessCompleted) {
        this(taskRepo, instanceRepo, eventRepo, historyRepo, scheduler, onTaskCreated,
             subProcessStarter, onSubProcessCompleted, afterCommitSchedule, onProcessCompleted,
             null, null, null);
    }
    
    public TokenAdvancer(
            TaskRepository taskRepo,
            InstanceRepository instanceRepo,
            EventRepository eventRepo,
            HistoryRepository historyRepo,
            TimeoutScheduler scheduler,
            Consumer<TaskInstance> onTaskCreated,
            SubProcessStarter subProcessStarter,
            Consumer<ProcessInstance> onSubProcessCompleted,
            Consumer<Runnable> afterCommitSchedule,
            Consumer<ProcessInstance> onProcessCompleted,
            com.workflow.dmn.DecisionRepository decisionRepo,
            com.workflow.dmn.DecisionTableExecutor decisionExecutor,
            com.workflow.dmn.DecisionHistoryRepository decisionHistoryRepo) {
        this.taskRepo = taskRepo;
        this.instanceRepo = instanceRepo;
        this.eventRepo = eventRepo;
        this.historyRepo = historyRepo;
        this.scheduler = scheduler;
        this.onTaskCreated = onTaskCreated;
        this.subProcessStarter = subProcessStarter;
        this.onSubProcessCompleted = onSubProcessCompleted;
        this.afterCommitSchedule = afterCommitSchedule;
        this.onProcessCompleted = onProcessCompleted;
        this.decisionRepo = decisionRepo;
        this.decisionExecutor = decisionExecutor;
        this.decisionHistoryRepo = decisionHistoryRepo;
    }
    
    public void advanceToken(ProcessInstance instance, ProcessDefinition def, String tokenId,
                            boolean recordsActivity) {
        Token token = instance.getActiveTokens().get(tokenId);
        if (token == null) {
            log.debug("[TokenAdvancer] Token not found, skip advance id={}", tokenId);
            checkAndFinalize(instance);
            return;
        }
        NodeDefinition current = def.getNode(token.getCurrentNodeId());
        openHistory(instance, current, tokenId, recordsActivity);
        try {
            advanceTokenInternal(instance, def, tokenId, recordsActivity);
        } finally {
            closeHistoryIfSettled(instance, current, tokenId);
        }
    }
    
    private void advanceTokenInternal(ProcessInstance instance, ProcessDefinition def, String tokenId,
                                     boolean recordsActivity) {
        Token token = instance.getActiveTokens().get(tokenId);
        if (token == null) {
            log.debug("[TokenAdvancer] Token not found, skip advance id={}", tokenId);
            checkAndFinalize(instance);
            return;
        }
        NodeDefinition current = def.getNode(token.getCurrentNodeId());

        switch (current.getType()) {
            case END -> {
                instance.consumeToken(tokenId);
                log.info("[TokenAdvancer] Token {} reached END", tokenId);
            }
            case USER_TASK -> {
                handleUserTask(instance, def, tokenId, current, recordsActivity);
            }
            case EXCLUSIVE_GATEWAY -> {
                handleExclusiveGateway(instance, def, tokenId, current, recordsActivity);
            }
            case PARALLEL_GATEWAY -> {
                handleParallelGateway(instance, def, tokenId, current, recordsActivity);
            }
            case START -> {
                handleStart(instance, def, tokenId, current, recordsActivity);
            }
            case SUB_PROCESS -> {
                handleSubProcess(instance, def, tokenId, current, recordsActivity);
            }
            case DYNAMIC_PARALLEL -> {
                handleDynamicParallel(instance, def, tokenId, current, recordsActivity);
            }
            case MESSAGE_EVENT -> {
                handleMessageEvent(instance, current);
            }
            case SIGNAL_EVENT -> {
                handleSignalEvent(instance, current);
            }
            case TIMER_BOUNDARY -> {
                handleTimerBoundary(instance, current);
            }
            case DECISION -> {
                handleDecision(instance, def, tokenId, current, recordsActivity);
            }
            case MULTI_INSTANCE -> {
                handleMultiInstance(instance, def, tokenId, current, recordsActivity);
            }
        }

        checkAndFinalize(instance);
    }
    
    private void handleUserTask(ProcessInstance instance, ProcessDefinition def,
                               String tokenId, NodeDefinition current, boolean recordsActivity) {
        TaskInstance existing = currentTaskOf(instance, tokenId, current.getId());
        log.debug("[TokenAdvancer] advanceToken USER_TASK node={} existing={}",
                current.getId(),
                existing == null ? "null" : ("taskId=" + existing.getId() + " status=" + existing.getStatus()));

        if (existing == null) {
            TaskInstance task = new TaskInstance(instance.getId(), tokenId,
                    current.getId(), current.getCandidate());
            task.setTenantId(instance.getTenantId());  // 任务继承实例租户
            taskRepo.save(task);
            syncTaskInInstance(instance, task);
            attachHistoryTask(instance, current.getId(), tokenId, task.getId(), recordsActivity);
            log.info("[TokenAdvancer] Created task node={} candidate={} taskId={}", 
                current.getId(), current.getCandidate(), task.getId());
            if (onTaskCreated != null) {
                onTaskCreated.accept(task);
            }
            instanceRepo.save(instance);
            
            if (current.hasTimeout()) {
                String newTaskId = task.getId();
                String instId = instance.getId();
                long timeout = current.getTimeoutMillis();
                TimeoutPolicy policy = current.getTimeoutPolicy();
                String target = current.getTimeoutTargetUserId();
                if (afterCommitSchedule != null) {
                    afterCommitSchedule.accept(() -> scheduler.schedule(newTaskId, instId, timeout, policy, target));
                }
            }
            registerTimerBoundaryFor(def, instance, current, tokenId);
        } else if (existing.getStatus() == TaskStatus.COMPLETED) {
            log.info("[TokenAdvancer] advanceToken USER_TASK node={} existing.status=COMPLETED -> advance Token", 
                current.getId());
            List<Transition> outs = def.getOutgoing(current.getId());
            if (outs.isEmpty()) {
                instance.consumeToken(tokenId);
                instanceRepo.save(instance);
            } else if (outs.size() == 1) {
                Token tk = instance.getActiveTokens().get(tokenId);
                tk.setCurrentNodeId(outs.get(0).getTo());
                instanceRepo.save(instance);
                advanceToken(instance, def, tokenId, recordsActivity);
            } else {
                throw new IllegalStateException("USER_TASK node " + current.getId() + " has multiple outgoing transitions");
            }
        } else {
            log.debug("[TokenAdvancer] Token {} task still PENDING on node {}, skip", tokenId, current.getId());
        }
    }
    
    private void handleExclusiveGateway(ProcessInstance instance, ProcessDefinition def,
                                       String tokenId, NodeDefinition current, boolean recordsActivity) {
        List<Transition> outs = def.getOutgoing(current.getId());
        if (outs.isEmpty()) {
            throw new IllegalStateException("ExclusiveGateway " + current.getId() + " has no outgoing");
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
            log.warn("[TokenAdvancer] ExclusiveGateway {} no matching outgoing, Token terminated", current.getId());
            instance.consumeToken(tokenId);
            instanceRepo.save(instance);
            return;
        }
        log.info("[TokenAdvancer] ExclusiveGateway {} selected outgoing {}", current.getId(), chosen.getTo());
        Token token = instance.getActiveTokens().get(tokenId);
        token.setCurrentNodeId(chosen.getTo());
        instanceRepo.save(instance);
        advanceToken(instance, def, tokenId, recordsActivity);
    }
    
    private void handleParallelGateway(ProcessInstance instance, ProcessDefinition def,
                                      String tokenId, NodeDefinition current, boolean recordsActivity) {
        List<Transition> outs = def.getOutgoing(current.getId());
        if (outs.isEmpty()) {
            instance.consumeToken(tokenId);
            return;
        }
        if (GatewayKind.isJoin(def, current.getId())) {
            instance.consumeToken(tokenId);
            log.info("[TokenAdvancer] Token {} reached parallel join {}", tokenId, current.getId());
            if (allJoinArrived(def, current.getId(), instance)) {
                Transition out = outs.get(0);
                Token next = new Token(instance.getId(), out.getTo());
                instance.addToken(next);
                instanceRepo.save(instance);
                advanceToken(instance, def, next.getId(), recordsActivity);
            } else {
                instanceRepo.save(instance);
            }
        } else {
            instance.consumeToken(tokenId);
            List<Token> forked = new ArrayList<>();
            for (Transition out : outs) {
                Token t = new Token(instance.getId(), out.getTo());
                instance.addToken(t);
                forked.add(t);
            }
            instanceRepo.save(instance);
            log.info("[TokenAdvancer] Parallel forked {} Tokens", forked.size());
            for (Token t : forked) {
                advanceToken(instance, def, t.getId(), recordsActivity);
            }
        }
    }
    
    private void handleStart(ProcessInstance instance, ProcessDefinition def,
                            String tokenId, NodeDefinition current, boolean recordsActivity) {
        List<Transition> outs = def.getOutgoing(current.getId());
        if (outs.isEmpty()) {
            throw new IllegalStateException("START node " + current.getId() + " has no outgoing");
        }
        Token token = instance.getActiveTokens().get(tokenId);
        token.setCurrentNodeId(outs.get(0).getTo());
        instanceRepo.save(instance);
        advanceToken(instance, def, tokenId, recordsActivity);
    }
    
    private void handleSubProcess(ProcessInstance instance, ProcessDefinition def,
                                 String tokenId, NodeDefinition current, boolean recordsActivity) {
        String markKey = SUB_MARK_PREFIX + tokenId;
        Object childId = instance.getVariable(markKey);
        Token token = instance.getActiveTokens().get(tokenId);
        
        if (childId == null) {
            if (subProcessStarter != null) {
                subProcessStarter.startSubProcess(instance, def, token, current);
            }
        } else {
            ProcessInstance child = instanceRepo.findById(childId.toString());
            if (child.getStatus() == InstanceStatus.COMPLETED) {
                log.info("[TokenAdvancer] SubProcess {} completed, advance parent Token to outgoing node={}",
                        child.getId(), current.getId());
                List<Transition> outs = def.getOutgoing(current.getId());
                if (outs.isEmpty()) {
                    instance.consumeToken(tokenId);
                    instanceRepo.save(instance);
                } else if (outs.size() == 1) {
                    token.setCurrentNodeId(outs.get(0).getTo());
                    instanceRepo.save(instance);
                    advanceToken(instance, def, tokenId, recordsActivity);
                } else {
                    throw new IllegalStateException("SUB_PROCESS node " + current.getId() + " has multiple outgoing");
                }
            } else {
                log.info("[TokenAdvancer] SubProcess {} still running, parent Token waiting node={}",
                        child.getId(), current.getId());
            }
        }
    }
    
    private void handleDynamicParallel(ProcessInstance instance, ProcessDefinition def,
                                      String tokenId, NodeDefinition current, boolean recordsActivity) {
        String variable = current.getDynamicParallelVariable();
        CandidateStrategy strategy = current.getDynamicParallelStrategy();
        
        String markKey = "__dynamic_" + tokenId;
        Object created = instance.getVariable(markKey);
        Token token = instance.getActiveTokens().get(tokenId);
        
        if (created == null) {
            Object varValue = instance.getVariable(variable);
            if (varValue == null) {
                throw new IllegalStateException("DYNAMIC_PARALLEL node " + current.getId() + 
                    " requires variable " + variable + " which does not exist");
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
                throw new IllegalStateException("DYNAMIC_PARALLEL node " + current.getId() + 
                    " variable " + variable + " must be List type");
            }
            
            if (candidates.isEmpty()) {
                log.info("[TokenAdvancer] DYNAMIC_PARALLEL node {} candidates empty, advance directly", current.getId());
                instance.setVariable(markKey, "done");
                List<Transition> outs = def.getOutgoing(current.getId());
                if (outs.isEmpty()) {
                    instance.consumeToken(tokenId);
                    instanceRepo.save(instance);
                } else if (outs.size() == 1) {
                    token.setCurrentNodeId(outs.get(0).getTo());
                    instanceRepo.save(instance);
                    advanceToken(instance, def, tokenId, recordsActivity);
                } else {
                    throw new IllegalStateException("DYNAMIC_PARALLEL node " + current.getId() + " has multiple outgoing");
                }
            } else {
                Candidate candidate = strategy == CandidateStrategy.ANY 
                        ? Candidate.ofAny(candidates.toArray(new String[0]))
                        : Candidate.ofAll(candidates.toArray(new String[0]));
                
                TaskInstance task = new TaskInstance(instance.getId(), tokenId,
                        current.getId(), candidate);
                task.setTenantId(instance.getTenantId());  // 任务继承实例租户
                instance.addTask(task);
                instance.setVariable(markKey, "created");
                log.info("[TokenAdvancer] DYNAMIC_PARALLEL node {} created dynamic task, candidates={}, strategy={}, taskId={}", 
                        current.getId(), candidates, strategy, task.getId());
                taskRepo.save(task);
                instanceRepo.save(instance);
            }
        } else if ("created".equals(created)) {
            TaskInstance existing = currentTaskOf(instance, tokenId, current.getId());
            
            if (existing != null && existing.getStatus() == TaskStatus.COMPLETED) {
                log.info("[TokenAdvancer] DYNAMIC_PARALLEL node {} dynamic task completed, advance Token", current.getId());
                instance.setVariable(markKey, "done");
                List<Transition> outs = def.getOutgoing(current.getId());
                if (outs.isEmpty()) {
                    instance.consumeToken(tokenId);
                    instanceRepo.save(instance);
                } else if (outs.size() == 1) {
                    token.setCurrentNodeId(outs.get(0).getTo());
                    instanceRepo.save(instance);
                    advanceToken(instance, def, tokenId, recordsActivity);
                } else {
                    throw new IllegalStateException("DYNAMIC_PARALLEL node " + current.getId() + " has multiple outgoing");
                }
            } else {
                log.debug("[TokenAdvancer] DYNAMIC_PARALLEL node {} dynamic task still running, continue waiting", current.getId());
            }
        }
    }
    
    private void handleMessageEvent(ProcessInstance instance, NodeDefinition current) {
        if (eventRepo == null) {
            throw new IllegalStateException("Event gateway not enabled, please inject EventRepository");
        }
        var messageEvent = current.getMessageEvent();
        if (messageEvent == null) {
            throw new IllegalStateException("MESSAGE_EVENT node " + current.getId() + " missing message event definition");
        }
        
        String correlationKey = evaluateExpression(messageEvent.correlationKeyExpression(), instance.getVariables());
        if (correlationKey == null || correlationKey.isBlank()) {
            throw new IllegalStateException("MESSAGE_EVENT node " + current.getId() + 
                    " correlationKey expression " + messageEvent.correlationKeyExpression() + " evaluated to empty");
        }
        
        eventRepo.saveMessageEvent(instance.getId(), current.getId(), messageEvent.messageName(), correlationKey);
        log.info("[TokenAdvancer] MESSAGE_EVENT node {} waiting for message name={} correlationKey={}", 
                current.getId(), messageEvent.messageName(), correlationKey);
    }
    
    private void handleSignalEvent(ProcessInstance instance, NodeDefinition current) {
        if (eventRepo == null) {
            throw new IllegalStateException("Event gateway not enabled, please inject EventRepository");
        }
        var signalEvent = current.getSignalEvent();
        if (signalEvent == null) {
            throw new IllegalStateException("SIGNAL_EVENT node " + current.getId() + " missing signal event definition");
        }
        
        eventRepo.saveSignalEvent(instance.getId(), current.getId(), signalEvent.signalName());
        log.info("[TokenAdvancer] SIGNAL_EVENT node {} waiting for signal name={}", current.getId(), signalEvent.signalName());
    }
    
    private void handleTimerBoundary(ProcessInstance instance, NodeDefinition current) {
        if (eventRepo == null) {
            throw new IllegalStateException("Event gateway not enabled, please inject EventRepository");
        }
        var timerEvent = current.getTimerBoundaryEvent();
        if (timerEvent == null) {
            throw new IllegalStateException("TIMER_BOUNDARY node " + current.getId() + " missing timer event definition");
        }
        
        Instant triggerTime = Instant.now().plusMillis(timerEvent.durationMillis());
        eventRepo.saveTimerEvent(instance.getId(), current.getId(), triggerTime, timerEvent.interrupting());
        log.info("[TokenAdvancer] TIMER_BOUNDARY node {} waiting for timer triggerTime={} interrupting={}", 
                current.getId(), triggerTime, timerEvent.interrupting());
    }
    
    private void handleDecision(ProcessInstance instance, ProcessDefinition def,
                               String tokenId, NodeDefinition current, boolean recordsActivity) {
        if (decisionRepo == null) {
            throw new IllegalStateException("Decision gateway not enabled, please inject DecisionRepository");
        }
        if (decisionExecutor == null) {
            throw new IllegalStateException("Decision executor not enabled");
        }
        
        String decisionTableId = current.getDecisionTableId();
        if (decisionTableId == null) {
            throw new IllegalStateException("DECISION node " + current.getId() + " missing decision table ID");
        }
        
        // 查找决策表
        com.workflow.dmn.DecisionTable decisionTable = decisionRepo.findById(decisionTableId);
        if (decisionTable == null) {
            throw new IllegalStateException("Decision table not found: " + decisionTableId);
        }
        
        // 执行决策表
        Map<String, Object> context = instance.getVariables();
        com.workflow.dmn.DecisionTableExecutor.DecisionResult result = decisionExecutor.execute(decisionTable, context);
        
        // 记录决策历史
        if (decisionHistoryRepo != null) {
            Map<String, Object> outputs = result.isMatched() ? result.getSingleOutput() : null;
            com.workflow.dmn.DecisionHistory history = new com.workflow.dmn.DecisionHistory(
                    java.util.UUID.randomUUID().toString(),
                    instance.getId(),
                    tokenId,
                    current.getId(),
                    decisionTableId,
                    new java.util.HashMap<>(context),
                    outputs,
                    result.getMatchedRuleId(),
                    System.currentTimeMillis()
            );
            decisionHistoryRepo.save(history);
        }
        
        if (!result.isMatched()) {
            log.warn("[TokenAdvancer] DECISION node {} no matching rule in decision table {}", 
                    current.getId(), decisionTableId);
            // 没有匹配规则，消耗 Token
            instance.consumeToken(tokenId);
            instanceRepo.save(instance);
            return;
        }
        
        // 将决策结果写入流程变量
        Map<String, Object> outputs = result.getSingleOutput();
        if (outputs != null) {
            for (Map.Entry<String, Object> entry : outputs.entrySet()) {
                instance.setVariable(entry.getKey(), entry.getValue());
            }
            instanceRepo.save(instance);
        }
        
        log.info("[TokenAdvancer] DECISION node {} executed decision table {}, outputs={}", 
                current.getId(), decisionTableId, outputs);
        
        // 决策完成后，继续推进 Token
        List<Transition> outs = def.getOutgoing(current.getId());
        if (outs.isEmpty()) {
            instance.consumeToken(tokenId);
            instanceRepo.save(instance);
        } else if (outs.size() == 1) {
            Token token = instance.getActiveTokens().get(tokenId);
            token.setCurrentNodeId(outs.get(0).getTo());
            instanceRepo.save(instance);
            advanceToken(instance, def, tokenId, recordsActivity);
        } else {
            // 多出口 - 使用排他网关逻辑，根据决策结果选择出口
            handleExclusiveGateway(instance, def, tokenId, current, recordsActivity);
        }
    }
    
    private boolean allJoinArrived(ProcessDefinition def, String joinNodeId, ProcessInstance instance) {
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
                return false;
            }
        }
        return true;
    }
    
    private void registerTimerBoundaryFor(ProcessDefinition def, ProcessInstance instance,
                                         NodeDefinition userTaskNode, String tokenId) {
        if (eventRepo == null) {
            return;
        }
        for (NodeDefinition n : def.getNodes().values()) {
            if (n.isTimerBoundary() && n.getTimerBoundaryEvent().attachedToNodeId().equals(userTaskNode.getId())) {
                var timerEvent = n.getTimerBoundaryEvent();
                Instant triggerTime = Instant.now().plusMillis(timerEvent.durationMillis());
                eventRepo.saveTimerEvent(instance.getId(), n.getId(), triggerTime, timerEvent.interrupting());
                log.info("[TokenAdvancer] TIMER_BOUNDARY node {} registered timer triggerTime={} interrupting={}",
                        n.getId(), triggerTime, timerEvent.interrupting());
            }
        }
    }
    
    private String evaluateExpression(String expression, Map<String, Object> variables) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        
        String varName = expression.trim();
        if (varName.startsWith("${") && varName.endsWith("}")) {
            varName = varName.substring(2, varName.length() - 1).trim();
        }
        
        Object value = variables.get(varName);
        return value != null ? value.toString() : null;
    }
    
    private void checkAndFinalize(ProcessInstance instance) {
        if (instance.isAllTokensConsumed() && instance.getStatus() == InstanceStatus.RUNNING) {
            boolean hasPending = taskRepo.findByInstanceId(instance.getId()).stream()
                    .anyMatch(t -> t.getStatus() == TaskStatus.PENDING);
            if (!hasPending) {
                instance.markCompleted();
                instanceRepo.save(instance);
                log.info("[TokenAdvancer] Instance {} process completed", instance.getId());
                // 通知流程完成（触发监听器）
                if (onProcessCompleted != null) {
                    onProcessCompleted.accept(instance);
                }
                // 子流程完成 -> 回调父流程推进
                if (instance.isSubProcess() && onSubProcessCompleted != null) {
                    onSubProcessCompleted.accept(instance);
                }
            }
        }
    }
    
    private void openHistory(ProcessInstance instance, NodeDefinition node, String tokenId, boolean recordsActivity) {
        if (!recordsActivity || historyRepo == null) {
            return;
        }
        if (historyRepo.findOpen(instance.getId(), tokenId, node.getId()) != null) {
            return;
        }
        historyRepo.save(new HistoricActivityInstance(
                instance.getId(), instance.getProcessKey(), instance.getProcessVersion(),
                node.getId(), node.getType(), tokenId, null, System.currentTimeMillis()));
    }
    
    private void closeHistoryIfSettled(ProcessInstance instance, NodeDefinition node, String tokenId) {
        if (historyRepo == null || isStillWaiting(instance, node, tokenId)) {
            return;
        }
        HistoricActivityInstance open = historyRepo.findOpen(instance.getId(), tokenId, node.getId());
        if (open == null) {
            return;
        }
        open.close(System.currentTimeMillis(), null);
        historyRepo.save(open);
    }
    
    private boolean isStillWaiting(ProcessInstance instance, NodeDefinition node, String tokenId) {
        if (node.getType() == NodeType.USER_TASK || node.getType() == NodeType.DYNAMIC_PARALLEL) {
            TaskInstance t = currentTaskOf(instance, tokenId, node.getId());
            return t != null && t.getStatus() == TaskStatus.PENDING;
        }
        if (node.getType() == NodeType.SUB_PROCESS) {
            Token now = instance.getActiveTokens().get(tokenId);
            return now != null && node.getId().equals(now.getCurrentNodeId());
        }
        return false;
    }
    
    private void attachHistoryTask(ProcessInstance instance, String nodeId,
                                  String tokenId, String taskId, boolean recordsActivity) {
        if (!recordsActivity || historyRepo == null) {
            return;
        }
        HistoricActivityInstance open = historyRepo.findOpen(instance.getId(), tokenId, nodeId);
        if (open != null) {
            open.attachTask(taskId);
            historyRepo.save(open);
        }
    }
    
    private TaskInstance currentTaskOf(ProcessInstance instance, String tokenId, String nodeId) {
        return taskRepo.findByInstanceId(instance.getId()).stream()
                .filter(t -> t.getTokenId().equals(tokenId) && t.getNodeId().equals(nodeId))
                .filter(t -> t.getStatus() != TaskStatus.TERMINATED && t.getStatus() != TaskStatus.TRANSFERRED)
                .findFirst()
                .orElse(null);
    }
    
    private void syncTaskInInstance(ProcessInstance instance, TaskInstance task) {
        if (instance.getTasks().stream().noneMatch(t -> t.getId().equals(task.getId()))) {
            instance.addTask(task);
        }
    }

    /** 某 token+node 上的全部任务（含各状态），供多实例完成判定。 */
    private List<TaskInstance> tasksOf(ProcessInstance instance, String tokenId, String nodeId) {
        return taskRepo.findByInstanceId(instance.getId()).stream()
                .filter(t -> t.getTokenId().equals(tokenId) && t.getNodeId().equals(nodeId))
                .toList();
    }

    private java.util.List<String> toStringList(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                out.add(o instanceof String s ? s : String.valueOf(o));
            }
        }
        return out;
    }

    /** 多实例节点完成后推进 token 到唯一出口（复用 USER_TASK 的推进尾）。 */
    private void advanceMultiInstanceToken(ProcessInstance instance, ProcessDefinition def,
                                           Token token, NodeDefinition current, String tokenId,
                                           boolean recordsActivity) {
        List<Transition> outs = def.getOutgoing(current.getId());
        if (outs.isEmpty()) {
            instance.consumeToken(tokenId);
            instanceRepo.save(instance);
        } else if (outs.size() == 1) {
            token.setCurrentNodeId(outs.get(0).getTo());
            instanceRepo.save(instance);
            advanceToken(instance, def, tokenId, recordsActivity);
        } else {
            throw new IllegalStateException("MULTI_INSTANCE 节点 " + current.getId() + " 有多条出口");
        }
    }

    /**
     * 多实例会签/或签：进入时按集合变量为每个审批人各建一个单候选人任务；
     * 之后每次有任务完成回到本节点，按 ALL/ANY 判定是否推进。
     */
    private void handleMultiInstance(ProcessInstance instance, ProcessDefinition def,
                                     String tokenId, NodeDefinition current, boolean recordsActivity) {
        String markKey = "__mi_" + tokenId;
        Object expanded = instance.getVariable(markKey);
        Token token = instance.getActiveTokens().get(tokenId);

        if (expanded == null) {
            java.util.List<String> assignees = toStringList(instance.getVariable(current.getMultiInstanceCollection()));
            if (assignees.isEmpty()) {
                log.info("[TokenAdvancer] MULTI_INSTANCE 节点 {} 集合为空,直接推进", current.getId());
                instance.setVariable(markKey, "expanded");
                instanceRepo.save(instance);
                advanceMultiInstanceToken(instance, def, token, current, tokenId, recordsActivity);
                return;
            }
            for (String a : assignees) {
                TaskInstance t = new TaskInstance(instance.getId(), tokenId, current.getId(), Candidate.ofAny(a));
                t.setTenantId(instance.getTenantId());  // 继承实例租户
                taskRepo.save(t);
                syncTaskInInstance(instance, t);
            }
            instance.setVariable(markKey, "expanded");
            instanceRepo.save(instance);
            log.info("[TokenAdvancer] MULTI_INSTANCE 节点 {} 展开 {} 个独立任务 assignees={}",
                    current.getId(), assignees.size(), assignees);
            return;  // 停在节点等待任务完成
        }

        // 已展开：判完成条件
        List<TaskInstance> tasks = tasksOf(instance, tokenId, current.getId());
        long pending = tasks.stream().filter(t -> t.getStatus() == TaskStatus.PENDING).count();
        long completed = tasks.stream().filter(t -> t.getStatus() == TaskStatus.COMPLETED).count();
        CandidateStrategy strategy = current.getMultiInstanceStrategy();

        if (strategy == CandidateStrategy.ALL) {
            if (pending == 0 && completed >= 1) {
                log.info("[TokenAdvancer] MULTI_INSTANCE 节点 {} 会签全部完成({}),推进", current.getId(), completed);
                advanceMultiInstanceToken(instance, def, token, current, tokenId, recordsActivity);
            }
            // 否则仍有 pending，停等
        } else { // ANY：任一完成即通过，取消其余
            if (completed >= 1) {
                for (TaskInstance t : tasks) {
                    if (t.getStatus() == TaskStatus.PENDING) {
                        t.setStatus(TaskStatus.TERMINATED);
                        taskRepo.save(t);
                        syncTaskInInstance(instance, t);
                    }
                }
                log.info("[TokenAdvancer] MULTI_INSTANCE 节点 {} 或签任一完成,取消其余,推进", current.getId());
                advanceMultiInstanceToken(instance, def, token, current, tokenId, recordsActivity);
            }
        }
    }
}
