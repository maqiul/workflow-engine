package com.workflow.tests.engine;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.ProcessDefinition;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
import com.workflow.runtime.ProcessInstance;
import com.workflow.tests.EngineTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用例 1: 纯串行流程 - 验证基本节点流转
 *
 * 流程: start → apply → review → end
 * 验证:
 *  - 发起后停在 apply 节点,PENDING 任务只有 1 个
 *  - 完成后自动推进到 review
 *  - 最后到 END,实例变 COMPLETED
 */
class SerialFlowTest extends EngineTestBase {

    @BeforeEach
    void init() { setUp(); }

    @Test
    void should_advance_through_serial_nodes() {
        ProcessDefinition def = simple("serial")
                .start("start")
                .userTask("apply", "提交", any("alice"))
                .userTask("review", "审核", any("bob"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "review")
                .connect("review", "end")
                .build();
        register(def);

        String instanceId = engine.start("serial", Map.of());

        // 发起后: 实例 RUNNING, 1 个 PENDING 任务(apply)
        ProcessInstance inst = engine.getInstance(instanceId);
        assertThat(inst.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(inst.getTasks()).hasSize(1);
        assertThat(inst.getTasks().get(0).getNodeId()).isEqualTo("apply");
        assertThat(inst.getTasks().get(0).getStatus()).isEqualTo(TaskStatus.PENDING);

        // 完成 apply
        String taskId = inst.getTasks().get(0).getId();
        engine.completeTask(taskId, "alice", true);

        // 推进到 review
        inst = engine.getInstance(instanceId);
        assertThat(inst.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        List<com.workflow.runtime.TaskInstance> tasks = inst.getTasks();
        assertThat(tasks).hasSize(2);
        long pendingCount = tasks.stream().filter(t -> t.getStatus() == TaskStatus.PENDING).count();
        assertThat(pendingCount).isEqualTo(1);
        assertThat(tasks.stream().filter(t -> t.getStatus() == TaskStatus.PENDING)
                .findFirst().get().getNodeId()).isEqualTo("review");

        // 完成 review
        String reviewTaskId = tasks.stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING).findFirst().get().getId();
        engine.completeTask(reviewTaskId, "bob", true);

        // 实例完成
        inst = engine.getInstance(instanceId);
        assertThat(inst.getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }
}