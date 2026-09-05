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
        // 历史活动：漏掉这行不会编译报错，只在 getMapper 时抛运行时异常
        configuration.addMapper(com.workflow.persistence.mybatis.mapper.WfHistActivityMapper.class);
        configuration.addMapper(com.workflow.persistence.mybatis.mapper.WfHistTaskMapper.class);
        // 事件网关
        configuration.addMapper(com.workflow.persistence.mybatis.mapper.WfEventMapper.class);
        // DMN 决策表和历史
        configuration.addMapper(com.workflow.persistence.mybatis.mapper.WfDecisionTableMapper.class);
        configuration.addMapper(com.workflow.persistence.mybatis.mapper.WfDecisionHistoryMapper.class);
        // 驼峰映射默认开启
        configuration.setMapUnderscoreToCamelCase(true);
        sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    /**
     * 事务模板 —— <b>事务感知</b>：引擎已在事务中时复用同一个 SqlSession，
     * 否则自开独立短事务。
     *
     * <p>与 {@code JpaPersistence.inTransaction} 同理：一次 {@code completeTask}
     * 要写 instance / token / task / audit 四张表，各仓储各自开 session 就等于
     * 四次独立提交，中途失败留下半完成状态。
     *
     * <p>MyBatis 无需像 Hibernate 那样手动 flush —— SQL 立即下发；而任何 update
     * 都会清掉 session 级本地缓存，所以同一 session 内读写交替能看到最新值。
     */
    public <T> T inSession(Function<SqlSession, T> action) {
        if (com.workflow.tx.TransactionContext.isActive()) {
            return inSharedSession(action);
        }
        return inStandaloneSession(action);
    }

    /** 独立短事务：供引擎之外的手工调用（如测试清表）使用。 */
    private <T> T inStandaloneSession(Function<SqlSession, T> action) {
        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            T result = action.apply(session);
            session.commit();
            return result;
        }
    }

    /** 加入引擎已开启的事务，复用本线程本事务的 SqlSession。 */
    private <T> T inSharedSession(Function<SqlSession, T> action) {
        ManagedSession holder = com.workflow.tx.TransactionContext.attached(ManagedSession.class);
        if (holder == null) {
            final SqlSession session = sqlSessionFactory.openSession(false);
            holder = new ManagedSession(session);
            com.workflow.tx.TransactionContext.attachIfAbsent(holder);
            com.workflow.tx.TransactionContext.beforeCommit(() -> {
                try {
                    session.commit();
                } catch (RuntimeException ex) {
                    try {
                        session.rollback();
                    } finally {
                        session.close();
                    }
                    throw ex;
                } finally {
                    session.close();
                }
            });
            com.workflow.tx.TransactionContext.onRollback(() -> {
                try {
                    session.rollback();
                } finally {
                    session.close();
                }
            });
        }
        return action.apply(holder.session);
    }

    /** 本事务共享的 SqlSession 包装（用作 {@code TransactionContext} 的挂载标识）。 */
    private static final class ManagedSession {
        final SqlSession session;
        ManagedSession(SqlSession session) { this.session = session; }
    }

    /** 清理所有表数据(测试用) */
    public void clearTables() {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DELETE FROM wf_hist_task");
            st.execute("DELETE FROM wf_hist_activity");
            st.execute("DELETE FROM wf_event");
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

    /** 提供 HistoryRepository（历史活动区间，见 README §18）。 */
    public com.workflow.repository.HistoryRepository historyRepo() {
        return new com.workflow.persistence.mybatis.repository.MybatisHistoryRepository(this);
    }

    /** 提供 EventRepository（事件网关：消息/信号/定时器）。 */
    public com.workflow.repository.EventRepository eventRepo() {
        return new com.workflow.persistence.mybatis.repository.MybatisEventRepository(this);
    }

    /** 提供 DecisionRepository（DMN 决策表）。 */
    public com.workflow.dmn.DecisionRepository decisionRepo() {
        return new com.workflow.persistence.mybatis.repository.MybatisDecisionRepository(this);
    }

    /** 提供 DecisionHistoryRepository（DMN 决策历史）。 */
    public com.workflow.dmn.DecisionHistoryRepository decisionHistoryRepo() {
        return new com.workflow.persistence.mybatis.repository.MybatisDecisionHistoryRepository(this);
    }
}
