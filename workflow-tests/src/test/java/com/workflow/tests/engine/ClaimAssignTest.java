package com.workflow.tests.engine;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
import com.workflow.listener.TaskListener;
import com.workflow.runtime.TaskInstance;
import com.workflow.tests.EngineTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * claim / setAssignee —— assignee 一等公民的行为契约（G-06）。
 *
 * <p>核心语义：认领/指派后 taskId 不变、assignee 独占办理；未指派时完全走原候选池逻辑
 * （向后兼容）。区别于 transferTo 的"关闭原任务 + 新建单人任务"简化模拟。
 */
@DisplayName("任务认领与指派")
class ClaimAssignTest extends EngineTestBase {

    @BeforeEach
    void init() {
        super.setUp();
    }

    private ProcessDefinition claimFlow() {
        return ProcessBuilder.create("claim-flow")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("u1", "u2"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
    }

    private String firstTaskId(String instanceId) {
        return engine.getInstance(instanceId).getTasks().get(0).getId();
    }

    @Test
    @DisplayName("claim 设置 assignee、taskId 不变、仍 PENDING")
    void claim_sets_assignee_keeps_taskId() {
        register(claimFlow());
        String id = engine.start("claim-flow", Map.of());
        String taskId = firstTaskId(id);

        engine.claim(taskId, "u1");

        TaskInstance t = engine.getTask(taskId);
        assertThat(t.getId()).as("认领不换任务").isEqualTo(taskId);
        assertThat(t.getAssignee()).isEqualTo("u1");
        assertThat(t.getStatus()).as("认领后仍待办").isEqualTo(TaskStatus.PENDING);
    }

    @Test
    @DisplayName("认领后仅 assignee 可办，别人被拒")
    void after_claim_only_assignee_can_complete() {
        register(claimFlow());
        String id = engine.start("claim-flow", Map.of());
        String taskId = firstTaskId(id);
        engine.claim(taskId, "u1");

        assertThatThrownBy(() -> engine.completeTask(taskId, "u2", true))
                .as("u2 不是 assignee")
                .isInstanceOf(IllegalArgumentException.class);

        engine.completeTask(taskId, "u1", true);
        assertThat(engine.getInstance(id).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    @DisplayName("非候选人无法认领")
    void claim_by_non_candidate_rejected() {
        register(claimFlow());
        String id = engine.start("claim-flow", Map.of());
        String taskId = firstTaskId(id);

        assertThatThrownBy(() -> engine.claim(taskId, "nobody"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("setAssignee 可指派非候选人，之后该人能办")
    void setAssignee_allows_non_candidate_to_complete() {
        register(claimFlow());
        String id = engine.start("claim-flow", Map.of());
        String taskId = firstTaskId(id);

        engine.setAssignee(taskId, "outsider");
        engine.completeTask(taskId, "outsider", true);

        assertThat(engine.getInstance(id).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    @DisplayName("claim 触发 onAssigned 事件（接口方法，无需强转）")
    void claim_fires_onAssigned() {
        register(claimFlow());
        List<String> who = new ArrayList<>();
        engine.addTaskListener(new TaskListener() {
            @Override
            public void onAssigned(TaskInstance task, String assignee) {
                who.add(assignee);
            }
        });

        String id = engine.start("claim-flow", Map.of());
        engine.claim(firstTaskId(id), "u1");

        assertThat(who).containsExactly("u1");
    }

    @Test
    @DisplayName("委派回签：u1 委派 u2 代办，u2 回签后回 u1、不推进；u1 再办结")
    void delegate_then_resolve_returns_to_origin() {
        register(claimFlow());
        String id = engine.start("claim-flow", Map.of());
        String taskId = firstTaskId(id);

        engine.delegateTask(taskId, "u1", "u2");
        TaskInstance t = engine.getTask(taskId);
        assertThat(t.getStatus()).isEqualTo(TaskStatus.DELEGATED);
        assertThat(t.getAssignee()).isEqualTo("u2");
        assertThat(t.getDelegatedFrom()).isEqualTo("u1");

        // 被委派人不能直接办结（须回签）
        assertThatThrownBy(() -> engine.completeTask(taskId, "u2", true))
                .isInstanceOf(IllegalStateException.class);

        engine.resolveTask(taskId, "u2");
        TaskInstance back = engine.getTask(taskId);
        assertThat(back.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(back.getAssignee()).isEqualTo("u1");
        assertThat(back.getDelegatedFrom()).isNull();
        assertThat(engine.getInstance(id).getStatus()).as("回签不推进流程").isEqualTo(InstanceStatus.RUNNING);

        // 原办理人办结才推进
        engine.completeTask(taskId, "u1", true);
        assertThat(engine.getInstance(id).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    @DisplayName("非被委派人不能回签")
    void only_delegatee_can_resolve() {
        register(claimFlow());
        String id = engine.start("claim-flow", Map.of());
        String taskId = firstTaskId(id);
        engine.delegateTask(taskId, "u1", "u2");

        assertThatThrownBy(() -> engine.resolveTask(taskId, "u1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
