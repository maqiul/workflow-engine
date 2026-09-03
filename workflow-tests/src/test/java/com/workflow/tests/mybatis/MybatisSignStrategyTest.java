package com.workflow.tests.mybatis;

import com.workflow.definition.ProcessDefinition;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import com.workflow.tests.MybatisEngineTestBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MybatisSignStrategyTest extends MybatisEngineTestBase {

    @Test
    void any_sign_should_pass_when_one_approves() {
        ProcessDefinition def = simple("any-sign")
                .start("start")
                .userTask("approve", "会签审批", any("u1", "u2", "u3"))
                .end("end")
                .connect("start", "approve")
                .connect("approve", "end")
                .build();
        register(def);

        String instanceId = engine.start("any-sign", Map.of());
        ProcessInstance inst = engine.getInstance(instanceId);
        TaskInstance task = inst.getTasks().get(0);
        assertThat(task.getCandidate().getStrategy())
                .isEqualTo(com.workflow.enums.CandidateStrategy.ANY);

        engine.completeTask(task.getId(), "u1", true);

        inst = engine.getInstance(instanceId);
        assertThat(inst.getStatus()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(inst.getTasks().get(0).getStatus()).isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    void all_sign_should_wait_for_everyone() {
        ProcessDefinition def = simple("all-sign")
                .start("start")
                .userTask("approve", "会签审批", all("u1", "u2"))
                .end("end")
                .connect("start", "approve")
                .connect("approve", "end")
                .build();
        register(def);

        String instanceId = engine.start("all-sign", Map.of());
        ProcessInstance inst = engine.getInstance(instanceId);
        TaskInstance task = inst.getTasks().get(0);
        assertThat(task.getCandidate().getStrategy())
                .isEqualTo(com.workflow.enums.CandidateStrategy.ALL);

        engine.completeTask(task.getId(), "u1", true);
        inst = engine.getInstance(instanceId);
        assertThat(inst.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(inst.getTasks().get(0).getStatus()).isEqualTo(TaskStatus.PENDING);

        engine.completeTask(task.getId(), "u2", true);
        inst = engine.getInstance(instanceId);
        assertThat(inst.getStatus()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(inst.getTasks().get(0).getStatus()).isEqualTo(TaskStatus.COMPLETED);
    }
}
