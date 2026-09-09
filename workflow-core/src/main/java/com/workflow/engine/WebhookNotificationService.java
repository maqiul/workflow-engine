package com.workflow.engine;

import com.alibaba.fastjson2.JSONObject;
import com.workflow.runtime.TaskInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Webhook 通知服务 —— 零依赖（JDK {@link HttpClient}）的通用外发实现。
 *
 * <p>把通知以 JSON POST 到配置的 HTTP 端点，业务侧用一个网关即可据此转发到
 * 邮件 / 短信 / 钉钉 / 企业微信等，避免引擎自身耦合任何具体渠道 SDK 与凭据。
 *
 * <p>容错原则：通知失败<b>绝不影响流程</b>——发送异常一律吞掉并 warn，
 * url 未配置时静默跳过（no-op）。
 */
public class WebhookNotificationService implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotificationService.class);

    private final String url;
    private final HttpClient client;
    private final Duration timeout;

    public WebhookNotificationService(String url) {
        this(url, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                Duration.ofSeconds(10));
    }

    public WebhookNotificationService(String url, HttpClient client, Duration timeout) {
        this.url = url;
        this.client = client;
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(10);
    }

    @Override
    public void notify(TaskInstance task, String userId, String message) {
        if (url == null || url.isBlank()) {
            log.debug("[Webhook] 未配置通知端点，跳过 userId={}", userId);
            return;
        }
        String payload = buildPayload(task, userId, message);
        try {
            deliver(payload);
        } catch (Exception e) {
            // 通知失败不能影响流程：只记录，不向上抛
            log.warn("[Webhook] 通知发送失败 url={} userId={} err={}", url, userId, e.toString());
        }
    }

    /** 构建通知 JSON。public 便于测试与复用。 */
    public String buildPayload(TaskInstance task, String userId, String message) {
        JSONObject json = new JSONObject();
        if (task != null) {
            json.put("taskId", task.getId());
            json.put("instanceId", task.getInstanceId());
            json.put("nodeId", task.getNodeId());
        }
        json.put("userId", userId);
        json.put("message", message);
        return json.toJSONString();
    }

    /**
     * 实际投递。默认用 JDK HttpClient POST。
     * 声明为 protected 以便测试注入替身、或子类替换为其它传输。
     */
    protected void deliver(String payload) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(payload, java.nio.charset.StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new java.io.IOException("webhook 返回状态 " + resp.statusCode());
        }
    }
}
