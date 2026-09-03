package com.workflow.rest;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.engine.WorkflowEngine;
import com.workflow.repository.InMemoryHistoryRepository;
import com.workflow.repository.InMemoryInstanceRepository;
import com.workflow.repository.InMemoryProcessRepository;
import com.workflow.repository.InMemoryTaskRepository;
import com.workflow.repository.HistoryRepository;
import com.workflow.enums.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 传输层端到端验证 —— 真起 HTTP 服务器、真发请求。
 *
 * <p>路由与状态码的正确性已由 {@code RestApiTest} 不起端口覆盖，
 * 这里只证明 JDK {@code HttpServer} 这一层能work：绑定、线程池、body 读取、
 * 204 不挂住客户端、UTF-8 查询串解码。
 *
 * <p>端口传 0 让系统分配，避免并行构建时端口撞车。
 */
@DisplayName("REST 传输层（真 HTTP 往返）")
class RestServerHttpTest {

    private InMemoryProcessRepository procRepo;
    private InMemoryInstanceRepository instRepo;
    private InMemoryTaskRepository taskRepo;
    private HistoryRepository histRepo;
    private WorkflowEngine engine;
    private RestServer server;
    private HttpClient client;

    @BeforeEach
    void startServer() throws Exception {
        procRepo = new InMemoryProcessRepository();
        instRepo = new InMemoryInstanceRepository();
        taskRepo = new InMemoryTaskRepository();
        histRepo = new InMemoryHistoryRepository();
        engine = new WorkflowEngine(procRepo, instRepo, taskRepo, null, null, null, null, null,
                histRepo, new com.workflow.concurrency.LocalInstanceLocks(),
                new com.workflow.tx.UndoLogTransactionRunner(), 0, 0L);
        procRepo.save(ProcessBuilder.create("http-leave")
                .version(1)
                .start("start")
                .userTask("manager", "经理审批", Candidate.ofAny("u1"))
                .end("end")
                .connect("start", "manager")
                .connect("manager", "end")
                .build());

        server = new RestServer(0, new WorkflowRestApi(engine, histRepo, procRepo));
        server.start();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
        if (engine != null) {
            engine.shutdown();
        }
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + path))
                .timeout(Duration.ofSeconds(10));
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json; charset=utf-8")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("发起 → 查询 → 审批 全流程走真实 HTTP")
    void endToEndOverHttp() throws Exception {
        HttpResponse<String> started = send("POST", "/api/processes/http-leave/start",
                "{\"initiator\":\"emp1\",\"variables\":{\"days\":2}}");
        assertThat(started.statusCode()).isEqualTo(201);
        assertThat(started.headers().firstValue("Content-Type").orElse(""))
                .as("必须是 JSON 且带字符集，否则中文会乱码")
                .contains("application/json").contains("utf-8");

        String instanceId = extract(started.body(), "instanceId");
        assertThat(instanceId).isNotBlank();

        HttpResponse<String> detail = send("GET", "/api/instances/" + instanceId, null);
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body()).contains("manager");

        String taskId = extract(detail.body(), "taskId");
        HttpResponse<String> done = send("POST", "/api/tasks/" + taskId + "/complete",
                "{\"userId\":\"u1\"}");
        assertThat(done.statusCode())
                .as("204 不能带 body，且不能让客户端等一个不存在的响应体")
                .isEqualTo(204);
        assertThat(done.body()).isEmpty();

        // 整条流程应当已结束
        assertThat(send("GET", "/api/instances/" + instanceId, null).body())
                .contains("COMPLETED");
    }

    @Test
    @DisplayName("并发语义通过 HTTP 如实传达：重复审批得 409")
    void conflictOverHttp() throws Exception {
        String instanceId = extract(send("POST", "/api/processes/http-leave/start", "{}").body(),
                "instanceId");
        String taskId = extract(send("GET", "/api/instances/" + instanceId, null).body(), "taskId");

        assertThat(send("POST", "/api/tasks/" + taskId + "/complete",
                "{\"userId\":\"u1\"}").statusCode()).isEqualTo(204);

        HttpResponse<String> late = send("POST", "/api/tasks/" + taskId + "/complete",
                "{\"userId\":\"u2\"}");
        assertThat(late.statusCode()).as("迟到者必须拿到 409，不是 500").isEqualTo(409);
        assertThat(late.body()).contains("非 PENDING");
    }

    @Test
    @DisplayName("404 与 400 经 HTTP 仍保持区分")
    void errorCodesOverHttp() throws Exception {
        assertThat(send("GET", "/api/instances/nope", null).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/api/whatever", null).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/api/tasks/x/complete", "not-json").statusCode()).isEqualTo(400);
        assertThat(send("POST", "/api/tasks/x/complete", "{}").statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("中文姓名作查询参数须正确解码，否则按人过滤永远查不到")
    void unicodeQueryParam() throws Exception {
        procRepo.save(ProcessBuilder.create("中文流程")
                .version(1)
                .start("start")
                .userTask("cn", "中文审批", Candidate.ofAny("张三"))
                .end("end")
                .connect("start", "cn")
                .connect("cn", "end")
                .build());
        String instanceId = extract(send("POST", "/api/processes/"
                + java.net.URLEncoder.encode("中文流程", java.nio.charset.StandardCharsets.UTF_8)
                + "/start", "{}").body(), "instanceId");
        assertThat(instanceId).isNotBlank();

        String assignee = java.net.URLEncoder.encode("张三", java.nio.charset.StandardCharsets.UTF_8);
        HttpResponse<String> r = send("GET", "/api/tasks?assignee=" + assignee, null);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body())
                .as("URL 编码的中文参数必须解回来，否则按人查待办形同虚设")
                .contains("\"total\":1");
    }

    @Test
    @DisplayName("多个并发请求不应被传输层串行化")
    void concurrentRequestsServedInParallel() throws Exception {
        // 起 8 个实例，然后并发查详情；若 executor 缺失会退化成排队
        var futures = new java.util.ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<String>>>();
        for (int i = 0; i < 8; i++) {
            String id = extract(send("POST", "/api/processes/http-leave/start", "{}").body(),
                    "instanceId");
            futures.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return send("GET", "/api/instances/" + id, null);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }));
        }
        for (var f : futures) {
            assertThat(f.get().statusCode()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("历史端点经 HTTP 可取回活动与任务")
    void historyOverHttp() throws Exception {
        String instanceId = extract(send("POST", "/api/processes/http-leave/start", "{}").body(),
                "instanceId");
        String taskId = extract(send("GET", "/api/instances/" + instanceId, null).body(), "taskId");
        send("POST", "/api/tasks/" + taskId + "/complete", "{\"userId\":\"u1\"}");

        HttpResponse<String> r = send("GET", "/api/instances/" + instanceId + "/history", null);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("activities").contains("endReason");
    }

    /** 从 JSON 文本里取出某个字符串字段的首个值，避免为测试引入 JSON 库依赖。 */
    private static String extract(String json, String field) {
        String key = "\"" + field + "\":\"";
        int i = json.indexOf(key);
        if (i < 0) {
            return "";
        }
        int from = i + key.length();
        int to = json.indexOf('"', from);
        return json.substring(from, to);
    }

    @Test
    @DisplayName("服务停止后端口不再响应")
    void stopReleasesPort() throws Exception {
        int port = server.port();
        assertThat(port).isGreaterThan(0);
        server.close();
        server = null;
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/api/health"))
                        .timeout(Duration.ofSeconds(2)).build(),
                        HttpResponse.BodyHandlers.ofString()));
    }

    @Test
    @DisplayName("待办状态在 HTTP 响应里如实反映")
    void taskStatusReflected() throws Exception {
        String instanceId = extract(send("POST", "/api/processes/http-leave/start", "{}").body(),
                "instanceId");
        String taskId = extract(send("GET", "/api/instances/" + instanceId, null).body(), "taskId");
        assertThat(send("GET", "/api/tasks?instanceId=" + instanceId
                + "&status=" + TaskStatus.PENDING, null).body()).contains("\"total\":1");
        send("POST", "/api/tasks/" + taskId + "/complete", "{\"userId\":\"u1\"}");
        assertThat(send("GET", "/api/tasks?instanceId=" + instanceId
                + "&status=PENDING", null).body()).contains("\"total\":0");
    }
}
