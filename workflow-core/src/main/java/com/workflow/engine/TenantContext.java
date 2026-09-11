package com.workflow.engine;

import java.util.function.Supplier;

/**
 * 租户上下文 - 管理当前线程的租户 ID
 *
 * <p>多租户隔离方案：通过 ThreadLocal 传递当前租户 ID，引擎在启动流程时自动设置，
 * 仓储查询时自动过滤。
 *
 * <p>使用示例：
 * <pre>
 * TenantContext.setTenantId("tenant-1");
 * engine.start("my-process", variables);  // 自动设置 tenantId
 * TenantContext.clear();  // 清理上下文
 * </pre>
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
        // 工具类，禁止实例化
    }

    /**
     * 设置当前租户 ID
     *
     * @param tenantId 租户 ID（可为 null，表示全局）
     */
    public static void setTenantId(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    /**
     * 获取当前租户 ID
     *
     * @return 租户 ID（可能为 null）
     */
    public static String getTenantId() {
        return CURRENT_TENANT.get();
    }

    /**
     * 清理当前线程的租户上下文
     */
    public static void clear() {
        CURRENT_TENANT.remove();
    }

    /**
     * 执行操作时临时设置租户 ID，执行完后自动恢复
     *
     * @param tenantId 临时租户 ID
     * @param action 要执行的操作
     * @param <T> 返回值类型
     * @return 操作结果
     */
    public static <T> T withTenant(String tenantId, Supplier<T> action) {
        String previous = getTenantId();
        try {
            setTenantId(tenantId);
            return action.get();
        } finally {
            if (previous != null) {
                setTenantId(previous);
            } else {
                clear();
            }
        }
    }

    /**
     * 执行操作时临时设置租户 ID，执行完后自动恢复（无返回值）
     *
     * @param tenantId 临时租户 ID
     * @param action 要执行的操作
     */
    public static void withTenant(String tenantId, Runnable action) {
        String previous = getTenantId();
        try {
            setTenantId(tenantId);
            action.run();
        } finally {
            if (previous != null) {
                setTenantId(previous);
            } else {
                clear();
            }
        }
    }
}
