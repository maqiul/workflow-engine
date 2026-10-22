package com.workflow.enums;

/**
 * 异步作业状态。
 *
 * <p>状态机：
 * <pre>
 *   READY ──领取──▶ (执行中，锁由 lock_owner 持有)
 *     │
 *     ├─ 执行成功 ──▶ 删除该行（终态，不留痕）
 *     ├─ 执行失败且未超重试上限 ──▶ READY（退避后重试，retries+1）
 *     └─ 执行失败且超重试上限 ──▶ FAILED（死信，保留行供人工排查/重放）
 * </pre>
 */
public enum JobState {
    /** 待执行 —— 到期且未被领取 */
    READY,
    /** 死信 —— 重试耗尽仍失败；行保留，不再自动执行，需人工介入 */
    FAILED
}
