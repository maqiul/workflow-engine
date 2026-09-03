package com.workflow.repository;

import com.workflow.runtime.AuditLog;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * InMemory 审计日志仓储 - 基于内存存储
 *
 * 线程安全：使用 CopyOnWriteArrayList 保证并发读写安全。
 */
public class InMemoryAuditLogRepository implements AuditLogRepository {

    private final List<AuditLog> logs = new CopyOnWriteArrayList<>();

    @Override
    public void save(AuditLog log) {
        logs.add(log);
    }

    @Override
    public List<AuditLog> findByInstanceId(String instanceId) {
        List<AuditLog> result = new ArrayList<>();
        for (AuditLog log : logs) {
            if (log.getInstanceId().equals(instanceId)) {
                result.add(log);
            }
        }
        result.sort(Comparator.comparing(AuditLog::getTimestamp));
        return result;
    }

    @Override
    public List<AuditLog> findByTaskId(String taskId) {
        List<AuditLog> result = new ArrayList<>();
        for (AuditLog log : logs) {
            if (taskId.equals(log.getTaskId())) {
                result.add(log);
            }
        }
        result.sort(Comparator.comparing(AuditLog::getTimestamp));
        return result;
    }

    @Override
    public List<AuditLog> findByTimeRange(Instant from, Instant to) {
        List<AuditLog> result = new ArrayList<>();
        for (AuditLog log : logs) {
            Instant ts = log.getTimestamp();
            if (!ts.isBefore(from) && !ts.isAfter(to)) {
                result.add(log);
            }
        }
        result.sort(Comparator.comparing(AuditLog::getTimestamp));
        return result;
    }

    @Override
    public void clear() {
        logs.clear();
    }
}
