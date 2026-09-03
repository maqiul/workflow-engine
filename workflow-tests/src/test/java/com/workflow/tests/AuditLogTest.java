package com.workflow.tests;

import com.workflow.definition.ProcessDefinition;
import com.workflow.enums.AuditEventType;
import com.workflow.enums.TimeoutPolicy;
import com.workflow.runtime.AuditLog;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计日志测试（InMemory 版）
 */
class AuditLogTest extends EngineTestBase {

    @BeforeEach
    void init() { setUp(); }

    @Test
    void should_log_process_started() {
        // 定义流程
        ProcessDefinition def = simple("audit-start")
                .start("start")
                .userTask("apply", "申请", any("user1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        register(def);

        // 发起流程
        String instanceId = engine.start("audit-start", null);

        // 验证审计日志
        List<AuditLog> logs = auditLogRepo.findByInstanceId(instanceId);
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getEventType()).isEqualTo(AuditEventType.PROCESS_STARTED);
        assertThat(logs.get(0).getOperator()).isEqualTo("system");
        assertThat(logs.get(0).getDetail()).contains("audit-start");
    }

    @Test
    void should_log_task_completed() {
        // 定义流程
        ProcessDefinition def = simple("audit-complete")
                .start("start")
                .userTask("apply", "申请", any("user1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        register(def);

        // 发起并完成
        String instanceId = engine.start("audit-complete", null);
        List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
        engine.completeTask(tasks.get(0).getId(), "user1", true);

        // 验证审计日志
        List<AuditLog> logs = auditLogRepo.findByInstanceId(instanceId);
        assertThat(logs).hasSize(2);
        assertThat(logs.get(0).getEventType()).isEqualTo(AuditEventType.PROCESS_STARTED);
        assertThat(logs.get(1).getEventType()).isEqualTo(AuditEventType.TASK_COMPLETED);
        assertThat(logs.get(1).getOperator()).isEqualTo("user1");
        assertThat(logs.get(1).getTaskId()).isEqualTo(tasks.get(0).getId());
    }

    @Test
    void should_log_task_rejected() {
        // 定义流程
        ProcessDefinition def = simple("audit-reject")
                .start("start")
                .userTask("apply", "申请", any("user1"))
                .userTask("review", "审批", any("user2"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "review")
                .connect("review", "end")
                .build();
        register(def);

        // 发起、完成第一个、驳回第二个
        String instanceId = engine.start("audit-reject", null);
        List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
        engine.completeTask(tasks.get(0).getId(), "user1", true);
        
        tasks = taskRepo.findByInstanceId(instanceId);
        TaskInstance reviewTask = tasks.stream()
                .filter(t -> t.getNodeId().equals("review"))
                .findFirst().orElseThrow();
        engine.rejectTask(reviewTask.getId(), "user2", "不符合要求");

        // 验证审计日志
        List<AuditLog> logs = auditLogRepo.findByInstanceId(instanceId);
        assertThat(logs).hasSize(3);
        assertThat(logs.get(2).getEventType()).isEqualTo(AuditEventType.TASK_REJECTED);
        assertThat(logs.get(2).getOperator()).isEqualTo("user2");
        assertThat(logs.get(2).getDetail()).contains("不符合要求");
    }

    @Test
    void should_log_task_transferred() {
        // 定义流程
        ProcessDefinition def = simple("audit-transfer")
                .start("start")
                .userTask("apply", "申请", any("user1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        register(def);

        // 发起并转办
        String instanceId = engine.start("audit-transfer", null);
        List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
        engine.transferTask(tasks.get(0).getId(), "user1", "user2");

        // 验证审计日志
        List<AuditLog> logs = auditLogRepo.findByInstanceId(instanceId);
        assertThat(logs).hasSize(2);
        assertThat(logs.get(1).getEventType()).isEqualTo(AuditEventType.TASK_TRANSFERRED);
        assertThat(logs.get(1).getOperator()).isEqualTo("user1");
        assertThat(logs.get(1).getDetail()).contains("user2");
    }

    @Test
    void should_log_process_terminated() {
        // 定义流程
        ProcessDefinition def = simple("audit-terminate")
                .start("start")
                .userTask("apply", "申请", any("user1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        register(def);

        // 发起并终止
        String instanceId = engine.start("audit-terminate", null);
        engine.terminate(instanceId);

        // 验证审计日志
        List<AuditLog> logs = auditLogRepo.findByInstanceId(instanceId);
        assertThat(logs).hasSize(2);
        assertThat(logs.get(1).getEventType()).isEqualTo(AuditEventType.PROCESS_TERMINATED);
        assertThat(logs.get(1).getOperator()).isEqualTo("system");
    }

    @Test
    void should_log_process_suspended_and_resumed() {
        // 定义流程
        ProcessDefinition def = simple("audit-suspend")
                .start("start")
                .userTask("apply", "申请", any("user1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        register(def);

        // 发起、挂起、恢复
        String instanceId = engine.start("audit-suspend", null);
        engine.suspend(instanceId);
        engine.resume(instanceId);

        // 验证审计日志
        List<AuditLog> logs = auditLogRepo.findByInstanceId(instanceId);
        assertThat(logs).hasSize(3);
        assertThat(logs.get(1).getEventType()).isEqualTo(AuditEventType.PROCESS_SUSPENDED);
        assertThat(logs.get(2).getEventType()).isEqualTo(AuditEventType.PROCESS_RESUMED);
    }

    @Test
    void should_log_timeout_auto_approved() throws InterruptedException {
        // 定义流程（超时自动通过）
        ProcessDefinition def = simple("audit-timeout-approve")
                .start("start")
                .userTask("apply", "申请", any("user1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .timeout("apply", 100, TimeoutPolicy.AUTO_APPROVE)
                .build();
        register(def);

        // 发起并等待超时
        String instanceId = engine.start("audit-timeout-approve", null);
        Thread.sleep(200);

        // 验证审计日志
        List<AuditLog> logs = auditLogRepo.findByInstanceId(instanceId);
        assertThat(logs).hasSize(2);
        assertThat(logs.get(1).getEventType()).isEqualTo(AuditEventType.TIMEOUT_AUTO_APPROVED);
        assertThat(logs.get(1).getOperator()).isEqualTo("__system__");
    }

    @Test
    void should_query_logs_by_task_id() {
        // 定义流程
        ProcessDefinition def = simple("audit-query-task")
                .start("start")
                .userTask("apply", "申请", any("user1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        register(def);

        // 发起并完成
        String instanceId = engine.start("audit-query-task", null);
        List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
        String taskId = tasks.get(0).getId();
        engine.completeTask(taskId, "user1", true);

        // 按任务 ID 查询
        List<AuditLog> logs = auditLogRepo.findByTaskId(taskId);
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getEventType()).isEqualTo(AuditEventType.TASK_COMPLETED);
    }

    @Test
    void should_query_logs_by_time_range() throws InterruptedException {
        // 定义流程
        ProcessDefinition def = simple("audit-query-time")
                .start("start")
                .userTask("apply", "申请", any("user1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        register(def);

        // 发起
        Instant before = Instant.now().minusSeconds(1);
        String instanceId = engine.start("audit-query-time", null);
        Thread.sleep(100);
        Instant after = Instant.now().plusSeconds(1);

        // 按时间范围查询
        List<AuditLog> logs = auditLogRepo.findByTimeRange(before, after);
        assertThat(logs).isNotEmpty();
        assertThat(logs.get(0).getInstanceId()).isEqualTo(instanceId);
    }
}
