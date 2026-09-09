package com.workflow.rest;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.engine.WorkflowEngine;
import com.workflow.engine.WorkflowEngineBuilder;
import com.workflow.repository.InMemoryInstanceRepository;
import com.workflow.repository.InMemoryProcessRepository;
import com.workflow.repository.InMemoryTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 监控端点的 REST 层测试 —— 验证路由与 JSON 序列化。
 */
@DisplayName("REST 监控端点")
class RestDashboardTest {

    private InMemoryProcessRepository procRepo;
    private InMemoryInstanceRepository instRepo;
    private InMemoryTaskRepository taskRepo;
    private WorkflowEngine engine;
    private WorkflowRestApi api;

    @BeforeEach
    void setUp() {
        procRepo = new InMemoryProcessRepository();
        instRepo = new InMemoryInstanceRepository();
        taskRepo = new InMemoryTaskRepository();
        procRepo.save(ProcessBuilder.create("rest-dash")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("u1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build());
        engine = WorkflowEngineBuilder.builder(procRepo, instRepo, taskRepo).build();
        api = new WorkflowRestApi(engine);
    }

    @Test
    @DisplayName("GET /api/metrics/dashboard 返回 200 与聚合字段")
    void dashboardEndpointReturnsMetrics() {
        engine.start("rest-dash", Map.of()); // 制造一个待办

        RestResponse r = api.handle(RestRequest.of("GET", "/api/metrics/dashboard"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.jsonBody())
                .contains("totalInstances")
                .contains("pendingTasks")
                .contains("instancesByStatus");
    }

    @Test
    @DisplayName("topN 非法报 400")
    void dashboardRejectsBadTopN() {
        RestResponse r = api.handle(RestRequest.of("GET", "/api/metrics/dashboard?topN=abc"));
        assertThat(r.status()).isEqualTo(400);
    }
}
