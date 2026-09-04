package com.workflow.enums;

/**
 * 节点类型
 */
public enum NodeType {
    /** 起始节点 - 每个流程有且仅有一个 */
    START,
    /** 结束节点 - 流程汇聚到此处表示完成 */
    END,
    /** 用户任务节点 - 需要人工审批 */
    USER_TASK,
    /** 排他网关 - 根据条件选择一条分支 */
    EXCLUSIVE_GATEWAY,
    /** 并行网关 - 分裂多条同时推进,或汇聚等待全部完成 */
    PARALLEL_GATEWAY,
    /** 子流程节点 - 引用另一个流程定义,执行完子流程后继续主流程 */
    SUB_PROCESS,
    /** 动态多实例节点 - 运行时根据变量动态创建多个任务 */
    DYNAMIC_PARALLEL,
    /** 消息事件 - 等待外部消息触发 */
    MESSAGE_EVENT,
    /** 信号事件 - 广播式信号，多个流程可以监听 */
    SIGNAL_EVENT,
    /** 定时器边界事件 - 附加在任务节点上，超时后触发 */
    TIMER_BOUNDARY
}