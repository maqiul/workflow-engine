package com.workflow.rest;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.engine.WorkflowEngine;
import com.workflow.engine.WorkflowEngineBuilder;
import com.workflow.repository.InMemoryInstanceRepository;
import com.workflow.repository.InMemoryProcessRepository;
import com.workflow.repository.InMemoryTaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 鉴权经真实 HTTP 往返的验证。
 *
 * <p>这一层不能省：{@code RestAuthenticationTest} 直接调 API 时，请求头是测试自己塞进去的，
 * 而真实的头要由 {@link RestServer} 从 {@code HttpExchange} 里捞出来再往上传。
 * 少捞这一步，症状是「配了鉴权、所有请求都被拒」，而纯逻辑层的用例全是绿的 ——
 * 所以传输层必须自己有一组用例盯着。
 */
@DisplayName("REST 鉴权（真 HTTP 往返）")
class RestAuthHttpTest {

    private static final String KEY = "e2e-secret";

    private InMemoryProcessRepository procRepo;
    private InMemoryInstanceRepository instRepo;
    private InMemoryTaskRepository taskRepo;
    private WorkflowEngine engine;
    private RestServer server;
    private HttpClient client;

    @BeforeEach
    void startServer() throws Exception {
        procRepo = new InMemoryProcessRepository();
        instRepo = new InMemoryInstanceRepository();
        taskRepo = new InMemoryTaskRepository();
        engine = WorkflowEngineBuilder.builder(procRepo, instRepo, taskRepo).build();
        procRepo.save(ProcessBuilder.create("auth-http")
                .version(1)
                .start("start")
                .userTask("manager", "经理审批", Candidate.ofAny("u1"))
                .end("end")
                .connect("start", "manager")
                .connect("manager", "end")
                .build());
        WorkflowRestApi api = new WorkflowRestApi(engine, null, procRepo,
                ApiKeyAuthenticator.of(KEY));
        server = new RestServer(0, api);
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

    private HttpResponse<String> send(String method, String path, String body, String apiKey)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + path))
                .timeout(Duration.ofSeconds(10));
        if (apiKey != null) {
            b.header("X-API-Key", apiKey);
        }
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json; charset=utf-8")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("无凭证经 HTTP → 401")
    void withoutKeyOverHttp() throws Exception {
        HttpResponse<String> r = send("GET", "/api/health", null, null);
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.body()).contains("缺少凭证");
    }

    @Test
    @DisplayName("带凭证经 HTTP → 请求头确实被传输层捞到并放行")
    void withKeyOverHttp() throws Exception {
        HttpResponse<String> r = send("GET", "/api/health", null, KEY);
        assertThat(r.statusCode())
                .as("请求头没被捞上去的话这里会是 401")
                .isEqualTo(200);
        assertThat(r.body()).contains("UP");
    }

    @Test
    @DisplayName("错误凭证经 HTTP → 401")
    void wrongKeyOverHttp() throws Exception {
        assertThat(send("POST", "/api/processes/auth-http/start", "{}", "bad-key").statusCode())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("带凭证的启动请求经 HTTP 正常创建实例")
    void startWithKeyOverHttp() throws Exception {
        HttpResponse<String> r = send("POST", "/api/processes/auth-http/start",
                "{\"initiator\":\"emp1\"}", KEY);
        assertThat(r.statusCode()).isEqualTo(201);
        assertThat(r.body()).contains("instanceId");
    }

    @Test
    @DisplayName("被拒的写请求经 HTTP 不产生副作用")
    void rejectedWriteHasNoSideEffectOverHttp() throws Exception {
        assertThat(send("POST", "/api/processes/auth-http/start", "{}", null).statusCode())
                .isEqualTo(401);
        assertThat(engine.allTasks()).isEmpty();
    }
}
