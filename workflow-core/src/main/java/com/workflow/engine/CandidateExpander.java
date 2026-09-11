package com.workflow.engine;

import com.workflow.definition.Candidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 候选组展开 —— 任务创建时的唯一展开出口。
 *
 * <p>把定义层的候选组解析成具体用户并并入任务候选人。两条不变式：
 * <ol>
 *   <li><b>失败即抛出</b>：未配解析器、组内无人、解析抛异常 —— 一律
 *       {@link GroupResolutionException}。引擎不创建"候选人空集"的任务，
 *       因为那等于把流程静默钉死在没人能办的节点上。</li>
 *   <li><b>快照 + 留痕</b>：只在任务创建时展开一次（此后组成员变动不影响在途任务
 *       —— 实时解析会让事后追责链漂移）；原始组名保留在 {@code groupIds} 里，
 *       供审计与「我所在组的待办」查询。</li>
 * </ol>
 *
 * <p>兜底途径是 {@link WorkflowEngine#adminTransferTask}，不是这里的"宽容处理"。
 * 纯函数式、无状态，便于在引擎之外单独测试。
 */
public final class CandidateExpander {

    private static final Logger log = LoggerFactory.getLogger(CandidateExpander.class);

    private CandidateExpander() {
    }

    /**
     * 展开候选人里的候选组。
     *
     * @param raw      定义层候选人（可能含组）；为 null 或不含组时原样返回
     * @param resolver 组织架构解析器；为 null 时抛 {@link GroupResolutionException}
     * @param nodeId   节点 ID，用于错误定位与日志
     * @return 展开后的候选人（{@code groupIds} 原样保留）
     * @throws GroupResolutionException 任何一个组无法解析出具体用户
     */
    public static Candidate expand(Candidate raw, GroupResolver resolver, String nodeId) {
        if (raw == null || !raw.hasGroups()) {
            return raw;
        }
        if (resolver == null) {
            throw new GroupResolutionException(
                    "节点 " + nodeId + " 的候选组 " + raw.getGroupIds() + " 无法展开：引擎未配置 GroupResolver。"
                            + "请注入组织架构解析器（engine.setGroupResolver(...)），"
                            + "或改用 adminTransferTask 把任务直接指派给具体的人",
                    nodeId, raw.getGroupIds());
        }

        Set<String> resolved = new LinkedHashSet<>();
        Map<String, String> failures = new LinkedHashMap<>();
        for (String groupId : raw.getGroupIds()) {
            try {
                Set<String> members = resolver.resolve(groupId);
                if (members == null || members.isEmpty()) {
                    failures.put(groupId, "组内无成员");
                } else {
                    resolved.addAll(members);
                }
            } catch (RuntimeException ex) {
                failures.put(groupId, ex.toString());
            }
        }

        // 组名绝不能当成用户：万一解析器把组名原样返回，这里剔除
        resolved.removeAll(raw.getGroupIds());

        if (!failures.isEmpty()) {
            throw new GroupResolutionException(
                    "节点 " + nodeId + " 的候选组展开失败：" + failures
                            + "。该任务不会以「无人可办」的状态创建 —— "
                            + "请修复组织架构数据或解析器，或用 adminTransferTask 改派",
                    nodeId, failures.keySet());
        }
        if (resolved.isEmpty()) {
            throw new GroupResolutionException(
                    "节点 " + nodeId + " 的候选组 " + raw.getGroupIds()
                            + " 未解析出任何用户（解析器可能把组名自身当成了成员）",
                    nodeId, raw.getGroupIds());
        }

        Candidate expanded = raw.withExpandedUsers(resolved);
        if (log.isDebugEnabled()) {
            log.debug("[CandidateExpander] 节点 {} 展开 {} -> {}", nodeId, raw, expanded);
        }
        return expanded;
    }
}
