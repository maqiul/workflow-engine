package com.workflow.tests;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.WorkflowEngine;
import com.workflow.persistence.mybatis.MybatisPersistence;
import com.workflow.repository.AuditLogRepository;
import com.workflow.repository.InstanceRepository;
import com.workflow.repository.ProcessRepository;
import com.workflow.repository.TaskRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

/**
 * MyBatis-Plus 版测试基类 - 用 MybatisPersistence 装配引擎,与 InMemory/JPA 互不干扰。
 *
 * 关键:
 *  - 每个仓储自己开 inSession(短事务),简单稳。
 *  - 每个用例 clearTables 保证独立。
 *  - 复用 JPA 版完全相同的用例(WorkflowEngine 行为与仓储实现无关)。
 */
public abstract class MybatisEngineTestBase {

    protected static MybatisPersistence mb;
    protected ProcessRepository procRepo;
    protected InstanceRepository instRepo;
    protected TaskRepository taskRepo;
    protected AuditLogRepository auditLogRepo;
    protected WorkflowEngine engine;

    @BeforeAll
    static void startMybatis() {
        mb = MybatisPersistence.getDefault();
        mb.init();
    }

    @AfterAll
    static void stopMybatis() {
        if (mb != null) {
            mb.close();
        }
    }

    @BeforeEach
    void setUp() {
        mb.clearTables();
        procRepo = mb.processRepo();
        instRepo = mb.instanceRepo();
        taskRepo = mb.taskRepo();
        auditLogRepo = mb.auditLogRepo();
        engine = new WorkflowEngine(procRepo, instRepo, taskRepo, null, auditLogRepo);
    }

    protected ProcessDefinition register(ProcessDefinition def) {
        procRepo.save(def);
        return def;
    }

    protected ProcessBuilder simple(String key) {
        return ProcessBuilder.create(key);
    }

    protected Candidate any(String... users) { return Candidate.ofAny(users); }
    protected Candidate all(String... users) { return Candidate.ofAll(users); }
}
