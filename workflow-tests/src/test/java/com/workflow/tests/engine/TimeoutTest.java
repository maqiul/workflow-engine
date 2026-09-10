package com.workflow.tests.engine;

import com.workflow.definition.ProcessDefinition;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
import com.workflow.enums.TimeoutPolicy;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import com.workflow.tests.EngineTestBase;
import com.workflow.tests.support.Await;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 超时调度测试 —— 验证 UserTask 超时后的自动动作。
 *
 * <p>正向等待（等超时动作发生）一律用 {@link Await} 轮询，避免 CI 慢机固定 sleep 缓冲不足 flaky；
 * 负向验证（确认超时被取消、不触发）保留固定 sleep（慢机器只会更安全）。
 */
public class TimeoutTest extends EngineTestBase {

    @BeforeEach
    protected void setUp() {
        super.setUp();
    }

    @Test
    void auto_approve_should_advance_flow() {
        ProcessDefinition def = simple("timeout_approve")
                .start("start")
                .userTask("task1", "任务1", any("user1"))
                .end("end")
                .connect("start", "task1")
                .connect("task1", "end")
                .timeout("task1", 100, TimeoutPolicy.AUTO_APPROVE)
                .build();
        register(def);

        String instanceId = engine.start("timeout_approve", Map.of());
        String taskId = taskRepo.findByInstanceId(instanceId).get(0).getId();
        assertThat(taskRepo.findById(taskId).getStatus()).isEqualTo(TaskStatus.PENDING);

        Await.until(() -> taskRepo.findById(taskId).getStatus() == TaskStatus.COMPLETED, 5000);

        TaskInstance task = taskRepo.findById(taskId);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.getCompletedApprovers()).contains("__system__");
        assertThat(engine.getInstance(instanceId).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void auto_reject_should_go_back() {
        ProcessDefinition def = simple("timeout_reject")
                .start("start")
                .userTask("task1", "任务1", any("user1"))
                .userTask("task2", "任务2", any("user2"))
                .end("end")
                .connect("start", "task1")
                .connect("task1", "task2")
                .connect("task2", "end")
                .timeout("task2", 100, TimeoutPolicy.AUTO_REJECT)
                .build();
        register(def);

        String instanceId = engine.start("timeout_reject", Map.of());
        TaskInstance task1 = taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> t.getNodeId().equals("task1")).findFirst().orElseThrow();
        engine.completeTask(task1.getId(), "user1", true);

        String task2Id = taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> t.getNodeId().equals("task2")).findFirst().orElseThrow().getId();
        Await.until(() -> taskRepo.findById(task2Id).getStatus() == TaskStatus.REJECTED, 5000);

        assertThat(taskRepo.findById(task2Id).getStatus()).isEqualTo(TaskStatus.REJECTED);
        assertThat(taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> t.getNodeId().equals("task1") && t.getStatus() == TaskStatus.PENDING)
                .toList()).hasSize(1);
    }

    @Test
    void auto_terminate_should_close_instance() {
        ProcessDefinition def = simple("timeout_terminate")
                .start("start")
                .userTask("task1", "任务1", any("user1"))
                .end("end")
                .connect("start", "task1")
                .connect("task1", "end")
                .timeout("task1", 100, TimeoutPolicy.AUTO_TERMINATE)
                .build();
        register(def);

        String instanceId = engine.start("timeout_terminate", Map.of());
        Await.until(() -> engine.getInstance(instanceId).getStatus() == InstanceStatus.TERMINATED, 5000);

        assertThat(engine.getInstance(instanceId).getStatus()).isEqualTo(InstanceStatus.TERMINATED);
        taskRepo.findByInstanceId(instanceId).forEach(t ->
                assertThat(t.getStatus()).isEqualTo(TaskStatus.TERMINATED));
    }

    @Test
    void auto_transfer_should_redirect_to_target() {
        ProcessDefinition def = simple("timeout_transfer")
                .start("start")
                .userTask("task1", "任务1", any("user1"))
                .end("end")
                .connect("start", "task1")
                .connect("task1", "end")
                .timeout("task1", 100, TimeoutPolicy.AUTO_TRANSFER, "user2")
                .build();
        register(def);

        String instanceId = engine.start("timeout_transfer", Map.of());
        String originalId = taskRepo.findByInstanceId(instanceId).get(0).getId();

        Await.until(() -> taskRepo.findById(originalId).getStatus() == TaskStatus.TRANSFERRED, 5000);

        assertThat(taskRepo.findById(originalId).getStatus()).isEqualTo(TaskStatus.TRANSFERRED);
        assertThat(taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> t.getCandidate().getUserIds().contains("user2") && t.getStatus() == TaskStatus.PENDING)
                .findFirst().orElseThrow().getNodeId()).isEqualTo("task1");
    }

    @Test
    void manual_complete_should_cancel_timeout() throws InterruptedException {
        ProcessDefinition def = simple("timeout_cancel")
                .start("start")
                .userTask("task1", "任务1", any("user1"))
                .end("end")
                .connect("start", "task1")
                .connect("task1", "end")
                .timeout("task1", 500, TimeoutPolicy.AUTO_APPROVE)
                .build();
        register(def);

        String instanceId = engine.start("timeout_cancel", Map.of());
        String taskId = taskRepo.findByInstanceId(instanceId).get(0).getId();
        engine.completeTask(taskId, "user1", true);

        // 负向：等超过超时时间，确认调度已取消、不会二次覆盖（慢机器只会更安全）
        Thread.sleep(700);

        TaskInstance task = taskRepo.findById(taskId);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.getCompletedApprovers()).contains("user1").doesNotContain("__system__");
        assertThat(engine.getInstance(instanceId).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void reject_should_cancel_timeout() throws InterruptedException {
        ProcessDefinition def = simple("timeout_reject_cancel")
                .start("start")
                .userTask("task1", "任务1", any("user1"))
                .userTask("task2", "任务2", any("user2"))
                .end("end")
                .connect("start", "task1")
                .connect("task1", "task2")
                .connect("task2", "end")
                .timeout("task1", 500, TimeoutPolicy.AUTO_APPROVE)
                .build();
        register(def);

        String instanceId = engine.start("timeout_reject_cancel", Map.of());
        String task1Id = taskRepo.findByInstanceId(instanceId).get(0).getId();
        engine.rejectTask(task1Id, "user1", "测试驳回");

        Thread.sleep(700);

        assertThat(taskRepo.findById(task1Id).getStatus()).isEqualTo(TaskStatus.REJECTED);
    }

    @Test
    void transfer_should_inherit_timeout() {
        ProcessDefinition def = simple("timeout_transfer_inherit")
                .start("start")
                .userTask("task1", "任务1", any("user1"))
                .end("end")
                .connect("start", "task1")
                .connect("task1", "end")
                .timeout("task1", 100, TimeoutPolicy.AUTO_APPROVE)
                .build();
        register(def);

        String instanceId = engine.start("timeout_transfer_inherit", Map.of());
        String task1Id = taskRepo.findByInstanceId(instanceId).get(0).getId();
        engine.transferTask(task1Id, "user1", "user2");

        String newTaskId = taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> t.getCandidate().getUserIds().contains("user2") && t.getStatus() == TaskStatus.PENDING)
                .findFirst().orElseThrow().getId();

        // 新任务继承超时配置，轮询等待其自动通过
        Await.until(() -> taskRepo.findById(newTaskId).getStatus() == TaskStatus.COMPLETED, 5000);

        TaskInstance newTask = taskRepo.findById(newTaskId);
        assertThat(newTask.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(newTask.getCompletedApprovers()).contains("__system__");
    }
}
