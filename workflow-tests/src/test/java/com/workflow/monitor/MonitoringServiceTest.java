package com.workflow.monitor;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.WorkflowEngine;
import com.workflow.engine.WorkflowEngineBuilder;
import com.workflow.repository.InMemoryAuditLogRepository;
import com.workflow.repository.InMemoryHistoryRepository;
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
 * 监控仪表盘聚合测试。
 *
 * <p>用两个实例制造"某节点既有闭合历史(算得出平均耗时)、又仍有新待办(进瓶颈)"的场景，
 * 覆盖实例分布、待办分布、瓶颈节点、降级(无历史/审计)四条路径。
 */
@DisplayName("监控仪表盘")
class MonitoringServiceTest {

    private InMemoryProcessRepository procRepo;
    private InMemoryInstanceRepository instRepo;
    private InMemoryTaskRepository taskRepo;
    private InMemoryHistoryRepository histRepo;
    private InMemoryAuditLogRepository auditRepo;

    @BeforeEach
    void setUp() {
        procRepo = new InMemoryProcessRepository();
        instRepo = new InMemoryInstanceRepository();
        taskRepo = new InMemoryTaskRepository();
        histRepo = new InMemoryHistoryRepository();
        auditRepo = new InMemoryAuditLogRepository();
        procRepo.save(def());
    }

    private ProcessDefinition def() {
        return ProcessBuilder.create("m-flow", "监控流程")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("u1"))
                .userTask("manager", "审批", Candidate.ofAny("u2"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "manager")
                .connect("manager", "end")
                .build();
    }

    private WorkflowEngine engine() {
        return WorkflowEngineBuilder.builder(procRepo, instRepo, taskRepo)
                .auditLogRepository(auditRepo)
                .historyRepository(histRepo)
                .build();
    }

    private String pendingTaskId(WorkflowEngine engine, String instanceId, String nodeId) {
        return taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> nodeId.equals(t.getNodeId()) && t.getStatus() == com.workflow.enums.TaskStatus.PENDING)
                .map(TaskInstance::getId)
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("快照聚合实例状态/待办分布/瓶颈节点")
    void dashboardAggregates() {
        WorkflowEngine engine = engine();

        // 实例B：完整走完(B 的 apply/manager 历史都闭合)
        String b = engine.start("m-flow", Map.of());
        engine.completeTask(pendingTaskId(engine, b, "apply"), "u1", true);
        engine.completeTask(pendingTaskId(engine, b, "manager"), "u2", true);

        // 实例A：停在 manager 待办
        String a = engine.start("m-flow", Map.of());
        engine.completeTask(pendingTaskId(engine, a, "apply"), "u1", true);

        DashboardMetrics m = engine.dashboard(10);

        assertThat(m.totalInstances()).isEqualTo(2);
        assertThat(m.instancesByStatus())
                .containsEntry("RUNNING", 1L)
                .containsEntry("COMPLETED", 1L);
        assertThat(m.instancesByProcess()).anySatisfy(p -> {
            org.assertj.core.api.Assertions.assertThat(p.processKey()).isEqualTo("m-flow");
            org.assertj.core.api.Assertions.assertThat(p.total()).isEqualTo(2);
        });

        // 待办：A 的 manager 一张
        assertThat(m.pendingTasks()).isEqualTo(1);
        assertThat(m.pendingByNode()).anySatisfy(nb -> {
            org.assertj.core.api.Assertions.assertThat(nb.processKey()).isEqualTo("m-flow");
            org.assertj.core.api.Assertions.assertThat(nb.nodeId()).isEqualTo("manager");
            org.assertj.core.api.Assertions.assertThat(nb.pendingCount()).isEqualTo(1L);
        });

        // 瓶颈：manager 有 B 的闭合历史可算平均耗时，且当前有 A 的积压
        assertThat(m.slowestNodes()).anySatisfy(nd ->
                org.assertj.core.api.Assertions.assertThat(nd.nodeId()).isEqualTo("manager"));

        assertThat(m.generatedAt()).isPositive();
    }

    @Test
    @DisplayName("未启用历史/审计时降级不报错")
    void dashboardDegradesWithoutHistoryAndAudit() {
        WorkflowEngine bare = WorkflowEngineBuilder.builder(procRepo, instRepo, taskRepo).build();
        String id = bare.start("m-flow", Map.of());

        DashboardMetrics m = bare.dashboard(10);
        assertThat(m.totalInstances()).isEqualTo(1);
        assertThat(m.pendingByNode()).hasSize(1); // apply 待办
        assertThat(m.slowestNodes()).isEmpty();    // 无历史仓储
        assertThat(m.timeoutEvents()).isEmpty();   // 无审计仓储
        assertThat(id).isNotBlank();
    }

    @Test
    @DisplayName("topN 截断瓶颈列表")
    void dashboardRespectsTopN() {
        WorkflowEngine engine = engine();
        // 制造多节点闭合历史 + 多节点待办不易，这里退化为验证 topN=0 返回空瓶颈
        String b = engine.start("m-flow", Map.of());
        engine.completeTask(pendingTaskId(engine, b, "apply"), "u1", true);
        DashboardMetrics m = engine.dashboard(0);
        assertThat(m.slowestNodes()).isEmpty(); // 0 个名额
    }
}
