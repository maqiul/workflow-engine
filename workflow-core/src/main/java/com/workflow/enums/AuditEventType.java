package com.workflow.enums;

/**
 * 审计事件类型 - 记录引擎关键状态变更
 */
public enum AuditEventType {
    /** 流程发起 */
    PROCESS_STARTED,
    /** 任务完成 */
    TASK_COMPLETED,
    /** 任务驳回 */
    TASK_REJECTED,
    /** 任务转办 */
    TASK_TRANSFERRED,
    /** 流程终止 */
    PROCESS_TERMINATED,
    /** 流程挂起 */
    PROCESS_SUSPENDED,
    /** 流程恢复 */
    PROCESS_RESUMED,
    /** 超时自动通过 */
    TIMEOUT_AUTO_APPROVED,
    /** 超时自动驳回 */
    TIMEOUT_AUTO_REJECTED,
    /** 超时自动终止 */
    TIMEOUT_AUTO_TERMINATED,
    /** 超时自动转办 */
    TIMEOUT_AUTO_TRANSFERRED,
    /** 流程撤回 - 发起人撤回未审批的申请 */
    PROCESS_WITHDRAWN,
    /** 流程跳转 - 跳转到任意节点 */
    PROCESS_JUMPED,
    /** 加签 - 多实例会签节点新增审批人 */
    SIGN_ADDED,
    /** 减签 - 多实例会签节点移除审批人 */
    SIGN_REMOVED,
    /** 实例迁移 - 运行中实例迁移到新版本流程定义 */
    INSTANCE_MIGRATED,
    /** 流程变量变更 - 运行期通过引擎 API 修改变量 */
    VARIABLE_UPDATED
}
