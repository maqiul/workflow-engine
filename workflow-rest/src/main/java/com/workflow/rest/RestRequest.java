package com.workflow.rest;

import java.util.Map;

/**
 * 与传输层无关的请求/响应模型。
 *
 * <p>存在的理由是可测试性：路由与状态映射逻辑可以在不监听端口、
 * 不做序列化的情况下被直接调用验证，只有少数用例需要真起 HTTP 服务器。
 *
 * <p>{@code headers} 是为鉴权引入的 —— 凭证只能从请求头拿，而路由与鉴权都要能在
 * 不起端口的前提下被断言。
 */
public record RestRequest(String method, String path, Map<String, String> query, String body,
                          Map<String, String> headers) {

    /** 不带请求头的便捷构造（库内调用、只关心路由的测试）。 */
    public RestRequest(String method, String path, Map<String, String> query, String body) {
        this(method, path, query, body, Map.of());
    }

    public static RestRequest of(String method, String path) {
        return new RestRequest(method, path, Map.of(), null);
    }

    public static RestRequest of(String method, String path, Map<String, String> query) {
        return new RestRequest(method, path, query, null);
    }

    public static RestRequest post(String path, String body) {
        return new RestRequest("POST", path, Map.of(), body);
    }

    public static RestRequest get(String path, Map<String, String> query) {
        return new RestRequest("GET", path, query, null);
    }

    /** 带请求头的构造（鉴权相关用例）。 */
    public static RestRequest withHeaders(String method, String path, String body,
                                          Map<String, String> headers) {
        return new RestRequest(method, path, Map.of(), body, headers);
    }

    /**
     * 取请求头的值，<b>大小写不敏感</b>；不存在返回 {@code null}。
     *
     * <p>HTTP 头名按规范不区分大小写：客户端发 {@code x-api-key} 还是 {@code X-API-Key}
     * 都合法。做成精确匹配会让一半客户端莫名拿到 401，而且这种错很难查 ——
     * 从服务端看"客户端就是没带密钥"。
     */
    public String header(String name) {
        if (headers == null || name == null) {
            return null;
        }
        String direct = headers.get(name);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
