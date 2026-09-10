package com.workflow.tests.jpa;

import com.workflow.definition.ProcessDefinition;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
import com.workflow.enums.TimeoutPolicy;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import com.workflow.tests.JpaEngineTestBase;
import com.workflow.tests.support.Await;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JPA 仓储下的超时调度测试 —— 与 InMemory 共用同一组用例，验证仓储可替换性。
 *
 * <p>等待真实调度用 {@link Await} 轮询，避免 CI 慢机上固定 sleep 缓冲不足导致 flaky。
 */
class JpaTimeoutSchedulerTest extends JpaEngineTestBase {

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

        Await.until(() -> taskRepo.findById(taskId).getStatus() == TaskStatus.COMPLETED, 5000);

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
        Await.until(() -> taskRepo.findById(reviewId).getStatus() == TaskStatus.REJECTED, 5000);

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

        Await.until(() -> taskRepo.findById(originalId).getStatus() == TaskStatus.TRANSFERRED, 5000);

        assertThat(taskRepo.findById(originalId).getStatus()).isEqualTo(TaskStatus.TRANSFERRED);
        TaskInstance newTask = taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING).findFirst().orElseThrow();
        assertThat(newTask.getCandidate().getUserIds()).containsExactly("user2");
        assertThat(newTask.getNodeId()).isEqualTo("review");
    }
}
