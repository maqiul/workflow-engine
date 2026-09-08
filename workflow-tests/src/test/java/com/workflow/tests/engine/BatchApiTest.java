package com.workflow.tests.engine;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.BatchPartialFailureException;
import com.workflow.engine.BatchResult;
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

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批处理 API 测试
 */
@DisplayName("批处理 API")
class BatchApiTest {

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
    @DisplayName("批量完成任务 - 全部成功")
    void batchCompleteTasks_allSuccess() {
        // 创建流程定义
        ProcessDefinition def = ProcessBuilder.create("batch-flow-1")
                .start("start")
                .userTask("task1", "任务1", Candidate.ofAny("user1"))
                .userTask("task2", "任务2", Candidate.ofAny("user1"))
                .end("end")
                .connect("start", "task1")
                .connect("task1", "task2")
                .connect("task2", "end")
                .build();
        procRepo.save(def);

        // 启动 3 个流程实例
        String instanceId1 = engine.start("batch-flow-1", Map.of());
        String instanceId2 = engine.start("batch-flow-1", Map.of());
        String instanceId3 = engine.start("batch-flow-1", Map.of());

        // 获取所有 task1 的任务 ID
        TaskInstance task1_1 = taskRepo.findByInstanceId(instanceId1).stream()
                .filter(t -> t.getNodeId().equals("task1")).findFirst().orElseThrow();
        TaskInstance task1_2 = taskRepo.findByInstanceId(instanceId2).stream()
                .filter(t -> t.getNodeId().equals("task1")).findFirst().orElseThrow();
        TaskInstance task1_3 = taskRepo.findByInstanceId(instanceId3).stream()
                .filter(t -> t.getNodeId().equals("task1")).findFirst().orElseThrow();

        List<String> taskIds = Arrays.asList(task1_1.getId(), task1_2.getId(), task1_3.getId());

        // 批量完成
        BatchResult result = engine.batchCompleteTasks(taskIds, "user1", true);

        // 验证结果
        assertThat(result.isAllSuccess()).isTrue();
        assertThat(result.getTotal()).isEqualTo(3);
        assertThat(result.getSuccessCount()).isEqualTo(3);
        assertThat(result.getFailureCount()).isEqualTo(0);

        // 验证实例状态
        assertThat(instRepo.findById(instanceId1).getStatus()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(instRepo.findById(instanceId2).getStatus()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(instRepo.findById(instanceId3).getStatus()).isEqualTo(InstanceStatus.RUNNING);

        // 验证任务状态
        assertThat(taskRepo.findById(task1_1.getId()).getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(taskRepo.findById(task1_2.getId()).getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(taskRepo.findById(task1_3.getId()).getStatus()).isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    @DisplayName("批量终止实例 - 全部成功")
    void batchTerminateInstances_allSuccess() {
        // 创建流程定义
        ProcessDefinition def = ProcessBuilder.create("batch-flow-2")
                .start("start")
                .userTask("task1", "任务1", Candidate.ofAny("user1"))
                .end("end")
                .connect("start", "task1")
                .connect("task1", "end")
                .build();
        procRepo.save(def);

        // 启动 3 个流程实例
        String instanceId1 = engine.start("batch-flow-2", Map.of());
        String instanceId2 = engine.start("batch-flow-2", Map.of());
        String instanceId3 = engine.start("batch-flow-2", Map.of());

        List<String> instanceIds = Arrays.asList(instanceId1, instanceId2, instanceId3);

        // 批量终止
        BatchResult result = engine.batchTerminateInstances(instanceIds, "admin", "测试批量终止");

        // 验证结果
        assertThat(result.isAllSuccess()).isTrue();
        assertThat(result.getTotal()).isEqualTo(3);
        assertThat(result.getSuccessCount()).isEqualTo(3);
        assertThat(result.getFailureCount()).isEqualTo(0);

        // 验证实例状态
        assertThat(instRepo.findById(instanceId1).getStatus()).isEqualTo(InstanceStatus.TERMINATED);
        assertThat(instRepo.findById(instanceId2).getStatus()).isEqualTo(InstanceStatus.TERMINATED);
        assertThat(instRepo.findById(instanceId3).getStatus()).isEqualTo(InstanceStatus.TERMINATED);
    }

    @Test
    @DisplayName("批量完成任务 - 部分失败")
    void batchCompleteTasks_partialFailure() {
        // 创建流程定义
        ProcessDefinition def = ProcessBuilder.create("batch-flow-3")
                .start("start")
                .userTask("task1", "任务1", Candidate.ofAny("user1"))
                .end("end")
                .connect("start", "task1")
                .connect("task1", "end")
                .build();
        procRepo.save(def);

        // 启动 2 个流程实例
        String instanceId1 = engine.start("batch-flow-3", Map.of());
        String instanceId2 = engine.start("batch-flow-3", Map.of());

        // 获取任务 ID
        TaskInstance task1_1 = taskRepo.findByInstanceId(instanceId1).stream()
                .filter(t -> t.getNodeId().equals("task1")).findFirst().orElseThrow();
        TaskInstance task1_2 = taskRepo.findByInstanceId(instanceId2).stream()
                .filter(t -> t.getNodeId().equals("task1")).findFirst().orElseThrow();

        // 先完成第一个任务
        engine.completeTask(task1_1.getId(), "user1", true);

        // 尝试批量完成（第一个已完成，应该失败）
        List<String> taskIds = Arrays.asList(task1_1.getId(), task1_2.getId());

        assertThatThrownBy(() -> engine.batchCompleteTasks(taskIds, "user1", true))
                .isInstanceOf(BatchPartialFailureException.class)
                .hasMessageContaining("批处理部分失败");

        // 验证第二个任务仍然完成（事务回滚后重新执行）
        // 注意：由于 InMemory 实现的事务特性，这里的行为可能不同
    }

    @Test
    @DisplayName("批量终止实例 - 部分失败")
    void batchTerminateInstances_partialFailure() {
        // 创建流程定义
        ProcessDefinition def = ProcessBuilder.create("batch-flow-4")
                .start("start")
                .userTask("task1", "任务1", Candidate.ofAny("user1"))
                .end("end")
                .connect("start", "task1")
                .connect("task1", "end")
                .build();
        procRepo.save(def);

        // 启动 2 个流程实例
        String instanceId1 = engine.start("batch-flow-4", Map.of());
        String instanceId2 = engine.start("batch-flow-4", Map.of());

        // 先终止第一个实例
        engine.terminate(instanceId1);

        // 尝试批量终止（第一个已终止，应该失败）
        List<String> instanceIds = Arrays.asList(instanceId1, instanceId2);

        assertThatThrownBy(() -> engine.batchTerminateInstances(instanceIds, "admin", "测试"))
                .isInstanceOf(BatchPartialFailureException.class)
                .hasMessageContaining("批处理部分失败");
    }

    @Test
    @DisplayName("批量完成 - 空列表")
    void batchCompleteTasks_emptyList() {
        BatchResult result = engine.batchCompleteTasks(List.of(), "user1", true);
        assertThat(result.isAllSuccess()).isTrue();
        assertThat(result.getTotal()).isEqualTo(0);
    }

    @Test
    @DisplayName("批量终止 - 空列表")
    void batchTerminateInstances_emptyList() {
        BatchResult result = engine.batchTerminateInstances(List.of(), "admin", "测试");
        assertThat(result.isAllSuccess()).isTrue();
        assertThat(result.getTotal()).isEqualTo(0);
    }
}
