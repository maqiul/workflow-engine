package com.workflow.enums;

/**
 * 超时策略 - UserTask 节点超时后的自动处理动作
 *
 * 取值说明:
 *  - NONE           不启用超时(默认)
 *  - AUTO_APPROVE   超时后自动通过(推进流程)
 *  - AUTO_REJECT    超时后自动驳回(退回上一 UserTask)
 *  - AUTO_TERMINATE 超时后自动终止整个实例
 *  - AUTO_TRANSFER  超时后自动转办给指定用户(需配置 timeoutTargetUserId)
 */
public enum TimeoutPolicy {
    NONE,
    AUTO_APPROVE,
    AUTO_REJECT,
    AUTO_TERMINATE,
    AUTO_TRANSFER
}
