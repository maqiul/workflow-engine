package com.workflow.rest;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.engine.WorkflowEngine;
import com.workflow.engine.WorkflowEngineBuilder;
import com.workflow.enums.TaskStatus;
import com.workflow.repository.InMemoryCommentRepository;
import com.workflow.repository.InMemoryInstanceRepository;
import com.workflow.repository.InMemoryProcessRepository;
import com.workflow.repository.InMemoryTaskRepository;
import com.workflow.runtime.TaskInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REST 层审批意见端点 —— OA 集成缺口 #1 的对外出口。
 *
 * <p>断言重心是<b>能力探测的语义</b>：未启用意见能力的部署要给 501（换部署 / 改配置），
 * 而不是 409（刷新重试）也不是 500（报警查日志）。这三者对客户端的含义完全不同，
 * 混在一起等于把「部署没开这个功能」伪装成「你重试一下就好」。
 *
 * <p>JSON 断言只用结构字段（ASCII），正文内容改由引擎对象验证 ——
 * 序列化器是否转义非 ASCII 是另一个话题，不该让这些用例跟着变脆。
 */
@DisplayName("REST 审批意见端点")
class RestCommentApiTest {

    private InMemoryProcessRepository procRepo;
    private InMemoryInstanceRepository instRepo;
    private InMemoryTaskRepository taskRepo;
    private InMemoryCommentRepository commentRepo;
    private WorkflowEngine engine;
    private WorkflowRestApi api;

    @BeforeEach
    void setUp() {
        procRepo = new InMemoryProcessRepository();
        instRepo = new InMemoryInstanceRepository();
        taskRepo = new InMemoryTaskRepository();
        commentRepo = new InMemoryCommentRepository();
        engine = engineWithComments(true);
        api = new WorkflowRestApi(engine);
        procRepo.save(ProcessBuilder.create("leave")
                .version(1)
                .start("start")
                .userTask("manager", "经理审批", Candidate.ofAny("u1", "u2"))
                .userTask("hr", "人事确认", Candidate.ofAny("u3"))
                .end("end")
                .connect("start", "manager")
                .connect("manager", "hr")
                .connect("hr", "end")
                .build());
    }

    /** 依开关装配引擎：{@code false} 即模拟一个没接意见仓储的部署。 */
    private WorkflowEngine engineWithComments(boolean enabled) {
        WorkflowEngineBuilder builder = WorkflowEngineBuilder.builder(procRepo, instRepo, taskRepo);
        if (enabled) {
            builder.commentRepository(commentRepo);
        }
        return builder.build();
    }

    private String startInstance() {
        return engine.start("leave", "emp1", Map.of("days", 3));
    }

    private String pendingTaskId(String instanceId, String nodeId) {
        return engine.getInstance(instanceId).getTasks().stream()
                .filter(t -> t.getNodeId().equals(nodeId))
                .filter(t -> t.getStatus() == TaskStatus.PENDING)
                .map(TaskInstance::getId)
                .findFirst().orElseThrow(() -> new AssertionError("节点无待办: " + nodeId));
    }

    // ---------- 写入 ----------

