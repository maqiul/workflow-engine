package com.workflow.tests.engine;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.NotificationService;
import com.workflow.engine.WorkflowEngine;
import com.workflow.enums.CandidateStrategy;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
import com.workflow.repository.InMemoryDelegationRepository;
import com.workflow.repository.InMemoryInstanceRepository;
import com.workflow.repository.InMemoryProcessRepository;
import com.workflow.repository.InMemoryTaskRepository;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import com.workflow.tests.EngineTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 企业级功能测试 - 撤回 / 委托 / 催办 / 动态多实例
 */
class EnterpriseFeaturesTest extends EngineTestBase {

    private InMemoryDelegationRepository delegationRepo;
    private TestNotificationService notificationService;

    @BeforeEach
    void init() {
        super.setUp();
        delegationRepo = new InMemoryDelegationRepository();
        notificationService = new TestNotificationService();
        engine = new WorkflowEngine(procRepo, instRepo, taskRepo, null, null, delegationRepo, notificationService);
    }

    // ========== 1. 流程撤回 ==========

    @Test
    void withdraw_should_terminate_instance_when_no_task_completed() {
        // 定义流程
        ProcessDefinition def = ProcessBuilder.create("withdraw-flow", "撤回测试")
                .start("start")
                .userTask("apply", "申请", any("u1"))
                .userTask("approve", "审批", any("u2"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "approve")
                .connect("approve", "end")
                .build();
        register(def);

        // 发起流程（记录发起人）
        String instanceId = engine.start("withdraw-flow", "u1", Map.of());
        ProcessInstance instance = engine.getInstance(instanceId);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.RUNNING);

        // 撤回
        engine.withdraw(instanceId, "u1");

        // 验证
        instance = engine.getInstance(instanceId);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.TERMINATED);
        
