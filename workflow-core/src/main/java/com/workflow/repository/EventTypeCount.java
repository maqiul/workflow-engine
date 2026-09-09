package com.workflow.repository;

import com.workflow.enums.AuditEventType;

/**
 * 审计事件类型分组计数结果 —— 按 eventType 聚合的一行。
 *
 * <p>监控仪表盘用它替代 {@code findByTimeRange()} 全表扫再内存分组：
 * 后者会把所有审计日志加载到内存（可能很大），而事件统计只需
 * {@code WHERE eventType LIKE 'TIMEOUT_%' GROUP BY eventType} 的轻量结果集。
 */
public record EventTypeCount(AuditEventType eventType, long count) { }
