package com.workflow.tests.crossdb;

import com.workflow.persistence.jpa.JpaPersistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.MySQLContainer;

import java.util.HashMap;
import java.util.Map;

/**
 * JPA + MySQL 跨库测试 - 复用 AbstractCrossDbTest 全部用例
 *
 * 说明:依赖本机 Docker(Testcontainers 自动拉取 mysql:8.4 镜像)。
 */
class JpaMysqlCrossDbTest extends AbstractCrossDbTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("workflow_db")
            .withUsername("wfuser")
            .withPassword("wfpass");

    static JpaPersistence jpa;

    @BeforeAll
    static void start() {
        MYSQL.start();
        jpa = JpaPersistence.getDefault();
        Map<String, String> props = new HashMap<>();
        props.put("jakarta.persistence.jdbc.driver", "com.mysql.cj.jdbc.Driver");
        props.put("jakarta.persistence.jdbc.url", MYSQL.getJdbcUrl());
        props.put("jakarta.persistence.jdbc.user", MYSQL.getUsername());
        props.put("jakarta.persistence.jdbc.password", MYSQL.getPassword());
        props.put("hibernate.dialect", "org.hibernate.dialect.MySQLDialect");
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
        MYSQL.stop();
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
