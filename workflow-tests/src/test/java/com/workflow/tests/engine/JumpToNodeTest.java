package com.workflow.tests.engine;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.WorkflowEngine;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 跳转到任意节点功能测试
 */
@DisplayName("跳转到任意节点")
class JumpToNodeTest {

    private InMemoryProcessRepository procRepo;
    private InMemoryInstanceRepository instRepo;
    private InMemoryTaskRepository taskRepo;
    private WorkflowEngine engine;

    @BeforeEach
    void setUp() {
        procRepo = new InMemoryProcessRepository();
        instRepo = new InMemoryInstanceRepository();
        taskRepo = new InMemoryTaskRepository();
        engine = new WorkflowEngine(procRepo, instRepo, taskRepo);
    }

    @Test
    @DisplayName("跳转到早期节点 - 回退到第一个任务节点")
    void jumpToEarlyNode() {
        // 创建流程：start -> apply -> manager -> hr -> end
        ProcessDefinition def = ProcessBuilder.create("jump-flow-1")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("u1"))
                .userTask("manager", "经理审批", Candidate.ofAny("m1"))
                .userTask("hr", "HR审批", Candidate.ofAny("h1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "manager")
                .connect("manager", "hr")
                .connect("hr", "end")
                .build();
        procRepo.save(def);

        // 启动流程并完成 apply 任务
        String instanceId = engine.start("jump-flow-1", Map.of());
        TaskInstance applyTask = taskRepo.findByInstanceId(instanceId).get(0);
        engine.completeTask(applyTask.getId(), "u1", true);

        // 验证：当前在 manager 节点
        ProcessInstance instance = engine.getInstance(instanceId);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        TaskInstance managerTask = taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING)
                .findFirst().orElseThrow();
        assertThat(managerTask.getNodeId()).isEqualTo("manager");

        // 跳转到 apply 节点（回退）
        engine.jumpToNode(instanceId, "apply", "admin", "需要重新填写申请");

        // 验证：manager 任务被终止，apply 任务重新创建
        instance = engine.getInstance(instanceId);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        
        List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
        TaskInstance terminatedManager = tasks.stream()
                .filter(t -> t.getNodeId().equals("manager"))
                .findFirst().orElseThrow();
        assertThat(terminatedManager.getStatus()).isEqualTo(TaskStatus.TERMINATED);

        TaskInstance newApplyTask = tasks.stream()
                .filter(t -> t.getNodeId().equals("apply") && t.getStatus() == TaskStatus.PENDING)
                .findFirst().orElseThrow();
        assertThat(newApplyTask).isNotNull();
        assertThat(newApplyTask.getCandidate().getUserIds()).contains("u1");
    }

    @Test
    @DisplayName("跳转到后期节点 - 跳过中间节点")
    void jumpToLaterNode() {
        // 创建流程：start -> apply -> manager -> hr -> end
        ProcessDefinition def = ProcessBuilder.create("jump-flow-2")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("u1"))
                .userTask("manager", "经理审批", Candidate.ofAny("m1"))
                .userTask("hr", "HR审批", Candidate.ofAny("h1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "manager")
                .connect("manager", "hr")
                .connect("hr", "end")
                .build();
        procRepo.save(def);

        // 启动流程，当前在 apply 节点
        String instanceId = engine.start("jump-flow-2", Map.of());
        ProcessInstance instance = engine.getInstance(instanceId);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.RUNNING);

        // 直接跳转到 hr 节点（跳过 manager）
        engine.jumpToNode(instanceId, "hr", "admin", "经理已口头同意");

        // 验证：apply 任务被终止，hr 任务创建
        instance = engine.getInstance(instanceId);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        
        List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
        TaskInstance terminatedApply = tasks.stream()
                .filter(t -> t.getNodeId().equals("apply"))
                .findFirst().orElseThrow();
        assertThat(terminatedApply.getStatus()).isEqualTo(TaskStatus.TERMINATED);

        TaskInstance hrTask = tasks.stream()
                .filter(t -> t.getNodeId().equals("hr") && t.getStatus() == TaskStatus.PENDING)
                .findFirst().orElseThrow();
        assertThat(hrTask).isNotNull();
        assertThat(hrTask.getCandidate().getUserIds()).contains("h1");
    }

