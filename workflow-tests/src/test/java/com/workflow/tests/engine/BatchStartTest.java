package com.workflow.tests.engine;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.WorkflowEngine;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批量启动测试
 */
@DisplayName("批量启动")
class BatchStartTest {

    private InMemoryProcessRepository procRepo;
    private InMemoryInstanceRepository instRepo;
    private InMemoryTaskRepository taskRepo;
    private WorkflowEngine engine;

    @BeforeEach
    void setUp() {
        procRepo = new InMemoryProcessRepository();
        instRepo = new InMemoryInstanceRepository();
        taskRepo = new InMemoryTaskRepository();
        engine = new WorkflowEngine(procRepo, instRepo, taskRepo);
    }

    @Test
    @DisplayName("批量启动 10 个实例")
    void batchStart10() {
        // 注册流程定义
        ProcessDefinition def = ProcessBuilder.create("batch-start-flow")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("u1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        procRepo.save(def);

        // 准备 10 个实例的变量
        List<Map<String, Object>> variablesList = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            variablesList.add(Map.of("index", i, "name", "实例" + i));
        }

        // 批量启动
        List<String> instanceIds = engine.batchStart("batch-start-flow", variablesList);

        // 验证
        assertThat(instanceIds).hasSize(10);

        // 验证每个实例都已创建且状态正确
        for (int i = 0; i < 10; i++) {
            String instanceId = instanceIds.get(i);
            ProcessInstance instance = instRepo.findById(instanceId);
            assertThat(instance).isNotNull();
            assertThat(instance.getStatus()).isEqualTo(InstanceStatus.RUNNING);
            assertThat(instance.getVariable("index")).isEqualTo(i);
            assertThat(instance.getVariable("name")).isEqualTo("实例" + i);

            // 验证任务已创建
            List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
            assertThat(tasks).hasSize(1);
            TaskInstance task = tasks.get(0);
            assertThat(task.getNodeId()).isEqualTo("apply");
            assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        }
    }

    @Test
    @DisplayName("批量启动 100 个实例")
    void batchStart100() {
        ProcessDefinition def = ProcessBuilder.create("batch-start-flow-100")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("u1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        procRepo.save(def);

        List<Map<String, Object>> variablesList = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            variablesList.add(Map.of("index", i));
        }

        List<String> instanceIds = engine.batchStart("batch-start-flow-100", variablesList);

        assertThat(instanceIds).hasSize(100);

        // 验证所有实例都已创建
        List<ProcessInstance> allInstances = instRepo.findAll();
        assertThat(allInstances).hasSize(100);
    }

    @Test
    @DisplayName("批量启动 - 空列表")
    void batchStartEmpty() {
        ProcessDefinition def = ProcessBuilder.create("batch-start-empty")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("u1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        procRepo.save(def);

        List<String> instanceIds = engine.batchStart("batch-start-empty", new ArrayList<>());
        assertThat(instanceIds).isEmpty();
    }

    @Test
    @DisplayName("批量启动 - null 列表")
    void batchStartNull() {
        ProcessDefinition def = ProcessBuilder.create("batch-start-null")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("u1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        procRepo.save(def);

        List<String> instanceIds = engine.batchStart("batch-start-null", null);
        assertThat(instanceIds).isEmpty();
    }

    @Test
    @DisplayName("批量启动 - 流程定义不存在")
    void batchStartNotExists() {
        assertThatThrownBy(() -> engine.batchStart("not-exists", List.of(Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("流程定义不存在");
    }

    @Test
    @DisplayName("批量启动 - 指定版本")
    void batchStartWithVersion() {
        ProcessDefinition def1 = ProcessBuilder.create("batch-start-versioned")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("u1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        procRepo.save(def1);

        List<Map<String, Object>> variablesList = List.of(Map.of("v", 1), Map.of("v", 2));
        List<String> instanceIds = engine.batchStart("batch-start-versioned", 1, variablesList);

        assertThat(instanceIds).hasSize(2);
        for (String instanceId : instanceIds) {
            ProcessInstance instance = instRepo.findById(instanceId);
            assertThat(instance.getProcessVersion()).isEqualTo(1);
        }
    }
}
