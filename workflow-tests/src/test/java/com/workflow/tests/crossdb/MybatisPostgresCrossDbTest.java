package com.workflow.tests.crossdb;

import com.workflow.persistence.mybatis.MybatisPersistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * MyBatis-Plus + PostgreSQL 跨库测试 - 复用 AbstractCrossDbTest 全部用例
 *
 * 说明:依赖本机 Docker(Testcontainers 自动拉取 postgres:16-alpine 镜像)。
 */
class MybatisPostgresCrossDbTest extends AbstractCrossDbTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("workflow_db")
            .withUsername("wfuser")
            .withPassword("wfpass");

    static MybatisPersistence mb;

    @BeforeAll
    static void start() {
        requireDocker();
        POSTGRES.start();
        mb = MybatisPersistence.getDefault();
        mb.init(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        procRepo = mb.processRepo();
        instRepo = mb.instanceRepo();
        taskRepo = mb.taskRepo();
    }

    @AfterAll
    static void stop() {
        if (mb != null) {
            mb.close();
        }
        POSTGRES.stop();
    }

    @Override
    protected void clearTables() {
        mb.clearTables();
    }
}
