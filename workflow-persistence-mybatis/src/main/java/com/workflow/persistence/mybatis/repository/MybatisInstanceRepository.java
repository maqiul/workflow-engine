package com.workflow.persistence.mybatis.repository;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.workflow.persistence.mybatis.MybatisPersistence;
import com.workflow.persistence.mybatis.entity.WfInstanceEntity;
import com.workflow.persistence.mybatis.entity.WfTaskEntity;
import com.workflow.persistence.mybatis.entity.WfTokenEntity;
import com.workflow.persistence.mybatis.mapper.WfInstanceMapper;
import com.workflow.persistence.mybatis.mapper.WfTaskMapper;
import com.workflow.persistence.mybatis.mapper.WfTokenMapper;
import com.workflow.repository.InstanceRepository;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import com.workflow.runtime.Token;
import org.apache.ibatis.session.SqlSession;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MyBatis-Plus 版 InstanceRepository
 *
 * 策略(与 JPA 版一致):
 *  - 反射写 final 字段(JDK 17 可用)
 *  - findById 单事务内加载 instance + token + task 全部转成 Domain 对象
 *  - save 是 delete-all + insert Token 的全量同步策略
 */
public class MybatisInstanceRepository implements InstanceRepository {

    private final MybatisPersistence mb;

    public MybatisInstanceRepository(MybatisPersistence mb) {
        this.mb = mb;
    }

    @Override
    public void save(ProcessInstance instance) {
        mb.inSession(session -> {
            WfInstanceMapper instanceMapper = session.getMapper(WfInstanceMapper.class);
            WfTokenMapper tokenMapper = session.getMapper(WfTokenMapper.class);

            WfInstanceEntity entity = instanceMapper.selectById(instance.getId());
            if (entity == null) {
                entity = new WfInstanceEntity();
                entity.setId(instance.getId());
                entity.setProcessKey(instance.getProcessKey());
                entity.setProcessVersion(instance.getProcessVersion());
                entity.setStatus(instance.getStatus());
                entity.setCreateTime(instance.getCreateTime());
                entity.setEndTime(instance.getEndTime() > 0 ? instance.getEndTime() : null);
                entity.setVariablesJson(JSON.toJSONString(instance.getVariables()));
                entity.setParentInstanceId(instance.getParentInstanceId());
                entity.setParentTokenId(instance.getParentTokenId());
                entity.setParentNodeId(instance.getParentNodeId());
                // 流程树根必须落库：否则重建后丢失，父子各持一把锁，ABBA 防护失效
                entity.setRootInstanceId(instance.getRootInstanceId());
                instanceMapper.insert(entity);
            } else {
                entity.setProcessKey(instance.getProcessKey());
                entity.setProcessVersion(instance.getProcessVersion());
                entity.setStatus(instance.getStatus());
                entity.setCreateTime(instance.getCreateTime());
                entity.setEndTime(instance.getEndTime() > 0 ? instance.getEndTime() : null);
                entity.setVariablesJson(JSON.toJSONString(instance.getVariables()));
                entity.setParentInstanceId(instance.getParentInstanceId());
                entity.setParentTokenId(instance.getParentTokenId());
                entity.setParentNodeId(instance.getParentNodeId());
                // 流程树根必须落库：否则重建后丢失，父子各持一把锁，ABBA 防护失效
                entity.setRootInstanceId(instance.getRootInstanceId());
                instanceMapper.updateById(entity);
            }

            // Token 全量同步:delete-all + insert
            tokenMapper.deleteByInstanceId(instance.getId());
            for (Token t : instance.getActiveTokens().values()) {
                WfTokenEntity te = new WfTokenEntity();
                te.setId(t.getId());
                te.setInstanceId(instance.getId());
                te.setCurrentNodeId(t.getCurrentNodeId());
                te.setStatus(t.getStatus());
                tokenMapper.insert(te);
            }
            return null;
        });
    }

    @Override
    public ProcessInstance findById(String instanceId) {
        return mb.inSession(session -> {
            WfInstanceEntity e = session.getMapper(WfInstanceMapper.class).selectById(instanceId);
            if (e == null) {
                throw new IllegalArgumentException("流程实例不存在: " + instanceId);
            }
            return rebuild(e, session);
        });
    }

    // ========== 列表查询 ==========
    //
    // 这四者此前全部落到 InstanceRepository 的 default 实现，一调用就抛
    // UnsupportedOperationException：「历史查询 / 复杂查询」只在内存版可用。
    // 走 BaseMapper + QueryWrapper 而非手写 SQL，可自动覆盖全部列，
    // 避开「新增列忘了写进 SELECT 导致读回丢字段」那个老坑。

    @Override
    public java.util.List<ProcessInstance> findByProcessKey(String processKey) {
        return queryInstances(new com.baomidou.mybatisplus.core.conditions.query
                        .QueryWrapper<WfInstanceEntity>()
                .eq("process_key", processKey).orderByAsc("create_time"));
    }

