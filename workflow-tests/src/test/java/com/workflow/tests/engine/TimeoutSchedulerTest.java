package com.workflow.tests.engine;

import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.WorkflowEngine;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
import com.workflow.enums.TimeoutPolicy;
import com.workflow.repository.InMemoryInstanceRepository;
import com.workflow.repository.InMemoryProcessRepository;
import com.workflow.repository.InMemoryTaskRepository;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import com.workflow.tests.EngineTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 超时调度测试 - 验证 UserTask 节点超时后的自动策略动作
 *
 * 覆盖场景:
 *  1. AUTO_APPROVE - 超时自动通过
 *  2. AUTO_REJECT  - 超时自动驳回
 *  3. AUTO_TERMINATE - 超时自动终止
 *  4. AUTO_TRANSFER - 超时自动转办
 *  5. 任务完成时取消超时调度(避免误触发)
 *  6. 会签场景:部分完成不取消调度,整体完成才取消
 */
class TimeoutSchedulerTest extends EngineTestBase {

    @BeforeEach
    void init() { setUp(); }

    @Test
    void autoApprove_should_complete_task_when_timeout() throws InterruptedException {
        // 流程:start -> userTask(timeout 100ms, AUTO_APPROVE) -> end
        ProcessDefinition def = simple("timeout-approve")
                .start("start")
                .userTask("review", "审批", any("user1"))
                .end("end")
                .connect("start", "review")
                .connect("review", "end")
                .timeout("review", 100, TimeoutPolicy.AUTO_APPROVE)
                .build();
        register(def);

        String instanceId = engine.start("timeout-approve", null);
        ProcessInstance inst = engine.getInstance(instanceId);
        List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
        assertThat(tasks).hasSize(1);
        TaskInstance task = tasks.get(0);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);

        // 等待超时触发
        Thread.sleep(200);

