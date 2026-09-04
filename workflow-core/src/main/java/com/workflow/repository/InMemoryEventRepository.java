package com.workflow.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存版事件仓储
 */
public class InMemoryEventRepository implements EventRepository {
    
    // messageName:correlationKey -> List<instanceId>
    private final Map<String, List<String>> messageEvents = new ConcurrentHashMap<>();
    
    // signalName -> List<instanceId>
    private final Map<String, List<String>> signalEvents = new ConcurrentHashMap<>();
    
    // instanceId:nodeId -> TimerEvent
    private final Map<String, TimerEvent> timerEvents = new ConcurrentHashMap<>();
    
    // instanceId:nodeId -> triggerTime
    private final Map<String, Instant> timerTriggerTimes = new ConcurrentHashMap<>();
    
    @Override
    public void saveMessageEvent(String instanceId, String nodeId, String messageName, String correlationKey) {
        String key = messageName + ":" + correlationKey;
        messageEvents.computeIfAbsent(key, k -> new ArrayList<>()).add(instanceId);
    }
    
    @Override
    public List<String> triggerMessageEvent(String messageName, String correlationKey) {
        String key = messageName + ":" + correlationKey;
        List<String> instances = messageEvents.remove(key);
        return instances != null ? instances : List.of();
    }
    
    @Override
    public void saveSignalEvent(String instanceId, String nodeId, String signalName) {
        signalEvents.computeIfAbsent(signalName, k -> new ArrayList<>()).add(instanceId);
    }
    
    @Override
    public List<String> triggerSignalEvent(String signalName) {
        List<String> instances = signalEvents.remove(signalName);
        return instances != null ? instances : List.of();
    }
    
    @Override
    public void saveTimerEvent(String instanceId, String nodeId, Instant triggerTime, boolean interrupting) {
        String key = instanceId + ":" + nodeId;
        timerEvents.put(key, new TimerEvent(instanceId, nodeId, interrupting));
        timerTriggerTimes.put(key, triggerTime);
    }
    
    @Override
    public List<TimerEvent> getExpiredTimers(Instant now) {
        return timerTriggerTimes.entrySet().stream()
            .filter(e -> !e.getValue().isAfter(now))
            .map(e -> timerEvents.get(e.getKey()))
            .collect(Collectors.toList());
    }
    
    @Override
    public void cancelTimer(String instanceId, String nodeId) {
        String key = instanceId + ":" + nodeId;
        timerEvents.remove(key);
        timerTriggerTimes.remove(key);
    }
    
    @Override
    public void cancelEvents(String instanceId) {
        // 取消消息事件
        messageEvents.values().forEach(list -> list.remove(instanceId));
        messageEvents.entrySet().removeIf(e -> e.getValue().isEmpty());
        
        // 取消信号事件
        signalEvents.values().forEach(list -> list.remove(instanceId));
        signalEvents.entrySet().removeIf(e -> e.getValue().isEmpty());
        
        // 取消定时器事件
        timerEvents.entrySet().removeIf(e -> e.getValue().instanceId().equals(instanceId));
        timerTriggerTimes.entrySet().removeIf(e -> {
            String key = e.getKey();
            return key.startsWith(instanceId + ":");
        });
    }
}
