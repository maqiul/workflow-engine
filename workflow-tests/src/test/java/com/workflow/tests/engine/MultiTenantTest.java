package com.workflow.tests.engine;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.TenantContext;
import com.workflow.engine.WorkflowEngine;
import com.workflow.enums.InstanceStatus;
import com.workflow.repository.InMemoryInstanceRepository;
import com.workflow.repository.InMemoryProcessRepository;
import com.workflow.repository.InMemoryTaskRepository;
import com.workflow.runtime.ProcessInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多租户隔离测试
 */
@DisplayName("多租户隔离")
class MultiTenantTest {

    private InMemoryProcessRepository procRepo;
    private InMemoryInstanceRepository instRepo;
    private InMemoryTaskRepository taskRepo;
    private WorkflowEngine engine;

    @BeforeEach
    void setUp() {
        procRepo = new InMemoryProcessRepository();
        instRepo = new InMemoryInstanceRepository();
        taskRepo = new InMemoryTaskRepository();
        engine = new WorkflowEngine(procRepo, instRepo, taskRepo);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("流程定义带租户 ID")
    void processDefinitionWithTenant() {
        ProcessDefinition def = ProcessBuilder.create("tenant-flow")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("u1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        // 手动设置租户（ProcessBuilder 暂不支持，通过反射或直接构造）
        // 这里简化测试：通过 TenantContext 设置
        TenantContext.setTenantId("tenant-1");
        procRepo.save(def);

        String instanceId = engine.start("tenant-flow", Map.of());
        ProcessInstance instance = instRepo.findById(instanceId);

        assertThat(instance.getTenantId()).isEqualTo("tenant-1");
    }

    @Test
    @DisplayName("不同租户的流程实例隔离")
    void tenantIsolation() {
        ProcessDefinition def = ProcessBuilder.create("isolation-flow")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("u1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        procRepo.save(def);

        // 租户 A 启动 2 个实例
        TenantContext.setTenantId("tenant-A");
        String a1 = engine.start("isolation-flow", Map.of("data", "A1"));
        String a2 = engine.start("isolation-flow", Map.of("data", "A2"));

        // 租户 B 启动 3 个实例
        TenantContext.setTenantId("tenant-B");
        String b1 = engine.start("isolation-flow", Map.of("data", "B1"));
        String b2 = engine.start("isolation-flow", Map.of("data", "B2"));
        String b3 = engine.start("isolation-flow", Map.of("data", "B3"));

        // 验证租户隔离
        assertThat(instRepo.findById(a1).getTenantId()).isEqualTo("tenant-A");
        assertThat(instRepo.findById(a2).getTenantId()).isEqualTo("tenant-A");
        assertThat(instRepo.findById(b1).getTenantId()).isEqualTo("tenant-B");
        assertThat(instRepo.findById(b2).getTenantId()).isEqualTo("tenant-B");
        assertThat(instRepo.findById(b3).getTenantId()).isEqualTo("tenant-B");
    }

    @Test
    @DisplayName("无租户上下文时，实例无租户 ID")
    void noTenantContext() {
        ProcessDefinition def = ProcessBuilder.create("no-tenant-flow")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("u1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        procRepo.save(def);

        // 不设置 TenantContext
        String instanceId = engine.start("no-tenant-flow", Map.of());
        ProcessInstance instance = instRepo.findById(instanceId);

        assertThat(instance.getTenantId()).isNull();
    }

    @Test
    @DisplayName("withTenant 临时切换租户")
    void withTenant() {
        ProcessDefinition def = ProcessBuilder.create("switch-tenant-flow")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("u1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        procRepo.save(def);

        // 当前租户 A
        TenantContext.setTenantId("tenant-A");
        String a1 = engine.start("switch-tenant-flow", Map.of());

        // 临时切换到租户 B
        String b1 = TenantContext.withTenant("tenant-B", () -> {
            return engine.start("switch-tenant-flow", Map.of());
        });

        // 临时切换后，上下文应恢复为租户 A
        assertThat(TenantContext.getTenantId()).isEqualTo("tenant-A");
        String a2 = engine.start("switch-tenant-flow", Map.of());

        // 验证实例租户
        assertThat(instRepo.findById(a1).getTenantId()).isEqualTo("tenant-A");
        assertThat(instRepo.findById(b1).getTenantId()).isEqualTo("tenant-B");
        assertThat(instRepo.findById(a2).getTenantId()).isEqualTo("tenant-A");
    }

    @Test
    @DisplayName("批量启动 - 所有实例继承租户 ID")
    void batchStartWithTenant() {
        ProcessDefinition def = ProcessBuilder.create("batch-tenant-flow")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("u1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        procRepo.save(def);

        TenantContext.setTenantId("batch-tenant");
        List<String> instanceIds = engine.batchStart("batch-tenant-flow", 
                List.of(Map.of("i", 1), Map.of("i", 2), Map.of("i", 3)));

        assertThat(instanceIds).hasSize(3);
        for (String instanceId : instanceIds) {
            assertThat(instRepo.findById(instanceId).getTenantId()).isEqualTo("batch-tenant");
        }
    }

    @Test
    @DisplayName("监控快照按租户隔离")
    void dashboardTenantIsolation() {
        ProcessDefinition def = ProcessBuilder.create("dash-tenant-flow")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("u1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        procRepo.save(def);

        // 租户 X: 3 个实例
        TenantContext.setTenantId("tenant-X");
        for (int i = 0; i < 3; i++) engine.start("dash-tenant-flow", Map.of("i", i));
        // 租户 Y: 2 个实例
        TenantContext.setTenantId("tenant-Y");
        for (int i = 0; i < 2; i++) engine.start("dash-tenant-flow", Map.of("i", i));

        // 按租户 X 过滤
        com.workflow.monitor.DashboardMetrics mx = engine.dashboard(10, "tenant-X");
        assertThat(mx.totalInstances()).isEqualTo(3);
        assertThat(mx.pendingTasks()).isEqualTo(3);
        assertThat(mx.instancesByStatus()).containsEntry("RUNNING", 3L);

        // 按租户 Y 过滤
        com.workflow.monitor.DashboardMetrics my = engine.dashboard(10, "tenant-Y");
        assertThat(my.totalInstances()).isEqualTo(2);
        assertThat(my.pendingTasks()).isEqualTo(2);

        // 全局(不过滤)
        com.workflow.monitor.DashboardMetrics all = engine.dashboard(10);
        assertThat(all.totalInstances()).isEqualTo(5);
        assertThat(all.pendingTasks()).isEqualTo(5);
    }
}