        // 验证任务状态为 WITHDRAWN
        TaskInstance task = instance.getTasks().stream()
                .filter(t -> t.getNodeId().equals("apply"))
                .findFirst().orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.WITHDRAWN);
    }

    @Test
    void withdraw_should_fail_when_task_already_completed() {
        ProcessDefinition def = ProcessBuilder.create("withdraw-fail", "撤回失败测试")
                .start("start")
                .userTask("apply", "申请", any("u1"))
                .userTask("approve", "审批", any("u2"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "approve")
                .connect("approve", "end")
                .build();
        register(def);

        String instanceId = engine.start("withdraw-fail", "u1", Map.of());
        
        // 完成第一个任务
        TaskInstance applyTask = engine.getInstance(instanceId).getTasks().stream()
                .filter(t -> t.getNodeId().equals("apply"))
                .findFirst().orElseThrow();
        engine.completeTask(applyTask.getId(), "u1", true);

        // 撤回应该失败
        assertThatThrownBy(() -> engine.withdraw(instanceId, "u1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已有审批人通过");
    }

    @Test
    void withdraw_should_fail_when_not_initiator() {
        ProcessDefinition def = ProcessBuilder.create("withdraw-not-initiator", "非发起人撤回")
                .start("start")
                .userTask("apply", "申请", any("u1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        register(def);

        String instanceId = engine.start("withdraw-not-initiator", "u1", Map.of());

        // 非发起人撤回应该失败
        assertThatThrownBy(() -> engine.withdraw(instanceId, "u2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是流程发起人");
    }

    // ========== 2. 流程委托 ==========

    @Test
    void delegate_should_allow_agent_to_complete_task() {
        ProcessDefinition def = ProcessBuilder.create("delegate-flow", "委托测试")
                .start("start")
                .userTask("approve", "审批", any("manager"))
                .end("end")
                .connect("start", "approve")
                .connect("approve", "end")
                .build();
        register(def);

        // 设置委托：manager 委托给 assistant
        engine.delegate("manager", "assistant");

        String instanceId = engine.start("delegate-flow", Map.of());
        TaskInstance task = engine.getInstance(instanceId).getTasks().stream()
                .filter(t -> t.getNodeId().equals("approve"))
                .findFirst().orElseThrow();

        // 代理人完成任务
        engine.completeTask(task.getId(), "assistant", true);

        // 验证流程完成
        ProcessInstance instance = engine.getInstance(instanceId);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.COMPLETED);
        
        // 验证完成记录是委托人（manager）
        TaskInstance completedTask = instance.getTasks().stream()
                .filter(t -> t.getNodeId().equals("approve"))
                .findFirst().orElseThrow();
        assertThat(completedTask.getCompletedApprovers()).contains("manager");
    }

    @Test
    void revoke_delegate_should_remove_delegation() {
        ProcessDefinition def = ProcessBuilder.create("revoke-delegate", "撤销委托")
                .start("start")
                .userTask("approve", "审批", any("manager"))
                .end("end")
                .connect("start", "approve")
                .connect("approve", "end")
                .build();
        register(def);

        // 设置委托
        engine.delegate("manager", "assistant");
        
        // 撤销委托
        engine.revokeDelegate("manager", "assistant");

        String instanceId = engine.start("revoke-delegate", Map.of());
        TaskInstance task = engine.getInstance(instanceId).getTasks().stream()
                .filter(t -> t.getNodeId().equals("approve"))
                .findFirst().orElseThrow();

        // 代理人完成任务应该失败
        assertThatThrownBy(() -> engine.completeTask(task.getId(), "assistant", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是本任务候选人");
    }

    // ========== 3. 流程催办 ==========

    @Test
    void urge_should_send_notification_to_candidates() {
        ProcessDefinition def = ProcessBuilder.create("urge-flow", "催办测试")
                .start("start")
                .userTask("approve", "审批", any("manager"))
                .end("end")
                .connect("start", "approve")
                .connect("approve", "end")
                .build();
        register(def);

        String instanceId = engine.start("urge-flow", Map.of());
        TaskInstance task = engine.getInstance(instanceId).getTasks().stream()
                .filter(t -> t.getNodeId().equals("approve"))
                .findFirst().orElseThrow();

        // 催办
        engine.urge(task.getId(), "admin", "请尽快审批");

        // 验证通知已发送
        assertThat(notificationService.notifications).hasSize(1);
        assertThat(notificationService.notifications.get(0).userId).isEqualTo("manager");
        assertThat(notificationService.notifications.get(0).message).contains("催办");
    }

    // ========== 4. 动态多实例 ==========

    @Test
    void dynamic_parallel_should_create_tasks_from_variable() {
        ProcessDefinition def = ProcessBuilder.create("dynamic-flow", "动态多实例")
                .start("start")
                .dynamicParallel("review", "多人审批", "reviewers", CandidateStrategy.ALL)
                .end("end")
                .connect("start", "review")
                .connect("review", "end")
                .build();
        register(def);

        // 传入候选人列表
        List<String> reviewers = List.of("r1", "r2", "r3");
        String instanceId = engine.start("dynamic-flow", Map.of("reviewers", reviewers));

        // 验证任务创建
        ProcessInstance instance = engine.getInstance(instanceId);
        TaskInstance task = instance.getTasks().stream()
                .filter(t -> t.getNodeId().equals("review"))
                .findFirst().orElseThrow();
        
        assertThat(task.getCandidate().getUserIds()).containsExactlyInAnyOrder("r1", "r2", "r3");
        assertThat(task.getCandidate().getStrategy()).isEqualTo(CandidateStrategy.ALL);
    }

    @Test
    void dynamic_parallel_any_should_complete_when_one_approves() {
        ProcessDefinition def = ProcessBuilder.create("dynamic-any", "动态多实例-或签")
                .start("start")
                .dynamicParallel("review", "多人审批", "reviewers", CandidateStrategy.ANY)
                .end("end")
                .connect("start", "review")
                .connect("review", "end")
                .build();
        register(def);

        List<String> reviewers = List.of("r1", "r2", "r3");
        String instanceId = engine.start("dynamic-any", Map.of("reviewers", reviewers));

        TaskInstance task = engine.getInstance(instanceId).getTasks().stream()
                .filter(t -> t.getNodeId().equals("review"))
                .findFirst().orElseThrow();

        // 一个人审批即可
        engine.completeTask(task.getId(), "r2", true);

        // 验证流程完成
        ProcessInstance instance = engine.getInstance(instanceId);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void dynamic_parallel_all_should_wait_for_everyone() {
        ProcessDefinition def = ProcessBuilder.create("dynamic-all", "动态多实例-会签")
                .start("start")
                .dynamicParallel("review", "多人审批", "reviewers", CandidateStrategy.ALL)
                .end("end")
                .connect("start", "review")
                .connect("review", "end")
                .build();
        register(def);

        List<String> reviewers = List.of("r1", "r2", "r3");
        String instanceId = engine.start("dynamic-all", Map.of("reviewers", reviewers));

        TaskInstance task = engine.getInstance(instanceId).getTasks().stream()
                .filter(t -> t.getNodeId().equals("review"))
                .findFirst().orElseThrow();

        // 第一个人审批
        engine.completeTask(task.getId(), "r1", true);
        assertThat(engine.getInstance(instanceId).getStatus()).isEqualTo(InstanceStatus.RUNNING);

        // 第二个人审批
        engine.completeTask(task.getId(), "r2", true);
        assertThat(engine.getInstance(instanceId).getStatus()).isEqualTo(InstanceStatus.RUNNING);

        // 第三个人审批
        engine.completeTask(task.getId(), "r3", true);
        assertThat(engine.getInstance(instanceId).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    // ========== 辅助类 ==========

    /**
     * 测试用通知服务 - 记录所有通知
     */
    static class TestNotificationService implements NotificationService {
        final List<Notification> notifications = new ArrayList<>();

        @Override
        public void notify(TaskInstance task, String userId, String message) {
            notifications.add(new Notification(task, userId, message));
        }

        record Notification(TaskInstance task, String userId, String message) {}
    }
}
