package com.workflow.repository;

import com.workflow.enums.InstanceStatus;

/**
 * 实例分组计数结果 —— 按「流程 key + 状态」聚合的一行。
 *
 * <p>监控仪表盘用它替代 {@code findAll()}：后者会为每个实例重建 Token/Task/变量
 * （JPA 下是 N 次子查询 + 反射），而分布统计只需 GROUP BY process_key, status 的
 * 轻量结果集。
 */
public record ProcessStatusCount(String processKey, InstanceStatus status, long count) { }
