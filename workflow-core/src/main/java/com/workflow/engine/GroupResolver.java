package com.workflow.engine;

import java.util.Set;

/**
 * 候选组解析器 —— 引擎与组织架构之间的唯一接缝。
 *
 * <p>引擎<b>不内置</b>组织架构存储：BPMN 的 {@code flowable:candidateGroups} 只把组名
 * 带进引擎，具体"这个组里有谁"由调用方按自己的用户中心（LDAP / OA / 配置表 / 远程接口）
 * 实现本接口并注入。
 *
 * <p>这与 Flowable 的分工一致（它把候选组交给 {@code ACT_ID_} 表 + {@code IdentityService}），
 * 差别只是本项目不替调用方决定组织架构该怎么存。
 *
 * <p><b>实现约定</b>：
 * <ul>
 *   <li>返回 {@code null} 或空集合表示"组不存在 / 组内无人"—— 引擎会告警但不阻断流程</li>
 *   <li>返回值里<b>不要</b>包含组名自身，否则组名会被当成用户写进候选人
 *       （引擎侧另有一道剔除兜底）</li>
 *   <li>本方法在<b>创建任务的路径上同步调用</b>，实现应快速、无副作用、可重入；
 *       不要在这里做慢查询或写操作</li>
 *   <li>抛出运行时异常会被引擎捕获并降级为"该组未展开"，只影响这一个组</li>
 * </ul>
 *
 * <p>展开是<b>快照</b>语义：只在任务创建时解析一次，此后组成员变动不影响在途任务。
 */
@FunctionalInterface
public interface GroupResolver {

    /**
     * 把组名解析为具体用户 ID 集合。
     *
     * @param groupId 组标识（来自流程定义）
     * @return 组内用户 ID；无成员时返回空集合或 null
     */
    Set<String> resolve(String groupId);
}
