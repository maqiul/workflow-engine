package com.workflow.persistence.mybatis.repository;

import com.workflow.persistence.mybatis.MybatisPersistence;
import com.workflow.persistence.mybatis.entity.WfEventEntity;
import com.workflow.persistence.mybatis.mapper.WfEventMapper;
import com.workflow.repository.EventRepository;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MyBatis 版事件仓储
 */
public class MybatisEventRepository implements EventRepository {

    private final MybatisPersistence mybatis;

    public MybatisEventRepository(MybatisPersistence mybatis) {
        this.mybatis = mybatis;
    }

    @Override
    public void saveMessageEvent(String instanceId, String nodeId, String messageName, String correlationKey) {
        String key = messageName + ":" + correlationKey;
        mybatis.inSession(session -> {
            WfEventMapper mapper = session.getMapper(WfEventMapper.class);
            WfEventEntity entity = mapper.findByKey(instanceId, nodeId);
            if (entity == null) {
                entity = new WfEventEntity();
                entity.setInstanceId(instanceId);
                entity.setNodeId(nodeId);
                entity.setEventType("MESSAGE");
                entity.setMessageKey(key);
                mapper.insert(entity);
            } else {
                entity.setEventType("MESSAGE");
                entity.setMessageKey(key);
                mapper.update(entity);
            }
            return null;
        });
    }

    @Override
    public List<String> triggerMessageEvent(String messageName, String correlationKey) {
        String key = messageName + ":" + correlationKey;
        return mybatis.inSession(session -> {
            WfEventMapper mapper = session.getMapper(WfEventMapper.class);
            List<WfEventEntity> list = mapper.findByMessageKey("MESSAGE", key);
            List<String> instances = list.stream()
                    .map(WfEventEntity::getInstanceId)
                    .collect(Collectors.toList());
            for (WfEventEntity e : list) {
                mapper.deleteByKey(e.getInstanceId(), e.getNodeId());
            }
            return instances;
        });
    }

    @Override
    public void saveSignalEvent(String instanceId, String nodeId, String signalName) {
        mybatis.inSession(session -> {
            WfEventMapper mapper = session.getMapper(WfEventMapper.class);
            WfEventEntity entity = mapper.findByKey(instanceId, nodeId);
            if (entity == null) {
                entity = new WfEventEntity();
                entity.setInstanceId(instanceId);
                entity.setNodeId(nodeId);
                entity.setEventType("SIGNAL");
                entity.setSignalName(signalName);
                mapper.insert(entity);
            } else {
                entity.setEventType("SIGNAL");
                entity.setSignalName(signalName);
                mapper.update(entity);
            }
            return null;
        });
    }

    @Override
    public List<String> triggerSignalEvent(String signalName) {
        return mybatis.inSession(session -> {
            WfEventMapper mapper = session.getMapper(WfEventMapper.class);
            List<WfEventEntity> list = mapper.findBySignalName("SIGNAL", signalName);
            List<String> instances = list.stream()
                    .map(WfEventEntity::getInstanceId)
                    .collect(Collectors.toList());
            for (WfEventEntity e : list) {
                mapper.deleteByKey(e.getInstanceId(), e.getNodeId());
            }
            return instances;
        });
    }

    @Override
    public void saveTimerEvent(String instanceId, String nodeId, Instant triggerTime, boolean interrupting) {
        mybatis.inSession(session -> {
            WfEventMapper mapper = session.getMapper(WfEventMapper.class);
            WfEventEntity entity = mapper.findByKey(instanceId, nodeId);
            if (entity == null) {
                entity = new WfEventEntity();
                entity.setInstanceId(instanceId);
                entity.setNodeId(nodeId);
                entity.setEventType("TIMER");
                entity.setTriggerTime(triggerTime.toEpochMilli());
                entity.setInterrupting(interrupting);
                mapper.insert(entity);
            } else {
                entity.setEventType("TIMER");
                entity.setTriggerTime(triggerTime.toEpochMilli());
                entity.setInterrupting(interrupting);
                mapper.update(entity);
            }
            return null;
        });
    }

    @Override
    public List<TimerEvent> getExpiredTimers(Instant now) {
        return mybatis.inSession(session -> {
            WfEventMapper mapper = session.getMapper(WfEventMapper.class);
            return mapper.findExpiredTimers("TIMER", now.toEpochMilli()).stream()
                    .map(e -> new TimerEvent(e.getInstanceId(), e.getNodeId(),
                            Boolean.TRUE.equals(e.getInterrupting())))
                    .collect(Collectors.toList());
        });
    }

    @Override
    public void cancelTimer(String instanceId, String nodeId) {
        mybatis.inSession(session -> {
            WfEventMapper mapper = session.getMapper(WfEventMapper.class);
            mapper.deleteByKey(instanceId, nodeId);
            return null;
        });
    }

    @Override
    public void cancelEvents(String instanceId) {
        mybatis.inSession(session -> {
            WfEventMapper mapper = session.getMapper(WfEventMapper.class);
            mapper.deleteByInstanceId(instanceId);
            return null;
        });
    }
}
