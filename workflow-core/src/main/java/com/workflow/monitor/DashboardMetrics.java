package com.workflow.monitor;

import java.util.List;
import java.util.Map;

/**
 * 监控仪表盘快照 —— 引擎运行态的一次性聚合视图。
 *
 * <p>作为基础引擎能力，只负责把已有仓储数据(instance/task/history/audit)聚合成结构化指标；
 * 具体看板 UI 由业务系统据此自建。所有指标均可由现成仓储方法得出，不新增 SQL 聚合层。
 *
 * @param totalInstances   流程实例总数
 * @param instancesByStatus 实例按状态分布(状态名 -> 数量)
 * @param instancesByProcess 实例按流程 key 分布
 * @param pendingTasks     当前待办任务总数
 * @param pendingByNode    待办按 (流程,节点) 分布
 * @param slowestNodes     节点平均耗时 TopN(瓶颈,降序)
 * @param timeoutEvents    超时自动处理事件计数(审计事件名 -> 次数)
 * @param generatedAt      快照生成时间(毫秒)
 */
public record DashboardMetrics(
        long totalInstances,
        Map<String, Long> instancesByStatus,
        List<ProcessInstanceSummary> instancesByProcess,
        long pendingTasks,
        List<NodeBacklog> pendingByNode,
        List<NodeDuration> slowestNodes,
        Map<String, Long> timeoutEvents,
        long generatedAt
) {

    /** 某流程 key 的实例分布。 */
    public record ProcessInstanceSummary(String processKey, long total, Map<String, Long> byStatus) { }

    /** 某 (流程,节点) 的待办堆积。 */
    public record NodeBacklog(String processKey, String nodeId, long pendingCount) { }

    /** 某 (流程,节点) 已闭合活动的平均耗时(毫秒)。 */
    public record NodeDuration(String processKey, String nodeId, double avgDurationMillis) { }
}
