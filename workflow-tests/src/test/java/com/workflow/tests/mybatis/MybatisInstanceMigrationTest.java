package com.workflow.tests.mybatis;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import com.workflow.tests.MybatisEngineTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MyBatis 实例版本迁移测试
 */
@DisplayName("MyBatis 实例版本迁移")
class MybatisInstanceMigrationTest extends MybatisEngineTestBase {

    @Test
    @DisplayName("MyBatis: 基本迁移后继续推进")
    void basicMigration() {
        ProcessDefinition v1 = ProcessBuilder.create("leave-flow", "请假审批")
                .version(1)
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("user1"))
                .userTask("manager", "经理审批", Candidate.ofAny("manager1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "manager")
                .connect("manager", "end")
                .build();

        ProcessDefinition v2 = ProcessBuilder.create("leave-flow", "请假审批")
                .version(2)
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("user1"))
                .userTask("manager", "经理审批", Candidate.ofAny("manager1"))
                .userTask("director", "总监审批", Candidate.ofAny("director1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "manager")
                .connect("manager", "director")
                .connect("director", "end")
                .build();

        procRepo.save(v1);
        procRepo.save(v2);

        String instanceId = engine.start("leave-flow", 1, Map.of());

        // 完成 apply，停在 manager
        TaskInstance applyTask = taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> "apply".equals(t.getNodeId()))
                .findFirst().orElseThrow();
        engine.completeTask(applyTask.getId(), "user1", true);

        // 迁移到 v2
        engine.migrateInstance(instanceId, "leave-flow", 2, Map.of(), "admin");

        // 验证迁移后状态
        ProcessInstance instance = instRepo.findById(instanceId);
        assertThat(instance.getProcessKey()).isEqualTo("leave-flow");
        assertThat(instance.getProcessVersion()).isEqualTo(2);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.RUNNING);

        // 继续完成 manager，应该走到 director（v2 新增）
        TaskInstance managerTask = taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> "manager".equals(t.getNodeId()) && t.getStatus() == TaskStatus.PENDING)
                .findFirst().orElseThrow();
        engine.completeTask(managerTask.getId(), "manager1", true);

        TaskInstance directorTask = taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> "director".equals(t.getNodeId()) && t.getStatus() == TaskStatus.PENDING)
                .findFirst().orElseThrow();
        assertThat(directorTask).isNotNull();
    }
}
