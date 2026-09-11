package com.workflow.engine;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 候选组无法展开为具体用户 —— 引擎拒绝创建"谁都办不了"的任务。
 *
 * <p><b>为什么是抛，而不是降级告警</b>：早先的做法是保留未展开的任务并输出告警，
 * 结果是流程静默停在没人能办的任务上 —— 调用方的 {@code start} 返回成功、
 * 界面显示"进行中"，直到有人问"流程怎么不动了"才查到组织架构压根没接上。
 * 把失败提前到<b>创建任务那一刻</b>抛出，问题在事发点、由发起方看见。
 *
 * <p>兜底不靠"放宽校验"，而靠 {@link WorkflowEngine#adminTransferTask}：
 * 运维可以在组织架构修好之前，先把任务强行改派给具体的人。
 */
public class GroupResolutionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String nodeId;
    private final Set<String> groupIds;

    public GroupResolutionException(String message, String nodeId, Set<String> groupIds) {
        super(message);
        this.nodeId = nodeId;
        this.groupIds = immutable(groupIds);
    }

    public GroupResolutionException(String message, String nodeId, Set<String> groupIds, Throwable cause) {
        super(message, cause);
        this.nodeId = nodeId;
        this.groupIds = immutable(groupIds);
    }

    /** 出问题的节点 ID；启动期预检时为 null（尚未定位到具体节点）。 */
    public String getNodeId() { return nodeId; }

    /** 未能展开的组名。 */
    public Set<String> getGroupIds() { return groupIds; }

    private static Set<String> immutable(Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(new LinkedHashSet<>(ids));
    }
}