        // 验证任务被自动通过
        task = taskRepo.findById(task.getId());
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.getCompletedApprovers()).contains("__system__");

        // 验证实例已完成
        inst = engine.getInstance(instanceId);
        assertThat(inst.getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void autoReject_should_reject_task_when_timeout() throws InterruptedException {
        // 流程:start -> userTask1 -> userTask2(timeout 100ms, AUTO_REJECT) -> end
        ProcessDefinition def = simple("timeout-reject")
                .start("start")
                .userTask("apply", "申请", any("user1"))
                .userTask("review", "审批", any("user2"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "review")
                .connect("review", "end")
                .timeout("review", 100, TimeoutPolicy.AUTO_REJECT)
                .build();
        register(def);

        String instanceId = engine.start("timeout-reject", null);

        // 完成第一个任务,推进到 review
        TaskInstance applyTask = taskRepo.findByInstanceId(instanceId).get(0);
        engine.completeTask(applyTask.getId(), "user1", true);

        // 等待超时触发
        Thread.sleep(200);

        // 验证 review 任务被驳回
        List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
        TaskInstance reviewTask = tasks.stream()
                .filter(t -> t.getNodeId().equals("review"))
                .findFirst().orElseThrow();
        assertThat(reviewTask.getStatus()).isEqualTo(TaskStatus.REJECTED);

        // 验证流程退回到 apply 节点(新建了 apply 任务)
        ProcessInstance inst = engine.getInstance(instanceId);
        assertThat(inst.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        List<TaskInstance> newApplyTasks = taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> t.getNodeId().equals("apply") && t.getStatus() == TaskStatus.PENDING)
                .toList();
        assertThat(newApplyTasks).hasSize(1);
    }

    @Test
    void autoTerminate_should_terminate_instance_when_timeout() throws InterruptedException {
        // 流程:start -> userTask(timeout 100ms, AUTO_TERMINATE) -> end
        ProcessDefinition def = simple("timeout-terminate")
                .start("start")
                .userTask("review", "审批", any("user1"))
                .end("end")
                .connect("start", "review")
                .connect("review", "end")
                .timeout("review", 100, TimeoutPolicy.AUTO_TERMINATE)
                .build();
        register(def);

        String instanceId = engine.start("timeout-terminate", null);

        // 等待超时触发
        Thread.sleep(200);

        // 验证实例被终止
        ProcessInstance inst = engine.getInstance(instanceId);
        assertThat(inst.getStatus()).isEqualTo(InstanceStatus.TERMINATED);

        // 验证任务被终止
        TaskInstance task = taskRepo.findByInstanceId(instanceId).get(0);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.TERMINATED);
    }

    @Test
    void autoTransfer_should_transfer_task_when_timeout() throws InterruptedException {
        // 流程:start -> userTask(timeout 100ms, AUTO_TRANSFER to user2) -> end
        ProcessDefinition def = simple("timeout-transfer")
                .start("start")
                .userTask("review", "审批", any("user1"))
                .end("end")
                .connect("start", "review")
                .connect("review", "end")
                .timeout("review", 100, TimeoutPolicy.AUTO_TRANSFER, "user2")
                .build();
        register(def);

        String instanceId = engine.start("timeout-transfer", null);
        TaskInstance originalTask = taskRepo.findByInstanceId(instanceId).get(0);

        // 等待超时触发
        Thread.sleep(200);

        // 验证原任务被转办
        originalTask = taskRepo.findById(originalTask.getId());
        assertThat(originalTask.getStatus()).isEqualTo(TaskStatus.TRANSFERRED);

        // 验证新建了 user2 的任务
        List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
        TaskInstance newTask = tasks.stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING)
                .findFirst().orElseThrow();
        assertThat(newTask.getCandidate().getUserIds()).containsExactly("user2");
        assertThat(newTask.getNodeId()).isEqualTo("review");
    }

    @Test
    void complete_task_should_cancel_timeout_scheduler() throws InterruptedException {
        // 流程:start -> userTask(timeout 500ms, AUTO_APPROVE) -> end
        ProcessDefinition def = simple("cancel-on-complete")
                .start("start")
                .userTask("review", "审批", any("user1"))
                .end("end")
                .connect("start", "review")
                .connect("review", "end")
                .timeout("review", 500, TimeoutPolicy.AUTO_APPROVE)
                .build();
        register(def);

        String instanceId = engine.start("cancel-on-complete", null);
        TaskInstance task = taskRepo.findByInstanceId(instanceId).get(0);

        // 在超时前手动完成
        engine.completeTask(task.getId(), "user1", true);

        // 等待超过原超时时间
        Thread.sleep(600);

        // 验证实例已完成(非超时触发)
        ProcessInstance inst = engine.getInstance(instanceId);
        assertThat(inst.getStatus()).isEqualTo(InstanceStatus.COMPLETED);

        // 验证 completedApprovers 只有 user1(无 __system__)
        task = taskRepo.findById(task.getId());
        assertThat(task.getCompletedApprovers()).containsExactly("user1");
    }

    @Test
    void allSign_partial_complete_should_keep_timeout() throws InterruptedException {
        // 流程:start -> userTask(ALL sign, timeout 200ms, AUTO_APPROVE) -> end
        ProcessDefinition def = simple("all-sign-timeout")
                .start("start")
                .userTask("review", "会签", all("user1", "user2"))
                .end("end")
                .connect("start", "review")
                .connect("review", "end")
                .timeout("review", 200, TimeoutPolicy.AUTO_APPROVE)
                .build();
        register(def);

        String instanceId = engine.start("all-sign-timeout", null);
        TaskInstance task = taskRepo.findByInstanceId(instanceId).get(0);

        // user1 完成,但 user2 未完成(会签整体未完成)
        engine.completeTask(task.getId(), "user1", true);
        task = taskRepo.findById(task.getId());
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.getCompletedApprovers()).containsExactly("user1");

        // 等待超时触发
        Thread.sleep(300);

        // 验证超时自动通过(即使 user1 已完成,超时仍触发)
        task = taskRepo.findById(task.getId());
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.getCompletedApprovers()).containsExactlyInAnyOrder("user1", "__system__");

        // 验证实例已完成
        ProcessInstance inst = engine.getInstance(instanceId);
        assertThat(inst.getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }
}
