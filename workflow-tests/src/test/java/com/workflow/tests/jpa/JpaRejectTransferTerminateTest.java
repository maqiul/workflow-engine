package com.workflow.tests.jpa;

import com.workflow.definition.ProcessDefinition;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import com.workflow.tests.JpaEngineTestBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JpaRejectTransferTerminateTest extends JpaEngineTestBase {

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

        TaskInstance apply = engine.getInstance(instanceId).getTasks().get(0);
        engine.completeTask(apply.getId(), "alice", true);

        ProcessInstance inst = engine.getInstance(instanceId);
        TaskInstance review = inst.getTasks().stream()
                .filter(t -> t.getNodeId().equals("review"))
                .findFirst().orElseThrow();
        engine.rejectTask(review.getId(), "bob", "材料不全");

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

        ProcessInstance inst = engine.getInstance(instanceId);
        assertThat(inst.getTasks()).hasSize(2);
        TaskInstance newTask = inst.getTasks().stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING).findFirst().orElseThrow();
        assertThat(newTask.getCandidate().getUserIds()).containsExactly("carol");

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