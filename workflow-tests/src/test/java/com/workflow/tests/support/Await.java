package com.workflow.tests.support;

import java.util.function.BooleanSupplier;

/**
 * 测试等待工具 —— 用轮询替代固定 {@code Thread.sleep}。
 *
 * <p>超时调度类测试依赖真实调度器触发；固定 sleep 在 CI 共享 runner 上因负载/抢占
 * 而缓冲不足，导致偶发 flaky（Windows 本地快、CI 慢）。轮询"等到条件成立或超时上限"
 * 既鲁棒又不拖慢（条件一满足立即返回）。
 */
public final class Await {

    private Await() {}

    /**
     * 轮询直到 {@code condition} 为真，或达到 {@code timeoutMs} 上限。
     *
     * @return true 表示条件在期限内成立；false 表示等满超时（调用方随后断言自然失败）
     */
    public static boolean until(BooleanSupplier condition, long timeoutMs) {
        return until(condition, timeoutMs, 25);
    }

    public static boolean until(BooleanSupplier condition, long timeoutMs, long intervalMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return condition.getAsBoolean();
            }
        }
        return condition.getAsBoolean();
    }
}
