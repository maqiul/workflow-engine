package com.workflow.repository;

import com.workflow.enums.TaskStatus;
import com.workflow.runtime.TaskInstance;

import java.util.List;

/**
 * 任务仓储
 */
public interface TaskRepository {

    /** 保存(新增或覆盖) */
    void save(TaskInstance task);

    /** 批量保存任务（默认实现：逐个保存，子类可优化为真正的批量插入） */
    default void saveBatch(List<TaskInstance> tasks) {
        for (TaskInstance task : tasks) {
            save(task);
        }
    }

    /** 按 id 查找 */
    TaskInstance findById(String taskId);

    /** 列出实例下的所有任务 */
    List<TaskInstance> findByInstanceId(String instanceId);

    /** 列出某用户作为候选人的待办任务 */
    List<TaskInstance> findPendingByUser(String userId);

    /**
     * 列出待办(PENDING)任务，条件是<b>候选组</b>命中给定组名。
     *
     * <p><b>与 {@link #findPendingByUser} 的分工</b>：那个查「组展开后的具体人」，
     * 这个查「任务当初来自哪个组」。两者都要，因为展开是<b>快照</b> ——
     * {@link com.workflow.definition.Candidate#withExpandedUsers} 会保留原始 {@code groupIds}：
     * <ul>
     *   <li>组里后来新加了人，旧任务不该突然冒到新人面前（快照的正当性），
     *       但组管理员仍需要看到「我们组名下还挂着哪些待办」；</li>
     *   <li>调用方没注入 {@code GroupResolver} 时组保持未展开 —— 这类任务
     *       {@code userIds} 为空、对任何人都不可办理，{@code findPendingByUser}
     *       对它完全失明；只有按组查能看到它们，进而用 {@code adminTransferTask} 兜底。</li>
     * </ul>
     *
     * <p><b>为什么是 default 而不是抽象</b>：三套官方仓储的
     * {@link #findPaged(TaskFilter)} 都已下推，这个 default 天然继承下推能力；
     * {@link #findPendingByUser} 是抽象方法属于既有的历史约定（强制实现），
     * 新方法不必复制那份重复。
     */
    default List<TaskInstance> findPendingByGroup(String groupId) {
        return findPaged(TaskFilter.create()
                .status(TaskStatus.PENDING)
                .candidateGroup(groupId));
    }

    /** 列出所有任务（用于复杂查询） */
    default List<TaskInstance> findAll() {
        throw new UnsupportedOperationException("findAll not implemented");
    }

    /**
     * 条件下推的分页查询 —— 契约：返回<b>已完成过滤、排序、分页</b>的结果。
     *
     * <p>调用方（{@link com.workflow.query.TaskQuery}）只把 SQL 能表达的条件放进
     * {@link TaskFilter}，剩下的实例条件由它自己在内存里补。所以本方法的职责很明确：
     * 把 filter 里的条件、排序、分页尽可能交给数据库，然后用
     * {@link TaskFilter#matches} 精筛兜底。
     *
     * <p>默认实现退回「全量 + 内存过滤」，结果与下推版完全一致，只是慢。
     * 三套官方仓储（内存 / JPA / MyBatis）都已覆写为下推版；
     * 这个 default 是留给第三方实现的兼容路径，不是可选的偷懒余地。
     */
    default List<TaskInstance> findPaged(TaskFilter filter) {
        return filter.finish(findAll().stream().filter(filter::matches).toList());
    }

    /**
     * 同条件下的命中总数 —— <b>不受 {@link TaskFilter#getLimit()} / {@code offset} 影响</b>。
     *
     * <p>分页组件要的是「一共几条」而不是「这一页几条」，所以计数绝不能经分页逻辑，
     * 否则总页数会随每页大小漂移。
     */
    default long countByFilter(TaskFilter filter) {
        return findAll().stream().filter(filter::matches).count();
    }

    /** 按节点 ID 查找任务 */
    default List<TaskInstance> findByNodeId(String nodeId) {
        throw new UnsupportedOperationException("findByNodeId not implemented");
    }

    /** 按状态查找任务 */
    default List<TaskInstance> findByStatus(TaskStatus status) {
        throw new UnsupportedOperationException("findByStatus not implemented");
    }

    /**
     * 待办(PENDING)任务总数。
     *
     * <p>抽象方法，强制三套仓储实现：监控只要计数时不必像
     * {@code findByStatus(PENDING)} 那样把每个待办实体全量重建。
     */
    long countPending();

    /**
     * 待办(PENDING)任务总数（多租户隔离）。
     *
     * <p>tenantId 为 null 时不过滤租户（兼容老数据）。
     */
    default long countPending(String tenantId) {
        // 默认实现：忽略租户，退化为无租户版本（向后兼容）
        return countPending();
    }
}