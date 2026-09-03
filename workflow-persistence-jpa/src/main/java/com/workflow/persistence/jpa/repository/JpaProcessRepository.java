package com.workflow.persistence.jpa.repository;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.workflow.definition.NodeDefinition;
import com.workflow.definition.ProcessDefinition;
import com.workflow.definition.Transition;
import com.workflow.definition.VariableDefinition;
import com.workflow.persistence.jpa.JpaPersistence;
import com.workflow.persistence.jpa.entity.WfProcessDefEntity;
import com.workflow.persistence.jpa.entity.WfProcessDefPK;
import com.workflow.repository.ProcessRepository;
import jakarta.persistence.TypedQuery;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JPA 版 ProcessRepository - 支持多版本(复合主键 key + version)
 *
 * 每个写操作内部用 JpaPersistence.inTransaction 包裹,
 * 引擎无需感知事务边界(与 InMemory 版 API 完全一致)。
 */
public class JpaProcessRepository implements ProcessRepository {

    private static final TypeReference<Map<String, NodeDefinition>> NODE_MAP_TYPE =
            new TypeReference<Map<String, NodeDefinition>>() {};
    private static final TypeReference<Map<String, List<Transition>>> OUTGOING_MAP_TYPE =
            new TypeReference<Map<String, List<Transition>>>() {};

    private final JpaPersistence jpa;

    public JpaProcessRepository(JpaPersistence jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(ProcessDefinition definition) {
        jpa.inTransaction(em -> {
            WfProcessDefPK pk = new WfProcessDefPK(definition.getKey(), definition.getVersion());
            WfProcessDefEntity entity = em.find(WfProcessDefEntity.class, pk);
            if (entity == null) {
                entity = new WfProcessDefEntity();
                entity.setKey(definition.getKey());
                entity.setVersion(definition.getVersion());
            }
            entity.setName(definition.getName());
            entity.setStartNodeId(definition.getStartNodeId());
            entity.setNodesJson(JSON.toJSONString(definition.getNodes()));
            entity.setOutgoingJson(JSON.toJSONString(readOutgoingMap(definition)));
            if (definition.hasVariableDefinitions()) {
                entity.setVariableDefinitionsJson(JSON.toJSONString(definition.getVariableDefinitions()));
            } else {
                entity.setVariableDefinitionsJson(null);
            }
            em.merge(entity);
            return null;
        });
    }

    @Override
    public ProcessDefinition findByKey(String key) {
        return jpa.inTransaction(em -> {
            TypedQuery<WfProcessDefEntity> q = em.createQuery(
                    "SELECT e FROM WfProcessDefEntity e WHERE e.key = :key ORDER BY e.version DESC",
                    WfProcessDefEntity.class);
            q.setParameter("key", key);
            q.setMaxResults(1);
            List<WfProcessDefEntity> rows = q.getResultList();
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("流程定义不存在: " + key);
            }
            return toDomain(rows.get(0));
        });
    }

    @Override
    public ProcessDefinition findByKeyAndVersion(String key, int version) {
        return jpa.inTransaction(em -> {
            WfProcessDefEntity e = em.find(WfProcessDefEntity.class, new WfProcessDefPK(key, version));
            if (e == null) {
                throw new IllegalArgumentException("流程定义不存在: " + key + " v" + version);
            }
            return toDomain(e);
        });
    }

    @Override
    public List<Integer> getVersions(String key) {
        return jpa.inTransaction(em -> {
            TypedQuery<Integer> q = em.createQuery(
                    "SELECT e.version FROM WfProcessDefEntity e WHERE e.key = :key ORDER BY e.version ASC",
                    Integer.class);
            q.setParameter("key", key);
            return q.getResultList();
        });
    }

    @Override
    public boolean exists(String key) {
        return jpa.inTransaction(em -> {
            TypedQuery<Long> q = em.createQuery(
                    "SELECT COUNT(e) FROM WfProcessDefEntity e WHERE e.key = :key", Long.class);
            q.setParameter("key", key);
            return q.getSingleResult() > 0;
        });
    }

    /** Entity -> ProcessDefinition */
    private ProcessDefinition toDomain(WfProcessDefEntity e) {
        Map<String, NodeDefinition> nodes = JSON.parseObject(e.getNodesJson(), NODE_MAP_TYPE);
        Map<String, List<Transition>> outgoing = JSON.parseObject(e.getOutgoingJson(), OUTGOING_MAP_TYPE);
        List<VariableDefinition> varDefs = null;
        if (e.getVariableDefinitionsJson() != null && !e.getVariableDefinitionsJson().isEmpty()) {
            varDefs = JSON.parseArray(e.getVariableDefinitionsJson(), VariableDefinition.class);
        }
        return new ProcessDefinition(e.getKey(), e.getName(), e.getVersion(), nodes, outgoing, e.getStartNodeId(), varDefs);
    }

    /** 反射读 ProcessDefinition 的 outgoing final 字段(没有 public getter) */
    @SuppressWarnings("unchecked")
    private static Map<String, List<Transition>> readOutgoingMap(ProcessDefinition def) {
        try {
            Field f = ProcessDefinition.class.getDeclaredField("outgoing");
            f.setAccessible(true);
            return new LinkedHashMap<>((Map<String, List<Transition>>) f.get(def));
        } catch (Exception ex) {
            throw new RuntimeException("读取 ProcessDefinition.outgoing 失败", ex);
        }
    }
}
