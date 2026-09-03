package com.workflow.enums;

/**
 * 流程实例状态
 */
public enum InstanceStatus {
    /** 运行中 */
    RUNNING,
    /** 暂停 - 用户主动挂起 */
    SUSPENDED,
    /** 已完成 - 流转到 END 节点 */
    COMPLETED,
    /** 已终止 - 用户主动终止 */
    TERMINATED
}