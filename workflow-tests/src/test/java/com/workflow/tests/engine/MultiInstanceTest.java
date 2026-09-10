package com.workflow.tests.engine;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.WorkflowEngine;
import com.workflow.engine.WorkflowEngineBuilder;
import com.workflow.enums.CandidateStrategy;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
import com.workflow.repository.InMemoryInstanceRepository;
import com.workflow.repository.InMemoryProcessRepository;
import com.workflow.repository.InMemoryTaskRepository;
import com.workflow.runtime.TaskInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多实例会签/或签测试 —— 每人一个独立任务，ALL/ANY 完成判定。
 */
@DisplayName("多实例会签")
class MultiInstanceTest {

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

    private void register(CandidateStrategy strategy) {
        ProcessDefinition def = ProcessBuilder.create("mi-flow", "会签流程")
                .start("start")
                .multiInstance("sign", "会签", "approvers", strategy)
                .end("end")
                .connect("start", "sign")
                .connect("sign", "end")
                .build();
        procRepo.save(def);
    }

    private List<TaskInstance> signTasks(String instanceId) {
        return taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> t.getNodeId().equals("sign")).toList();
    }

    /** 按审批人取该多实例节点上属于 ta 的任务 id（不依赖列表顺序）。 */
    private String taskIdFor(String instanceId, String user) {
        return signTasks(instanceId).stream()
                .filter(t -> t.getCandidate().getUserIds().contains(user))
                .map(TaskInstance::getId).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("进入多实例节点：按集合展开为每人一个独立任务")
    void expandsToPerPersonTasks() {
        register(CandidateStrategy.ALL);
        String id = engine.start("mi-flow", Map.of("approvers", List.of("u1", "u2", "u3")));

        List<TaskInstance> tasks = signTasks(id);
        assertThat(tasks).hasSize(3);
        assertThat(tasks).allMatch(t -> t.getStatus() == TaskStatus.PENDING);
        // 每个任务单候选人
        assertThat(tasks).allMatch(t -> t.getCandidate().getUserIds().size() == 1);
        assertThat(tasks).extracting(t -> t.getCandidate().getUserIds().iterator().next())
                .containsExactlyInAnyOrder("u1", "u2", "u3");
        assertThat(engine.getInstance(id).getStatus()).isEqualTo(InstanceStatus.RUNNING);
    }

    @Test
    @DisplayName("ALL 会签：全部完成才推进")
    void allStrategy_waitsForEveryone() {
        register(CandidateStrategy.ALL);
        String id = engine.start("mi-flow", Map.of("approvers", List.of("u1", "u2")));
        String t1 = taskIdFor(id, "u1");
        String t2 = taskIdFor(id, "u2");

        engine.completeTask(t1, "u1", true);
        // 只完成一个：实例仍在会签节点等待
        assertThat(taskRepo.findById(t1).getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(engine.getInstance(id).getStatus()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(signTasks(id).stream().filter(t -> t.getStatus() == TaskStatus.PENDING)).hasSize(1);

        engine.completeTask(t2, "u2", true);
        assertThat(engine.getInstance(id).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    @DisplayName("ALL 会签：任一人驳回则整体驳回")
    void allStrategy_rejectStopsFlow() {
        register(CandidateStrategy.ALL);
        String id = engine.start("mi-flow", Map.of("approvers", List.of("u1", "u2")));
        engine.rejectTask(taskIdFor(id, "u1"), "u1", "不同意");
        // 驳回后实例不应完成（会签未全部通过）
        assertThat(engine.getInstance(id).getStatus()).isNotEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    @DisplayName("ANY 或签：任一完成即通过并取消其余")
    void anyStrategy_firstCompletionWins() {
        register(CandidateStrategy.ANY);
        String id = engine.start("mi-flow", Map.of("approvers", List.of("u1", "u2", "u3")));
        assertThat(signTasks(id)).hasSize(3);

        engine.completeTask(taskIdFor(id, "u1"), "u1", true);

        assertThat(engine.getInstance(id).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
        // 其余未办任务被取消（TERMINATED），不残留 PENDING
        assertThat(signTasks(id).stream().filter(t -> t.getStatus() == TaskStatus.PENDING)).isEmpty();
        assertThat(signTasks(id).stream().filter(t -> t.getStatus() == TaskStatus.TERMINATED)).hasSize(2);
    }

    @Test
    @DisplayName("空集合：多实例节点直接通过")
    void emptyCollection_passesThrough() {
        register(CandidateStrategy.ALL);
        String id = engine.start("mi-flow", Map.of("approvers", List.of()));
        assertThat(engine.getInstance(id).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(signTasks(id)).isEmpty();
    }
}
