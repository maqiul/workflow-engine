package com.workflow.persistence.mybatis.repository;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.workflow.concurrency.WorkflowConflictException;
import com.workflow.definition.Candidate;
import com.workflow.definition.CandidateCodec;
import com.workflow.enums.TaskStatus;
import com.workflow.persistence.mybatis.MybatisPersistence;
import com.workflow.persistence.mybatis.entity.WfTaskEntity;
import com.workflow.persistence.mybatis.mapper.WfTaskMapper;
import com.workflow.repository.TaskFilter;
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
                entity.setRevision(FIRST_REVISION);
                entity.setCreateTime(task.getCreateTime());
                entity.setInstanceId(task.getInstanceId());
                entity.setTokenId(task.getTokenId());
                entity.setNodeId(task.getNodeId());
                entity.setCandidateJson(CandidateCodec.toJson(task.getCandidate()));
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
                entity.setCandidateJson(CandidateCodec.toJson(task.getCandidate()));
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
                requireCas(mapper.updateById(entity), task.getId());
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
                    entity.setRevision(FIRST_REVISION);
                    entity.setCreateTime(task.getCreateTime());
                    entity.setInstanceId(task.getInstanceId());
                    entity.setTokenId(task.getTokenId());
                    entity.setNodeId(task.getNodeId());
                    entity.setCandidateJson(CandidateCodec.toJson(task.getCandidate()));
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
                    entity.setCandidateJson(CandidateCodec.toJson(task.getCandidate()));
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
                    requireCas(mapper.updateById(entity), task.getId());
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
        return findPaged(TaskFilter.create()
                .status(TaskStatus.PENDING)
                .candidateUser(userId));
    }

    // ========== 条件下推 ==========

    @Override
    public List<TaskInstance> findPaged(TaskFilter filter) {
        return mb.inSession(session -> {
            boolean pushLimit = filter.canPushDownLimit();
            List<TaskInstance> hits = session.getMapper(WfTaskMapper.class)
                    .selectList(buildWrapper(filter, pushLimit))
                    .stream()
                    .map(MybatisTaskRepository::toDomain)
                    .filter(filter::matches)
                    .toList();
            // 粗筛场景下 limit 没敢下推，分页必须挪到精筛之后 ——
            // 否则被 LIKE 误命中的行会把真匹配挤出这一页
            return pushLimit ? hits : filter.finish(hits);
        });
    }

    @Override
    public long countByFilter(TaskFilter filter) {
        if (filter.hasCandidateCondition()) {
            // 候选人条件走 LIKE 粗筛，SQL 计数会把假阳性算进去，只能取回来精筛后数
            return mb.inSession(session -> session.getMapper(WfTaskMapper.class)
                    .selectList(buildWrapper(filter, false)).stream()
                    .map(MybatisTaskRepository::toDomain)
                    .filter(filter::matches)
                    .count());
        }
        return mb.inSession(session -> session.getMapper(WfTaskMapper.class)
                .selectCount(buildWrapper(filter, false)));
    }

    /**
     * 拼可下推条件。
     *
     * <p>候选人用 {@code apply} 而不是 {@code like}：{@code like} 会把模式包成
     * {@code %值%}，而 JSON 里的用户 id 一定带引号 —— 带上引号才能让 u1 不误配 u10。
     *
     * <p>分页刻意<b>不</b>由 {@code withPaging} 之外的调用方触发：计数路径绝不能带
     * limit，否则「一共几条」会变成「这一页几条」，分页组件的总页数就错了。
     */
    private static QueryWrapper<WfTaskEntity> buildWrapper(TaskFilter filter, boolean withPaging) {
        QueryWrapper<WfTaskEntity> qw = new QueryWrapper<>();
        if (filter.getInstanceId() != null) {
            qw.eq("instance_id", filter.getInstanceId());
        }
        if (filter.getStatus() != null) {
            qw.eq("status", filter.getStatus().name());
        }
        if (filter.getNodeId() != null) {
            qw.eq("node_id", filter.getNodeId());
        }
        if (filter.getCandidateUserId() != null) {
            qw.apply("candidate_json LIKE {0}",
                    TaskFilter.candidateLikePattern(filter.getCandidateUserId()));
        }
        if (filter.getCandidateGroupId() != null) {
            qw.apply("candidate_json LIKE {0}",
                    TaskFilter.candidateLikePattern(filter.getCandidateGroupId()));
        }
        if (filter.isOrderByCreateTime()) {
            // create_time 同毫秒时用 id 兜底，与 TaskFilter.finish 的排序规则一致
            qw.orderBy(true, !filter.isDescending(), "create_time", "id");
        }
        if (withPaging && filter.getLimit() != null) {
            int offset = filter.getOffset() == null ? 0 : filter.getOffset();
            // H2 与 MySQL 的 LIMIT n OFFSET m 语法一致；last 一定落在 ORDER BY 之后
            qw.last("LIMIT " + filter.getLimit() + " OFFSET " + offset);
        }
        return qw;
    }

    private static TaskInstance toDomain(WfTaskEntity e) {
        Candidate candidate = CandidateCodec.fromJson(e.getCandidateJson());
        Set<String> completed = JSON.parseObject(e.getCompletedApproversJson(), STRING_SET_TYPE);
        if (completed == null) completed = new HashSet<>();
        return rebuildFromEntity(e, candidate, completed);
    }

    /** Entity -> TaskInstance,供 MybatisInstanceRepository 复用 */
    public static TaskInstance rebuildFromEntity(WfTaskEntity e) {
        Candidate candidate = CandidateCodec.fromJson(e.getCandidateJson());
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
                e.getNodeId(), candidate, completed, e.getStatus(),
                e.getRevision(), e.getCreateTime(), e.getTenantId(), e.getArrival());
    }

    /** 插入行的初始版本号（与内存仓储 save 后的版本号对齐） */
    private static final long FIRST_REVISION = 1L;

    /**
     * 乐观锁 CAS 检查。
     *
     * <p>MyBatis-Plus 的乐观锁插件把 {@code updateById} 改写成带版本条件的 UPDATE，
     * 但**影响 0 行时不抛异常**，只把返回值交出来 —— 不检查就等于没开乐观锁。
     */
    private static void requireCas(int affectedRows, String taskId) {
        if (affectedRows == 0) {
            throw new WorkflowConflictException(
                    "任务 " + taskId + " 已被其它节点修改，本次写入作废", taskId);
        }
    }
}
