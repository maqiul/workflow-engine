package com.workflow.persistence.mybatis.repository;

import org.apache.ibatis.session.SqlSession;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * MyBatis 侧从 {@code wf_sequence} 取全局单调序号（跨重启、跨节点，替代进程内 AtomicLong）。
 *
 * <p>用 {@link SqlSession#getConnection()} 拿到的<b>事务连接</b>直接执行 UPDATE+SELECT，
 * 因此取号与业务写入在同一事务、同一连接内 —— 回滚时序号消耗也无所谓（序列允许有空洞，
 * 只要求单调不撞）。避开新建 mapper/注册，逻辑集中一处。
 */
final class MybatisSequenceSupport {

    private MybatisSequenceSupport() { }

    static long nextSeq(SqlSession session, String name) {
        Connection c = session.getConnection();
        try (PreparedStatement inc = c.prepareStatement(
                "UPDATE wf_sequence SET next_val = next_val + 1 WHERE name = ?")) {
            inc.setString(1, name);
            inc.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("序列取号失败: " + name, e);
        }
        try (PreparedStatement q = c.prepareStatement(
                "SELECT next_val FROM wf_sequence WHERE name = ?")) {
            q.setString(1, name);
            try (ResultSet rs = q.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("序列未初始化，检查 V14: " + name);
                }
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("序列取值失败: " + name, e);
        }
    }
}
