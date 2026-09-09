package com.workflow.persistence.mybatis.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workflow.persistence.mybatis.MybatisPersistence;
import com.workflow.persistence.mybatis.entity.WfAuditLogEntity;
import com.workflow.persistence.mybatis.mapper.WfAuditLogMapper;
import com.workflow.repository.AuditLogRepository;
import com.workflow.runtime.AuditLog;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MyBatis 版审计日志仓储
 */
public class MybatisAuditLogRepository implements AuditLogRepository {

    private final MybatisPersistence mb;

    public MybatisAuditLogRepository(MybatisPersistence mb) {
        this.mb = mb;
    }

    @Override
    public void save(AuditLog log) {
        mb.inSession(session -> {
            WfAuditLogMapper mapper = session.getMapper(WfAuditLogMapper.class);
            WfAuditLogEntity entity = new WfAuditLogEntity();
            entity.setId(log.getId());
            entity.setInstanceId(log.getInstanceId());
            entity.setTaskId(log.getTaskId());
            entity.setEventType(log.getEventType());
            entity.setOperator(log.getOperator());
            entity.setTimestamp(log.getTimestamp().toEpochMilli());
            entity.setDetail(log.getDetail());
            mapper.insert(entity);
            return null;
        });
    }

    @Override
    public List<AuditLog> findByInstanceId(String instanceId) {
        return mb.inSession(session -> {
            WfAuditLogMapper mapper = session.getMapper(WfAuditLogMapper.class);
            LambdaQueryWrapper<WfAuditLogEntity> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(WfAuditLogEntity::getInstanceId, instanceId)
                    .orderByAsc(WfAuditLogEntity::getTimestamp);
            List<WfAuditLogEntity> list = mapper.selectList(wrapper);
            return list.stream().map(MybatisAuditLogRepository::toDomain).collect(Collectors.toList());
        });
    }

    @Override
    public List<AuditLog> findByTaskId(String taskId) {
        return mb.inSession(session -> {
            WfAuditLogMapper mapper = session.getMapper(WfAuditLogMapper.class);
            LambdaQueryWrapper<WfAuditLogEntity> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(WfAuditLogEntity::getTaskId, taskId)
                    .orderByAsc(WfAuditLogEntity::getTimestamp);
            List<WfAuditLogEntity> list = mapper.selectList(wrapper);
            return list.stream().map(MybatisAuditLogRepository::toDomain).collect(Collectors.toList());
        });
    }

    @Override
    public List<AuditLog> findByTimeRange(Instant from, Instant to) {
        return mb.inSession(session -> {
            WfAuditLogMapper mapper = session.getMapper(WfAuditLogMapper.class);
            LambdaQueryWrapper<WfAuditLogEntity> wrapper = new LambdaQueryWrapper<>();
            wrapper.ge(WfAuditLogEntity::getTimestamp, from.toEpochMilli())
                    .le(WfAuditLogEntity::getTimestamp, to.toEpochMilli())
                    .orderByAsc(WfAuditLogEntity::getTimestamp);
            List<WfAuditLogEntity> list = mapper.selectList(wrapper);
            return list.stream().map(MybatisAuditLogRepository::toDomain).collect(Collectors.toList());
        });
    }

    @Override
    public void clear() {
        mb.inSession(session -> {
            WfAuditLogMapper mapper = session.getMapper(WfAuditLogMapper.class);
            mapper.delete(null);
            return null;
        });
    }

    @Override
    public java.util.List<com.workflow.repository.EventTypeCount> countGroupByEventTypePrefix(String prefix) {
        return mb.inSession(session -> {
            WfAuditLogMapper mapper = session.getMapper(WfAuditLogMapper.class);
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<WfAuditLogEntity> wrapper =
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            wrapper.select("event_type", "COUNT(1) AS cnt")
                    .likeRight("event_type", prefix)
                    .groupBy("event_type");
            java.util.List<java.util.Map<String, Object>> maps = mapper.selectMaps(wrapper);
            java.util.List<com.workflow.repository.EventTypeCount> out = new java.util.ArrayList<>();
            for (java.util.Map<String, Object> m : maps) {
                Object et = pick(m, "event_type", "EVENT_TYPE");
                Object cn = pick(m, "cnt", "CNT");
                out.add(new com.workflow.repository.EventTypeCount(
                        com.workflow.enums.AuditEventType.valueOf(String.valueOf(et)),
                        ((Number) cn).longValue()));
            }
            return out;
        });
    }

    private static Object pick(java.util.Map<String, Object> m, String... keys) {
        for (String k : keys) {
            if (m.containsKey(k)) {
                return m.get(k);
            }
        }
        return null;
    }

    private static AuditLog toDomain(WfAuditLogEntity e) {
        AuditLog log = new AuditLog(
                e.getInstanceId(),
                e.getTaskId(),
                e.getEventType(),
                e.getOperator(),
                e.getDetail()
        );
        // 反射设置 id 和 timestamp
        try {
            java.lang.reflect.Field idField = AuditLog.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(log, e.getId());

            java.lang.reflect.Field tsField = AuditLog.class.getDeclaredField("timestamp");
            tsField.setAccessible(true);
            tsField.set(log, Instant.ofEpochMilli(e.getTimestamp()));
        } catch (Exception ex) {
            throw new RuntimeException("重建 AuditLog 失败: " + ex.getMessage(), ex);
        }
        return log;
    }
}
