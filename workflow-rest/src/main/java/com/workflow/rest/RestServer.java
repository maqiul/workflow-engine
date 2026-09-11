package com.workflow.rest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;

/**
 * 基于 JDK 内置 {@code HttpServer} 的最小 HTTP 传输层。
 *
 * <p>不用 Spring Boot：引擎定位是嵌入式 jar，把整个 Spring 栈塞进依赖会逼所有调用方接受它。
 * {@code com.sun.net.httpserver} 是 JDK 自带的，因此本模块除 {@code workflow-core} 外
 * <b>零第三方依赖</b>。需要 Spring 的场景应当自行写薄 Controller 包 {@code IWorkflowEngine}，
 * 而不是让 core 反向依赖 web。
 *
 * <p>只做传输：解请求、调 {@link WorkflowRestApi}、写响应。业务判定一律不在这里，
 * 所以路由逻辑的测试不需要起端口。
 */
public class RestServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RestServer.class);

    private final HttpServer server;
    private final WorkflowRestApi api;

    /**
     * @param port 绑定端口；传 0 让系统分配随机空闲端口（测试用），用 {@link #port()} 取回
     */
    public RestServer(int port, WorkflowRestApi api) throws IOException {
        this.api = Objects.requireNonNull(api);
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        // 必须有线程池：默认单线程会把并发请求排成队，等于在传输层重新引入串行化
        this.server.setExecutor(Executors.newFixedThreadPool(8));
        this.server.createContext("/", this::dispatch);
    }

    public void start() {
        server.start();
        log.info("[REST] 服务已启动 http://127.0.0.1:{}/api", port());
    }

    /** 实际监听端口（构造传 0 时由系统分配）。 */
    public int port() {
        return server.getAddress().getPort();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    @Override
    public void close() {
        server.stop(0);
        if (server.getExecutor() instanceof java.util.concurrent.ExecutorService es) {
            es.shutdownNow();
        }
        log.info("[REST] 服务已停止");
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            String body = readBody(exchange);

            RestResponse resp = api.handle(new RestRequest(
                    exchange.getRequestMethod(), path, query, body, readHeaders(exchange)));

            byte[] payload = resp.jsonBody() == null
                    ? new byte[0]
                    : resp.jsonBody().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            // 204 必须传 0 长度，否则 JDK 会等一个不存在的 body 导致客户端挂住
            exchange.sendResponseHeaders(resp.status(), payload.length == 0 ? -1 : payload.length);
            if (payload.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(payload);
                }
            }
            exchange.close();
        } catch (Exception unexpected) {
            log.error("[REST] 传输层异常", unexpected);
            byte[] msg = "{\"error\":500,\"message\":\"服务内部错误\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, msg.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(msg);
            }
            exchange.close();
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        byte[] raw = exchange.getRequestBody().readAllBytes();
        if (raw.length == 0) {
            return null;
        }
        return new String(raw, StandardCharsets.UTF_8);
    }

    /**
     * 收集请求头。
     *
     * <p>凭证只能从请求头拿，所以传输层必须把它传上去 —— 漏了这一步，
     * {@link RequestAuthenticator} 拿到的请求永远没有密钥，表现为"配了鉴权但全被拒"。
     */
    private static Map<String, String> readHeaders(HttpExchange exchange) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
            List<String> values = entry.getValue();
            // 同名多值按 HTTP 语义用逗号连接；本场景只有单值，但传输层不该丢信息
            out.put(entry.getKey(),
                    values == null || values.isEmpty() ? "" : String.join(",", values));
        }
        return out;
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return out;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(decode(key), decode(value));
        }
        return out;
    }

    /** 查询串按 UTF-8 解码，否则中文姓名作为 assignee 过滤会永远查不到。 */
    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return s;
        }
    }
}
