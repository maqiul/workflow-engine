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
import com.workflow.tests.support.Await;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 超时调度测试 - 验证 UserTask 节点超时后的自动策略动作。
 *
 * <p>等待真实调度触发一律用 {@link Await} 轮询（而非固定 sleep）：CI 共享 runner
 * 上调度线程可能被抢占，固定 sleep 缓冲不足会偶发 flaky。轮询到条件成立即返回，
 * 既鲁棒又不拖慢。
 */
class TimeoutSchedulerTest extends EngineTestBase {

    @BeforeEach
    void init() { setUp(); }

    @Test
    void autoApprove_should_complete_task_when_timeout() {
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
        List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
        assertThat(tasks).hasSize(1);
        String taskId = tasks.get(0).getId();
        assertThat(taskRepo.findById(taskId).getStatus()).isEqualTo(TaskStatus.PENDING);

        // 轮询等待超时自动通过
        // 轮询最终态(实例完成)，而非 task COMPLETED 这一中间态(其后还要 advance 到 end)
        Await.until(() -> engine.getInstance(instanceId).getStatus() == InstanceStatus.COMPLETED, 5000);

        TaskInstance task = taskRepo.findById(taskId);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.getCompletedApprovers()).contains("__system__");
        assertThat(engine.getInstance(instanceId).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void autoReject_should_reject_task_when_timeout() {
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
        TaskInstance applyTask = taskRepo.findByInstanceId(instanceId).get(0);
        engine.completeTask(applyTask.getId(), "user1", true);

        String reviewId = taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> t.getNodeId().equals("review")).findFirst().orElseThrow().getId();
        // 轮询到"驳回后回到 apply 且新待办已生成"这一最终态；
        // 只等 review=REJECTED 会读到中间态(调度线程尚未建 apply)。
        Await.until(() -> taskRepo.findByInstanceId(instanceId).stream()
                .anyMatch(t -> t.getNodeId().equals("apply") && t.getStatus() == TaskStatus.PENDING), 5000);

        assertThat(taskRepo.findById(reviewId).getStatus()).isEqualTo(TaskStatus.REJECTED);
        assertThat(engine.getInstance(instanceId).getStatus()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> t.getNodeId().equals("apply") && t.getStatus() == TaskStatus.PENDING)
                .toList()).hasSize(1);
    }

    @Test
    void autoTerminate_should_terminate_instance_when_timeout() {
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
        String taskId = taskRepo.findByInstanceId(instanceId).get(0).getId();

        Await.until(() -> engine.getInstance(instanceId).getStatus() == InstanceStatus.TERMINATED, 5000);

        assertThat(engine.getInstance(instanceId).getStatus()).isEqualTo(InstanceStatus.TERMINATED);
        assertThat(taskRepo.findById(taskId).getStatus()).isEqualTo(TaskStatus.TERMINATED);
    }

    @Test
    void autoTransfer_should_transfer_task_when_timeout() {
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
        String originalId = taskRepo.findByInstanceId(instanceId).get(0).getId();

        // 轮询到"转办后 user2 新待办已生成"最终态，避免只等原任务 TRANSFERRED 的中间态
        Await.until(() -> taskRepo.findByInstanceId(instanceId).stream()
                .anyMatch(t -> t.getStatus() == TaskStatus.PENDING
                        && t.getCandidate().getUserIds().contains("user2")), 5000);

        assertThat(taskRepo.findById(originalId).getStatus()).isEqualTo(TaskStatus.TRANSFERRED);
        TaskInstance newTask = taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING).findFirst().orElseThrow();
        assertThat(newTask.getCandidate().getUserIds()).containsExactly("user2");
        assertThat(newTask.getNodeId()).isEqualTo("review");
    }

    @Test
    void complete_task_should_cancel_timeout_scheduler() throws InterruptedException {
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
        String taskId = taskRepo.findByInstanceId(instanceId).get(0).getId();
        engine.completeTask(taskId, "user1", true);

        // 负向验证：等足够久，确认超时调度已被取消、不会二次触发（慢机器上只会更安全）
        Thread.sleep(700);

        assertThat(engine.getInstance(instanceId).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(taskRepo.findById(taskId).getCompletedApprovers()).containsExactly("user1");
    }

    @Test
    void allSign_partial_complete_should_keep_timeout() {
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
        String taskId = taskRepo.findByInstanceId(instanceId).get(0).getId();
        engine.completeTask(taskId, "user1", true);
        assertThat(taskRepo.findById(taskId).getStatus()).isEqualTo(TaskStatus.PENDING);

        // 轮询最终态(实例完成)，而非 task COMPLETED 这一中间态(其后还要 advance 到 end)
        Await.until(() -> engine.getInstance(instanceId).getStatus() == InstanceStatus.COMPLETED, 5000);

        TaskInstance task = taskRepo.findById(taskId);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.getCompletedApprovers()).containsExactlyInAnyOrder("user1", "__system__");
        assertThat(engine.getInstance(instanceId).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }
}
