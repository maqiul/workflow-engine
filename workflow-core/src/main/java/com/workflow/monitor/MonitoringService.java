package com.workflow.monitor;

import com.workflow.enums.TaskStatus;
import com.workflow.repository.AuditLogRepository;
import com.workflow.repository.EventTypeCount;
import com.workflow.repository.HistoryRepository;
import com.workflow.repository.InstanceRepository;
import com.workflow.repository.ProcessStatusCount;
import com.workflow.repository.TaskRepository;
import com.workflow.runtime.AuditLog;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * 监控服务 —— 把引擎各仓储的数据聚合成 {@link DashboardMetrics} 快照。
 *
 * <p>设计约束：
 * <ul>
 *   <li>只读聚合，不改任何状态；</li>
 *   <li>不新增数据库层聚合 SQL，全部基于既有仓储方法(instance/task 全量或按状态、
 *       history 平均耗时、audit 时间范围查询)在内存里汇总；</li>
 *   <li>historyRepo / auditLogRepo 允许为 null —— 未启用时对应指标返回空，
 *       而不是抛异常或返回误导性的 0。</li>
 * </ul>
 */
public class MonitoringService {

    private final InstanceRepository instanceRepo;
    private final TaskRepository taskRepo;
    /** 可为 null：未启用历史仓储时不产出耗时/瓶颈指标。 */
    private final HistoryRepository historyRepo;
    /** 可为 null：未启用审计仓储时不产出超时事件统计。 */
    private final AuditLogRepository auditLogRepo;

    public MonitoringService(InstanceRepository instanceRepo,
                             TaskRepository taskRepo,
                             HistoryRepository historyRepo,
                             AuditLogRepository auditLogRepo) {
        this.instanceRepo = instanceRepo;
        this.taskRepo = taskRepo;
        this.historyRepo = historyRepo;
        this.auditLogRepo = auditLogRepo;
    }

    /**
     * 生成仪表盘快照。
     *
     * @param bottleneckTopN 瓶颈节点取前 N 个(按平均耗时降序)
     */
    public DashboardMetrics snapshot(int bottleneckTopN) {
        return snapshot(bottleneckTopN, null);
    }

    /**
     * 生成指定租户的仪表盘快照(多租户隔离)。
     *
     * @param tenantId 租户 ID；null 表示统计全部(兼容老数据)
     */
    public DashboardMetrics snapshot(int bottleneckTopN, String tenantId) {
        // 实例分布:走 GROUP BY 聚合，避免 findAll 逐实例重建 Token/Task/变量(JPA 下 N 次子查询+反射)
        List<ProcessStatusCount> groups =
                instanceRepo.countGroupByProcessAndStatus(tenantId);
        long totalInstances = 0;
        Map<String, Long> byStatus = new LinkedHashMap<>();
        Map<String, Map<String, Long>> byProcessStatus = new LinkedHashMap<>();
        Map<String, Long> byProcessTotal = new LinkedHashMap<>();
        for (ProcessStatusCount g : groups) {
            totalInstances += g.count();
            String status = g.status() == null ? "UNKNOWN" : g.status().name();
            byStatus.merge(status, g.count(), Long::sum);
            byProcessStatus.computeIfAbsent(g.processKey(), k -> new LinkedHashMap<>())
                    .merge(status, g.count(), Long::sum);
            byProcessTotal.merge(g.processKey(), g.count(), Long::sum);
        }
        List<DashboardMetrics.ProcessInstanceSummary> processSummaries = new ArrayList<>();
        for (Map.Entry<String, Long> e : byProcessTotal.entrySet()) {
            processSummaries.add(new DashboardMetrics.ProcessInstanceSummary(
                    e.getKey(), e.getValue(), byProcessStatus.get(e.getKey())));
        }
        processSummaries.sort(Comparator.comparingLong(DashboardMetrics.ProcessInstanceSummary::total).reversed());

        // 待办:总数走 COUNT(带租户)；按 (流程,节点) 分布只对涉及的实例回查 processKey(缓存,量=待办实例数)
        long pendingTasks = taskRepo.countPending(tenantId);
        List<TaskInstance> pending = taskRepo.findByStatus(TaskStatus.PENDING);
        Map<String, String> instKey = new HashMap<>();
        Map<String, long[]> backlog = new LinkedHashMap<>(); // processKey|nodeId -> count
        for (TaskInstance t : pending) {
            // 租户过滤：tenantId 非空时只统计该租户的待办
            if (tenantId != null && !tenantId.equals(t.getTenantId())) {
                continue;
            }
            String key = instKey.computeIfAbsent(t.getInstanceId(), id -> {
                try {
                    return instanceRepo.findById(id).getProcessKey();
                } catch (RuntimeException ex) {
                    return "?";
                }
            });
            String groupKey = key + "\u0000" + t.getNodeId();
            backlog.computeIfAbsent(groupKey, k -> new long[1])[0]++;
        }
        List<DashboardMetrics.NodeBacklog> pendingByNode = new ArrayList<>();
        for (Map.Entry<String, long[]> e : backlog.entrySet()) {
            String[] pk = e.getKey().split("\u0000", 2);
            pendingByNode.add(new DashboardMetrics.NodeBacklog(pk[0], pk[1], e.getValue()[0]));
        }
        pendingByNode.sort(Comparator.comparingLong(DashboardMetrics.NodeBacklog::pendingCount).reversed());

        // 瓶颈:对当前有积压的 (流程,节点) 求已闭合活动平均耗时(降序 TopN)
        List<DashboardMetrics.NodeDuration> slowest = new ArrayList<>();
        if (historyRepo != null) {
            for (DashboardMetrics.NodeBacklog nb : pendingByNode) {
                OptionalDouble avg = historyRepo.averageClosedDuration(nb.processKey(), nb.nodeId());
                if (avg.isPresent()) {
                    slowest.add(new DashboardMetrics.NodeDuration(nb.processKey(), nb.nodeId(), avg.getAsDouble()));
                }
            }
            slowest.sort(Comparator.comparingDouble(DashboardMetrics.NodeDuration::avgDurationMillis).reversed());
            if (bottleneckTopN >= 0 && slowest.size() > bottleneckTopN) {
                slowest = new ArrayList<>(slowest.subList(0, bottleneckTopN));
            }
        }

        // 超时自动处理事件统计(审计里以 TIMEOUT_ 开头的事件)
        Map<String, Long> timeoutEvents = new LinkedHashMap<>();
        if (auditLogRepo != null) {
            for (EventTypeCount c :
                    auditLogRepo.countGroupByEventTypePrefix("TIMEOUT_")) {
                timeoutEvents.put(c.eventType().name(), c.count());
            }
        }

        return new DashboardMetrics(
                totalInstances,
                byStatus,
                processSummaries,
                pendingTasks,
                pendingByNode,
                slowest,
                timeoutEvents,
                System.currentTimeMillis()
        );
    }

    /** 默认瓶颈 TopN=10。 */
    public DashboardMetrics snapshot() {
        return snapshot(10);
    }
}
