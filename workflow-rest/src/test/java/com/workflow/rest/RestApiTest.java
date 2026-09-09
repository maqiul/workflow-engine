package com.workflow.rest;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.WorkflowEngine;
import com.workflow.repository.HistoryRepository;
import com.workflow.repository.InMemoryHistoryRepository;
import com.workflow.repository.InMemoryInstanceRepository;
import com.workflow.repository.InMemoryProcessRepository;
import com.workflow.repository.InMemoryTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REST 层路由与状态码映射测试 —— 直接调 {@link WorkflowRestApi#handle}，不起端口。
 *
 * <p>重点是<b>状态码语义</b>而不是流程功能（后者在引擎测试里已覆盖）：
 * 客户端只能靠 400 / 404 / 409 / 501 的区别决定"改请求"、"换资源"还是"刷新重试"。
 */
@DisplayName("REST 路由与状态码")
class RestApiTest {

    private InMemoryProcessRepository procRepo;
    private InMemoryInstanceRepository instRepo;
    private InMemoryTaskRepository taskRepo;
    private InMemoryHistoryRepository histRepo;
    private WorkflowEngine engine;
    private WorkflowRestApi api;

    @BeforeEach
    void setUp() {
        procRepo = new InMemoryProcessRepository();
        instRepo = new InMemoryInstanceRepository();
        taskRepo = new InMemoryTaskRepository();
        histRepo = new InMemoryHistoryRepository();
        engine = engine(histRepo);
        api = new WorkflowRestApi(engine, histRepo, procRepo);
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

    /** 用给定历史仓储装配引擎；hr 传 null 即不记历史。 */
    private WorkflowEngine engine(HistoryRepository hr) {
        return new WorkflowEngine(procRepo, instRepo, taskRepo, null, null, null, null, null,
                hr, java.util.EnumSet.allOf(com.workflow.enums.HistoryKind.class), 
                null, null, null, new com.workflow.concurrency.LocalInstanceLocks(),
                new com.workflow.tx.UndoLogTransactionRunner(), 0, 0L);
    }

    private String startInstance() {
        return engine.start("leave", "emp1", Map.of("days", 3));
    }

    private String firstPendingTask(String instanceId) {
        return taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> t.getStatus() == com.workflow.enums.TaskStatus.PENDING)
                .map(t -> t.getId())
                .findFirst().orElseThrow();
    }

    // ---------- 基本端点 ----------

    @Test
    @DisplayName("发起流程返回 201 与实例 id 及首个待办")
    void startReturnsCreated() {
        RestResponse r = api.handle(RestRequest.post("/api/processes/leave/start",
                "{\"initiator\":\"emp1\",\"variables\":{\"days\":3}}"));

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.jsonBody()).contains("\"instanceId\"");
        assertThat(r.jsonBody()).as("应顺带返回待办，省一次往返")
                .contains("manager").contains("u1");
    }

    @Test
    @DisplayName("指定版本发起")
    void startWithVersion() {
        RestResponse r = api.handle(RestRequest.post("/api/processes/leave/start",
                "{\"version\":1}"));
        assertThat(r.status()).isEqualTo(201);
    }

    @Test
    @DisplayName("实例详情含状态、活跃令牌与任务")
    void getInstance() {
        String id = startInstance();
        RestResponse r = api.handle(RestRequest.of("GET", "/api/instances/" + id));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.jsonBody()).contains("\"processKey\":\"leave\"")
                .contains("\"status\":\"RUNNING\"")
                .contains("activeTokens")
                .contains("candidateUsers");
    }

    @Test
    @DisplayName("完成/驳回/转办与实例级操作返回 204")
    void actionsReturnNoContent() {
        String id = startInstance();
        String t1 = firstPendingTask(id);
        assertThat(api.handle(RestRequest.post("/api/tasks/" + t1 + "/complete",
                "{\"userId\":\"u1\"}")).status()).isEqualTo(204);

        String t2 = firstPendingTask(id);
        assertThat(api.handle(RestRequest.post("/api/tasks/" + t2 + "/transfer",
                "{\"fromUserId\":\"u3\",\"toUserId\":\"u4\"}")).status()).isEqualTo(204);

        String t3 = firstPendingTask(id);
        assertThat(api.handle(RestRequest.post("/api/tasks/" + t3 + "/reject",
                "{\"userId\":\"u4\",\"reason\":\"材料不全\"}")).status()).isEqualTo(204);

        assertThat(api.handle(RestRequest.post("/api/instances/" + id + "/suspend", null)).status())
                .isEqualTo(204);
        assertThat(api.handle(RestRequest.post("/api/instances/" + id + "/resume", null)).status())
                .isEqualTo(204);
        assertThat(api.handle(RestRequest.post("/api/instances/" + id + "/terminate", null)).status())
                .isEqualTo(204);
        assertThat(engine.getInstance(id).getStatus().name()).isEqualTo("TERMINATED");
    }

    // ---------- 状态码语义 ----------

    @Test
    @DisplayName("重复审批返回 409 而非 400/500，客户端据此刷新而非改参数")
    void duplicateApprovalIsConflict() {
        String id = startInstance();
        String taskId = firstPendingTask(id);

        assertThat(api.handle(RestRequest.post("/api/tasks/" + taskId + "/complete",
                "{\"userId\":\"u1\"}")).status()).isEqualTo(204);

        RestResponse again = api.handle(RestRequest.post("/api/tasks/" + taskId + "/complete",
                "{\"userId\":\"u2\"}"));
        assertThat(again.status())
                .as("待办已被处理属状态冲突；400 会被客户端理解为参数错误，500 会被当成服务故障")
                .isEqualTo(409);
        assertThat(again.jsonBody()).contains("非 PENDING");
    }

    @Test
    @DisplayName("资源不存在返回 404，与参数错误 400 区分开")
    void notFoundVersusBadRequest() {
        assertThat(api.handle(RestRequest.of("GET", "/api/instances/no-such")).status())
                .isEqualTo(404);
        assertThat(api.handle(RestRequest.post("/api/tasks/no-such/complete",
                "{\"userId\":\"u1\"}")).status()).isEqualTo(404);
        assertThat(api.handle(RestRequest.post("/api/processes/no-such/start", null)).status())
                .isEqualTo(404);
        assertThat(api.handle(RestRequest.of("GET", "/api/nope")).status()).isEqualTo(404);

        // 参数问题
        assertThat(api.handle(RestRequest.post("/api/tasks/x/complete", "{}")).status())
                .isEqualTo(400);
        assertThat(api.handle(RestRequest.post("/api/tasks/x/complete", "不是JSON")).status())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("非法 status 报 400 并列出可用取值")
    void invalidStatusEnumRejected() {
        RestResponse r = api.handle(RestRequest.get("/api/tasks", Map.of("status", "WAITING")));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.jsonBody()).as("应告诉客户端合法取值，否则只能猜").contains("PENDING");
    }

    @Test
    @DisplayName("size 越界与 page 为负都报 400")
    void pagingBounds() {
        assertThat(api.handle(RestRequest.get("/api/tasks", Map.of("size", "0"))).status())
                .isEqualTo(400);
        assertThat(api.handle(RestRequest.get("/api/tasks", Map.of("size", "9999"))).status())
                .isEqualTo(400);
        assertThat(api.handle(RestRequest.get("/api/tasks", Map.of("page", "-1"))).status())
                .isEqualTo(400);
        assertThat(api.handle(RestRequest.get("/api/tasks", Map.of("page", "abc"))).status())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("实例方法不支持非 POST 动作")
    void wrongMethodRejected() {
        String id = startInstance();
        assertThat(api.handle(RestRequest.of("GET", "/api/instances/" + id + "/terminate")).status())
                .isEqualTo(400);
    }

    // ---------- 分页 total ----------

    @Test
    @DisplayName("total 是真实命中数，不被本页 size 截断")
    void totalIsNotTruncatedByPageSize() {
        for (int i = 0; i < 4; i++) {
            startInstance();
        }
        RestResponse r = api.handle(RestRequest.get("/api/tasks",
                Map.of("status", "PENDING", "size", "2")));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.jsonBody()).as("4 个实例各 1 待办，每页 2 条时总数必须是 4")
                .contains("\"total\":4")
                .contains("\"totalPages\":2");
        assertThat(countOccurrences(r.jsonBody(), "\"taskId\"")).isEqualTo(2);
    }

    @Test
    @DisplayName("第二页拿到剩余条目，且 total 不变")
    void secondPageReturnsRest() {
        for (int i = 0; i < 4; i++) {
            startInstance();
        }
        RestResponse p0 = api.handle(RestRequest.get("/api/tasks",
                Map.of("status", "PENDING", "size", "2", "page", "0")));
        RestResponse p1 = api.handle(RestRequest.get("/api/tasks",
                Map.of("status", "PENDING", "size", "2", "page", "1")));

        assertThat(p0.jsonBody()).contains("\"total\":4");
        assertThat(p1.jsonBody()).contains("\"total\":4");
        List<String> page0First = firstTaskIdOf(p0);
        assertThat(page0First).as("第一页应非空").isNotEmpty();
        assertThat(p1.jsonBody())
                .as("第二页不得混入第一页的条目")
                .doesNotContain(page0First.get(0));
    }

    private List<String> firstTaskIdOf(RestResponse r) {
        // 取本页首个 taskId，用于确认第二页不是同一批
        String body = r.jsonBody();
        int i = body.indexOf("\"taskId\":\"");
        if (i < 0) {
            return List.of();
        }
        int from = i + "\"taskId\":\"".length();
        int to = body.indexOf('"', from);
        return List.of(body.substring(from, to));
    }

    @Test
    @DisplayName("按 assignee 与 processKey 组合过滤")
    void filterByAssigneeAndKey() {
        startInstance();
        assertThat(api.handle(RestRequest.get("/api/tasks", Map.of("assignee", "u1"))).jsonBody())
                .contains("\"total\":1");
        assertThat(api.handle(RestRequest.get("/api/tasks", Map.of("assignee", "nobody")))
                .jsonBody()).contains("\"total\":0");
        assertThat(api.handle(RestRequest.get("/api/tasks",
                Map.of("processKey", "leave", "nodeId", "manager"))).jsonBody())
                .contains("\"total\":1");
        assertThat(api.handle(RestRequest.get("/api/tasks",
                Map.of("processKey", "other"))).jsonBody()).contains("\"total\":0");
    }

    // ---------- 历史端点 ----------

    @Test
    @DisplayName("启用历史时返回活动与任务记录")
    void historyEndpoint() {
        String id = startInstance();
        api.handle(RestRequest.post("/api/tasks/" + firstPendingTask(id) + "/complete",
                "{\"userId\":\"u1\"}"));

        RestResponse r = api.handle(RestRequest.of("GET", "/api/instances/" + id + "/history"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.jsonBody()).contains("activities").contains("manager")
                .contains("\"endReason\":\"COMPLETED\"");
    }

    @Test
    @DisplayName("未启用历史仓储时返回 501，而不是空结果骗人")
    void historyUnsupported() {
        WorkflowEngine e = engine(null);
        WorkflowRestApi noHist = new WorkflowRestApi(e, null, procRepo);
        String id = e.start("leave", Map.of());

        RestResponse r = noHist.handle(RestRequest.of("GET", "/api/instances/" + id + "/history"));
        assertThat(r.status())
                .as("501 明确告知能力未启用；返回空列表会让调用方误以为确实没有历史")
                .isEqualTo(501);
        e.shutdown();
    }

    @Test
    @DisplayName("健康检查")
    void health() {
        assertThat(api.handle(RestRequest.of("GET", "/api/health")).jsonBody())
                .contains("UP");
    }

    /** 尾斜杠归一化，避免 /api/xxx/ 与 /api/xxx 行为不一致。 */
    @Test
    @DisplayName("路径尾斜杠不影响路由")
    void trailingSlashTolerated() {
        assertThat(api.handle(RestRequest.of("GET", "/api/health/")).status()).isEqualTo(200);
        String id = startInstance();
        assertThat(api.handle(RestRequest.of("GET", "/api/instances/" + id + "/")).status())
                .isEqualTo(200);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
