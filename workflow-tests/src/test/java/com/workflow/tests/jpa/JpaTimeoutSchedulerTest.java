package com.workflow.tests.jpa;

import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.WorkflowEngine;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
import com.workflow.enums.TimeoutPolicy;
import com.workflow.persistence.jpa.JpaPersistence;
import com.workflow.repository.InstanceRepository;
import com.workflow.repository.ProcessRepository;
import com.workflow.repository.TaskRepository;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import com.workflow.tests.JpaEngineTestBase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JPA 仓储下的超时调度测试 - 验证超时功能在 JPA 持久化下也能正常工作
 *
 * 与 InMemory 版本共用相同用例,验证仓储可替换性
 */
class JpaTimeoutSchedulerTest extends JpaEngineTestBase {

    @Test
    void autoApprove_should_complete_task_when_timeout() throws InterruptedException {
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
        TaskInstance task = tasks.get(0);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);

        Thread.sleep(200);

        task = taskRepo.findById(task.getId());
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.getCompletedApprovers()).contains("__system__");

        ProcessInstance inst = engine.getInstance(instanceId);
        assertThat(inst.getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void autoReject_should_reject_task_when_timeout() throws InterruptedException {
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

        Thread.sleep(200);

        List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
        TaskInstance reviewTask = tasks.stream()
                .filter(t -> t.getNodeId().equals("review"))
                .findFirst().orElseThrow();
        assertThat(reviewTask.getStatus()).isEqualTo(TaskStatus.REJECTED);

        ProcessInstance inst = engine.getInstance(instanceId);
        assertThat(inst.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        List<TaskInstance> newApplyTasks = taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> t.getNodeId().equals("apply") && t.getStatus() == TaskStatus.PENDING)
                .toList();
        assertThat(newApplyTasks).hasSize(1);
    }

    @Test
    void autoTerminate_should_terminate_instance_when_timeout() throws InterruptedException {
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

        Thread.sleep(200);

        ProcessInstance inst = engine.getInstance(instanceId);
        assertThat(inst.getStatus()).isEqualTo(InstanceStatus.TERMINATED);

        TaskInstance task = taskRepo.findByInstanceId(instanceId).get(0);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.TERMINATED);
    }

    @Test
    void autoTransfer_should_transfer_task_when_timeout() throws InterruptedException {
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

        Thread.sleep(200);

        originalTask = taskRepo.findById(originalTask.getId());
        assertThat(originalTask.getStatus()).isEqualTo(TaskStatus.TRANSFERRED);

        List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
        TaskInstance newTask = tasks.stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING)
                .findFirst().orElseThrow();
        assertThat(newTask.getCandidate().getUserIds()).containsExactly("user2");
        assertThat(newTask.getNodeId()).isEqualTo("review");
    }
}
