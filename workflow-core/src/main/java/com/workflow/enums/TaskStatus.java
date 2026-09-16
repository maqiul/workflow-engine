package com.workflow.enums;

/**
 * 任务状态
 */
public enum TaskStatus {
    /** 待办 - 等待候选人操作 */
    PENDING,
    /** 已完成 - 候选人同意 */
    COMPLETED,
    /** 已驳回 */
    REJECTED,
    /** 已转办 - 该任务被转给其他人,本任务关闭 */
    TRANSFERRED,
    /** 被终止 */
    TERMINATED,
    /** 已撤回 - 发起人撤回了未审批的申请 */
    WITHDRAWN,
    /** 已委派 - 办理人把任务委派给他人代办；他人 resolveTask 后回原办理人继续（G-09 回签） */
    DELEGATED
}