    @Test
    @DisplayName("实例级意见：返回 201，类型缺省为 COMMENT")
    void postInstanceComment() {
        String instanceId = startInstance();

        RestResponse r = api.handle(RestRequest.post("/api/instances/" + instanceId + "/comments",
                "{\"userId\":\"emp1\",\"message\":\"补充：往返高铁\"}"));

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.jsonBody()).contains("\"type\":\"COMMENT\"")
                .contains("\"userId\":\"emp1\"")
                .as("流程级意见不带任务").doesNotContain("\"taskId\":\"");
        assertThat(engine.getInstanceComments(instanceId))
                .singleElement()
                .satisfies(c -> assertThat(c.getMessage()).isEqualTo("补充：往返高铁"));
    }

    @Test
    @DisplayName("任务级意见：路径只要 taskId，实例与节点由服务端推导")
    void postTaskComment() {
        String instanceId = startInstance();
        String taskId = pendingTaskId(instanceId, "manager");

        RestResponse r = api.handle(RestRequest.post("/api/tasks/" + taskId + "/comments",
                "{\"userId\":\"u1\",\"type\":\"APPROVE\",\"message\":\"同意\"}"));

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.jsonBody()).contains("\"taskId\":\"" + taskId + "\"")
                .contains("\"nodeId\":\"manager\"")
                .contains("\"instanceId\":\"" + instanceId + "\"")
                .contains("\"type\":\"APPROVE\"");
    }

    @Test
    @DisplayName("类型名大小写不敏感")
    void commentTypeIsCaseInsensitive() {
        String instanceId = startInstance();

        RestResponse r = api.handle(RestRequest.post("/api/instances/" + instanceId + "/comments",
                "{\"userId\":\"u1\",\"type\":\"reject\",\"message\":\"不行\"}"));

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.jsonBody()).contains("\"type\":\"REJECT\"");
    }

    // ---------- 读取 ----------

    @Test
    @DisplayName("实例意见列表：流程级与任务级合并返回")
    void listInstanceComments() {
        String instanceId = startInstance();
        String taskId = pendingTaskId(instanceId, "manager");
        api.handle(RestRequest.post("/api/instances/" + instanceId + "/comments",
                "{\"userId\":\"emp1\",\"message\":\"first\"}"));
        api.handle(RestRequest.post("/api/tasks/" + taskId + "/comments",
                "{\"userId\":\"u1\",\"message\":\"second\"}"));

        RestResponse r = api.handle(
                RestRequest.get("/api/instances/" + instanceId + "/comments", Map.of()));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.jsonBody()).contains("\"taskId\":\"" + taskId + "\"");
        assertThat(engine.getInstanceComments(instanceId))
                .as("流程级那条也在同一个列表里").hasSize(2);
    }

    @Test
    @DisplayName("任务意见列表只含本任务，不混入流程级意见")
    void listTaskComments() {
        String instanceId = startInstance();
        String taskId = pendingTaskId(instanceId, "manager");
        api.handle(RestRequest.post("/api/instances/" + instanceId + "/comments",
                "{\"userId\":\"emp1\",\"message\":\"first\"}"));
        api.handle(RestRequest.post("/api/tasks/" + taskId + "/comments",
                "{\"userId\":\"u1\",\"message\":\"second\"}"));

        RestResponse r = api.handle(RestRequest.get("/api/tasks/" + taskId + "/comments", Map.of()));

        assertThat(r.status()).isEqualTo(200);
        assertThat(engine.getTaskComments(taskId)).singleElement()
                .satisfies(c -> assertThat(c.getMessage()).isEqualTo("second"));
    }

    // ---------- 错误语义 ----------

    @Test
    @DisplayName("未知意见类型给 400，不让它落到 500")
    void unknownCommentTypeIsBadRequest() {
        String instanceId = startInstance();

        RestResponse r = api.handle(RestRequest.post("/api/instances/" + instanceId + "/comments",
                "{\"userId\":\"emp1\",\"type\":\"NOT_A_TYPE\",\"message\":\"x\"}"));

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.jsonBody()).contains("NOT_A_TYPE");
    }

    @Test
    @DisplayName("缺 userId 给 400")
    void missingUserIsBadRequest() {
        String instanceId = startInstance();

        RestResponse r = api.handle(RestRequest.post("/api/instances/" + instanceId + "/comments",
                "{\"userId\":\"   \",\"message\":\"no signature\"}"));

        assertThat(r.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("未启用意见仓储的部署：读写都给 501，而不是 409")
    void disabledDeploymentIsNotImplemented() {
        WorkflowEngine plain = engineWithComments(false);
        WorkflowRestApi plainApi = new WorkflowRestApi(plain);
        String instanceId = plain.start("leave", "emp1", Map.of());
        String taskId = plain.getInstance(instanceId).getTasks().get(0).getId();

        RestResponse get = plainApi.handle(
                RestRequest.get("/api/instances/" + instanceId + "/comments", Map.of()));
        RestResponse post = plainApi.handle(
                RestRequest.post("/api/instances/" + instanceId + "/comments",
                        "{\"userId\":\"u1\",\"message\":\"x\"}"));
        RestResponse postTask = plainApi.handle(
                RestRequest.post("/api/tasks/" + taskId + "/comments",
                        "{\"userId\":\"u1\",\"message\":\"x\"}"));

        assertThat(get.status()).isEqualTo(501);
        assertThat(post.status()).isEqualTo(501);
        assertThat(postTask.status()).isEqualTo(501);
        assertThat(plain.supportsComments()).isFalse();
    }
}
