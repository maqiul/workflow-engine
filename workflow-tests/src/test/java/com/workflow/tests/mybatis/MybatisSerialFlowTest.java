package com.workflow.tests.mybatis;

import com.workflow.definition.ProcessDefinition;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
import com.workflow.runtime.ProcessInstance;
import com.workflow.tests.MybatisEngineTestBase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MyBatis-Plus 版串行流转测试
 */
class MybatisSerialFlowTest extends MybatisEngineTestBase {

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

        ProcessInstance inst = engine.getInstance(instanceId);
        assertThat(inst.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(inst.getTasks()).hasSize(1);
        assertThat(inst.getTasks().get(0).getNodeId()).isEqualTo("apply");
        assertThat(inst.getTasks().get(0).getStatus()).isEqualTo(TaskStatus.PENDING);

        String taskId = inst.getTasks().get(0).getId();
        engine.completeTask(taskId, "alice", true);

        inst = engine.getInstance(instanceId);
        assertThat(inst.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        List<com.workflow.runtime.TaskInstance> tasks = inst.getTasks();
        assertThat(tasks).hasSize(2);
        long pendingCount = tasks.stream().filter(t -> t.getStatus() == TaskStatus.PENDING).count();
        assertThat(pendingCount).isEqualTo(1);
        assertThat(tasks.stream().filter(t -> t.getStatus() == TaskStatus.PENDING)
                .findFirst().get().getNodeId()).isEqualTo("review");

        String reviewTaskId = tasks.stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING).findFirst().get().getId();
        engine.completeTask(reviewTaskId, "bob", true);

        inst = engine.getInstance(instanceId);
        assertThat(inst.getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }
}
