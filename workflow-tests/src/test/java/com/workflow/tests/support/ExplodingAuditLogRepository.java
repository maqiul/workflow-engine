package com.workflow.tests.support;

import com.workflow.enums.AuditEventType;
import com.workflow.repository.AuditLogRepository;
import com.workflow.runtime.AuditLog;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 在指定事件类型上故意抛异常的审计仓储 —— 用来探测「事务边界是否覆盖全部写入」。
 *
 * <p>审计日志的写入排在实例/任务写入<b>之后</b>，因此它是天然的「最后一步失败」探针：
 * 若引擎没有把整个动作纳入一个事务，异常抛出时前面的写入已经各自提交，
 * 实例就停在「任务已 COMPLETED 但 Token 未推进」这类无法自愈的半完成状态。
 *
 * <p>同一份探针在 InMemory / JPA / MyBatis 三套仓储上跑同一个断言，
 * 才能真正证明「引擎正确」而不是「某个仓储碰巧正确」。
 */
public class ExplodingAuditLogRepository implements AuditLogRepository {

    private final Set<AuditEventType> explodingOn;

    /** 在所有 TASK_COMPLETED 与 TASK_REJECTED 上失败。 */
    public ExplodingAuditLogRepository() {
        this(Set.of(AuditEventType.TASK_COMPLETED, AuditEventType.TASK_REJECTED));
    }

    public ExplodingAuditLogRepository(Set<AuditEventType> explodingOn) {
        this.explodingOn = explodingOn;
    }

    @Override
    public void save(AuditLog log) {
        if (explodingOn.contains(log.getEventType())) {
            throw new IllegalStateException("模拟审计库故障: " + log.getEventType());
        }
    }

    @Override
    public List<AuditLog> findByInstanceId(String instanceId) {
        return List.of();
    }

    @Override
    public List<AuditLog> findByTaskId(String taskId) {
        return List.of();
    }

    @Override
    public List<AuditLog> findByTimeRange(Instant from, Instant to) {
        return List.of();
    }

    @Override
    public void clear() {
        // 无状态
    }

    @Override
    public java.util.List<com.workflow.repository.EventTypeCount> countGroupByEventTypePrefix(String prefix) {
        return java.util.List.of();
    }
}
