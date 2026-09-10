package com.workflow.tests.perf;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.WorkflowEngine;
import com.workflow.engine.WorkflowEngineBuilder;
import com.workflow.monitor.DashboardMetrics;
import com.workflow.repository.AuditLogRepository;
import com.workflow.repository.HistoryRepository;
import com.workflow.repository.InMemoryAuditLogRepository;
import com.workflow.repository.InMemoryHistoryRepository;
import com.workflow.repository.InMemoryInstanceRepository;
import com.workflow.repository.InMemoryProcessRepository;
import com.workflow.repository.InMemoryTaskRepository;
import com.workflow.runtime.TaskInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 性能基准测试。
 *
 * <p>默认<b>不</b>随常规构建运行（用 {@code -Dperf=true} 显式开启）：
 * 这类用例是"测量并输出耗时报表"，跑起来有秒级开销，不应拖慢每次 CI。
 * 开启方式：{@code gradle :workflow-tests:test -Dperf=true}
 *
 * <p>断言刻意宽松——目的是<b>暴露明显的算法退化</b>（如误成 O(n²)、批量反而更慢），
 * 而非钉死与机器强相关的绝对耗时。真正的耗时看打印输出。
 */
@EnabledIfSystemProperty(named = "perf", matches = "true")
@Timeout(value = 5, unit = TimeUnit.MINUTES)
@DisplayName("性能基准(需 -Dperf=true)")
class PerformanceBenchmarkTest {

    private InMemoryProcessRepository procRepo;
    private InMemoryInstanceRepository instRepo;
    private InMemoryTaskRepository taskRepo;
    private HistoryRepository histRepo;
    private AuditLogRepository auditRepo;
    private WorkflowEngine engine;

    @BeforeEach
    void setUp() {
        procRepo = new InMemoryProcessRepository();
        instRepo = new InMemoryInstanceRepository();
        taskRepo = new InMemoryTaskRepository();
        histRepo = new InMemoryHistoryRepository();
        auditRepo = new InMemoryAuditLogRepository();
        engine = WorkflowEngineBuilder.builder(procRepo, instRepo, taskRepo)
                .historyRepository(histRepo)
                .auditLogRepository(auditRepo)
                .build();
        procRepo.save(def());
    }

    private ProcessDefinition def() {
        return ProcessBuilder.create("perf-flow", "性能流程")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("u1"))
                .userTask("manager", "审批", Candidate.ofAny("m1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "manager")
                .connect("manager", "end")
                .build();
    }

    @Test
    @DisplayName("批量启动 vs 逐个启动")
    void batchStartVsSequential() {
        int n = 2000;

        // 逐个启动
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            engine.start("perf-flow", Map.of("i", i));
        }
        long seqNanos = System.nanoTime() - t0;
        int afterSeq = instRepo.findAll().size();

        // 批量启动
        List<Map<String, Object>> vars = new ArrayList<>();
        for (int i = 0; i < n; i++) vars.add(Map.of("i", i));
        long t1 = System.nanoTime();
        engine.batchStart("perf-flow", vars);
        long batchNanos = System.nanoTime() - t1;

        int total = instRepo.findAll().size();
        report("逐个启动 " + n, seqNanos);
        report("批量启动 " + n, batchNanos);

        assertThat(afterSeq).isEqualTo(n);
        assertThat(total).isEqualTo(2 * n);
        // 批量不应显著慢于逐个（留 3x 宽松余量防 flaky，只拦明显退化）
        assertThat(batchNanos).isLessThan(Math.max(seqNanos * 3, TimeUnit.SECONDS.toNanos(30)));
    }

    @Test
    @DisplayName("批量完成任务吞吐")
    void batchCompleteThroughput() {
        int n = 1000;
        List<String> taskIds = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String id = engine.start("perf-flow", Map.of());
            // 找 apply 待办
            taskRepo.findByInstanceId(id).stream()
                    .filter(t -> "apply".equals(t.getNodeId()))
                    .findFirst().ifPresent(t -> taskIds.add(t.getId()));
        }
        assertThat(taskIds).hasSize(n);

        long t0 = System.nanoTime();
        engine.batchCompleteTasks(taskIds, "u1", true);
        long nanos = System.nanoTime() - t0;
        report("批量完成 " + n + " 任务", nanos);

        // 全部推进到 manager
        long onManager = taskRepo.findByStatus(com.workflow.enums.TaskStatus.PENDING).stream()
                .filter(t -> "manager".equals(t.getNodeId())).count();
        assertThat(onManager).isEqualTo(n);
    }

    @Test
    @DisplayName("监控快照在大 dataset 下仍走聚合(非逐行)耗时可控")
    void dashboardAtScale() {
        List<Map<String, Object>> vars = new ArrayList<>();
        for (int i = 0; i < 3000; i++) vars.add(Map.of("i", i));
        engine.batchStart("perf-flow", vars);

        long t0 = System.nanoTime();
        DashboardMetrics m = engine.dashboard(10);
        report("dashboard over " + vars.size() + " 实例", System.nanoTime() - t0);

        assertThat(m.totalInstances()).isEqualTo(3000);
        assertThat(m.pendingTasks()).isEqualTo(3000);
        // 聚合查询应为亚线性体验：宽松上限 10s，只拦全表逐行重建级别的爆炸
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(10_000);
    }

    private void report(String label, long nanos) {
        double ms = nanos / 1_000_000.0;
        String line = String.format("[PERF] %-28s : %,.1f ms  (%,.0f ops/sec)",
                label, ms, label.matches(".*\\d.*") ? countOf(label) / (nanos / 1e9) : 0);
        System.out.println(line);
    }

    private long countOf(String label) {
        // 从 "xxx N ..." 里粗取数字用于打印吞吐
        StringBuilder d = new StringBuilder();
        for (char c : label.toCharArray()) if (Character.isDigit(c)) d.append(c);
        return d.length() == 0 ? 1 : Long.parseLong(d.toString());
    }
}
