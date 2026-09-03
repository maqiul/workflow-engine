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

class JpaParallelGatewayTest extends JpaEngineTestBase {

    @Test
    void should_fork_and_join_parallel_branches() {
        ProcessDefinition def = simple("parallel")
                .start("start")
                .userTask("apply", "提交", any("alice"))
                .parallelGateway("fork")
                .userTask("branchA", "分支 A", any("worker1"))
                .userTask("branchB", "分支 B", any("worker2"))
                .parallelGateway("join")
                .end("end")
                .connect("start", "apply")
                .connect("apply", "fork")
                .connect("fork", "branchA")
                .connect("fork", "branchB")
                .connect("branchA", "join")
                .connect("branchB", "join")
                .connect("join", "end")
                .build();
        register(def);

        String instanceId = engine.start("parallel", Map.of());

        TaskInstance apply = findPending(engine.getInstance(instanceId));
        engine.completeTask(apply.getId(), "alice", true);

        ProcessInstance inst = engine.getInstance(instanceId);
        long pendingCount = inst.getTasks().stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING).count();
        assertThat(pendingCount).isEqualTo(2);
        assertThat(inst.getActiveTokens()).hasSize(2);

        TaskInstance branchA = findPendingOnNode(inst, "branchA");
        engine.completeTask(branchA.getId(), "worker1", true);
        inst = engine.getInstance(instanceId);
        assertThat(inst.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        long stillPending = inst.getTasks().stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING).count();
        assertThat(stillPending).isEqualTo(1);

        TaskInstance branchB = findPendingOnNode(inst, "branchB");
        engine.completeTask(branchB.getId(), "worker2", true);
        inst = engine.getInstance(instanceId);
        assertThat(inst.getStatus()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(inst.getActiveTokens()).isEmpty();
    }

    private TaskInstance findPending(ProcessInstance inst) {
        return inst.getTasks().stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING)
                .findFirst().orElseThrow();
    }

    private TaskInstance findPendingOnNode(ProcessInstance inst, String nodeId) {
        return inst.getTasks().stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING && t.getNodeId().equals(nodeId))
                .findFirst().orElseThrow();
    }
}