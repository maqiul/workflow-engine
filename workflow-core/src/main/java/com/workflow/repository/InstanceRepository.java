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
}