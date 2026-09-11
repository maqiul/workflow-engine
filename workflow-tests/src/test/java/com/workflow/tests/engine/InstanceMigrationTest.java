package com.workflow.tests.engine;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.WorkflowEngine;
import com.workflow.engine.WorkflowEngineBuilder;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
import com.workflow.repository.InMemoryInstanceRepository;
import com.workflow.repository.InMemoryProcessRepository;
import com.workflow.repository.InMemoryTaskRepository;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 运行中实例版本迁移测试
 */
@DisplayName("实例版本迁移")
class InstanceMigrationTest {

    private InMemoryProcessRepository procRepo;
    private InMemoryInstanceRepository instRepo;
    private InMemoryTaskRepository taskRepo;
    private WorkflowEngine engine;

    @BeforeEach
    void setUp() {
        procRepo = new InMemoryProcessRepository();
        instRepo = new InMemoryInstanceRepository();
        taskRepo = new InMemoryTaskRepository();
        engine = WorkflowEngineBuilder.builder(procRepo, instRepo, taskRepo).build();
    }

    private ProcessDefinition v1() {
        return ProcessBuilder.create("leave-flow", "请假审批")
                .version(1)
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("user1"))
                .userTask("manager", "经理审批", Candidate.ofAny("manager1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "manager")
                .connect("manager", "end")
                .build();
    }

    private ProcessDefinition v2() {
        return ProcessBuilder.create("leave-flow", "请假审批")
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
    }

    @Test
    @DisplayName("基本迁移：节点改名")
    void basicMigration() {
        procRepo.save(v1());
        procRepo.save(v2());

        String instanceId = engine.start("leave-flow", 1, Map.of());

        // 完成 apply，停在 manager
        TaskInstance applyTask = taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> "apply".equals(t.getNodeId()))
                .findFirst().orElseThrow();
        engine.completeTask(applyTask.getId(), "user1", true);

        // 迁移到 v2，manager → manager（不变）
        engine.migrateInstance(instanceId, "leave-flow", 2, Map.of(), "admin");

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

    @Test
    @DisplayName("迁移到最新版（version=-1）")
    void migrateToLatest() {
        procRepo.save(v1());
        procRepo.save(v2());

        String instanceId = engine.start("leave-flow", 1, Map.of());

        // 迁移到最新版（v2）
        engine.migrateInstance(instanceId, "leave-flow", -1, Map.of(), "admin");

        ProcessInstance instance = instRepo.findById(instanceId);
        assertThat(instance.getProcessVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("节点类型不兼容抛异常")
    void incompatibleNodeType() {
        ProcessDefinition v1 = ProcessBuilder.create("flow", "流程")
                .version(1)
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("user1"))
                .userTask("step1", "步骤 1", Candidate.ofAny("user2"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "step1")
                .connect("step1", "end")
                .build();

        ProcessDefinition v2 = ProcessBuilder.create("flow", "流程")
                .version(2)
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("user1"))
                .exclusiveGateway("step1")  // USER_TASK → EXCLUSIVE_GATEWAY 类型不兼容
                .end("end")
                .connect("start", "apply")
                .connect("apply", "step1")
                .connect("step1", "end")
                .build();

        procRepo.save(v1);
        procRepo.save(v2);

        String instanceId = engine.start("flow", 1, Map.of());

        // 完成 apply，Token 停在 step1（USER_TASK）
        TaskInstance applyTask = taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> "apply".equals(t.getNodeId()))
                .findFirst().orElseThrow();
        engine.completeTask(applyTask.getId(), "user1", true);

        // 迁移：step1(USER_TASK) → step1(EXCLUSIVE_GATEWAY) 类型不兼容
        assertThatThrownBy(() -> engine.migrateInstance(instanceId, "flow", 2, Map.of(), "admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("节点类型不兼容");
    }

    @Test
    @DisplayName("目标版本不存在抛异常")
    void targetVersionNotFound() {
        procRepo.save(v1());

        String instanceId = engine.start("leave-flow", 1, Map.of());

        assertThatThrownBy(() -> engine.migrateInstance(instanceId, "leave-flow", 99, Map.of(), "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("非 RUNNING 实例不可迁移")
    void nonRunningInstanceCannotMigrate() {
        procRepo.save(v1());

        String instanceId = engine.start("leave-flow", 1, Map.of());

        // 终止实例
        engine.terminate(instanceId);

        assertThatThrownBy(() -> engine.migrateInstance(instanceId, "leave-flow", 1, Map.of(), "admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("仅 RUNNING 实例可迁移");
    }

    @Test
    @DisplayName("节点映射：旧节点 ID → 新节点 ID")
    void nodeMapping() {
        ProcessDefinition v1 = ProcessBuilder.create("flow", "流程")
                .version(1)
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("user1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();

        ProcessDefinition v2 = ProcessBuilder.create("flow", "流程")
                .version(2)
                .start("start")
                .userTask("application", "申请表单", Candidate.ofAny("user1"))
                .end("end")
                .connect("start", "application")
                .connect("application", "end")
                .build();

        procRepo.save(v1);
        procRepo.save(v2);

        String instanceId = engine.start("flow", 1, Map.of());

        // 迁移：apply → application
        engine.migrateInstance(instanceId, "flow", 2, Map.of("apply", "application"), "admin");

        ProcessInstance instance = instRepo.findById(instanceId);
        assertThat(instance.getProcessVersion()).isEqualTo(2);

        // 验证 Token 已移动到 application 节点
        assertThat(instance.getActiveTokens().values().stream()
                .anyMatch(t -> "application".equals(t.getCurrentNodeId())))
                .isTrue();
    }
}
