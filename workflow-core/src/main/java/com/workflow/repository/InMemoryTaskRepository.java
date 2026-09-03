package com.workflow.repository;

import com.workflow.concurrency.WorkflowConflictException;
import com.workflow.enums.TaskStatus;
import com.workflow.runtime.TaskInstance;
import com.workflow.tx.TransactionContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存版 TaskRepository。
 *
 * <p><b>拷贝语义（关键）</b>：{@code save} 存入的是入参的独立副本，{@code findById}
 * 返回的也是库内对象的独立副本 —— 调用方永远拿不到、也改不动仓库持有的那份。
 * 这样事务的 before-image 才真正等于「事务开始前的状态」；否则像旧实现那样
 * 存活引用、引擎原地改状态，登记 undo 时对象早已是脏的，回滚形同虚设。
 *
 * <p>顺带修正 {@code save} 幂等：同一 taskId 重复保存不再在实例索引里堆积重复条目。
 *
 * <p>单 JVM 并发正确性由引擎的分段锁保证；因此这里默认<b>不</b>启用 revision 校验
 * （内存版无跨进程竞争者）。数据库版实现才必须开启。
 */
public class InMemoryTaskRepository implements TaskRepository {

    private static final String UNDO_PREFIX = "task:";

    private final ConcurrentHashMap<String, TaskInstance> byId = new ConcurrentHashMap<>();
    /** 乐观锁开关；内存实现默认关闭，见类注释。 */
    private final boolean optimisticLock;

    public InMemoryTaskRepository() {
        this(false);
    }

    public InMemoryTaskRepository(boolean optimisticLock) {
        this.optimisticLock = optimisticLock;
    }

    @Override
    public void save(TaskInstance task) {
        Objects.requireNonNull(task);
        String id = task.getId();
        TaskInstance incoming = task.copy();

        // 首次写入前登记 before-image；TransactionContext 内部保留最早一份，
        // 保证嵌套 save 不会把「事务中途的脏值」当成回滚目标。
        TransactionContext.recordUndo(UNDO_PREFIX + id, restoreAction(id, byId.get(id)));

        if (optimisticLock) {
            TaskInstance current = byId.get(id);
            if (current != null && current.getRevision() != incoming.getRevision()) {
                throw new WorkflowConflictException(
                        "任务并发冲突: task=" + id + " 期望 revision=" + incoming.getRevision()
                                + " 实际=" + current.getRevision(), id);
            }
            incoming.setRevision(current == null ? 1L : current.getRevision() + 1);
        }
        byId.put(id, incoming);
    }

    /** 恢复动作：旧值存在则放回，不存在（本次为新增）则删除。 */
    private java.lang.Runnable restoreAction(String id, TaskInstance before) {
        return () -> {
            if (before == null) {
                byId.remove(id);
            } else {
                byId.put(id, before);
            }
        };
    }

    @Override
    public TaskInstance findById(String taskId) {
        TaskInstance t = byId.get(taskId);
        if (t == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
        return t.copy();
    }

    @Override
    public List<TaskInstance> findByInstanceId(String instanceId) {
        return byId.values().stream()
                .filter(t -> t.getInstanceId().equals(instanceId))
                .map(TaskInstance::copy)
                .collect(Collectors.toList());
    }

    @Override
    public List<TaskInstance> findPendingByUser(String userId) {
        return byId.values().stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING)
                .filter(t -> t.getCandidate().getUserIds().contains(userId))
                .map(TaskInstance::copy)
                .collect(Collectors.toList());
    }

    @Override
    public List<TaskInstance> findAll() {
        List<TaskInstance> out = new ArrayList<>(byId.size());
        for (TaskInstance t : byId.values()) {
            out.add(t.copy());
        }
        return out;
    }

    @Override
    public List<TaskInstance> findByNodeId(String nodeId) {
        return byId.values().stream()
                .filter(t -> nodeId.equals(t.getNodeId()))
                .map(TaskInstance::copy)
                .collect(Collectors.toList());
    }

    @Override
    public List<TaskInstance> findByStatus(TaskStatus status) {
        return byId.values().stream()
                .filter(t -> t.getStatus() == status)
                .map(TaskInstance::copy)
                .collect(Collectors.toList());
    }
}
