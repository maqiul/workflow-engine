package com.workflow.repository;

import com.workflow.enums.InstanceStatus;
import com.workflow.runtime.ProcessInstance;

import java.util.List;

/**
 * 流程实例仓储
 */
public interface InstanceRepository {

    /** 保存(新增或覆盖) */
    void save(ProcessInstance instance);

    /** 批量保存流程实例（默认实现：逐个保存，子类可优化为真正的批量插入） */
    default void saveBatch(List<ProcessInstance> instances) {
        for (ProcessInstance instance : instances) {
            save(instance);
        }
    }

    /** 按 id 查找 */
    ProcessInstance findById(String instanceId);

    /** 删除 */
    void delete(String instanceId);

    /** 按流程定义 key 查找实例 */
    default List<ProcessInstance> findByProcessKey(String processKey) {
        throw new UnsupportedOperationException("findByProcessKey not implemented");
    }

    /** 按状态查找实例 */
    default List<ProcessInstance> findByStatus(InstanceStatus status) {
        throw new UnsupportedOperationException("findByStatus not implemented");
    }

    /** 查找所有实例（用于历史查询） */
    default List<ProcessInstance> findAll() {
        throw new UnsupportedOperationException("findAll not implemented");
    }

    /** 按流程定义 key 和版本查找实例 */
    default List<ProcessInstance> findByProcessKeyAndVersion(String processKey, int version) {
        throw new UnsupportedOperationException("findByProcessKeyAndVersion not implemented");
    }

    /**
     * 按「流程 key + 状态」分组计数。
     *
     * <p>刻意声明为<b>抽象方法</b>强制三套仓储各自实现（历史教训：用 default 抛
     * {@code UnsupportedOperationException} 会让真实库实现缺席、内存测试全绿）。
     * 监控仪表盘用它替代 {@code findAll()} —— 后者会为每个实例重建 Token/Task/变量，
     * 分布统计只需 {@code GROUP BY process_key, status} 的轻量结果。
     */
    List<ProcessStatusCount> countGroupByProcessAndStatus();

    /**
     * 按「流程 key + 状态 + 租户」分组计数（多租户隔离）。
     *
     * <p>tenantId 为 null 时不过滤租户（兼容老数据）。
     */
    default List<ProcessStatusCount> countGroupByProcessAndStatus(String tenantId) {
        // 默认实现：忽略租户，退化为无租户版本（向后兼容）
        return countGroupByProcessAndStatus();
    }
}