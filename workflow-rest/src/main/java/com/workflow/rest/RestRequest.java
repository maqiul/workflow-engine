package com.workflow.rest;

import java.util.Map;

/**
 * 与传输层无关的请求/响应模型。
 *
 * <p>存在的理由是可测试性：路由与状态映射逻辑可以在不监听端口、
 * 不做序列化的情况下被直接调用验证，只有少数用例需要真起 HTTP 服务器。
 */
public record RestRequest(String method, String path, Map<String, String> query, String body) {

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
}
