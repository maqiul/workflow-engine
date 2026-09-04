package com.workflow.tests;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.WorkflowEngine;
import com.workflow.engine.WorkflowEngineBuilder;
import com.workflow.repository.AuditLogRepository;
import com.workflow.repository.InMemoryAuditLogRepository;
import com.workflow.repository.InMemoryInstanceRepository;
import com.workflow.repository.InMemoryProcessRepository;
import com.workflow.repository.InMemoryTaskRepository;

/**
 * 测试基类 - 统一装配引擎和仓储,简化测试用例
 */
public abstract class EngineTestBase {

    protected InMemoryProcessRepository procRepo;
    protected InMemoryInstanceRepository instRepo;
    protected InMemoryTaskRepository taskRepo;
    protected InMemoryAuditLogRepository auditLogRepo;
    protected WorkflowEngine engine;

    protected void setUp() {
        procRepo = new InMemoryProcessRepository();
        instRepo = new InMemoryInstanceRepository();
        taskRepo = new InMemoryTaskRepository();
        auditLogRepo = new InMemoryAuditLogRepository();
        engine = WorkflowEngineBuilder.builder(procRepo, instRepo, taskRepo)
                .auditLogRepository(auditLogRepo)
                .build();
    }

    /** 注册流程 */
    protected ProcessDefinition register(ProcessDefinition def) {
        procRepo.save(def);
        return def;
    }

    /** 简单流程构造器 */
    protected ProcessBuilder simple(String key) {
        return ProcessBuilder.create(key);
    }

    protected Candidate any(String... users) { return Candidate.ofAny(users); }
    protected Candidate all(String... users) { return Candidate.ofAll(users); }
}