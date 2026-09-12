package com.workflow.persistence.migrate;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

/**
 * Flyway 迁移入口 - 统一 DDL 管理
 *
 * 两种持久化实现(JPA / MyBatis-Plus)在初始化时都调用本类执行迁移,
 * 保证同一份 schema 脚本(V1__init.sql)在所有数据库上保持一致:
 *   - H2(内存/演示)
 *   - MySQL(生产)
 *   - PostgreSQL(生产)
 */
public final class FlywayMigrator {

    private static final Logger log = LoggerFactory.getLogger(FlywayMigrator.class);

    private FlywayMigrator() {
    }

    /** 基于已有 DataSource 执行迁移(MyBatis 用) */
    public static void migrate(DataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
        int applied = flyway.migrate().migrationsExecuted;
        log.info("[Flyway] 迁移完成, 执行脚本数={}", applied);
    }

    /**
     * 基于已有 DataSource 执行迁移,并<b>指定 schema history 表名</b>。
     *
     * <p>嵌入式集成时必须用这个重载:引擎与宿主<b>同库</b>时,两者若都用 Flyway 默认表名
     * {@code flyway_schema_history},就会共用同一张历史表 —— 宿主已有的 {@code V1__xxx}
     * 会让引擎的 {@code V1__init} 被判为「已执行」而<b>静默跳过建表</b>,或直接抛
     * 「Found more than one migration with version」。给引擎一张自己的历史表即彻底隔离。
     *
     * @param historyTable 引擎专用的 schema history 表名(约定 {@code wf_schema_history})
     */
    public static void migrate(DataSource dataSource, String historyTable) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .table(historyTable)
                .load();
        int applied = flyway.migrate().migrationsExecuted;
        log.info("[Flyway] 迁移完成, history={}, 执行脚本数={}", historyTable, applied);
    }

    /** 基于 JDBC 参数执行迁移(JPA 用,EMF 创建前) */
    public static void migrate(String jdbcUrl, String user, String password) {
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, user, password)
                .locations("classpath:db/migration")
                .load();
        int applied = flyway.migrate().migrationsExecuted;
        log.info("[Flyway] 迁移完成 url={} 执行脚本数={}", jdbcUrl, applied);
    }
}
