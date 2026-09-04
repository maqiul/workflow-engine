package com.workflow.repository;

import java.time.Instant;
import java.util.List;

/**
 * 事件仓储接口
 * 
 * 管理流程中的事件等待和触发：
 * - 消息事件：等待外部消息，通过 correlationKey 匹配
 * - 信号事件：广播式，多个实例可以监听
 * - 定时器事件：基于时间的触发
 */
public interface EventRepository {
    
    /**
     * 保存等待中的消息事件
     * 
     * @param instanceId 流程实例 ID
     * @param nodeId 节点 ID
     * @param messageName 消息名称
     * @param correlationKey 关联键（用于匹配消息到实例）
     */
    void saveMessageEvent(String instanceId, String nodeId, String messageName, String correlationKey);
    
    /**
     * 触发消息事件
     * 
     * @param messageName 消息名称
     * @param correlationKey 关联键
     * @return 匹配到的实例 ID 列表（可能为空）
     */
    List<String> triggerMessageEvent(String messageName, String correlationKey);
    
    /**
     * 保存等待中的信号事件
     * 
     * @param instanceId 流程实例 ID
     * @param nodeId 节点 ID
     * @param signalName 信号名称
     */
    void saveSignalEvent(String instanceId, String nodeId, String signalName);
    
    /**
     * 触发信号事件
     * 
     * @param signalName 信号名称
     * @return 匹配到的实例 ID 列表（可能为空）
     */
    List<String> triggerSignalEvent(String signalName);
    
    /**
     * 保存等待中的定时器事件
     * 
     * @param instanceId 流程实例 ID
     * @param nodeId 节点 ID
     * @param triggerTime 触发时间
     * @param interrupting 是否中断任务
     */
    void saveTimerEvent(String instanceId, String nodeId, Instant triggerTime, boolean interrupting);
    
    /**
     * 获取到期的定时器事件
     * 
     * @param now 当前时间
     * @return 到期的定时器事件列表
     */
    List<TimerEvent> getExpiredTimers(Instant now);
    
    /**
     * 取消指定的定时器事件
     * 
     * @param instanceId 流程实例 ID
     * @param nodeId 节点 ID
     */
    void cancelTimer(String instanceId, String nodeId);
    
    /**
     * 取消实例的所有事件（当流程结束或任务完成时调用）
     * 
     * @param instanceId 流程实例 ID
     */
    void cancelEvents(String instanceId);
    
    /**
     * 定时器事件
     */
    record TimerEvent(String instanceId, String nodeId, boolean interrupting) {}
}
