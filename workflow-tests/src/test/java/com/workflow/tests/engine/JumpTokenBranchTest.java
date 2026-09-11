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
import com.workflow.runtime.TaskInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并行区逐支跳转测试 —— jumpTokenToNode 只回退指定分支，另一支不受影响。
 */
@DisplayName("逐支跳转")
class JumpTokenBranchTest {

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
        procRepo.save(def());
    }

    private ProcessDefinition def() {
        return ProcessBuilder.create("branch-flow")
                .start("start")
                .parallelGateway("fork")
                .userTask("task1", "A支", Candidate.ofAny("u1"))
                .userTask("task2", "B支", Candidate.ofAny("u2"))
                .parallelGateway("join")
                .end("end")
                .connect("start", "fork")
                .connect("fork", "task1").connect("fork", "task2")
                .connect("task1", "join").connect("task2", "join")
                .connect("join", "end")
                .build();
    }

    private TaskInstance pendingAt(String instanceId, String nodeId) {
        return taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> nodeId.equals(t.getNodeId()) && t.getStatus() == TaskStatus.PENDING)
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("并行两支各一个待办")
    void forkCreatesTwoBranches() {
        String id = engine.start("branch-flow", Map.of());
        assertThat(pendingAt(id, "task1")).isNotNull();
        assertThat(pendingAt(id, "task2")).isNotNull();
    }

    @Test
    @DisplayName("逐支跳转：只回退 A 支，B 支待办不受影响")
    void jumpOnlyAffectsTargetBranch() {
        String id = engine.start("branch-flow", Map.of());
        TaskInstance a = pendingAt(id, "task1");
        TaskInstance b = pendingAt(id, "task2");
        String tokenA = a.getTokenId();

        engine.jumpTokenToNode(id, tokenA, "task1", "admin", "A支重办");

        // A 支旧待办被终止、并重建一个新的 A 支待办（同 token）
        assertThat(taskRepo.findById(a.getId()).getStatus()).isEqualTo(TaskStatus.TERMINATED);
        TaskInstance newA = pendingAt(id, "task1");
        assertThat(newA.getId()).isNotEqualTo(a.getId());

        // 关键：B 支待办完全没被动过（仍是原来那条 PENDING）
        assertThat(taskRepo.findById(b.getId()).getStatus()).isEqualTo(TaskStatus.PENDING);

        assertThat(engine.getInstance(id).getStatus()).isEqualTo(InstanceStatus.RUNNING);
    }

    @Test
    @DisplayName("逐支跳转后两支都完成 → 汇聚正常结束")
    void jumpThenCompleteBothBranches() {
        String id = engine.start("branch-flow", Map.of());
        TaskInstance a = pendingAt(id, "task1");
        String tokenA = a.getTokenId();

        engine.jumpTokenToNode(id, tokenA, "task1", "admin", "重办");
        // 新 A 支 + 原 B 支
        engine.completeTask(pendingAt(id, "task1").getId(), "u1", true);
        engine.completeTask(pendingAt(id, "task2").getId(), "u2", true);

        assertThat(engine.getInstance(id).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    @DisplayName("跳转到不存在节点 / 非法 token 抛异常")
    void invalidArgs() {
        String id = engine.start("branch-flow", Map.of());
        String tokenA = pendingAt(id, "task1").getTokenId();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> engine.jumpTokenToNode(id, tokenA, "nope", "admin", null))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> engine.jumpTokenToNode(id, "bad-token", "task1", "admin", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
