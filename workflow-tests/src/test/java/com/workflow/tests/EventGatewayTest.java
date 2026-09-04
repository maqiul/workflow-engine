package com.workflow.tests;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.WorkflowEngine;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
import com.workflow.repository.InMemoryEventRepository;
import com.workflow.repository.InMemoryInstanceRepository;
import com.workflow.repository.InMemoryProcessRepository;
import com.workflow.repository.InMemoryTaskRepository;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事件网关测试
 * 
 * 测试三种事件类型：
 * - MESSAGE_EVENT：等待外部消息，通过 correlationKey 匹配
 * - SIGNAL_EVENT：广播式信号，多个实例可以监听
 * - TIMER_BOUNDARY：定时器边界事件
 */
@DisplayName("事件网关")
class EventGatewayTest {

    private InMemoryProcessRepository procRepo;
    private InMemoryInstanceRepository instRepo;
    private InMemoryTaskRepository taskRepo;
    private InMemoryEventRepository eventRepo;
    private WorkflowEngine engine;

    @BeforeEach
    void setUp() {
        procRepo = new InMemoryProcessRepository();
        instRepo = new InMemoryInstanceRepository();
        taskRepo = new InMemoryTaskRepository();
        eventRepo = new InMemoryEventRepository();
        engine = new WorkflowEngine(procRepo, instRepo, taskRepo, null, null, null, null, null, null, null, eventRepo, null, null, 0, 0L);
    }

    // ========== 消息事件测试 ==========

    @Test
    @DisplayName("消息事件：流程启动后 Token 停留在消息节点")
    void messageEvent_flowStartsAndWaitsAtMessageNode() {
        // 创建流程：start -> message(waitForApproval) -> end
        ProcessDefinition def = ProcessBuilder.create("msg-flow")
                .start("start")
                .messageEvent("waitForApproval", "等待审批", "approvalMessage", "${orderId}")
                .end("end")
                .connect("start", "waitForApproval")
                .connect("waitForApproval", "end")
                .build();
        procRepo.save(def);

        // 启动流程，orderId=12345
        String instanceId = engine.start("msg-flow", Map.of("orderId", "12345"));
        ProcessInstance instance = engine.getInstance(instanceId);

        // 验证：流程仍在运行
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        
        // 验证：Token 停留在消息节点
        assertThat(instance.getActiveTokens()).hasSize(1);
        var token = instance.getActiveTokens().values().iterator().next();
        assertThat(token.getCurrentNodeId()).isEqualTo("waitForApproval");
    }

