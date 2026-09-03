package com.workflow.repository;

import com.workflow.runtime.HistoricActivityInstance;
import com.workflow.runtime.HistoricTaskInstance;
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
    /** 以 taskId 作键：一个任务只该有一条历史，重复写天然覆盖而非新增。 */
    private final ConcurrentHashMap<String, HistoricTaskInstance> taskById = new ConcurrentHashMap<>();

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

    // ========== 历史任务 ==========
    //
    // HistoricTaskInstance 完全不可变，因此 undo 的 before-image 直接持有引用即可 ——
    // 不像活动记录那样有 volatile endTime 会被原地改写，需要显式 copy。

    private static final String TASK_UNDO_PREFIX = "histTask:";

    @Override
    public void saveTask(HistoricTaskInstance task) {
        Objects.requireNonNull(task);
        String id = task.getTaskId();
        TransactionContext.recordUndo(TASK_UNDO_PREFIX + id, taskRestoreAction(id, taskById.get(id)));
        taskById.put(id, task);
    }

    private Runnable taskRestoreAction(String id, HistoricTaskInstance before) {
        return () -> {
            if (before == null) {
                taskById.remove(id);
            } else {
                taskById.put(id, before);
            }
        };
    }

    @Override
    public List<HistoricTaskInstance> findTasksByInstanceId(String instanceId) {
        return taskById.values().stream()
                .filter(t -> t.getInstanceId().equals(instanceId))
                .sorted(byEndTime())
                .collect(Collectors.toList());
    }

    @Override
    public List<HistoricTaskInstance> findTasksInvolving(String userId) {
        return taskById.values().stream()
                .filter(t -> t.involves(userId))
                .sorted(byEndTime())
                .collect(Collectors.toList());
    }

    @Override
    public OptionalDouble averageClosedTaskDuration(String processKey, String nodeId) {
        // 历史里只有已落定的任务，无需再过滤未闭合
        return taskById.values().stream()
                .filter(t -> t.getProcessKey().equals(processKey) && t.getNodeId().equals(nodeId))
                .mapToLong(HistoricTaskInstance::getDuration)
                .average();
    }

    @Override
    public int deleteTasksBefore(long cutoffMillis) {
        List<String> victims = taskById.values().stream()
                .filter(t -> t.getEndTime() < cutoffMillis)
                .map(HistoricTaskInstance::getTaskId)
                .collect(Collectors.toList());
        int removed = 0;
        for (String id : victims) {
            TransactionContext.recordUndo(TASK_UNDO_PREFIX + id, taskRestoreAction(id, taskById.get(id)));
            if (taskById.remove(id) != null) {
                removed++;
            }
        }
        return removed;
    }

    private static Comparator<HistoricTaskInstance> byEndTime() {
        // 同毫秒完成的任务靠随机 UUID 定序会让审批链顺序失真，必须用 seq
        return Comparator.comparingLong(HistoricTaskInstance::getEndTime)
                .thenComparingLong(HistoricTaskInstance::getSeq);
    }

    private static Comparator<HistoricActivityInstance> byStartTime() {
        // 毫秒级 startTime 不足以区分同一毫秒内推进的节点，UUID 又是随机序 ——
        // 必须用 seq 作次级键，否则报表还原出的路径顺序是错的。
        return Comparator.comparingLong(HistoricActivityInstance::getStartTime)
                .thenComparingLong(HistoricActivityInstance::getSeq);
    }
}
