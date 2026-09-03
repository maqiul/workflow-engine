package com.workflow.persistence.mybatis.repository;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.workflow.definition.NodeDefinition;
import com.workflow.definition.ProcessDefinition;
import com.workflow.definition.Transition;
import com.workflow.definition.VariableDefinition;
import com.workflow.persistence.mybatis.MybatisPersistence;
import com.workflow.persistence.mybatis.entity.WfProcessDefEntity;
import com.workflow.persistence.mybatis.mapper.WfProcessDefMapper;
import com.workflow.repository.ProcessRepository;
import org.apache.ibatis.session.SqlSession;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MyBatis-Plus 版 ProcessRepository - 支持多版本(复合主键 key + version)
 *
 * 与 JPA 版 API 完全一致:写操作内部用 inSession 包裹事务,引擎无感知。
 * wf_process_def 是复合主键,BaseMapper 无法直接 CRUD,全部走手写注解 SQL。
 */
public class MybatisProcessRepository implements ProcessRepository {

    private static final TypeReference<Map<String, NodeDefinition>> NODE_MAP_TYPE =
            new TypeReference<Map<String, NodeDefinition>>() {};
    private static final TypeReference<Map<String, List<Transition>>> OUTGOING_MAP_TYPE =
            new TypeReference<Map<String, List<Transition>>>() {};

    private final MybatisPersistence mb;

    public MybatisProcessRepository(MybatisPersistence mb) {
        this.mb = mb;
    }

    @Override
    public void save(ProcessDefinition definition) {
        mb.inSession(session -> {
            WfProcessDefMapper mapper = session.getMapper(WfProcessDefMapper.class);
            WfProcessDefEntity entity = toEntity(definition);
            // 逻辑 upsert:先查是否存在(跨库标准 SQL,MERGE/ON CONFLICT 方言差异大)
            WfProcessDefEntity existing = mapper.findByKeyVersion(entity.getKey(), entity.getVersion());
            if (existing == null) {
                mapper.insert(entity);
            } else {
                mapper.update(entity);
            }
            return null;
        });
    }

    @Override
    public ProcessDefinition findByKey(String key) {
        return mb.inSession(session -> {
            WfProcessDefEntity e = session.getMapper(WfProcessDefMapper.class).findLatestByKey(key);
            if (e == null) {
                throw new IllegalArgumentException("流程定义不存在: " + key);
            }
            return toDomain(e);
        });
    }

    @Override
    public ProcessDefinition findByKeyAndVersion(String key, int version) {
        return mb.inSession(session -> {
            WfProcessDefEntity e = session.getMapper(WfProcessDefMapper.class)
                    .findByKeyVersion(key, version);
            if (e == null) {
                throw new IllegalArgumentException("流程定义不存在: " + key + " v" + version);
            }
            return toDomain(e);
        });
    }

    @Override
    public List<Integer> getVersions(String key) {
        return mb.inSession(session ->
                session.getMapper(WfProcessDefMapper.class).findVersions(key));
    }

    @Override
    public boolean exists(String key) {
        return mb.inSession(session ->
                session.getMapper(WfProcessDefMapper.class).countByKey(key) > 0);
    }

    private static WfProcessDefEntity toEntity(ProcessDefinition def) {
        WfProcessDefEntity e = new WfProcessDefEntity();
        e.setKey(def.getKey());
        e.setVersion(def.getVersion());
        e.setName(def.getName());
        e.setStartNodeId(def.getStartNodeId());
        e.setNodesJson(JSON.toJSONString(def.getNodes()));
        e.setOutgoingJson(JSON.toJSONString(readOutgoingMap(def)));
        if (def.hasVariableDefinitions()) {
            e.setVariableDefinitionsJson(JSON.toJSONString(def.getVariableDefinitions()));
        } else {
            e.setVariableDefinitionsJson(null);
        }
        return e;
    }

    /** Entity -> ProcessDefinition */
    private static ProcessDefinition toDomain(WfProcessDefEntity e) {
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
