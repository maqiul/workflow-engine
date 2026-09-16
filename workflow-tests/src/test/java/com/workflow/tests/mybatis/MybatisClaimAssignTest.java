package com.workflow.tests.mybatis;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.runtime.TaskInstance;
import com.workflow.tests.MybatisEngineTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MyBatis 版 assignee 持久化往返（G-06）。
 *
 * <p>覆盖 new + update 两条 save 分支，防 assignee 列在 update 路径静默丢失。
 */
@DisplayName("MyBatis 任务认领持久化往返")
class MybatisClaimAssignTest extends MybatisEngineTestBase {

    private ProcessDefinition flow() {
        return ProcessBuilder.create("mb-claim")
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
    @DisplayName("claim 后 assignee 落库，重新读回仍在（insert 分支）")
    void claim_persists_assignee() {
        register(flow());
        String id = engine.start("mb-claim", Map.of());
        String taskId = firstTaskId(id);

        engine.claim(taskId, "u1");

        TaskInstance reloaded = engine.getTask(taskId);
        assertThat(reloaded.getAssignee()).as("assignee 列往返").isEqualTo("u1");
    }

    @Test
    @DisplayName("改派后 assignee 更新落库（update 分支）")
    void reassign_updates_persisted_assignee() {
        register(flow());
        String id = engine.start("mb-claim", Map.of());
        String taskId = firstTaskId(id);

        engine.claim(taskId, "u1");
        engine.claim(taskId, "u2");

        assertThat(engine.getTask(taskId).getAssignee()).as("update 分支也要写 assignee").isEqualTo("u2");
    }
}
