package com.workflow.runtime;

import com.workflow.enums.TokenStatus;

import java.util.Objects;
import java.util.UUID;

/**
 * 执行令牌 - 流程图中的"游标"
 *
 * 设计动机:
 *  并行网关会把一条流分裂成多条,每条分支需要独立追踪。
 *  汇聚网关需要等待所有到达此处的 Token 都被消耗后再往下走。
 *
 * 状态:
 *  ACTIVE   - 正在某节点上,等待被推进
 *  CONSUMED - 已流过该节点,可被回收
 *
 * 注:会签节点不算分裂(同节点多候选人),因此会签不需要新建 Token。
 *     只有经过 PARALLEL_GATEWAY 时才会分裂/合并 Token。
 */
public final class Token {
    private final String id;
    private final String instanceId;
    private String currentNodeId;
    private TokenStatus status;

    public Token(String instanceId, String currentNodeId) {
        this.id = UUID.randomUUID().toString();
        this.instanceId = Objects.requireNonNull(instanceId);
        this.currentNodeId = Objects.requireNonNull(currentNodeId);
        this.status = TokenStatus.ACTIVE;
    }

    public String getId() { return id; }
    public String getInstanceId() { return instanceId; }
    public String getCurrentNodeId() { return currentNodeId; }
    public void setCurrentNodeId(String currentNodeId) { this.currentNodeId = currentNodeId; }
    public TokenStatus getStatus() { return status; }
    public void setStatus(TokenStatus status) { this.status = status; }
    public boolean isActive() { return status == TokenStatus.ACTIVE; }

    /**
     * 深拷贝（保持同一 id）—— 供事务 before-image 使用。
     * Token 的 currentNodeId 会被 advanceToken 原地改写，因此快照必须独立。
     */
    public Token copy() {
        Token t = new Token(instanceId, currentNodeId);
        try {
            java.lang.reflect.Field f = Token.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(t, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("无法重建 Token.id", ex);
        }
        t.status = this.status;
        return t;
    }

    @Override
    public String toString() {
        return "Token[" + id.substring(0, 8) + "@" + currentNodeId + "/" + status + "]";
    }
}