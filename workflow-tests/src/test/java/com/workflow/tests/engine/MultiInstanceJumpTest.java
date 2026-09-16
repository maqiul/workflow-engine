package com.workflow.tests.engine;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.enums.CandidateStrategy;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
import com.workflow.engine.WorkflowEngine;
import com.workflow.engine.WorkflowEngineBuilder;
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
 * 多实例（会签）节点的改道回归（G-08）。
 *
 * <p>v2 清单点名"多实例 token 语义"是并行/会签区改道的历史事故高发区。引擎的 MI 是
 * <b>单 token 展开每人一 task</b>（区别于 Flowable 的多 execution），故改道是"整个会签节点
 * 一起进 / 一起出"。本用例钉死这两个方向的语义：
 * <ul>
 *   <li>从会签节点回退 → MI 全部 PENDING 待办必须被终止，不留"办不动的脏待办"；</li>
 *   <li>跳入会签节点 → 按当前 approvers 集合重新展开每人一任务。</li>
 * </ul>
 * 注：Flowable 的"只移部分 MI execution"是模型差异（引擎 MI 单 token），需 facade 用
 * removeSign + jump 组合，不在引擎 jumpToNode 语义内 —— 本用例覆盖的是"整体进出"。
 */
@DisplayName("多实例节点改道回归")
class MultiInstanceJumpTest {

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

    // start -> apply -> sign(MI) -> end
    private void register() {
        procRepo.save(ProcessBuilder.create("mi-jump", "会签改道")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("u0"))
                .multiInstance("sign", "会签", "approvers", CandidateStrategy.ALL)
                .end("end")
                .connect("start", "apply")
                .connect("apply", "sign")
                .connect("sign", "end")
                .build());
    }

    private List<TaskInstance> tasks(String id) {
        return taskRepo.findByInstanceId(id);
    }

    @Test
    @DisplayName("从会签节点回退：MI 全部待办终止，目标重建")
    void jumpOutOfMultiInstance_terminatesAllSignTasks() {
        register();
        String id = engine.start("mi-jump", Map.of("approvers", List.of("u1", "u2")));
        String apply = tasks(id).stream()
                .filter(t -> t.getNodeId().equals("apply")).findFirst().orElseThrow().getId();
        engine.completeTask(apply, "u0", true);   // 进入会签，展开 u1/u2

        assertThat(tasks(id).stream()
                .filter(t -> t.getNodeId().equals("sign") && t.getStatus() == TaskStatus.PENDING).count())
                .as("会签应展开 2 个待办").isEqualTo(2);

        engine.jumpToNode(id, "apply", "admin", "会签前退回重填");

        assertThat(tasks(id).stream()
                .filter(t -> t.getNodeId().equals("sign"))
                .allMatch(t -> t.getStatus() == TaskStatus.TERMINATED))
                .as("MI 全部待办必须被终止，不留脏待办").isTrue();
        assertThat(tasks(id).stream()
                .anyMatch(t -> t.getNodeId().equals("apply") && t.getStatus() == TaskStatus.PENDING))
                .as("apply 重建为待办").isTrue();
        assertThat(engine.getInstance(id).getStatus()).isEqualTo(InstanceStatus.RUNNING);
    }

    @Test
    @DisplayName("跳入会签节点：按当前集合重新展开每人一任务")
    void jumpIntoMultiInstance_reExpands() {
        register();
        String id = engine.start("mi-jump", Map.of("approvers", List.of("u1", "u2", "u3")));
        // 当前停在 apply，直接跳到会签节点
        engine.jumpToNode(id, "sign", "admin", "直接进入会签");

        long pending = tasks(id).stream()
                .filter(t -> t.getNodeId().equals("sign") && t.getStatus() == TaskStatus.PENDING).count();
        assertThat(pending).as("跳入 MI 节点应按 approvers 重新展开 3 个待办").isEqualTo(3);
    }
}
