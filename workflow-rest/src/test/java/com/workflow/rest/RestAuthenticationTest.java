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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * REST 鉴权语义测试 —— 直接调 {@link WorkflowRestApi#handle}，不起端口。
 *
 * <p>重点不是"密钥对不对"，而是几件容易做错、做错了又很难查的事：
 * <ul>
 *   <li>鉴权必须挡在路由<b>之前</b>：401 之后引擎不该被碰过</li>
 *   <li>请求头名按 HTTP 规范大小写不敏感 —— 精确匹配会让一半客户端莫名 401</li>
 *   <li>鉴权器自身异常要<b>按拒绝处理</b>：坏掉的鉴权器不能退化成放行</li>
 *   <li>401 与 403 不能混：一个该换凭证，一个该找管理员</li>
 * </ul>
 */
@DisplayName("REST 鉴权语义")
class RestAuthenticationTest {

    private static final String KEY = "s3cr3t-key";

    private InMemoryProcessRepository procRepo;
    private InMemoryInstanceRepository instRepo;
    private InMemoryTaskRepository taskRepo;
    private WorkflowEngine engine;
    private WorkflowRestApi openApi;

    @BeforeEach
    void setUp() {
        procRepo = new InMemoryProcessRepository();
        instRepo = new InMemoryInstanceRepository();
        taskRepo = new InMemoryTaskRepository();
        engine = WorkflowEngineBuilder.builder(procRepo, instRepo, taskRepo).build();
        openApi = new WorkflowRestApi(engine, null, procRepo);
        procRepo.save(ProcessBuilder.create("auth-leave")
                .version(1)
                .start("start")
                .userTask("manager", "经理审批", Candidate.ofAny("u1"))
                .end("end")
                .connect("start", "manager")
                .connect("manager", "end")
                .build());
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
    }

    private WorkflowRestApi apiWith(RequestAuthenticator auth) {
        return new WorkflowRestApi(engine, null, procRepo, auth);
    }

    private static RestRequest withHeader(String headerName, String value) {
        return RestRequest.withHeaders("GET", "/api/health", null, Map.of(headerName, value));
    }

    // ========== 默认行为 ==========

    @Test
    @DisplayName("未配置鉴权器 → 放行，保持既有零配置行为")
    void defaultAuthenticatorAllowsEverything() {
        assertThat(openApi.handle(RestRequest.of("GET", "/api/health")).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("显式传 null 鉴权器 → 同样放行，不抛 NPE")
    void nullAuthenticatorMeansNone() {
        WorkflowRestApi api = new WorkflowRestApi(engine, null, procRepo, null);
        assertThat(api.handle(RestRequest.of("GET", "/api/health")).status()).isEqualTo(200);
    }

    // ========== API Key ==========

    @Test
    @DisplayName("缺凭证 → 401")
    void missingKeyIsUnauthorized() {
        RestResponse r = apiWith(ApiKeyAuthenticator.of(KEY))
                .handle(RestRequest.of("GET", "/api/health"));
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.jsonBody()).contains("\"error\":401").contains("缺少凭证");
    }

    @Test
    @DisplayName("凭证错误 → 401")
    void wrongKeyIsUnauthorized() {
        RestResponse r = apiWith(ApiKeyAuthenticator.of(KEY))
                .handle(withHeader("X-API-Key", "not-the-key"));
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.jsonBody()).contains("凭证无效");
    }

    @Test
    @DisplayName("凭证正确 → 正常路由")
    void correctKeyPasses() {
        RestResponse r = apiWith(ApiKeyAuthenticator.of(KEY))
                .handle(withHeader("X-API-Key", KEY));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.jsonBody()).contains("UP");
    }

    @Test
    @DisplayName("请求头名大小写不敏感")
    void headerNameIsCaseInsensitive() {
        WorkflowRestApi guarded = apiWith(ApiKeyAuthenticator.of(KEY));
        assertThat(guarded.handle(withHeader("x-api-key", KEY)).status())
                .as("全小写是 curl / 部分 SDK 的默认写法").isEqualTo(200);
        assertThat(guarded.handle(withHeader("X-Api-Key", KEY)).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("自定义请求头名：换了头名后，旧头名不再被认")
    void customHeaderName() {
        WorkflowRestApi guarded = apiWith(ApiKeyAuthenticator.ofHeader("X-Token", KEY));
        assertThat(guarded.handle(withHeader("X-Token", KEY)).status()).isEqualTo(200);
        assertThat(guarded.handle(withHeader("X-API-Key", KEY)).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("多密钥白名单：任一个都算通过")
    void multipleKeys() {
        WorkflowRestApi guarded = apiWith(ApiKeyAuthenticator.of("key-a", "key-b"));
        assertThat(guarded.handle(withHeader("X-API-Key", "key-a")).status()).isEqualTo(200);
        assertThat(guarded.handle(withHeader("X-API-Key", "key-b")).status()).isEqualTo(200);
        assertThat(guarded.handle(withHeader("X-API-Key", "key-c")).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("空密钥白名单在构造时就该拒绝，而不是运行时全部放行")
    void emptyKeyListRejectedAtConstruction() {
        assertThat(assertThrows(IllegalArgumentException.class, () -> ApiKeyAuthenticator.of())
                .getMessage())
                .contains("至少需要一个有效密钥");
    }

    // ========== Bearer ==========

    @Test
    @DisplayName("Bearer 形式：前缀大小写不敏感")
    void bearerPrefixIsCaseInsensitive() {
        WorkflowRestApi guarded = apiWith(ApiKeyAuthenticator.bearer(KEY));
        assertThat(guarded.handle(withHeader("Authorization", "Bearer " + KEY)).status())
                .isEqualTo(200);
        assertThat(guarded.handle(withHeader("Authorization", "bearer " + KEY)).status())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("Bearer：漏了前缀 → 401 且提示格式，而不是笼统的「凭证无效」")
    void bearerWithoutPrefixReportsFormat() {
        RestResponse r = apiWith(ApiKeyAuthenticator.bearer(KEY))
                .handle(withHeader("Authorization", KEY));
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.jsonBody()).contains("格式");
    }

    // ========== 保护范围 ==========

    @Test
    @DisplayName("写操作同样受保护：401 之后引擎不该被碰过")
    void writeEndpointsProtectedAndEngineUntouched() {
        RestResponse r = apiWith(ApiKeyAuthenticator.of(KEY)).handle(
                RestRequest.post("/api/processes/auth-leave/start", "{\"initiator\":\"emp1\"}"));
        assertThat(r.status()).isEqualTo(401);
        assertThat(engine.allTasks())
                .as("鉴权必须挡在路由之前 —— 否则被拒的请求已经产生了副作用")
                .isEmpty();
    }

    @Test
    @DisplayName("健康检查不豁免鉴权：需要匿名探针请在网关放行，本层不做特例")
    void healthCheckIsNotExempt() {
        assertThat(apiWith(ApiKeyAuthenticator.of(KEY))
                .handle(RestRequest.of("GET", "/api/health")).status())
                .as("刻意如此：本层不为任何路径开特例。k8s 探针应在网关或自定义鉴权器里豁免")
                .isEqualTo(401);
    }

    // ========== 自定义鉴权器 ==========

    @Test
    @DisplayName("自定义鉴权器可返回 403，与 401 区分：一个是换凭证，一个是找管理员")
    void customAuthenticatorCanForbid() {
        WorkflowRestApi api = apiWith(req -> req.path().contains("/processes/")
                ? AuthResult.forbidden("只读部署，不允许发起流程")
                : AuthResult.allowed());
        RestResponse r = api.handle(RestRequest.post("/api/processes/auth-leave/start", "{}"));
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.jsonBody()).contains("只读部署");
        assertThat(api.handle(RestRequest.of("GET", "/api/health")).status())
                .as("同一鉴权器对放行的路径不该误伤")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("鉴权器自身抛异常 → 按拒绝处理（fail closed），且不外泄异常细节")
    void brokenAuthenticatorFailsClosed() {
        RestResponse r = apiWith(req -> {
            throw new IllegalStateException("鉴权服务连不上");
        }).handle(RestRequest.of("GET", "/api/health"));
        assertThat(r.status())
                .as("坏掉的鉴权器绝不能退化成放行，也不该被当成 409 之类的业务冲突")
                .isEqualTo(500);
        assertThat(r.jsonBody()).doesNotContain("鉴权服务连不上");
    }

    @Test
    @DisplayName("鉴权器返回 null 视为放行，不炸")
    void nullResultTreatedAsAllowed() {
        assertThat(apiWith(req -> null).handle(RestRequest.of("GET", "/api/health")).status())
                .isEqualTo(200);
    }
}
