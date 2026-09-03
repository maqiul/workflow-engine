package com.workflow.enums;

/**
 * Token 状态 - 用于并行网关的多路追踪
 */
public enum TokenStatus {
    /** 活跃 - 正在某个节点上 */
    ACTIVE,
    /** 已消耗 - 流过网关/结束节点 */
    CONSUMED
}