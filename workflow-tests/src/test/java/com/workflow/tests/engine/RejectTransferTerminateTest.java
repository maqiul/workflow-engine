package com.workflow.tests.engine;

import com.workflow.definition.ProcessDefinition;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import com.workflow.tests.EngineTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用例 4: 驳回 + 转办 + 终止
 */
class RejectTransferTerminateTest extends EngineTestBase {

    @BeforeEach
    void init() { setUp(); }

    @Test
    void reject_should_jump_back_to_previous_user_task() {
        ProcessDefinition def = simple("reject-flow")
                .start("start")
                .userTask("apply", "提交", any("alice"))
                .userTask("review", "审核", any("bob"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "review")
                .connect("review", "end")
                .build();
        register(def);

        String instanceId = engine.start("reject-flow", Map.of());

        // 完成 apply,推进到 review
        TaskInstance apply = engine.getInstance(instanceId).getTasks().get(0);
        engine.completeTask(apply.getId(), "alice", true);

        // 找到 review 任务并驳回
        ProcessInstance inst = engine.getInstance(instanceId);
        TaskInstance review = inst.getTasks().stream()
                .filter(t -> t.getNodeId().equals("review"))
                .findFirst().orElseThrow();
        engine.rejectTask(review.getId(), "bob", "材料不全");

        // 驳回后: 回到 apply 节点,review 任务被 REJECTED,实例仍 RUNNING
        inst = engine.getInstance(instanceId);
        assertThat(inst.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        long pendingCount = inst.getTasks().stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING).count();
        assertThat(pendingCount).isEqualTo(1);
        assertThat(inst.getTasks().stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING)
                .findFirst().get().getNodeId()).isEqualTo("apply");
    }

    @Test
    void transfer_should_redirect_to_new_user() {
        ProcessDefinition def = simple("transfer-flow")
                .start("start")
                .userTask("approve", "审批", any("bob"))
                .end("end")
                .connect("start", "approve")
                .connect("approve", "end")
                .build();
        register(def);

        String instanceId = engine.start("transfer-flow", Map.of());
        TaskInstance bob = engine.getInstance(instanceId).getTasks().get(0);
        engine.transferTask(bob.getId(), "bob", "carol");

        // 验证: 原任务 TRANSFERRED, 新任务 PENDING 候选人 = carol
        ProcessInstance inst = engine.getInstance(instanceId);
        assertThat(inst.getTasks()).hasSize(2);
        TaskInstance oldTask = inst.getTasks().stream()
                .filter(t -> t.getStatus() == TaskStatus.TRANSFERRED).findFirst().orElseThrow();
        TaskInstance newTask = inst.getTasks().stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING).findFirst().orElseThrow();
        assertThat(newTask.getCandidate().getUserIds()).containsExactly("carol");

        // carol 通过后实例完成
        engine.completeTask(newTask.getId(), "carol", true);
        assertThat(engine.getInstance(instanceId).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void terminate_should_close_instance_and_pending_tasks() {
        ProcessDefinition def = simple("terminate-flow")
                .start("start")
                .userTask("approve", "审批", any("bob"))
                .end("end")
                .connect("start", "approve")
                .connect("approve", "end")
                .build();
        register(def);

        String instanceId = engine.start("terminate-flow", Map.of());
        engine.terminate(instanceId);

        ProcessInstance inst = engine.getInstance(instanceId);
        assertThat(inst.getStatus()).isEqualTo(InstanceStatus.TERMINATED);
        assertThat(inst.getTasks().stream().allMatch(t -> t.getStatus() == TaskStatus.TERMINATED))
                .isTrue();
    }

    @Test
    void suspend_and_resume_should_change_status() {
        ProcessDefinition def = simple("suspend-flow")
                .start("start")
                .userTask("approve", "审批", any("bob"))
                .end("end")
                .connect("start", "approve")
                .connect("approve", "end")
                .build();
        register(def);

        String instanceId = engine.start("suspend-flow", Map.of());
        engine.suspend(instanceId);
        assertThat(engine.getInstance(instanceId).getStatus()).isEqualTo(InstanceStatus.SUSPENDED);
        engine.resume(instanceId);
        assertThat(engine.getInstance(instanceId).getStatus()).isEqualTo(InstanceStatus.RUNNING);
    }

    @Test
    void non_candidate_user_should_be_rejected() {
        ProcessDefinition def = simple("auth-flow")
                .start("start")
                .userTask("approve", "审批", any("bob"))
                .end("end")
                .connect("start", "approve")
                .connect("approve", "end")
                .build();
        register(def);

        String instanceId = engine.start("auth-flow", Map.of());
        TaskInstance t = engine.getInstance(instanceId).getTasks().get(0);
        assertThatThrownBy(() -> engine.completeTask(t.getId(), "mallory", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是本任务候选人");
    }
}