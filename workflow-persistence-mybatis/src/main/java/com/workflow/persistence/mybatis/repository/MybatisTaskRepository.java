package com.workflow.persistence.mybatis.repository;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.workflow.definition.Candidate;
import com.workflow.enums.TaskStatus;
import com.workflow.persistence.mybatis.MybatisPersistence;
import com.workflow.persistence.mybatis.entity.WfTaskEntity;
import com.workflow.persistence.mybatis.mapper.WfTaskMapper;
import com.workflow.repository.TaskRepository;
import com.workflow.runtime.TaskInstance;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MyBatis-Plus 版 TaskRepository
 *
 * 单主键表,用 BaseMapper 通用 CRUD:
 *  - save:selectById 判断存在 → updateById / insert
 *  - 查询:findByInstanceId / findByStatus 走注解 SQL
 *  Entity -> Domain 重建逻辑与 JPA 版一致(反射写 final 字段)。
 */
public class MybatisTaskRepository implements TaskRepository {

    private static final TypeReference<Set<String>> STRING_SET_TYPE = new TypeReference<Set<String>>() {};

    private final MybatisPersistence mb;

    public MybatisTaskRepository(MybatisPersistence mb) {
        this.mb = mb;
    }

    @Override
    public void save(TaskInstance task) {
        mb.inSession(session -> {
            WfTaskMapper mapper = session.getMapper(WfTaskMapper.class);
            WfTaskEntity entity = mapper.selectById(task.getId());
            if (entity == null) {
                entity = new WfTaskEntity();
                entity.setId(task.getId());
                entity.setCreateTime(task.getCreateTime());
                entity.setInstanceId(task.getInstanceId());
                entity.setTokenId(task.getTokenId());
                entity.setNodeId(task.getNodeId());
                entity.setCandidateJson(JSON.toJSONString(task.getCandidate()));
                // 直接序列化底层 HashSet,避免 unmodifiableSet 包装类型的序列化问题
                try {
                    java.lang.reflect.Field f = TaskInstance.class.getDeclaredField("completedApprovers");
                    f.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Set<String> approvers = (Set<String>) f.get(task);
                    entity.setCompletedApproversJson(JSON.toJSONString(approvers));
                } catch (Exception e) {
                    throw new RuntimeException("序列化 completedApprovers 失败", e);
                }
                entity.setTenantId(task.getTenantId());
                entity.setArrival(task.getArrival());
                entity.setStatus(task.getStatus());
                mapper.insert(entity);
            } else {
                entity.setInstanceId(task.getInstanceId());
                entity.setTokenId(task.getTokenId());
                entity.setNodeId(task.getNodeId());
                entity.setCandidateJson(JSON.toJSONString(task.getCandidate()));
                try {
                    java.lang.reflect.Field f = TaskInstance.class.getDeclaredField("completedApprovers");
                    f.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Set<String> approvers = (Set<String>) f.get(task);
                    entity.setCompletedApproversJson(JSON.toJSONString(approvers));
                } catch (Exception e) {
                    throw new RuntimeException("序列化 completedApprovers 失败", e);
                }
                entity.setTenantId(task.getTenantId());
                entity.setArrival(task.getArrival());
                entity.setStatus(task.getStatus());
                mapper.updateById(entity);
            }
            return null;
        });
    }

    @Override
    public void saveBatch(List<TaskInstance> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        // 批量优化：单事务内批量插入，减少事务开销
        mb.inSession(session -> {
            WfTaskMapper mapper = session.getMapper(WfTaskMapper.class);
            for (TaskInstance task : tasks) {
                WfTaskEntity entity = mapper.selectById(task.getId());
                if (entity == null) {
                    entity = new WfTaskEntity();
                    entity.setId(task.getId());
                    entity.setCreateTime(task.getCreateTime());
                    entity.setInstanceId(task.getInstanceId());
                    entity.setTokenId(task.getTokenId());
                    entity.setNodeId(task.getNodeId());
                    entity.setCandidateJson(JSON.toJSONString(task.getCandidate()));
                    try {
                        java.lang.reflect.Field f = TaskInstance.class.getDeclaredField("completedApprovers");
                        f.setAccessible(true);
                        @SuppressWarnings("unchecked")
                        Set<String> approvers = (Set<String>) f.get(task);
                        entity.setCompletedApproversJson(JSON.toJSONString(approvers));
                    } catch (Exception e) {
                        throw new RuntimeException("序列化 completedApprovers 失败", e);
                    }
                    entity.setTenantId(task.getTenantId());
                entity.setArrival(task.getArrival());
                entity.setStatus(task.getStatus());
                    mapper.insert(entity);
                } else {
                    entity.setInstanceId(task.getInstanceId());
                    entity.setTokenId(task.getTokenId());
                    entity.setNodeId(task.getNodeId());
                    entity.setCandidateJson(JSON.toJSONString(task.getCandidate()));
                    try {
                        java.lang.reflect.Field f = TaskInstance.class.getDeclaredField("completedApprovers");
                        f.setAccessible(true);
                        @SuppressWarnings("unchecked")
                        Set<String> approvers = (Set<String>) f.get(task);
                        entity.setCompletedApproversJson(JSON.toJSONString(approvers));
                    } catch (Exception e) {
                        throw new RuntimeException("序列化 completedApprovers 失败", e);
                    }
                    entity.setTenantId(task.getTenantId());
                entity.setArrival(task.getArrival());
                entity.setStatus(task.getStatus());
                    mapper.updateById(entity);
                }
            }
            return null;
        });
    }

