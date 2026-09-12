package com.workflow.tests;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.WorkflowEngine;
import com.workflow.engine.WorkflowEngineBuilder;
import com.workflow.persistence.jpa.JpaPersistence;
import com.workflow.repository.AuditLogRepository;
import com.workflow.repository.InstanceRepository;
import com.workflow.repository.ProcessRepository;
import com.workflow.repository.TaskRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

/**
 * JPA 版测试基类 - 用 JpaPersistence 装配引擎,与 InMemory 版互不干扰。
 *
 * 关键:
 *  - 每个仓储自己开 inTransaction(短事务),简单稳。
 *  - 每个用例 clearTables 保证独立。
 *  - JPA 子任务的核心修复:WorkflowEngine 在创建新 Task 后必须显式
 *    instanceRepo.save(instance),让 JPA 测试能查到全部 task。
 */
public abstract class JpaEngineTestBase {

    protected static JpaPersistence jpa;
    protected ProcessRepository procRepo;
    protected InstanceRepository instRepo;
    protected TaskRepository taskRepo;
    protected AuditLogRepository auditLogRepo;
    protected WorkflowEngine engine;

    @BeforeAll
    static void startJpa() {
        jpa = JpaPersistence.getDefault();
        jpa.init();
    }

    @AfterAll
    static void stopJpa() {
        if (jpa != null) {
            jpa.close();
        }
    }

    @BeforeEach
    void setUp() {
        clearTables();
        procRepo = jpa.processRepo();
        instRepo = jpa.instanceRepo();
        taskRepo = jpa.taskRepo();
        auditLogRepo = jpa.auditLogRepo();
        engine = WorkflowEngineBuilder.builder(procRepo, instRepo, taskRepo)
                .auditLogRepository(auditLogRepo)
                .build();
    }

    /** 清空 5 张表的全部数据(测试隔离) */
    private void clearTables() {
        jpa.inTransaction(em -> {
            em.createNativeQuery("DELETE FROM wf_comment").executeUpdate();
            em.createNativeQuery("DELETE FROM wf_audit_log").executeUpdate();
            em.createNativeQuery("DELETE FROM wf_task").executeUpdate();
            em.createNativeQuery("DELETE FROM wf_token").executeUpdate();
            em.createNativeQuery("DELETE FROM wf_instance").executeUpdate();
            em.createNativeQuery("DELETE FROM wf_process_def").executeUpdate();
            return null;
        });
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