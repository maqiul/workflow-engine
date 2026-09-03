package com.workflow.tests.crossdb;

import com.workflow.persistence.mybatis.MybatisPersistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.MySQLContainer;

/**
 * MyBatis-Plus + MySQL 跨库测试 - 复用 AbstractCrossDbTest 全部用例
 *
 * 说明:依赖本机 Docker(Testcontainers 自动拉取 mysql:8.4 镜像)。
 */
class MybatisMysqlCrossDbTest extends AbstractCrossDbTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("workflow_db")
            .withUsername("wfuser")
            .withPassword("wfpass");

    static MybatisPersistence mb;

    @BeforeAll
    static void start() {
        MYSQL.start();
        mb = MybatisPersistence.getDefault();
        mb.init(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        procRepo = mb.processRepo();
        instRepo = mb.instanceRepo();
        taskRepo = mb.taskRepo();
    }

    @AfterAll
    static void stop() {
        if (mb != null) {
            mb.close();
        }
        MYSQL.stop();
    }

    @Override
    protected void clearTables() {
        mb.clearTables();
    }
}
