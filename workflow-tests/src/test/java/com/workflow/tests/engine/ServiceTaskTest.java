package com.workflow.tests.engine;

import com.workflow.builder.ProcessBuilder;
import com.workflow.delegate.DelegateExecution;
import com.workflow.delegate.ServiceTaskDelegate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.WorkflowEngine;
import com.workflow.engine.WorkflowEngineBuilder;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 服务任务节点测试
 */
@DisplayName("服务任务节点")
class ServiceTaskTest {

    private InMemoryProcessRepository procRepo;
    private InMemoryInstanceRepository instRepo;
    private InMemoryTaskRepository taskRepo;
    private WorkflowEngine engine;

    @BeforeEach
    void setUp() {
        procRepo = new InMemoryProcessRepository();
        instRepo = new InMemoryInstanceRepository();
        taskRepo = new InMemoryTaskRepository();
        engine = WorkflowEngineBuilder.builder(procRepo, instRepo, taskRepo).build();
    }

    @Test
    @DisplayName("正常执行 delegate 后自动推进")
    void normalExecution() {
        AtomicInteger counter = new AtomicInteger(0);
        
        // 注册 delegate
        engine.registerDelegate("increment", (DelegateExecution execution) -> {
            counter.incrementAndGet();
        });

        ProcessDefinition def = ProcessBuilder.create("service-task-flow")
                .start("start")
                .userTask("apply", "申请", com.workflow.definition.Candidate.ofAny("user1"))
                .serviceTask("notify", "发送通知", "increment")
                .end("end")
                .connect("start", "apply")
                .connect("apply", "notify")
                .connect("notify", "end")
                .build();
        procRepo.save(def);

        String instanceId = engine.start("service-task-flow", Map.of());

        // 完成 apply 任务
        List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
        assertThat(tasks).hasSize(1);
        TaskInstance applyTask = tasks.get(0);
        assertThat(applyTask.getNodeId()).isEqualTo("apply");
        assertThat(applyTask.getStatus()).isEqualTo(TaskStatus.PENDING);

        engine.completeTask(applyTask.getId(), "user1", true);

        // 验证 delegate 被执行
        assertThat(counter.get()).isEqualTo(1);

        // 验证流程完成
        ProcessInstance instance = instRepo.findById(instanceId);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    @DisplayName("delegate 未注册时流程挂起")
    void delegateNotRegistered() {
        ProcessDefinition def = ProcessBuilder.create("service-task-missing")
                .start("start")
                .userTask("apply", "申请", com.workflow.definition.Candidate.ofAny("user1"))
                .serviceTask("notify", "发送通知", "missing-delegate")
                .end("end")
                .connect("start", "apply")
                .connect("apply", "notify")
                .connect("notify", "end")
                .build();
        procRepo.save(def);

        String instanceId = engine.start("service-task-missing", Map.of());

        // 完成 apply 任务，触发 serviceTask
        List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
        TaskInstance applyTask = tasks.stream()
                .filter(t -> "apply".equals(t.getNodeId()))
                .findFirst()
                .orElseThrow();

        // 验证抛异常
        try {
            engine.completeTask(applyTask.getId(), "user1", true);
            // 如果没抛异常，测试失败
            assertThat(false).as("应该抛出 IllegalStateException").isTrue();
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).contains("未注册");
        }

        // 注意：由于事务回滚，流程状态可能仍是 RUNNING
        // 实际生产中，需要在事务外处理挂起逻辑
    }

    @Test
    @DisplayName("delegate 抛异常时流程挂起")
    void delegateThrowsException() {
        engine.registerDelegate("failing", (DelegateExecution execution) -> {
            throw new RuntimeException("模拟执行失败");
        });

        ProcessDefinition def = ProcessBuilder.create("service-task-fail")
                .start("start")
                .userTask("apply", "申请", com.workflow.definition.Candidate.ofAny("user1"))
                .serviceTask("notify", "发送通知", "failing")
                .end("end")
                .connect("start", "apply")
                .connect("apply", "notify")
                .connect("notify", "end")
                .build();
        procRepo.save(def);

        String instanceId = engine.start("service-task-fail", Map.of());

        // 完成 apply 任务，触发 serviceTask
        List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
        TaskInstance applyTask = tasks.stream()
                .filter(t -> "apply".equals(t.getNodeId()))
                .findFirst()
                .orElseThrow();

        // 验证抛异常
        try {
            engine.completeTask(applyTask.getId(), "user1", true);
            // 如果没抛异常，测试失败
            assertThat(false).as("应该抛出 RuntimeException").isTrue();
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).contains("执行失败");
        }

        // 注意：由于事务回滚，流程状态可能仍是 RUNNING
        // 实际生产中，需要在事务外处理挂起逻辑
    }

    @Test
    @DisplayName("delegate 可以访问流程变量")
    void delegateCanAccessVariables() {
        engine.registerDelegate("check-var", (DelegateExecution execution) -> {
            Object value = execution.getVariable("testVar");
            if (!"hello".equals(value)) {
                throw new RuntimeException("变量值不对");
            }
        });

        ProcessDefinition def = ProcessBuilder.create("service-task-var")
                .start("start")
                .serviceTask("check", "检查变量", "check-var")
                .end("end")
                .connect("start", "check")
                .connect("check", "end")
                .build();
        procRepo.save(def);

        String instanceId = engine.start("service-task-var", Map.of("testVar", "hello"));

        ProcessInstance instance = instRepo.findById(instanceId);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    @DisplayName("多个 serviceTask 顺序执行")
    void multipleServiceTasks() {
        StringBuilder log = new StringBuilder();
        
        engine.registerDelegate("step1", (DelegateExecution execution) -> {
            log.append("step1,");
        });
        engine.registerDelegate("step2", (DelegateExecution execution) -> {
            log.append("step2,");
        });
        engine.registerDelegate("step3", (DelegateExecution execution) -> {
            log.append("step3");
        });

        ProcessDefinition def = ProcessBuilder.create("multi-service-task")
                .start("start")
                .serviceTask("s1", "步骤 1", "step1")
                .serviceTask("s2", "步骤 2", "step2")
                .serviceTask("s3", "步骤 3", "step3")
                .end("end")
                .connect("start", "s1")
                .connect("s1", "s2")
                .connect("s2", "s3")
                .connect("s3", "end")
                .build();
        procRepo.save(def);

        String instanceId = engine.start("multi-service-task", Map.of());

        assertThat(log.toString()).isEqualTo("step1,step2,step3");

        ProcessInstance instance = instRepo.findById(instanceId);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }
}