    @Test
    @DisplayName("跳转到并行网关后的节点")
    void jumpToParallelBranch() {
        // 创建流程：start -> apply -> parallel_fork -> (task1, task2) -> parallel_join -> end
        ProcessDefinition def = ProcessBuilder.create("jump-flow-3")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("u1"))
                .parallelGateway("fork")
                .userTask("task1", "任务1", Candidate.ofAny("w1"))
                .userTask("task2", "任务2", Candidate.ofAny("w2"))
                .parallelGateway("join")
                .end("end")
                .connect("start", "apply")
                .connect("apply", "fork")
                .connect("fork", "task1")
                .connect("fork", "task2")
                .connect("task1", "join")
                .connect("task2", "join")
                .connect("join", "end")
                .build();
        procRepo.save(def);

        // 启动流程并完成 apply
        String instanceId = engine.start("jump-flow-3", Map.of());
        TaskInstance applyTask = taskRepo.findByInstanceId(instanceId).get(0);
        engine.completeTask(applyTask.getId(), "u1", true);

        // 验证：当前在并行分支，有两个 PENDING 任务
        ProcessInstance instance = engine.getInstance(instanceId);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
        long pendingCount = tasks.stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING)
                .count();
        assertThat(pendingCount).isEqualTo(2);

        // 跳转到 apply 节点（回退到并行网关之前）
        engine.jumpToNode(instanceId, "apply", "admin", "需要重新申请");

        // 验证：并行分支的任务被终止，apply 任务重新创建
        instance = engine.getInstance(instanceId);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        
        tasks = taskRepo.findByInstanceId(instanceId);
        long terminatedCount = tasks.stream()
                .filter(t -> t.getStatus() == TaskStatus.TERMINATED)
                .count();
        assertThat(terminatedCount).isGreaterThanOrEqualTo(2); // task1 和 task2 被终止

        TaskInstance newApplyTask = tasks.stream()
                .filter(t -> t.getNodeId().equals("apply") && t.getStatus() == TaskStatus.PENDING)
                .findFirst().orElseThrow();
        assertThat(newApplyTask).isNotNull();
    }

    @Test
    @DisplayName("跳转到不存在的节点 - 应抛异常")
    void jumpToNonExistentNode() {
        ProcessDefinition def = ProcessBuilder.create("jump-flow-4")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("u1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        procRepo.save(def);

        String instanceId = engine.start("jump-flow-4", Map.of());

        assertThatThrownBy(() -> engine.jumpToNode(instanceId, "nonexistent", "admin", "test"))
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("非 RUNNING 状态跳转 - 应抛异常")
    void jumpWhenNotRunning() {
        ProcessDefinition def = ProcessBuilder.create("jump-flow-5")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("u1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        procRepo.save(def);

        String instanceId = engine.start("jump-flow-5", Map.of());
        
        // 终止流程
        engine.terminate(instanceId);

        assertThatThrownBy(() -> engine.jumpToNode(instanceId, "apply", "admin", "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("仅 RUNNING 状态的流程可跳转");
    }

    @Test
    @DisplayName("跳转后继续正常流转")
    void jumpAndContinueFlow() {
        ProcessDefinition def = ProcessBuilder.create("jump-flow-6")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("u1"))
                .userTask("manager", "经理审批", Candidate.ofAny("m1"))
                .userTask("hr", "HR审批", Candidate.ofAny("h1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "manager")
                .connect("manager", "hr")
                .connect("hr", "end")
                .build();
        procRepo.save(def);

        // 启动流程并完成 apply
        String instanceId = engine.start("jump-flow-6", Map.of());
        TaskInstance applyTask = taskRepo.findByInstanceId(instanceId).get(0);
        engine.completeTask(applyTask.getId(), "u1", true);

        // 跳转到 apply（回退）
        engine.jumpToNode(instanceId, "apply", "admin", "重新申请");

        // 重新完成 apply
        TaskInstance newApplyTask = taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> t.getNodeId().equals("apply") && t.getStatus() == TaskStatus.PENDING)
                .findFirst().orElseThrow();
        engine.completeTask(newApplyTask.getId(), "u1", true);

        // 验证：流程正常流转到 manager
        ProcessInstance instance = engine.getInstance(instanceId);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        
        TaskInstance managerTask = taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> t.getNodeId().equals("manager") && t.getStatus() == TaskStatus.PENDING)
                .findFirst().orElseThrow();
        assertThat(managerTask).isNotNull();

        // 完成 manager 和 hr
        engine.completeTask(managerTask.getId(), "m1", true);
        TaskInstance hrTask = taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> t.getNodeId().equals("hr") && t.getStatus() == TaskStatus.PENDING)
                .findFirst().orElseThrow();
        engine.completeTask(hrTask.getId(), "h1", true);

        // 验证：流程完成
        instance = engine.getInstance(instanceId);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }
}
