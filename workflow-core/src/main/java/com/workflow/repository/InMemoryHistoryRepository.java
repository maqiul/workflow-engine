package com.workflow.repository;

import com.workflow.runtime.HistoricActivityInstance;
import com.workflow.tx.TransactionContext;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存版历史活动仓储。
 *
 * <p>与 {@link InMemoryInstanceRepository} / {@link InMemoryTaskRepository} 保持
 * 同一套契约：<b>拷贝语义</b>（save 存副本、find 还副本）+ <b>事务 undo</b>
 * （登记 before-image，回滚时逆序恢复）。
 *
 * <p>拷贝语义在这里尤其重要：{@link HistoricActivityInstance#close} 会原地改
 * {@code endTime}，若仓库与调用方共享引用，事务回滚时那份"已闭合"的状态改不回来
 * （引用的字段早就脏了），报表里就会留下凭空多出的耗时。
 */
public class InMemoryHistoryRepository implements HistoryRepository {

    private static final String UNDO_PREFIX = "histAct:";

    private final ConcurrentHashMap<String, HistoricActivityInstance> byId = new ConcurrentHashMap<>();

    @Override
    public void save(HistoricActivityInstance activity) {
        Objects.requireNonNull(activity);
        String id = activity.getId();
        // 首次写入前登记 before-image；TransactionContext 只保留最早一份
        TransactionContext.recordUndo(UNDO_PREFIX + id, restoreAction(id, byId.get(id)));
        byId.put(id, activity.copy());
    }

    private Runnable restoreAction(String id, HistoricActivityInstance before) {
        return () -> {
            if (before == null) {
                byId.remove(id);
            } else {
                byId.put(id, before);
            }
        };
    }

    @Override
    public HistoricActivityInstance findOpen(String instanceId, String tokenId, String activityId) {
        return byId.values().stream()
                .filter(HistoricActivityInstance::isOpen)
                .filter(a -> a.getInstanceId().equals(instanceId)
                        && a.getTokenId().equals(tokenId)
                        && a.getActivityId().equals(activityId))
                .findFirst()
                .map(HistoricActivityInstance::copy)
                .orElse(null);
    }

    @Override
    public List<HistoricActivityInstance> findByInstanceId(String instanceId) {
        return byId.values().stream()
                .filter(a -> a.getInstanceId().equals(instanceId))
                .sorted(byStartTime())
                .map(HistoricActivityInstance::copy)
                .collect(Collectors.toList());
    }

    @Override
    public List<HistoricActivityInstance> findByActivity(String processKey, String activityId) {
        return byId.values().stream()
                .filter(a -> a.getProcessKey().equals(processKey)
                        && a.getActivityId().equals(activityId))
                .sorted(byStartTime())
                .map(HistoricActivityInstance::copy)
                .collect(Collectors.toList());
    }

    @Override
    public OptionalDouble averageClosedDuration(String processKey, String activityId) {
        // 未闭合活动不计入：其"耗时"随查询时刻漂移，会让同一报表每次刷新结果不同
        return byId.values().stream()
                .filter(a -> a.getProcessKey().equals(processKey)
                        && a.getActivityId().equals(activityId))
                .filter(a -> !a.isOpen() && a.getDuration() != null)
                .mapToLong(HistoricActivityInstance::getDuration)
                .average();
    }

    @Override
    public int deleteClosedBefore(long cutoffMillis) {
        List<String> victims = byId.values().stream()
                .filter(a -> !a.isOpen())
                .filter(a -> a.getStartTime() < cutoffMillis)
                .map(HistoricActivityInstance::getId)
                .collect(Collectors.toList());
        int removed = 0;
        for (String id : victims) {
            HistoricActivityInstance before = byId.get(id);
            // 逐条登记 undo，回滚时可按原样放回
            TransactionContext.recordUndo(UNDO_PREFIX + id, restoreAction(id, before));
            if (byId.remove(id) != null) {
                removed++;
            }
        }
        return removed;
    }

    private static Comparator<HistoricActivityInstance> byStartTime() {
        // 毫秒级 startTime 不足以区分同一毫秒内推进的节点，UUID 又是随机序 ——
        // 必须用 seq 作次级键，否则报表还原出的路径顺序是错的。
        return Comparator.comparingLong(HistoricActivityInstance::getStartTime)
                .thenComparingLong(HistoricActivityInstance::getSeq);
    }
}
