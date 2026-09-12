package com.workflow.rest;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.VariableDefinition;
import com.workflow.definition.VariableType;
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
 * REST 层运行期变量端点 —— OA 集成缺口 #4 的对外出口。
 *
 * <p>断言重心在<b>错误语义的区分度</b>：保留前缀/类型错是调用方改参数就能解决的 400，
 * 实例不存在是 404，实例已结束是「刷新后换个对象再试」的 409。
 * 三者都曾落到同一个 500，客户端只能一律重试。
 */
@DisplayName("REST 运行期变量端点")
class RestVariableApiTest {

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
        engine = WorkflowEngineBuilder.builder(procRepo, instRepo, taskRepo).build();
        api = new WorkflowRestApi(engine);
        procRepo.save(ProcessBuilder.create("leave")
                .version(1)
                .start("start")
                .userTask("manager", "经理审批", Candidate.ofAny("u1"))
                .end("end")
                .connect("start", "manager")
                .connect("manager", "end")
                .variable(VariableDefinition.builder("days", VariableType.INTEGER).build())
                .build());
    }

    private String startInstance() {
        return engine.start("leave", "emp1", Map.of("days", 3));
    }

    @Test
    @DisplayName("写入成功：200 并回传更新后的变量快照")
    void postVariablesReturnsSnapshot() {
        String instanceId = startInstance();

        RestResponse r = api.handle(RestRequest.post("/api/instances/" + instanceId + "/variables",
                "{\"operator\":\"u1\",\"variables\":{\"days\":5,\"note\":\"补正\"}}"));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.jsonBody()).contains("\"days\":5").contains("\"note\":\"补正\"");
        assertThat(engine.getInstance(instanceId).getVariable("days")).isEqualTo(5);
    }

    @Test
    @DisplayName("保留前缀给 400（改参数就能解决，不是服务坏了）")
    void reservedPrefixIsBadRequest() {
        String instanceId = startInstance();

        RestResponse r = api.handle(RestRequest.post("/api/instances/" + instanceId + "/variables",
                "{\"operator\":\"u2\",\"variables\":{\"__initiator\":\"emp2\"}}"));

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.jsonBody()).contains("保留前缀");
    }

    @Test
    @DisplayName("类型不符给 400")
    void typeMismatchIsBadRequest() {
        String instanceId = startInstance();

        RestResponse r = api.handle(RestRequest.post("/api/instances/" + instanceId + "/variables",
                "{\"operator\":\"u1\",\"variables\":{\"days\":\"三天\"}}"));

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.jsonBody()).contains("类型不匹配");
    }

    @Test
    @DisplayName("实例不存在给 404，而不是 400（参数对，资源没有）")
    void unknownInstanceIsNotFound() {
        RestResponse r = api.handle(RestRequest.post("/api/instances/no-such-id/variables",
                "{\"operator\":\"u1\",\"variables\":{\"days\":5}}"));

        assertThat(r.status()).isEqualTo(404);
    }

    @Test
    @DisplayName("已结束实例给 409（刷新看到新状态后可改道）")
    void endedInstanceIsConflict() {
        String instanceId = startInstance();
        engine.terminate(instanceId);

        RestResponse r = api.handle(RestRequest.post("/api/instances/" + instanceId + "/variables",
                "{\"operator\":\"u1\",\"variables\":{\"days\":5}}"));

        assertThat(r.status()).isEqualTo(409);
    }

    @Test
    @DisplayName("variables 为空给 400（空调用是调用方的 bug）")
    void emptyVariablesIsBadRequest() {
        String instanceId = startInstance();

        RestResponse r = api.handle(RestRequest.post("/api/instances/" + instanceId + "/variables",
                "{\"operator\":\"u1\",\"variables\":{}}"));

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.jsonBody()).contains("不能为空");
    }

    @Test
    @DisplayName("只收 POST：GET 给 400")
    void getMethodIsRejected() {
        String instanceId = startInstance();

        RestResponse r = api.handle(
                RestRequest.get("/api/instances/" + instanceId + "/variables", Map.of()));

        assertThat(r.status()).isEqualTo(400);
    }
}