    @Test
    @DisplayName("消息事件：发送消息后流程继续推进")
    void messageEvent_flowContinuesAfterMessageSent() {
        ProcessDefinition def = ProcessBuilder.create("msg-flow-2")
                .start("start")
                .messageEvent("waitForApproval", "等待审批", "approvalMessage", "${orderId}")
                .end("end")
                .connect("start", "waitForApproval")
                .connect("waitForApproval", "end")
                .build();
        procRepo.save(def);

        // 启动流程
        String instanceId = engine.start("msg-flow-2", Map.of("orderId", "12345"));
        
        // 发送消息
        engine.sendMessage("approvalMessage", "12345");
        
        // 验证：流程已完成
        ProcessInstance instance = engine.getInstance(instanceId);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    @DisplayName("消息事件：correlationKey 不匹配时流程继续等待")
    void messageEvent_flowContinuesWaitingWhenCorrelationKeyNotMatch() {
        ProcessDefinition def = ProcessBuilder.create("msg-flow-3")
                .start("start")
                .messageEvent("waitForApproval", "等待审批", "approvalMessage", "${orderId}")
                .end("end")
                .connect("start", "waitForApproval")
                .connect("waitForApproval", "end")
                .build();
        procRepo.save(def);

        // 启动流程，orderId=12345
        String instanceId = engine.start("msg-flow-3", Map.of("orderId", "12345"));
        
        // 发送消息，但 correlationKey 不匹配
        engine.sendMessage("approvalMessage", "99999");
        
        // 验证：流程仍在等待
        ProcessInstance instance = engine.getInstance(instanceId);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(instance.getActiveTokens()).hasSize(1);
        var token = instance.getActiveTokens().values().iterator().next();
        assertThat(token.getCurrentNodeId()).isEqualTo("waitForApproval");
    }

    // ========== 信号事件测试 ==========

    @Test
    @DisplayName("信号事件：流程启动后 Token 停留在信号节点")
    void signalEvent_flowStartsAndWaitsAtSignalNode() {
        ProcessDefinition def = ProcessBuilder.create("sig-flow")
                .start("start")
                .signalEvent("waitForSignal", "等待信号", "systemShutdown")
                .end("end")
                .connect("start", "waitForSignal")
                .connect("waitForSignal", "end")
                .build();
        procRepo.save(def);

        String instanceId = engine.start("sig-flow", Map.of());
        ProcessInstance instance = engine.getInstance(instanceId);

        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(instance.getActiveTokens()).hasSize(1);
        var token = instance.getActiveTokens().values().iterator().next();
        assertThat(token.getCurrentNodeId()).isEqualTo("waitForSignal");
    }

    @Test
    @DisplayName("信号事件：发送信号后所有等待的实例都被触发")
    void signalEvent_allWaitingInstancesTriggered() {
        ProcessDefinition def = ProcessBuilder.create("sig-flow-2")
                .start("start")
                .signalEvent("waitForSignal", "等待信号", "systemShutdown")
                .end("end")
                .connect("start", "waitForSignal")
                .connect("waitForSignal", "end")
                .build();
        procRepo.save(def);

        // 启动多个实例
        String instanceId1 = engine.start("sig-flow-2", Map.of());
        String instanceId2 = engine.start("sig-flow-2", Map.of());
        String instanceId3 = engine.start("sig-flow-2", Map.of());

        // 发送信号
        engine.sendSignal("systemShutdown");

        // 验证：所有实例都已完成
        assertThat(engine.getInstance(instanceId1).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(engine.getInstance(instanceId2).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(engine.getInstance(instanceId3).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    // ========== 定时器边界事件测试 ==========

    @Test
    @DisplayName("定时器边界事件（中断模式）：超时后取消任务并走定时器分支")
    void timerBoundary_interruptingMode_cancelsTaskAndTakesTimerBranch() {
        ProcessDefinition def = ProcessBuilder.create("timer-flow")
                .start("start")
                .userTask("review", "审核", Candidate.ofAny("alice"))
                .timerBoundary("timeout", "超时", "review", 100, true) // 100ms 超时，中断模式
                .end("end")
                .connect("start", "review")
                .connect("review", "timeout") // review 完成后进入 timeout 节点（定时器等待）
                .connect("timeout", "end") // 定时器触发后到 end
                .build();
        procRepo.save(def);

        String instanceId = engine.start("timer-flow", Map.of());
        
        // 验证：任务已创建
        List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
        assertThat(tasks).hasSize(1);
        TaskInstance task = tasks.get(0);
        assertThat(task.getNodeId()).isEqualTo("review");
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);

        // 等待定时器触发
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        engine.checkAndTriggerTimers();

        // 验证：任务已被取消
        task = taskRepo.findById(task.getId());
        assertThat(task.getStatus()).isEqualTo(TaskStatus.TERMINATED);

        // 验证：流程已完成
        ProcessInstance instance = engine.getInstance(instanceId);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    @DisplayName("定时器边界事件（非中断模式）：超时后触发分支但任务继续")
    void timerBoundary_nonInterruptingMode_triggersBranchButTaskContinues() {
        ProcessDefinition def = ProcessBuilder.create("timer-flow-2")
                .start("start")
                .userTask("review", "审核", Candidate.ofAny("alice"))
                .timerBoundary("timeout", "超时", "review", 100, false) // 100ms 超时，非中断模式
                .userTask("escalate", "升级处理", Candidate.ofAny("bob"))
                .end("end")
                .connect("start", "review")
                .connect("review", "timeout") // review 完成后进入 timeout 节点
                .connect("timeout", "escalate") // 定时器触发后到 escalate
                .connect("escalate", "end")
                .build();
        procRepo.save(def);

        String instanceId = engine.start("timer-flow-2", Map.of());
        
        // 验证：review 任务已创建
        List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
        assertThat(tasks).hasSize(1);
        TaskInstance reviewTask = tasks.get(0);
        assertThat(reviewTask.getNodeId()).isEqualTo("review");
        assertThat(reviewTask.getStatus()).isEqualTo(TaskStatus.PENDING);

        // 等待定时器触发
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        engine.checkAndTriggerTimers();

        // 验证：review 任务仍在等待（未被取消）
        reviewTask = taskRepo.findById(reviewTask.getId());
        assertThat(reviewTask.getStatus()).isEqualTo(TaskStatus.PENDING);

        // 验证：escalate 任务已创建
        tasks = taskRepo.findByInstanceId(instanceId);
        assertThat(tasks).hasSize(2);
        TaskInstance escalateTask = tasks.stream()
                .filter(t -> t.getNodeId().equals("escalate"))
                .findFirst()
                .orElseThrow();
        assertThat(escalateTask.getStatus()).isEqualTo(TaskStatus.PENDING);
    }

    // ========== 事件取消测试 ==========

    @Test
    @DisplayName("事件取消：流程终止时取消等待中的事件")
    void eventCancellation_eventsCancelledWhenProcessTerminated() {
        ProcessDefinition def = ProcessBuilder.create("cancel-flow")
                .start("start")
                .messageEvent("waitForApproval", "等待审批", "approvalMessage", "${orderId}")
                .end("end")
                .connect("start", "waitForApproval")
                .connect("waitForApproval", "end")
                .build();
        procRepo.save(def);

        String instanceId = engine.start("cancel-flow", Map.of("orderId", "12345"));
        
        // 验证：流程在等待
        ProcessInstance instance = engine.getInstance(instanceId);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.RUNNING);

        // 终止流程
        engine.terminate(instanceId);

        // 验证：流程已终止
        instance = engine.getInstance(instanceId);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.TERMINATED);

        // 验证：发送消息不会触发已终止的流程
        engine.sendMessage("approvalMessage", "12345");
        instance = engine.getInstance(instanceId);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.TERMINATED); // 仍然是终止状态
    }

    // ========== 组合场景测试 ==========

    @Test
    @DisplayName("组合场景：用户任务后跟消息事件")
    void combinedScenario_userTaskFollowedByMessageEvent() {
        ProcessDefinition def = ProcessBuilder.create("combined-flow")
                .start("start")
                .userTask("submit", "提交申请", Candidate.ofAny("alice"))
                .messageEvent("waitForApproval", "等待审批", "approvalMessage", "${orderId}")
                .end("end")
                .connect("start", "submit")
                .connect("submit", "waitForApproval")
                .connect("waitForApproval", "end")
                .build();
        procRepo.save(def);

        String instanceId = engine.start("combined-flow", Map.of("orderId", "12345"));
        
        // 完成用户任务
        List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
        TaskInstance submitTask = tasks.get(0);
        engine.completeTask(submitTask.getId(), "alice", true);

        // 验证：流程在消息节点等待
        ProcessInstance instance = engine.getInstance(instanceId);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(instance.getActiveTokens()).hasSize(1);
        var token = instance.getActiveTokens().values().iterator().next();
        assertThat(token.getCurrentNodeId()).isEqualTo("waitForApproval");

        // 发送消息
        engine.sendMessage("approvalMessage", "12345");

        // 验证：流程已完成
        instance = engine.getInstance(instanceId);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }
}
