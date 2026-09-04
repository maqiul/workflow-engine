package com.workflow.persistence.jpa.repository;

import com.workflow.persistence.jpa.JpaPersistence;
import com.workflow.persistence.jpa.entity.WfEventEntity;
import com.workflow.repository.EventRepository;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * JPA 版事件仓储
 */
public class JpaEventRepository implements EventRepository {

    private final JpaPersistence jpa;

    public JpaEventRepository(JpaPersistence jpa) {
        this.jpa = jpa;
    }

    @Override
    public void saveMessageEvent(String instanceId, String nodeId, String messageName, String correlationKey) {
        String key = messageName + ":" + correlationKey;
        jpa.inTransaction(em -> {
            WfEventEntity entity = findOrCreate(em, instanceId, nodeId, WfEventEntity.EventType.MESSAGE);
            entity.setMessageKey(key);
            return null;
        });
    }

    @Override
    public List<String> triggerMessageEvent(String messageName, String correlationKey) {
        String key = messageName + ":" + correlationKey;
        return jpa.inTransaction(em -> {
            List<WfEventEntity> list = em.createQuery(
                    "SELECT e FROM WfEventEntity e WHERE e.eventType = :type AND e.messageKey = :key",
                    WfEventEntity.class)
                    .setParameter("type", WfEventEntity.EventType.MESSAGE)
                    .setParameter("key", key)
                    .getResultList();
            List<String> instances = list.stream()
                    .map(WfEventEntity::getInstanceId)
                    .collect(Collectors.toList());
            for (WfEventEntity e : list) {
                em.remove(e);
            }
            return instances;
        });
    }

    @Override
    public void saveSignalEvent(String instanceId, String nodeId, String signalName) {
        jpa.inTransaction(em -> {
            WfEventEntity entity = findOrCreate(em, instanceId, nodeId, WfEventEntity.EventType.SIGNAL);
            entity.setSignalName(signalName);
            return null;
        });
    }

    @Override
    public List<String> triggerSignalEvent(String signalName) {
        return jpa.inTransaction(em -> {
            List<WfEventEntity> list = em.createQuery(
                    "SELECT e FROM WfEventEntity e WHERE e.eventType = :type AND e.signalName = :name",
                    WfEventEntity.class)
                    .setParameter("type", WfEventEntity.EventType.SIGNAL)
                    .setParameter("name", signalName)
                    .getResultList();
            List<String> instances = list.stream()
                    .map(WfEventEntity::getInstanceId)
                    .collect(Collectors.toList());
            for (WfEventEntity e : list) {
                em.remove(e);
            }
            return instances;
        });
    }

    @Override
    public void saveTimerEvent(String instanceId, String nodeId, Instant triggerTime, boolean interrupting) {
        jpa.inTransaction(em -> {
            WfEventEntity entity = findOrCreate(em, instanceId, nodeId, WfEventEntity.EventType.TIMER);
            entity.setTriggerTime(triggerTime.toEpochMilli());
            entity.setInterrupting(interrupting);
            return null;
        });
    }

    @Override
    public List<TimerEvent> getExpiredTimers(Instant now) {
        return jpa.inTransaction(em -> em.createQuery(
                        "SELECT e FROM WfEventEntity e WHERE e.eventType = :type AND e.triggerTime <= :now ORDER BY e.triggerTime",
                        WfEventEntity.class)
                .setParameter("type", WfEventEntity.EventType.TIMER)
                .setParameter("now", now.toEpochMilli())
                .getResultList().stream()
                .map(e -> new TimerEvent(e.getInstanceId(), e.getNodeId(),
                        Boolean.TRUE.equals(e.getInterrupting())))
                .collect(Collectors.toList()));
    }

    @Override
    public void cancelTimer(String instanceId, String nodeId) {
        jpa.inTransaction(em -> {
            WfEventEntity entity = em.find(WfEventEntity.class, new WfEventEntity.EventKey(instanceId, nodeId));
            if (entity != null) {
                em.remove(entity);
            }
            return null;
        });
    }

    @Override
    public void cancelEvents(String instanceId) {
        jpa.inTransaction(em -> em.createQuery(
                        "DELETE FROM WfEventEntity e WHERE e.instanceId = :iid")
                .setParameter("iid", instanceId)
                .executeUpdate());
    }

    private WfEventEntity findOrCreate(EntityManager em, String instanceId, String nodeId,
                                        WfEventEntity.EventType type) {
        WfEventEntity.EventKey key = new WfEventEntity.EventKey(instanceId, nodeId);
        WfEventEntity entity = em.find(WfEventEntity.class, key);
        if (entity == null) {
            entity = new WfEventEntity();
            entity.setInstanceId(instanceId);
            entity.setNodeId(nodeId);
            entity.setEventType(type);
            em.persist(entity);
        } else {
            entity.setEventType(type);
        }
        return entity;
    }
}