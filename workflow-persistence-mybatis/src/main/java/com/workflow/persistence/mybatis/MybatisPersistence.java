package com.workflow.persistence.mybatis;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.workflow.persistence.migrate.FlywayMigrator;
import com.workflow.persistence.mybatis.mapper.WfAuditLogMapper;
import com.workflow.persistence.mybatis.mapper.WfProcessDefMapper;
import com.workflow.persistence.mybatis.mapper.WfInstanceMapper;
import com.workflow.persistence.mybatis.mapper.WfTaskMapper;
import com.workflow.persistence.mybatis.mapper.WfTokenMapper;
import com.workflow.persistence.mybatis.repository.MybatisAuditLogRepository;
import com.workflow.persistence.mybatis.repository.MybatisInstanceRepository;
import com.workflow.persistence.mybatis.repository.MybatisProcessRepository;
import com.workflow.persistence.mybatis.repository.MybatisTaskRepository;
import com.workflow.repository.AuditLogRepository;
import com.workflow.repository.InstanceRepository;
import com.workflow.repository.ProcessRepository;
import com.workflow.repository.TaskRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.function.Function;

/**
 * MyBatis-Plus 持久化入口(单例工厂)
 *
 * 无 Spring 轻量用法:
 *  1. HikariCP 数据源(默认 H2 内存库,支持 MySQL/PostgreSQL 切换)
 *  2. Flyway 统一建表(db/migration/V1__init.sql,跨库同一份 DDL)
 *  3. MybatisConfiguration + MybatisSqlSessionFactoryBuilder 构建 SqlSessionFactory
 *  4. inSession 模板:每次操作独立 SqlSession + 事务,与 JpaPersistence.inTransaction 对齐
 *
 * 用法:
 *  <pre>
 *  MybatisPersistence.init();
 *  ProcessRepository  pr = MybatisPersistence.getDefault().processRepo();
 *  ...
 *  MybatisPersistence.close();
 *  </pre>
 */
public final class MybatisPersistence {

    private static final Logger log = LoggerFactory.getLogger(MybatisPersistence.class);

    private static volatile MybatisPersistence defaultInstance;

    private HikariDataSource dataSource;
    private SqlSessionFactory sqlSessionFactory;

    // 仓储实例(懒创建)
    private volatile MybatisProcessRepository processRepo;
    private volatile MybatisInstanceRepository instanceRepo;
    private volatile MybatisTaskRepository taskRepo;
    private volatile MybatisAuditLogRepository auditLogRepo;

    private MybatisPersistence() {
    }

    public static MybatisPersistence getDefault() {
        if (defaultInstance == null) {
            synchronized (MybatisPersistence.class) {
                if (defaultInstance == null) {
                    defaultInstance = new MybatisPersistence();
                }
            }
        }
        return defaultInstance;
    }

    /** 初始化(默认 H2 内存库) */
    public synchronized void init() {
        if (sqlSessionFactory != null) {
            log.warn("MybatisPersistence 已初始化,忽略重复调用");
            return;
        }
        long t0 = System.currentTimeMillis();
        initDataSource("jdbc:h2:mem:workflow_mybatis;DB_CLOSE_DELAY=-1", "sa", "");
        FlywayMigrator.migrate(dataSource);
        buildSqlSessionFactory();
        log.info("MybatisPersistence 初始化完成,耗时 {}ms", System.currentTimeMillis() - t0);
    }

    /** 初始化(自定义 JDBC URL,如 MySQL/PostgreSQL 演示) */
    public synchronized void init(String jdbcUrl, String user, String password) {
        if (sqlSessionFactory != null) {
            log.warn("MybatisPersistence 已初始化,忽略重复调用");
            return;
        }
        long t0 = System.currentTimeMillis();
        initDataSource(jdbcUrl, user, password);
        FlywayMigrator.migrate(dataSource);
        buildSqlSessionFactory();
        log.info("MybatisPersistence 初始化完成 url={}, 耗时 {}ms", jdbcUrl, System.currentTimeMillis() - t0);
    }

    /** 按 JDBC URL 自动识别驱动类 */
    private static String driverOf(String jdbcUrl) {
        if (jdbcUrl.startsWith("jdbc:mysql:")) {
            return "com.mysql.cj.jdbc.Driver";
        }
        if (jdbcUrl.startsWith("jdbc:postgresql:")) {
            return "org.postgresql.Driver";
        }
        return "org.h2.Driver";
    }

    private void initDataSource(String jdbcUrl, String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(password);
        config.setDriverClassName(driverOf(jdbcUrl));
        config.setMaximumPoolSize(8);
        config.setMinimumIdle(2);
        config.setPoolName("workflow-mybatis");
        dataSource = new HikariDataSource(config);
    }

    private void buildSqlSessionFactory() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("workflow", new JdbcTransactionFactory(), dataSource));
        // 注册 Mapper 接口
        configuration.addMapper(WfProcessDefMapper.class);
        configuration.addMapper(WfInstanceMapper.class);
        configuration.addMapper(WfTokenMapper.class);
        configuration.addMapper(WfTaskMapper.class);
        configuration.addMapper(WfAuditLogMapper.class);
        // 驼峰映射默认开启
        configuration.setMapUnderscoreToCamelCase(true);
        sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    /** 事务模板:开 SqlSession → 执行 → commit;异常自动回滚 */
    public <T> T inSession(Function<SqlSession, T> action) {
        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            T result = action.apply(session);
            session.commit();
            return result;
        } catch (RuntimeException ex) {
            throw ex;
        }
    }

    /** 清理所有表数据(测试用) */
    public void clearTables() {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DELETE FROM wf_audit_log");
            st.execute("DELETE FROM wf_task");
            st.execute("DELETE FROM wf_token");
            st.execute("DELETE FROM wf_instance");
            st.execute("DELETE FROM wf_process_def");
        } catch (Exception ex) {
            throw new RuntimeException("清理表数据失败: " + ex.getMessage(), ex);
        }
    }

    public synchronized void close() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
            sqlSessionFactory = null;
            processRepo = null;
            instanceRepo = null;
            taskRepo = null;
            auditLogRepo = null;
        }
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public SqlSessionFactory sqlSessionFactory() {
        return sqlSessionFactory;
    }

    public ProcessRepository processRepo() {
        if (processRepo == null) {
            processRepo = new MybatisProcessRepository(this);
        }
        return processRepo;
    }

    public InstanceRepository instanceRepo() {
        if (instanceRepo == null) {
            instanceRepo = new MybatisInstanceRepository(this);
        }
        return instanceRepo;
    }

    public TaskRepository taskRepo() {
        if (taskRepo == null) {
            taskRepo = new MybatisTaskRepository(this);
        }
        return taskRepo;
    }

    public AuditLogRepository auditLogRepo() {
        if (auditLogRepo == null) {
            auditLogRepo = new MybatisAuditLogRepository(this);
        }
        return auditLogRepo;
    }
}
