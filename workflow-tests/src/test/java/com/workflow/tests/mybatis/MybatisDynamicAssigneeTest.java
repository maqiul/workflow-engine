package com.workflow.tests.mybatis;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MyBatis 动态 assignee 测试
 */
@DisplayName("MyBatis 动态 assignee")
class MybatisDynamicAssigneeTest extends MybatisEngineTestBase {

    @Test
    @DisplayName("MyBatis: 动态 assignee 启动时设变量 → 任务候选人 = 变量值")
    void dynamicAssigneeFromVariable() {
        ProcessDefinition def = simple("mybatis-dynamic-assignee")
                .start("start")
                .userTask("apply", "申请", "applyApprover")
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        register(def);

        String instanceId = engine.start("mybatis-dynamic-assignee", Map.of("applyApprover", "user1"));

        List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
        assertThat(tasks).hasSize(1);
        TaskInstance task = tasks.get(0);
        assertThat(task.getNodeId()).isEqualTo("apply");
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.getCandidate().getUserIds()).containsExactly("user1");

        engine.completeTask(task.getId(), "user1", true);
        ProcessInstance instance = instRepo.findById(instanceId);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    @DisplayName("MyBatis: 变量未设 → 抛异常")
    void variableNotSet() {
        ProcessDefinition def = simple("mybatis-dynamic-assignee-noset")
                .start("start")
                .userTask("apply", "申请", "applyApprover")
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        register(def);

        assertThatThrownBy(() -> engine.start("mybatis-dynamic-assignee-noset", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("动态 assignee 变量 'applyApprover' 未设置");
    }

    @Test
    @DisplayName("MyBatis: 驳回后变量变了 → 新任务候选人 = 新变量值")
    void rejectWithNewVariable() {
        ProcessDefinition def = simple("mybatis-reject-flow")
                .start("start")
                .userTask("apply", "申请", "applyApprover")
                .userTask("review", "审核", "reviewApprover")
                .end("end")
                .connect("start", "apply")
                .connect("apply", "review")
                .connect("review", "apply")  // 驳回回退
                .connect("review", "end")
                .build();
        register(def);

        // 启动：apply=user1, review=user2
        String instanceId = engine.start("mybatis-reject-flow", Map.of("applyApprover", "user1", "reviewApprover", "user2"));

        // 完成 apply
        TaskInstance applyTask = taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> "apply".equals(t.getNodeId())).findFirst().orElseThrow();
        engine.completeTask(applyTask.getId(), "user1", true);

        // 完成 review
        TaskInstance reviewTask = taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> "review".equals(t.getNodeId())).findFirst().orElseThrow();
        assertThat(reviewTask.getCandidate().getUserIds()).containsExactly("user2");

        // 先改变量，再驳回
        ProcessInstance instance = instRepo.findById(instanceId);
        instance.setVariable("applyApprover", "user3");
        instRepo.save(instance);

        // 驳回回退到 apply
        engine.rejectTask(reviewTask.getId(), "user2", "需要修改");

        // 新 apply 任务
        TaskInstance newApplyTask = taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> "apply".equals(t.getNodeId()) && t.getStatus() == TaskStatus.PENDING)
                .findFirst().orElseThrow();
        assertThat(newApplyTask.getCandidate().getUserIds()).containsExactly("user3");
    }
}
