package com.workflow.runtime;

import com.workflow.enums.CommentType;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 审批意见 —— 人在流程上说过的话。
 *
 * <p><b>与 {@link AuditLog} 的分工</b>：审计记「引擎发生了什么」（谁调了什么接口、状态怎么变），
 * 意见记「人表达了什么」（同意 / 驳回理由 / 说明）。前者是系统流水，后者是审批过程的
 * <b>一等证据</b> —— 归档、导出、责任追溯都靠它。
 *
 * <p><b>为什么不塞进 {@code AuditLog.detail} 凑合</b>：审计日志受保留策略清理
 * （{@code HistoryRetention.purgeBefore} → {@code deleteClosedBefore}），
 * 意见落在那里等于放在一个会过期的地方，而归档恰恰要求长期保留。
 * 因此意见有独立的表与独立的清理时机，<b>不随历史保留策略被删</b>。
 *
 * <p>{@code seq} 与历史表同理：时间戳只到毫秒，同一毫秒内的多条意见用随机 UUID 定序
 * 等于把审批链顺序交给运气（历史活动表上实测踩过：manager 排到 apply 前面）。
 *
 * <p>不可变对象：创建后字段不可修改。仓储按 id 幂等覆盖，重新构造走 {@link #reconstruct}。
 */
public final class Comment {

    /** 同毫秒内的次级排序键，见类注释。 */
    private static final AtomicLong SEQ_GEN = new AtomicLong();

    private final String id;
    private final String instanceId;
    private final String taskId;    // 可为 null：流程级意见（不针对某张待办）
    private final String nodeId;    // 可为 null
    private final String userId;    // 发表人
    private final CommentType type;
    private final String message;   // 可为 null：如「同意」这类无可述内容
    private final long createTime;
    private final long seq;

    public Comment(String instanceId, String taskId, String nodeId,
                   String userId, CommentType type, String message) {
        this(UUID.randomUUID().toString(), instanceId, taskId, nodeId, userId, type, message,
                System.currentTimeMillis(), SEQ_GEN.incrementAndGet());
    }

    private Comment(String id, String instanceId, String taskId, String nodeId,
                    String userId, CommentType type, String message, long createTime, long seq) {
        this.id = Objects.requireNonNull(id, "id 不能为空");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId 不能为空");
        this.taskId = taskId;
        this.nodeId = nodeId;
        this.userId = Objects.requireNonNull(userId, "userId 不能为空");
        this.type = Objects.requireNonNull(type, "type 不能为空");
        this.message = message;
        this.createTime = createTime;
        this.seq = seq;
    }

    /** 仓储专用：从已落库的行重建（保留原始 id / 时间 / seq，不能被查询改写）。 */
    public static Comment reconstruct(String id, String instanceId, String taskId, String nodeId,
                                      String userId, CommentType type, String message,
                                      long createTime, long seq) {
        return new Comment(id, instanceId, taskId, nodeId, userId, type, message, createTime, seq);
    }

    public String getId() { return id; }
    public String getInstanceId() { return instanceId; }
    public String getTaskId() { return taskId; }
    public String getNodeId() { return nodeId; }
    public String getUserId() { return userId; }
    public CommentType getType() { return type; }
    public String getMessage() { return message; }
    public long getCreateTime() { return createTime; }
    public long getSeq() { return seq; }

    /** 是否有可读文本 —— 空消息不算「有意见」，导出时按此决定是否渲染气泡。 */
    public boolean hasMessage() {
        return message != null && !message.isBlank();
    }

    @Override
    public String toString() {
        return "Comment[" + id.substring(0, 8) + " inst="
                + instanceId.substring(0, Math.min(8, instanceId.length()))
                + " task=" + (taskId == null ? "-" : taskId.substring(0, 8))
                + " by=" + userId + " type=" + type + " msg="
                + (hasMessage() ? message : "-") + "]";
    }
}
