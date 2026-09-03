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
}
