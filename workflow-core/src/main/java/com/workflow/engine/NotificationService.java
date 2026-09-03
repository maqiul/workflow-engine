package com.workflow.engine;

import com.workflow.runtime.TaskInstance;

/**
 * 通知服务接口 - 用于流程催办、超时提醒等场景
 *
 * 实现方可以是：
 *  - 邮件通知
 *  - 短信通知
 *  - 站内信
 *  - 钉钉/企业微信
 *  - 等等
 */
public interface NotificationService {

    /**
     * 发送通知
     *
     * @param task      任务实例
     * @param userId    接收通知的用户
     * @param message   通知内容
     */
    void notify(TaskInstance task, String userId, String message);

    /**
     * 发送催办通知
     *
     * @param task      任务实例
     * @param userId    被催办的用户
     * @param operator  催办操作人
     * @param reason    催办原因（可为 null）
     */
    default void urge(TaskInstance task, String userId, String operator, String reason) {
        String msg = "您有一个待办任务需要处理";
        if (reason != null && !reason.isBlank()) {
            msg += "，原因：" + reason;
        }
        msg += "（催办人：" + operator + "）";
        notify(task, userId, msg);
    }

    /**
     * 发送超时提醒
     *
     * @param task      任务实例
     * @param userId    超时的用户
     * @param timeoutMillis 超时时间（毫秒）
     */
    default void timeoutReminder(TaskInstance task, String userId, long timeoutMillis) {
        String msg = "您的任务已超时 " + (timeoutMillis / 1000 / 60) + " 分钟，请尽快处理";
        notify(task, userId, msg);
    }
}
