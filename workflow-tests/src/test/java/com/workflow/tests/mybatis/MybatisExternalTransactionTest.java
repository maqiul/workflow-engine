package com.workflow.tests.mybatis;

import com.workflow.engine.WorkflowEngine;
import com.workflow.engine.WorkflowEngineBuilder;
import com.workflow.enums.TaskStatus;
import com.workflow.tests.MybatisEngineTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 嵌入式集成:引擎写入必须并入<b>宿主事务</b>(v3.19.0)。
 *
 * <p>宿主(OA)自己管事务 —— Spring 的 {@code @Transactional} 把连接绑在线程上。
 * 引擎若从自建连接池另取一条连接,两边就是两笔独立事务,宿主回滚不会回滚引擎写入,
 * 于是留下「宿主业务失败但流程已推进」这类无法自愈的脏数据。
 *
 * <p>本基类所在路径下,引擎表与宿主业务表<b>同库</b>,宿主用裸 JDBC 事务模拟
 * (测试不引 Spring,引擎保持零依赖):{@code hostTx} 相当于
 * {@code TransactionSynchronizationManager} 里绑定的那条连接。
 *
 * <p>{@link #withoutProviderHostRollbackLeavesEngineWrites()} 是<b>对照组</b>,
 * 它复现的正是改造前的缺口 —— 若哪天该用例变红,说明缺口的成因没被理解对。
 */
@DisplayName("MyBatis 引擎接入宿主事务")
class MybatisExternalTransactionTest extends MybatisEngineTestBase {

    /** 当前线程的宿主事务连接;null 表示没有宿主事务(引擎走自身事务边界) */
    private final ThreadLocal<Connection> hostTx = new ThreadLocal<>();

    @AfterEach
    void clearHostTransaction() {
        mb.externalTransactionProvider(null);
        hostTx.remove();
    }

    private void registerFlow(String key) {
        register(simple(key)
                .start("start")
                .userTask("apply", "申请", any("u0"))
                .userTask("review", "审批", any("u1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "review")
                .connect("review", "end")
                .build());
    }

    /** 装配一个"认宿主事务"的引擎:连接由 provider 提供 */
    private WorkflowEngine hostAwareEngine() {
        mb.externalTransactionProvider(hostTx::get);
        return WorkflowEngineBuilder.builder(procRepo, instRepo, taskRepo)
                .auditLogRepository(auditLogRepo)
                .build();
    }

    /** 用一条<b>独立</b>连接数实例行数 —— 绕开引擎,避免用被测对象验证自身 */
    private int countInstances(String instanceId) {
        try (Connection conn = mb.dataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM wf_instance WHERE id = ?")) {
            ps.setString(1, instanceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("统计 wf_instance 失败", ex);
        }
    }

    @Test
    @DisplayName("宿主事务里引擎写入对外不可见 —— 证明写入真的挂在宿主那条连接上")
    void engineWritesRideOnHostConnection() {
        registerFlow("mb-host-visibility");
        WorkflowEngine hostEngine = hostAwareEngine();

        try (Connection hostConn = mb.dataSource().getConnection()) {
            hostConn.setAutoCommit(false);
            hostTx.set(hostConn);

            String instanceId = hostEngine.start("mb-host-visibility", Map.of());

            assertThat(countInstancesOn(hostConn, instanceId))
                    .as("宿主这条连接上应当看得见自己的写入")
                    .isEqualTo(1);
            assertThat(countInstances(instanceId))
                    .as("换一条连接就看不见 —— 说明引擎没提交,写入确实在宿主事务里")
                    .isZero();

            hostConn.rollback();
            hostConn.setAutoCommit(true);
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        } finally {
            hostTx.remove();
            hostEngine.shutdown();
        }
    }

    @Test
    @DisplayName("宿主回滚 → 引擎写入一并消失(v3.19.0 的核心保证)")
    void hostRollbackDiscardsEngineWrites() {
        registerFlow("mb-host-rollback");
        WorkflowEngine hostEngine = hostAwareEngine();

        String instanceId;
        try (Connection hostConn = mb.dataSource().getConnection()) {
            hostConn.setAutoCommit(false);
            hostTx.set(hostConn);

            instanceId = hostEngine.start("mb-host-rollback", Map.of());
            assertThat(countInstancesOn(hostConn, instanceId)).isEqualTo(1);

            // 宿主后续业务失败 —— 应用层决定回滚
            hostConn.rollback();
            hostConn.setAutoCommit(true);
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        } finally {
            hostTx.remove();
            hostEngine.shutdown();
        }

        assertThat(countInstances(instanceId))
                .as("宿主已回滚,引擎写入必须一起消失")
                .isZero();
    }

    @Test
    @DisplayName("宿主提交 → 引擎写入保留,且流程可在同一事务内继续推进")
    void hostCommitKeepsEngineWritesAndFlowCanAdvance() {
        registerFlow("mb-host-commit");
        WorkflowEngine hostEngine = hostAwareEngine();

        String instanceId;
        try (Connection hostConn = mb.dataSource().getConnection()) {
            hostConn.setAutoCommit(false);
            hostTx.set(hostConn);

            instanceId = hostEngine.start("mb-host-commit", Map.of());

            // 同一个宿主事务里连续做两步引擎动作:开流程 + 完成第一个任务
            String applyTaskId = hostEngine.getInstance(instanceId).getTasks().stream()
                    .filter(t -> "apply".equals(t.getNodeId()))
                    .findFirst().orElseThrow().getId();
            hostEngine.completeTask(applyTaskId, "u0", true);

            assertThat(taskRepo.findById(applyTaskId).getStatus())
                    .as("同一条连接上,已完成的动作彼此可见")
                    .isEqualTo(TaskStatus.COMPLETED);

            hostConn.commit();
            hostConn.setAutoCommit(true);
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        } finally {
            hostTx.remove();
            hostEngine.shutdown();
        }

        assertThat(countInstances(instanceId))
                .as("宿主提交后写入自然保留")
                .isEqualTo(1);
        assertThat(taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> "review".equals(t.getNodeId()))
                .findFirst().orElseThrow().getStatus())
                .as("流程确实推进到了下一节点")
                .isEqualTo(TaskStatus.PENDING);
    }

    @Test
    @DisplayName("对照组:未注入 provider 时宿主回滚管不到引擎写入(= 改造前的缺口)")
    void withoutProviderHostRollbackLeavesEngineWrites() {
        registerFlow("mb-no-provider-rollback");

        String instanceId;
        try (Connection hostConn = mb.dataSource().getConnection()) {
            hostConn.setAutoCommit(false);
            // 刻意不注入 provider:引擎仍从自建池另取连接、自成事务

            instanceId = engine.start("mb-no-provider-rollback", Map.of());

            hostConn.rollback();
            hostConn.setAutoCommit(true);
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }

        assertThat(countInstances(instanceId))
                .as("宿主回滚了,流程却已经推进 —— 这正是 v3.19.0 要消除的缺口")
                .isEqualTo(1);
    }

    /** 在指定连接上统计实例行数(用于观察"宿主连接自己看得见、别处看不见") */
    private int countInstancesOn(Connection conn, String instanceId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM wf_instance WHERE id = ?")) {
            ps.setString(1, instanceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
