package com.workflow.tests.crossdb;

import com.workflow.persistence.jpa.JpaPersistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.HashMap;
import java.util.Map;

/**
 * JPA + PostgreSQL 跨库测试 - 复用 AbstractCrossDbTest 全部用例
 *
 * 说明:依赖本机 Docker(Testcontainers 自动拉取 postgres:16-alpine 镜像)。
 */
class JpaPostgresCrossDbTest extends AbstractCrossDbTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("workflow_db")
            .withUsername("wfuser")
            .withPassword("wfpass");

    static JpaPersistence jpa;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        jpa = JpaPersistence.getDefault();
        Map<String, String> props = new HashMap<>();
        props.put("jakarta.persistence.jdbc.driver", "org.postgresql.Driver");
        props.put("jakarta.persistence.jdbc.url", POSTGRES.getJdbcUrl());
        props.put("jakarta.persistence.jdbc.user", POSTGRES.getUsername());
        props.put("jakarta.persistence.jdbc.password", POSTGRES.getPassword());
        props.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        props.put("hibernate.hbm2ddl.auto", "none");
        jpa.init(props);
        procRepo = jpa.processRepo();
        instRepo = jpa.instanceRepo();
        taskRepo = jpa.taskRepo();
    }

    @AfterAll
    static void stop() {
        if (jpa != null) {
            jpa.close();
        }
        POSTGRES.stop();
    }

    @Override
    protected void clearTables() {
        jpa.inTransaction(em -> {
            em.createNativeQuery("DELETE FROM wf_task").executeUpdate();
            em.createNativeQuery("DELETE FROM wf_token").executeUpdate();
            em.createNativeQuery("DELETE FROM wf_instance").executeUpdate();
            em.createNativeQuery("DELETE FROM wf_process_def").executeUpdate();
            return null;
        });
    }
}