    @Override
    public TaskInstance findById(String taskId) {
        return mb.inSession(session -> {
            WfTaskEntity e = session.getMapper(WfTaskMapper.class).selectById(taskId);
            if (e == null) {
                throw new IllegalArgumentException("任务不存在: " + taskId);
            }
            return toDomain(e);
        });
    }

    @Override
    public List<TaskInstance> findByInstanceId(String instanceId) {
        return mb.inSession(session ->
                session.getMapper(WfTaskMapper.class).findByInstanceId(instanceId)
                        .stream().map(MybatisTaskRepository::rebuildFromEntity).toList());
    }

    @Override
    public List<TaskInstance> findPendingByUser(String userId) {
        return mb.inSession(session ->
                session.getMapper(WfTaskMapper.class).findByStatus(TaskStatus.PENDING)
                        .stream().map(MybatisTaskRepository::toDomain)
                        .filter(t -> t.getCandidate().getUserIds().contains(userId))
                        .toList());
    }

    private static TaskInstance toDomain(WfTaskEntity e) {
        Candidate candidate = JSON.parseObject(e.getCandidateJson(), Candidate.class);
        Set<String> completed = JSON.parseObject(e.getCompletedApproversJson(), STRING_SET_TYPE);
        if (completed == null) completed = new HashSet<>();
        return rebuildFromEntity(e, candidate, completed);
    }

    /** Entity -> TaskInstance,供 MybatisInstanceRepository 复用 */
    public static TaskInstance rebuildFromEntity(WfTaskEntity e) {
        Candidate candidate = JSON.parseObject(e.getCandidateJson(), Candidate.class);
        Set<String> completed = JSON.parseObject(e.getCompletedApproversJson(), STRING_SET_TYPE);
        if (completed == null) completed = new HashSet<>();
        return rebuildFromEntity(e, candidate, completed);
    }

    // ========== 全局查询 ==========
    //
    // 此前 findAll / findByNodeId / findByStatus 落到 TaskRepository 的 default 实现
    // （抛 UnsupportedOperationException），TaskQuery 不带 processInstanceId 时在真实
    // 数据库上完全无法工作。走 BaseMapper + QueryWrapper，自动全列映射，
    // 避开「手写 SQL 漏了新列导致读回丢字段」的老坑。

    @Override
    public List<TaskInstance> findAll() {
        return queryBy(new com.baomidou.mybatisplus.core.conditions.query
                .QueryWrapper<WfTaskEntity>().orderByAsc("create_time"));
    }

    @Override
    public List<TaskInstance> findByNodeId(String nodeId) {
        return queryBy(new com.baomidou.mybatisplus.core.conditions.query
                .QueryWrapper<WfTaskEntity>()
                .eq("node_id", nodeId).orderByAsc("create_time"));
    }

    @Override
    public List<TaskInstance> findByStatus(TaskStatus status) {
        return queryBy(new com.baomidou.mybatisplus.core.conditions.query
                .QueryWrapper<WfTaskEntity>()
                .eq("status", status.name()).orderByAsc("create_time"));
    }

    @Override
    public long countPending() {
        return countPending(null);
    }

    @Override
    public long countPending(String tenantId) {
        return mb.inSession(session -> {
            com.baomidou.mybatisplus.core.conditions.query
                    .QueryWrapper<WfTaskEntity> qw =
                    new com.baomidou.mybatisplus.core.conditions.query
                            .QueryWrapper<WfTaskEntity>()
                            .eq("status", TaskStatus.PENDING.name());
            if (tenantId != null) {
                qw.eq("tenant_id", tenantId);
            }
            Long c = session.getMapper(WfTaskMapper.class).selectCount(qw);
            return c == null ? 0L : c;
        });
    }

    private List<TaskInstance> queryBy(
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<WfTaskEntity> qw) {
        return mb.inSession(session -> session.getMapper(WfTaskMapper.class)
                .selectList(qw).stream()
                .map(MybatisTaskRepository::toDomain)
                .toList());
    }

    private static TaskInstance rebuildFromEntity(WfTaskEntity e,
                                                  Candidate candidate,
                                                  Set<String> completed) {
        // 统一走 TaskInstance.reconstruct：不再反射逐字段写，
        // 并把 create_time 读回来（此前丢弃导致按创建时间排序退化成按 id 排序）。
        return TaskInstance.reconstruct(e.getId(), e.getInstanceId(), e.getTokenId(),
                e.getNodeId(), candidate, completed, e.getStatus(), 0L, e.getCreateTime(), e.getTenantId(), e.getArrival());
    }
}
