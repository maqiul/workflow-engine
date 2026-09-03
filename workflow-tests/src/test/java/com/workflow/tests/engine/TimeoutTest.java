package com.workflow.tests.engine;

import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.WorkflowEngine;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
import com.workflow.enums.TimeoutPolicy;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import com.workflow.tests.EngineTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 超时调度测试 - 验证 UserTask 节点超时后的自动动作
 *
 * 测试场景:
 *  1. AUTO_APPROVE:超时后自动通过,流程继续
 *  2. AUTO_REJECT:超时后自动驳回,回到上一节点
 *  3. AUTO_TERMINATE:超时后实例终止
 *  4. AUTO_TRANSFER:超时后自动转办给目标用户
 *  5. 手动完成后超时不触发(幂等)
 *  6. 驳回后超时取消
 *  7. 转办后新任务继承超时配置
 */
public class TimeoutTest extends EngineTestBase {

    @BeforeEach
    protected void setUp() {
        super.setUp();
    }

    @Test
    void auto_approve_should_advance_flow() throws InterruptedException {
        // 构建流程:start -> task1(超时 100ms AUTO_APPROVE) -> end
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
        ProcessInstance instance = engine.getInstance(instanceId);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.RUNNING);

        List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
        assertThat(tasks).hasSize(1);
        TaskInstance task = tasks.get(0);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);

        // 等待超时触发
        Thread.sleep(200);

        // 验证任务已自动通过,流程完成
        task = taskRepo.findById(task.getId());
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.getCompletedApprovers()).contains("__system__");

        instance = engine.getInstance(instanceId);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void auto_reject_should_go_back() throws InterruptedException {
        // 构建流程:start -> task1 -> task2(超时 100ms AUTO_REJECT) -> end
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
        ProcessInstance instance = engine.getInstance(instanceId);

        // 完成 task1,让流程走到 task2
        List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
        TaskInstance task1 = tasks.stream().filter(t -> t.getNodeId().equals("task1")).findFirst().orElseThrow();
        engine.completeTask(task1.getId(), "user1", true);

        // 等待 task2 超时
        Thread.sleep(200);

        // 验证 task2 已驳回,流程回到 task1
        tasks = taskRepo.findByInstanceId(instanceId);
        TaskInstance task2 = tasks.stream().filter(t -> t.getNodeId().equals("task2")).findFirst().orElseThrow();
        assertThat(task2.getStatus()).isEqualTo(TaskStatus.REJECTED);

        // 应该重新生成 task1 的待办
        List<TaskInstance> newTask1List = tasks.stream()
                .filter(t -> t.getNodeId().equals("task1") && t.getStatus() == TaskStatus.PENDING)
                .toList();
        assertThat(newTask1List).hasSize(1);
    }

    @Test
    void auto_terminate_should_close_instance() throws InterruptedException {
        // 构建流程:start -> task1(超时 100ms AUTO_TERMINATE) -> end
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

        // 等待超时
        Thread.sleep(200);

        // 验证实例已终止
        ProcessInstance instance = engine.getInstance(instanceId);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.TERMINATED);

        // 验证所有任务已终止
        List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
        tasks.forEach(t -> assertThat(t.getStatus()).isEqualTo(TaskStatus.TERMINATED));
    }

    @Test
    void auto_transfer_should_redirect_to_target() throws InterruptedException {
        // 构建流程:start -> task1(超时 100ms AUTO_TRANSFER to user2) -> end
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

        // 等待超时(给后台线程足够时间执行)
        Thread.sleep(500);

        // 验证原任务已转办
        List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
        TaskInstance originalTask = tasks.stream()
                .filter(t -> t.getCandidate().getUserIds().contains("user1"))
                .findFirst().orElseThrow();
        assertThat(originalTask.getStatus()).isEqualTo(TaskStatus.TRANSFERRED);

        // 验证新任务已生成,候选人为 user2
        TaskInstance newTask = tasks.stream()
                .filter(t -> t.getCandidate().getUserIds().contains("user2") && t.getStatus() == TaskStatus.PENDING)
                .findFirst().orElseThrow();
        assertThat(newTask.getNodeId()).isEqualTo("task1");
    }

    @Test
    void manual_complete_should_cancel_timeout() throws InterruptedException {
        // 构建流程:start -> task1(超时 500ms AUTO_APPROVE) -> end
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
        List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
        TaskInstance task = tasks.get(0);

        // 手动完成(在超时前)
        engine.completeTask(task.getId(), "user1", true);

        // 等待超时时间过去
        Thread.sleep(600);

        // 验证任务状态仍为 COMPLETED(未被超时覆盖)
        task = taskRepo.findById(task.getId());
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.getCompletedApprovers()).contains("user1").doesNotContain("__system__");

        // 验证流程已完成
        ProcessInstance instance = engine.getInstance(instanceId);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void reject_should_cancel_timeout() throws InterruptedException {
        // 构建流程:start -> task1(超时 500ms AUTO_APPROVE) -> task2 -> end
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
        List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
        TaskInstance task1 = tasks.get(0);

        // 手动驳回(在超时前)
        engine.rejectTask(task1.getId(), "user1", "测试驳回");

        // 等待超时时间过去
        Thread.sleep(600);

        // 验证 task1 状态仍为 REJECTED(未被超时覆盖)
        task1 = taskRepo.findById(task1.getId());
        assertThat(task1.getStatus()).isEqualTo(TaskStatus.REJECTED);
    }

    @Test
    void transfer_should_inherit_timeout() throws InterruptedException {
        // 构建流程:start -> task1(超时 100ms AUTO_APPROVE) -> end
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
        List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
        TaskInstance task1 = tasks.get(0);

        // 手动转办给 user2
        engine.transferTask(task1.getId(), "user1", "user2");

        // 验证新任务已生成
        tasks = taskRepo.findByInstanceId(instanceId);
        TaskInstance newTask = tasks.stream()
                .filter(t -> t.getCandidate().getUserIds().contains("user2") && t.getStatus() == TaskStatus.PENDING)
                .findFirst().orElseThrow();

        // 等待超时(新任务应继承超时配置)
        Thread.sleep(200);

        // 验证新任务已自动通过
        newTask = taskRepo.findById(newTask.getId());
        assertThat(newTask.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(newTask.getCompletedApprovers()).contains("__system__");
    }
}
