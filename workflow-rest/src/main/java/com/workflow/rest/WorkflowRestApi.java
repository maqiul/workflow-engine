package com.workflow.rest;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.workflow.concurrency.WorkflowConflictException;
import com.workflow.engine.IWorkflowEngine;
import com.workflow.enums.TaskStatus;
import com.workflow.query.TaskQuery;
import com.workflow.repository.HistoryRepository;
import com.workflow.repository.ProcessRepository;
import com.workflow.runtime.HistoricActivityInstance;
import com.workflow.runtime.HistoricTaskInstance;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import com.workflow.runtime.Token;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * REST 路由与状态映射 —— 纯逻辑，不依赖任何 HTTP 实现。
 *
 * <p>刻意与 {@link RestServer} 分开：本类只认 {@link RestRequest} / {@link RestResponse}，
 * 因此绝大多数行为可以用直接方法调用来断言，不必监听端口、不必序列化往返。
 * 真 HTTP 往返只在 {@code RestServerTest} 里验一次传输层。
 *
 * <p><b>鉴权是可选的先置步骤</b>：{@link #handle} 会先把请求交给
 * {@link RequestAuthenticator}，默认实现放行一切。本模块不引入安全框架，只留钩子。
 *
 * <p><b>为什么 409 是这一层最重要的设计</b>：引擎在 v3.7 之后有了确定的并发语义 ——
 * 同棵流程树串行、后到的操作会被前置校验正当拒绝（"任务非 PENDING 状态"）。
 * 如果 REST 层把它压成 500 或 400，客户端就分不清"我请求写错了"和
 * "这单刚被别人批掉、我该刷新再看" —— 后者恰恰是审批系统最常见的交互。
 */
public class WorkflowRestApi {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRestApi.class);

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final IWorkflowEngine engine;
    private final HistoryRepository historyRepo;   // 可为 null：该部署未启用历史
    private final ProcessRepository processRepo;   // 可为 null：跳过定义存在性预检
    private final RequestAuthenticator authenticator;

    public WorkflowRestApi(IWorkflowEngine engine) {
        this(engine, null, null);
    }

    public WorkflowRestApi(IWorkflowEngine engine, HistoryRepository historyRepo,
                           ProcessRepository processRepo) {
        this(engine, historyRepo, processRepo, RequestAuthenticator.NONE);
    }

    /** @param authenticator 请求鉴权器；传 {@code null} 等价于放行 */
    public WorkflowRestApi(IWorkflowEngine engine, HistoryRepository historyRepo,
                           ProcessRepository processRepo, RequestAuthenticator authenticator) {
        this.engine = Objects.requireNonNull(engine);
        this.historyRepo = historyRepo;
        this.processRepo = processRepo;
        this.authenticator = authenticator == null ? RequestAuthenticator.NONE : authenticator;
    }

    // ========== 入口 ==========

    public RestResponse handle(RestRequest req) {
        try {
            RestResponse denied = authorize(req);
            if (denied != null) {
                return denied;
            }
            return route(req);
        } catch (ResourceNotFound nf) {
            return RestResponse.error(404, nf.getMessage());
        } catch (BadRequest br) {
            return RestResponse.error(400, br.getMessage());
        } catch (UnsupportedOperation us) {
            return RestResponse.error(501, us.getMessage());
        } catch (WorkflowConflictException conflict) {
            // 乐观锁冲突：重试无意义，必须重新读取最新状态
            return RestResponse.error(409, "并发冲突，请刷新后重试: " + conflict.getMessage());
        } catch (IllegalStateException state) {
            // 含引擎对"迟到者"的正当拒绝：待办已被他人处理
            return RestResponse.error(409, state.getMessage());
        } catch (IllegalArgumentException bad) {
            String msg = String.valueOf(bad.getMessage());
            // 引擎对"定义/实例/任务不存在"抛的是 IllegalArgumentException，
            // 这里按消息区分出 404，否则客户端看到的全是 400，无从判断是自己的
            // 参数错了还是资源根本不存在
            if (msg.contains("不存在")) {
                return RestResponse.error(404, msg);
            }
            return RestResponse.error(400, msg);
        } catch (RuntimeException unexpected) {
            log.error("[REST] 未预期错误 {} {}", req.method(), req.path(), unexpected);
            return RestResponse.error(500, "服务内部错误");
        }
    }

    /**
     * 鉴权前置检查：放行返回 {@code null}，拒绝返回对应状态码的响应。
     *
     * <p>鉴权器自己抛异常时<b>按拒绝处理</b>（fail closed）—— 一个坏掉的鉴权器
     * 绝不能退化成「全部放行」。这条比它看起来重要：配置错误、外部鉴权服务超时，
     * 都属于"鉴权器不可用"，此时正确的行为是拒绝服务而不是敞开门。
     */
    private RestResponse authorize(RestRequest req) {
        AuthResult result;
        try {
            result = authenticator.authenticate(req);
        } catch (RuntimeException broken) {
            log.error("[REST] 鉴权器异常，按拒绝处理 {} {}", req.method(), req.path(), broken);
            return RestResponse.error(500, "服务内部错误");
        }
        if (result == null || result.granted()) {
            return null;
        }
        log.warn("[REST] 鉴权拒绝 {} {} -> {}", req.method(), req.path(), result.status());
        return RestResponse.error(result.status(), result.message());
    }

    private RestResponse route(RestRequest req) {
        String path = trim(req.path());
        String m = req.method().toUpperCase();

        if (path.equals("/api/health")) {
            return RestResponse.ok(Map.of("status", "UP"));
        }

        // /api/metrics/dashboard —— 只读监控快照
        int qi = path.indexOf('?');
        String base = qi < 0 ? path : path.substring(0, qi);
        if (base.equals("/api/metrics/dashboard") && m.equals("GET")) {
            int topN = 10;
            String t = req.query().get("topN");
            if ((t == null || t.isBlank()) && qi >= 0) {
                for (String kv : path.substring(qi + 1).split("&")) {
                    String[] ab = kv.split("=", 2);
                    if (ab.length == 2 && ab[0].equals("topN")) {
                        t = ab[1];
                    }
                }
            }
            if (t != null && !t.isBlank()) {
                try {
                    topN = Integer.parseInt(t.trim());
                } catch (NumberFormatException ex) {
                    throw new BadRequest("topN 必须是整数: " + t);
                }
            }
            return RestResponse.ok(engine.dashboard(topN));
        }

        // /api/processes/{key}/start
        if (path.startsWith("/api/processes/") && path.endsWith("/start") && m.equals("POST")) {
            String key = segment(path, 3);
            return startProcess(key, req);
        }

        // /api/instances/{id}/...
        if (path.startsWith("/api/instances/")) {
            String id = segment(path, 3);
            String action = path.length() > ("/api/instances/" + id).length()
                    ? segment(path, 4) : "";
            if (action.isEmpty()) {
                if (!m.equals("GET")) {
                    throw new BadRequest("实例详情只支持 GET");
                }
                return getInstance(id);
            }
            switch (action) {
                case "terminate" -> {
                    requirePost(m);
                    engine.terminate(id);
                    return RestResponse.noContent();
                }
                case "suspend" -> {
                    requirePost(m);
                    engine.suspend(id);
                    return RestResponse.noContent();
                }
                case "resume" -> {
                    requirePost(m);
                    engine.resume(id);
                    return RestResponse.noContent();
                }
                case "withdraw" -> {
                    requirePost(m);
                    JSONObject body = parse(req.body());
                    engine.withdraw(id, requireString(body, "initiator"));
                    return RestResponse.noContent();
                }
                case "jump" -> {
                    requirePost(m);
                    JSONObject body = parse(req.body());
                    engine.jumpToNode(id, requireString(body, "targetNodeId"),
                            requireString(body, "operator"), body.getString("reason"));
                    return RestResponse.noContent();
                }
                case "history" -> {
                    return getHistory(id);
                }
                default -> throw new ResourceNotFound("未知实例操作: " + action);
            }
        }

        // /api/tasks 与 /api/tasks/{id}/{action}
        if (path.equals("/api/tasks")) {
            if (!m.equals("GET")) {
                throw new BadRequest("任务列表只支持 GET");
            }
            return listTasks(req.query());
        }
        if (path.startsWith("/api/tasks/")) {
            String taskId = segment(path, 3);
            String action = segment(path, 4);
            JSONObject body = parse(req.body());
            switch (action) {
                case "complete" -> {
                    requirePost(m);
                    String userId = requireString(body, "userId");
                    boolean approved = body.getBooleanValue("approved", true);
                    engine.completeTask(taskId, userId, approved);
                    return RestResponse.noContent();
                }
                case "reject" -> {
                    requirePost(m);
                    engine.rejectTask(taskId, requireString(body, "userId"),
                            body.getString("reason"));
                    return RestResponse.noContent();
                }
                case "transfer" -> {
                    requirePost(m);
                    engine.transferTask(taskId, requireString(body, "fromUserId"),
                            requireString(body, "toUserId"));
                    return RestResponse.noContent();
                }
                default -> throw new ResourceNotFound("未知任务操作: " + action);
            }
        }

        throw new ResourceNotFound("无此端点: " + req.method() + " " + req.path());
    }

    // ========== 各端点实现 ==========

    private RestResponse startProcess(String key, RestRequest req) {
        if (processRepo != null && !processRepo.exists(key)) {
            throw new ResourceNotFound("流程定义不存在: " + key);
        }
        JSONObject body = parse(req.body());
        String initiator = body.getString("initiator");
        @SuppressWarnings("unchecked")
        Map<String, Object> variables = body.getObject("variables", Map.class);
        Integer version = body.getInteger("version");

        String instanceId = (version != null)
                ? engine.start(key, version, initiator, variables)
                : engine.start(key, initiator, variables);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instanceId", instanceId);
        out.put("processKey", key);
        out.put("tasks", taskDtos(engine.getInstance(instanceId)));
        return RestResponse.created(out);
    }

    private RestResponse getInstance(String id) {
        ProcessInstance instance = engine.getInstance(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instanceId", instance.getId());
        out.put("processKey", instance.getProcessKey());
        out.put("processVersion", instance.getProcessVersion());
        out.put("status", instance.getStatus().name());
        out.put("createTime", instance.getCreateTime());
        out.put("endTime", instance.getEndTime());
        out.put("rootInstanceId", instance.getRootInstanceId());
        out.put("subProcess", instance.isSubProcess());
        List<Map<String, Object>> tokens = new ArrayList<>();
        for (Token t : instance.getActiveTokens().values()) {
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("tokenId", t.getId());
            tm.put("nodeId", t.getCurrentNodeId());
            tm.put("status", t.getStatus().name());
            tokens.add(tm);
        }
        out.put("activeTokens", tokens);
        out.put("tasks", taskDtos(instance));
        return RestResponse.ok(out);
    }

    /**
     * 任务列表 + 分页。
     *
     * <p>{@code total} 走 {@code TaskQuery.count()} —— 它与 {@code list()} 共用过滤链
     * 但不经 skip/limit，因此是真实命中数。旧实现的 count 复用 list()，
     * 加一页 20 条就会把总数报成 20，分页控件据此少算页数。
     */
    private RestResponse listTasks(Map<String, String> query) {
        TaskQuery q = TaskQuery.create();
        if (has(query, "assignee")) {
            q.candidate(query.get("assignee"));
        }
        if (has(query, "status")) {
            q.status(parseTaskStatus(query.get("status")));
        }
        if (has(query, "nodeId")) {
            q.nodeId(query.get("nodeId"));
        }
        if (has(query, "processKey")) {
            q.processDefinitionKey(query.get("processKey"));
        }
        if (has(query, "instanceId")) {
            q.processInstanceId(query.get("instanceId"));
        }
        if (has(query, "processVersion")) {
            q.processDefinitionVersion(parseInt(query.get("processVersion"), "processVersion"));
        }
        int page = query.containsKey("page") ? parseInt(query.get("page"), "page") : 0;
        int size = query.containsKey("size") ? parseInt(query.get("size"), "size") : DEFAULT_PAGE_SIZE;
        if (page < 0) {
            throw new BadRequest("page 不能为负: " + page);
        }
        if (size <= 0 || size > MAX_PAGE_SIZE) {
            throw new BadRequest("size 须在 1.." + MAX_PAGE_SIZE + " 之间，实际: " + size);
        }
        q.orderByCreateTime();

        long total = q.count(engine);
        List<TaskInstance> items = q.offset(page * size).limit(size).list(engine);

        List<Map<String, Object>> dtos = new ArrayList<>();
        for (TaskInstance t : items) {
            dtos.add(taskDto(t));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", dtos);
        out.put("page", page);
        out.put("size", size);
        out.put("total", total);
        out.put("totalPages", (total + size - 1) / size);
        return RestResponse.ok(out);
    }

    private RestResponse getHistory(String instanceId) {
        if (historyRepo == null) {
            throw new UnsupportedOperation("该部署未启用历史仓储");
        }
        List<Map<String, Object>> acts = new ArrayList<>();
        for (HistoricActivityInstance a : historyRepo.findByInstanceId(instanceId)) {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("activityId", a.getActivityId());
            am.put("activityType", a.getActivityType().name());
            am.put("tokenId", a.getTokenId());
            am.put("taskId", a.getTaskId());
            am.put("startTime", a.getStartTime());
            am.put("endTime", a.getEndTime());
            am.put("duration", a.getDuration());
            am.put("open", a.isOpen());
            acts.add(am);
        }
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (HistoricTaskInstance t : historyRepo.findTasksByInstanceId(instanceId)) {
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("taskId", t.getTaskId());
            tm.put("nodeId", t.getNodeId());
            tm.put("candidateUsers", t.getCandidateUsers());
            tm.put("completedBy", t.getCompletedBy());
            tm.put("startTime", t.getStartTime());
            tm.put("endTime", t.getEndTime());
            tm.put("duration", t.getDuration());
            tm.put("endReason", t.getEndReason().name());
            tasks.add(tm);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instanceId", instanceId);
        out.put("activities", acts);
        out.put("tasks", tasks);
        return RestResponse.ok(out);
    }

    // ========== DTO 与工具 ==========

    private List<Map<String, Object>> taskDtos(ProcessInstance instance) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TaskInstance t : engine.allTasks()) {
            if (t.getInstanceId().equals(instance.getId())) {
                out.add(taskDto(t));
            }
        }
        return out;
    }

    private Map<String, Object> taskDto(TaskInstance t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", t.getId());
        m.put("instanceId", t.getInstanceId());
        m.put("tokenId", t.getTokenId());
        m.put("nodeId", t.getNodeId());
        m.put("status", t.getStatus().name());
        m.put("candidateUsers", t.getCandidate().getUserIds());
        // 候选组只在确实存在时输出：它的语义是"尚未展开的组名"，
        // 绝大多数任务没有组，恒定输出一个空数组只是噪音
        if (t.getCandidate().hasGroups()) {
            m.put("candidateGroups", t.getCandidate().getGroupIds());
        }
        m.put("strategy", t.getCandidate().getStrategy().name());
        m.put("completedBy", t.getCompletedApprovers());
        m.put("createTime", t.getCreateTime());
        return m;
    }

    private JSONObject parse(String body) {
        if (body == null || body.isBlank()) {
            return new JSONObject();
        }
        try {
            JSONObject obj = JSON.parseObject(body);
            return obj == null ? new JSONObject() : obj;
        } catch (RuntimeException malformed) {
            throw new BadRequest("请求体不是合法 JSON: " + malformed.getMessage());
        }
    }

    private static String requireString(JSONObject body, String field) {
        String v = body.getString(field);
        if (v == null || v.isBlank()) {
            throw new BadRequest("缺少必填字段: " + field);
        }
        return v;
    }

    private static TaskStatus parseTaskStatus(String raw) {
        try {
            return TaskStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            throw new BadRequest("status 取值非法: " + raw + "，可用: "
                    + java.util.Arrays.toString(TaskStatus.values()));
        }
    }

    private static int parseInt(String raw, String field) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (RuntimeException bad) {
            throw new BadRequest(field + " 必须是整数，实际: " + raw);
        }
    }

    private static void requirePost(String method) {
        if (!method.equalsIgnoreCase("POST")) {
            throw new BadRequest("该端点只接受 POST，实际: " + method);
        }
    }

    private static boolean has(Map<String, String> q, String key) {
        String v = q.get(key);
        return v != null && !v.isBlank();
    }

    private static String trim(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String p = path.startsWith("/") ? path : "/" + path;
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /** 取路径第 i 段（0 为空串段），越界返回空串。 */
    private static String segment(String path, int i) {
        String[] parts = path.split("/");
        if (i < 0 || i >= parts.length) {
            return "";
        }
        return parts[i];
    }

    // ========== 内部异常（映射到状态码） ==========

    static class ResourceNotFound extends RuntimeException {
        ResourceNotFound(String message) { super(message); }
    }

    static class BadRequest extends RuntimeException {
        BadRequest(String message) { super(message); }
    }

    static class UnsupportedOperation extends RuntimeException {
        UnsupportedOperation(String message) { super(message); }
    }
}
