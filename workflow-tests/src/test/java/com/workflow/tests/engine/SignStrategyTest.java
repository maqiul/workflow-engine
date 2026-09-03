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

/**
 * 用例 3: 会签策略 - ANY 与 ALL
 *
 * ANY (或签): 任一候选人完成即任务通过
 * ALL (会签): 全部候选人完成才通过
 */
class SignStrategyTest extends EngineTestBase {

    @BeforeEach
    void init() { setUp(); }

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

        // u1 通过 - 任一通过即过
        engine.completeTask(task.getId(), "u1", true);

        inst = engine.getInstance(instanceId);
        assertThat(inst.getStatus()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
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

        // u1 完成 - 还不够,实例继续 RUNNING
        engine.completeTask(task.getId(), "u1", true);
        inst = engine.getInstance(instanceId);
        assertThat(inst.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);

        // u2 完成 - 全员通过,实例完成
        engine.completeTask(task.getId(), "u2", true);
        inst = engine.getInstance(instanceId);
        assertThat(inst.getStatus()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
    }
}