    @Override
    public java.util.List<ProcessInstance> findByStatus(com.workflow.enums.InstanceStatus status) {
        return queryInstances(new com.baomidou.mybatisplus.core.conditions.query
                        .QueryWrapper<WfInstanceEntity>()
                .eq("status", status.name()).orderByAsc("create_time"));
    }

    @Override
    public java.util.List<ProcessInstance> findAll() {
        return queryInstances(new com.baomidou.mybatisplus.core.conditions.query
                .QueryWrapper<WfInstanceEntity>().orderByAsc("create_time"));
    }

    @Override
    public java.util.List<ProcessInstance> findByProcessKeyAndVersion(String processKey, int version) {
        return queryInstances(new com.baomidou.mybatisplus.core.conditions.query
                        .QueryWrapper<WfInstanceEntity>()
                .eq("process_key", processKey)
                .eq("process_version", version)
                .orderByAsc("create_time"));
    }

    private java.util.List<ProcessInstance> queryInstances(
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<WfInstanceEntity> qw) {
        return mb.inSession(session -> {
            java.util.List<WfInstanceEntity> entities = session.getMapper(WfInstanceMapper.class)
                    .selectList(qw);
            java.util.List<ProcessInstance> out = new java.util.ArrayList<>(entities.size());
            for (WfInstanceEntity e : entities) {
                out.add(rebuild(e, session));
            }
            return out;
        });
    }

    private ProcessInstance rebuild(WfInstanceEntity e, SqlSession session) {
        // 1) 加载 Token 和 Task
        WfTokenMapper tokenMapper = session.getMapper(WfTokenMapper.class);
        WfTaskMapper taskMapper = session.getMapper(WfTaskMapper.class);
        List<WfTokenEntity> tokens = tokenMapper.findByInstanceId(e.getId());
        List<WfTaskEntity> tasks = taskMapper.findByInstanceId(e.getId());

        // 2) 重建 ProcessInstance(反射写 final 字段)
        ProcessInstance instance = new ProcessInstance(e.getProcessKey(), e.getProcessVersion());
        setFinal(instance, "id", e.getId());
        setFinal(instance, "createTime", e.getCreateTime());
        setFinal(instance, "endTime", e.getEndTime() != null ? e.getEndTime() : 0L);
        setFinal(instance, "parentInstanceId", e.getParentInstanceId());
        setFinal(instance, "parentTokenId", e.getParentTokenId());
        setFinal(instance, "parentNodeId", e.getParentNodeId());
        // 读回流程树根，保持与写入库的值一致
        instance.assignRootInstanceId(e.getRootInstanceId());
        try {
            java.lang.reflect.Field statusField = ProcessInstance.class.getDeclaredField("status");
            statusField.setAccessible(true);
            statusField.set(instance, e.getStatus());
        } catch (Exception ex) {
            throw new RuntimeException("设置 status 失败", ex);
        }

        // 3) Token 列表
        Map<String, Token> tokenMap = new LinkedHashMap<>();
        for (WfTokenEntity te : tokens) {
            Token token = new Token(e.getId(), te.getCurrentNodeId());
            setFinal(token, "id", te.getId());
            token.setStatus(te.getStatus());
            tokenMap.put(token.getId(), token);
        }
        setFinal(instance, "activeTokens", tokenMap);

        // 4) Task 列表
        List<TaskInstance> taskList = new ArrayList<>();
        for (WfTaskEntity te : tasks) {
            taskList.add(MybatisTaskRepository.rebuildFromEntity(te));
        }
        setFinal(instance, "tasks", taskList);

        // 5) 变量
        Map<String, Object> variables = new LinkedHashMap<>();
        if (e.getVariablesJson() != null && !e.getVariablesJson().isEmpty()) {
            Map<String, Object> parsed = JSON.parseObject(e.getVariablesJson(),
                    new TypeReference<Map<String, Object>>() {});
            if (parsed != null) variables.putAll(parsed);
        }
        setFinal(instance, "variables", variables);

        return instance;
    }

    @Override
    public void delete(String instanceId) {
        mb.inSession(session -> {
            WfInstanceMapper instanceMapper = session.getMapper(WfInstanceMapper.class);
            if (instanceMapper.selectById(instanceId) != null) {
                session.getMapper(WfTokenMapper.class).deleteByInstanceId(instanceId);
                session.getMapper(WfTaskMapper.class).deleteByInstanceId(instanceId);
                instanceMapper.deleteById(instanceId);
            }
            return null;
        });
    }

    /** 反射写 final 字段 - JDK 17 setAccessible + set 可用 */
    private static void setFinal(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception ex) {
            throw new RuntimeException("反射设置字段失败: " + fieldName, ex);
        }
    }
}
