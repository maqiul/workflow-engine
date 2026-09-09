package com.workflow.tests.engine;

import com.workflow.definition.Candidate;
import com.workflow.engine.LoggingNotificationService;
import com.workflow.engine.WebhookNotificationService;
import com.workflow.runtime.TaskInstance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 通知服务实现测试。
 */
@DisplayName("通知服务")
class NotificationServiceTest {

    private TaskInstance task() {
        return new TaskInstance("inst-1", "tok-1", "review", Candidate.ofAny("u1"));
    }

    @Test
    @DisplayName("日志通知：notify/urge/timeoutReminder 生成记录")
    void loggingService() {
        LoggingNotificationService svc = new LoggingNotificationService();

        svc.notify(task(), "u1", "你好");
        svc.urge(task(), "u1", "boss", "尽快处理");
        svc.timeoutReminder(task(), "u1", 120000);

        assertThat(svc.count()).isEqualTo(3);
        List<String> recent = svc.getRecentNotifications();
        assertThat(recent).anySatisfy(s -> {
            assertThat(s).contains("to=u1").contains("node=review").contains("你好");
        });
        assertThat(recent).anyMatch(s -> s.contains("boss") && s.contains("尽快处理"));
        assertThat(recent).anyMatch(s -> s.contains("超时"));

        svc.clear();
        assertThat(svc.count()).isZero();
    }

    @Test
    @DisplayName("Webhook：未配置 url 时静默跳过，不抛异常")
    void webhookNullUrlNoop() {
        WebhookNotificationService svc = new WebhookNotificationService(null);
        assertThatCode(() -> svc.notify(task(), "u1", "msg")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Webhook：payload 含任务字段")
    void webhookBuildPayload() {
        WebhookNotificationService svc = new WebhookNotificationService("http://example.invalid/hook");
        String payload = svc.buildPayload(task(), "u9", "内容");
        assertThat(payload)
                .contains("\"instanceId\":\"inst-1\"")
                .contains("\"nodeId\":\"review\"")
                .contains("\"userId\":\"u9\"")
                .contains("内容");
    }

    @Test
    @DisplayName("Webhook：投递失败被吞掉，不影响调用方")
    void webhookSwallowsDeliveryFailure() {
        List<String> delivered = new ArrayList<>();
        WebhookNotificationService svc = new WebhookNotificationService("http://example.invalid/hook") {
            @Override
            protected void deliver(String payload) throws Exception {
                delivered.add(payload);
                throw new java.io.IOException("模拟网络故障");
            }
        };
        // notify 内部吞异常，不抛出
        assertThatCode(() -> svc.notify(task(), "u1", "m")).doesNotThrowAnyException();
        assertThat(delivered).hasSize(1);
    }

    @Test
    @DisplayName("Webhook：deliver 正常路径记录载荷")
    void webhookDeliversPayload() {
        List<String> delivered = new ArrayList<>();
        WebhookNotificationService svc = new WebhookNotificationService("http://example.invalid/hook") {
            @Override
            protected void deliver(String payload) {
                delivered.add(payload);
            }
        };
        svc.notify(task(), "u1", "m");
        assertThat(delivered).hasSize(1).allSatisfy(p -> assertThat(p).contains("u1"));
    }

    @Test
    @DisplayName("Webhook：buildPayload 对 null task 容错")
    void webhookPayloadNullTask() {
        WebhookNotificationService svc = new WebhookNotificationService("http://x/hook");
        assertThatCode(() -> {
            String p = svc.buildPayload(null, "u", "m");
            assertThat(p).contains("u").doesNotContain("nodeId");
        }).doesNotThrowAnyException();
    }
}
