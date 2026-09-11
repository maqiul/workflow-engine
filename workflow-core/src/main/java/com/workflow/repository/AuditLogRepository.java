package com.workflow.repository;

import com.workflow.runtime.AuditLog;

import java.time.Instant;
import java.util.List;

/**
 * 审计日志仓储接口
 */
public interface AuditLogRepository {

    /** 保存审计日志 */
    void save(AuditLog log);

    /** 按实例 ID 查询（按时间升序） */
    List<AuditLog> findByInstanceId(String instanceId);

    /** 按任务 ID 查询（按时间升序） */
    List<AuditLog> findByTaskId(String taskId);

    /** 按时间范围查询（按时间升序） */
    List<AuditLog> findByTimeRange(Instant from, Instant to);

    /** 清空所有审计日志（测试用） */
    void clear();

    /**
     * 按事件类型前缀分组计数。
     *
     * <p>用于监控仪表盘统计超时事件（{@code prefix="TIMEOUT_"}）等场景。
     * 比 {@code findByTimeRange()} 全表扫再内存分组快得多。
     *
     * @param prefix 事件类型前缀（如 "TIMEOUT_"）
     * @return 匹配的事件类型及其计数
     */
    List<EventTypeCount> countGroupByEventTypePrefix(String prefix);
}
