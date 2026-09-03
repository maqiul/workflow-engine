package com.workflow.tests.engine;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.WorkflowEngine;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
import com.workflow.listener.ExecutionListener;
import com.workflow.listener.TaskListener;
import com.workflow.query.TaskQuery;
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

/**
 * Phase 1 测试 - 监听器 + 查询 API
 */
class Phase1ListenerQueryTest extends EngineTestBase {

    private TestExecutionListener execListener;
    private TestTaskListener taskListener;

    @BeforeEach
    void init() {
        super.setUp();
        execListener = new TestExecutionListener();
        taskListener = new TestTaskListener();
        ((WorkflowEngine) engine).addExecutionListener(execListener);
        ((WorkflowEngine) engine).addTaskListener(taskListener);
    }

    // ========== 执行监听器测试 ==========

    @Test
    void execution_listener_should_fire_on_started() {
        ProcessDefinition def = ProcessBuilder.create("listener-start", "监听器启动测试")
                .start("start")
                .userTask("apply", "申请", any("u1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        register(def);

        String instanceId = engine.start("listener-start", Map.of());

        assertThat(execListener.startedInstances).hasSize(1);
        assertThat(execListener.startedInstances.get(0).getId()).isEqualTo(instanceId);
    }

    @Test
    void execution_listener_should_fire_on_completed() {
        ProcessDefinition def = ProcessBuilder.create("listener-complete", "监听器完成测试")
                .start("start")
                .userTask("apply", "申请", any("u1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        register(def);

        String instanceId = engine.start("listener-complete", Map.of());
        TaskInstance task = engine.getInstance(instanceId).getTasks().stream()
                .filter(t -> t.getNodeId().equals("apply"))
                .findFirst().orElseThrow();
        engine.completeTask(task.getId(), "u1", true);

        assertThat(execListener.completedInstances).hasSize(1);
        assertThat(execListener.completedInstances.get(0).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void execution_listener_should_fire_on_terminated() {
        ProcessDefinition def = ProcessBuilder.create("listener-terminate", "监听器终止测试")
                .start("start")
                .userTask("apply", "申请", any("u1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        register(def);

        String instanceId = engine.start("listener-terminate", Map.of());
        engine.terminate(instanceId);

        assertThat(execListener.terminatedInstances).hasSize(1);
    }

    @Test
    void execution_listener_should_fire_on_suspend_resume() {
        ProcessDefinition def = ProcessBuilder.create("listener-suspend", "监听器挂起测试")
                .start("start")
                .userTask("apply", "申请", any("u1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        register(def);

        String instanceId = engine.start("listener-suspend", Map.of());
        engine.suspend(instanceId);
        engine.resume(instanceId);

        assertThat(execListener.suspendedInstances).hasSize(1);
        assertThat(execListener.resumedInstances).hasSize(1);
    }

    // ========== 任务监听器测试 ==========

    @Test
    void task_listener_should_fire_on_created() {
        ProcessDefinition def = ProcessBuilder.create("task-created", "任务创建监听")
                .start("start")
                .userTask("apply", "申请", any("u1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        register(def);

        engine.start("task-created", Map.of());

        assertThat(taskListener.createdTasks).hasSize(1);
        assertThat(taskListener.createdTasks.get(0).getNodeId()).isEqualTo("apply");
    }

    @Test
    void task_listener_should_fire_on_completed() {
        ProcessDefinition def = ProcessBuilder.create("task-complete", "任务完成监听")
                .start("start")
                .userTask("apply", "申请", any("u1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        register(def);

        String instanceId = engine.start("task-complete", Map.of());
        TaskInstance task = engine.getInstance(instanceId).getTasks().get(0);
        engine.completeTask(task.getId(), "u1", true);

        assertThat(taskListener.completedTasks).hasSize(1);
        assertThat(taskListener.completedUsers).containsExactly("u1");
    }

    @Test
    void task_listener_should_fire_on_rejected() {
        ProcessDefinition def = ProcessBuilder.create("task-reject", "任务驳回监听")
                .start("start")
                .userTask("apply", "申请", any("u1"))
                .userTask("approve", "审批", any("u2"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "approve")
                .connect("approve", "end")
                .build();
        register(def);

        String instanceId = engine.start("task-reject", Map.of());
        TaskInstance applyTask = engine.getInstance(instanceId).getTasks().stream()
                .filter(t -> t.getNodeId().equals("apply"))
                .findFirst().orElseThrow();
        engine.completeTask(applyTask.getId(), "u1", true);

        TaskInstance approveTask = engine.getInstance(instanceId).getTasks().stream()
                .filter(t -> t.getNodeId().equals("approve"))
                .findFirst().orElseThrow();
        engine.rejectTask(approveTask.getId(), "u2", "资料不全");

        assertThat(taskListener.rejectedTasks).hasSize(1);
        assertThat(taskListener.rejectedReasons).containsExactly("资料不全");
    }

    @Test
    void task_listener_should_fire_on_transferred() {
        ProcessDefinition def = ProcessBuilder.create("task-transfer", "任务转办监听")
                .start("start")
                .userTask("apply", "申请", any("u1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        register(def);

        String instanceId = engine.start("task-transfer", Map.of());
        TaskInstance task = engine.getInstance(instanceId).getTasks().get(0);
        engine.transferTask(task.getId(), "u1", "u2");

        assertThat(taskListener.transferredTasks).hasSize(1);
        assertThat(taskListener.transferredFromUsers).containsExactly("u1");
        assertThat(taskListener.transferredToUsers).containsExactly("u2");
    }

    // ========== 查询 API 测试 ==========

    @Test
    void task_query_should_filter_by_status() {
        ProcessDefinition def = ProcessBuilder.create("query-status", "查询状态过滤")
                .start("start")
                .userTask("apply", "申请", any("u1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        register(def);

        String instanceId = engine.start("query-status", Map.of());
        
        // 查询待办任务
        List<TaskInstance> pendingTasks = TaskQuery.create()
                .status(TaskStatus.PENDING)
                .processInstanceId(instanceId)
                .list(engine);
        
        assertThat(pendingTasks).hasSize(1);
        assertThat(pendingTasks.get(0).getStatus()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void task_query_should_filter_by_candidate() {
        ProcessDefinition def = ProcessBuilder.create("query-candidate", "查询候选人过滤")
                .start("start")
                .userTask("apply", "申请", any("u1"))
                .userTask("approve", "审批", any("u2"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "approve")
                .connect("approve", "end")
                .build();
        register(def);

        String instanceId = engine.start("query-candidate", Map.of());
        
        // 查询 u1 的待办
        List<TaskInstance> u1Tasks = TaskQuery.create()
                .candidate("u1")
                .status(TaskStatus.PENDING)
                .processInstanceId(instanceId)
                .list(engine);
        
        assertThat(u1Tasks).hasSize(1);
        assertThat(u1Tasks.get(0).getCandidate().getUserIds()).contains("u1");
    }

    // ========== 辅助类 ==========

    static class TestExecutionListener implements ExecutionListener {
        final List<ProcessInstance> startedInstances = new ArrayList<>();
        final List<ProcessInstance> completedInstances = new ArrayList<>();
        final List<ProcessInstance> terminatedInstances = new ArrayList<>();
        final List<ProcessInstance> suspendedInstances = new ArrayList<>();
        final List<ProcessInstance> resumedInstances = new ArrayList<>();

        @Override
        public void onStarted(ProcessInstance instance) { startedInstances.add(instance); }
        @Override
        public void onCompleted(ProcessInstance instance) { completedInstances.add(instance); }
        @Override
        public void onTerminated(ProcessInstance instance) { terminatedInstances.add(instance); }
        @Override
        public void onSuspended(ProcessInstance instance) { suspendedInstances.add(instance); }
        @Override
        public void onResumed(ProcessInstance instance) { resumedInstances.add(instance); }
    }

    static class TestTaskListener implements TaskListener {
        final List<TaskInstance> createdTasks = new ArrayList<>();
        final List<TaskInstance> completedTasks = new ArrayList<>();
        final List<String> completedUsers = new ArrayList<>();
        final List<TaskInstance> rejectedTasks = new ArrayList<>();
        final List<String> rejectedReasons = new ArrayList<>();
        final List<TaskInstance> transferredTasks = new ArrayList<>();
        final List<String> transferredFromUsers = new ArrayList<>();
        final List<String> transferredToUsers = new ArrayList<>();

        @Override
        public void onCreated(TaskInstance task) { createdTasks.add(task); }
        @Override
        public void onCompleted(TaskInstance task, String userId) { 
            completedTasks.add(task); 
            completedUsers.add(userId);
        }
        @Override
        public void onRejected(TaskInstance task, String userId, String reason) { 
            rejectedTasks.add(task); 
            rejectedReasons.add(reason);
        }
        @Override
        public void onTransferred(TaskInstance task, String fromUser, String toUser) { 
            transferredTasks.add(task);
            transferredFromUsers.add(fromUser);
            transferredToUsers.add(toUser);
        }
    }
}
