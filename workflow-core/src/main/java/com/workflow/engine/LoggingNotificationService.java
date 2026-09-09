package com.workflow.engine;

import com.workflow.runtime.TaskInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 日志通知服务 —— 零依赖的默认实现。
 *
 * <p>把通知写到日志（SLF4J），并保留最近 N 条到内存环形缓冲，方便演示 / 测试断言。
 * 生产环境可换成邮件 / 短信 / 钉钉 / 企微等具体实现，或直接用
 * {@link WebhookNotificationService} 对接 HTTP 通知网关。
 */
public class LoggingNotificationService implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationService.class);

    /** 内存里保留的最近通知条数上限（环形缓冲，防无界增长）。 */
    private static final int MAX_RECENT = 100;

    private final List<String> recent = new ArrayList<>();

    @Override
    public void notify(TaskInstance task, String userId, String message) {
        String line = String.format("[通知] to=%s task=%s node=%s msg=%s",
                userId,
                task != null ? task.getId() : "null",
                task != null ? task.getNodeId() : "null",
                message);
        log.info(line);
        record(line);
    }

    private void record(String line) {
        synchronized (recent) {
            recent.add(line);
            if (recent.size() > MAX_RECENT) {
                recent.remove(0);
            }
        }
    }

    /** 最近记录的通知（副本），供演示 / 测试查看。 */
    public List<String> getRecentNotifications() {
        synchronized (recent) {
            return new ArrayList<>(recent);
        }
    }

    public int count() {
        synchronized (recent) {
            return recent.size();
        }
    }

    public void clear() {
        synchronized (recent) {
            recent.clear();
        }
    }
}
