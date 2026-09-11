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
 *
 * 到达代次(arrival)：Token 每「移动到一个节点」自增一次，用于区分同一节点的多轮到达，
 * 从而支持「排他网关回边」式循环——回边重入时 arrival 变化，节点会新建任务而非把上一轮的
 * 完成任务误判为「已完成→推进」。运行时移动只能走 {@link #moveTo}（自增），
 * 重建/快照走构造 + 反射设值（不自增）。
 */
public final class Token {
    private final String id;
    private final String instanceId;
    private String currentNodeId;
    private TokenStatus status;
    private int arrival;

    public Token(String instanceId, String currentNodeId) {
        this.id = UUID.randomUUID().toString();
        this.instanceId = Objects.requireNonNull(instanceId);
        this.currentNodeId = Objects.requireNonNull(currentNodeId);
        this.status = TokenStatus.ACTIVE;
        this.arrival = 0;
    }

    public String getId() { return id; }
    public String getInstanceId() { return instanceId; }
    public String getCurrentNodeId() { return currentNodeId; }

    /** 运行时把 Token 移动到某节点：唯一的移动入口，自增到达代次。 */
    public void moveTo(String newNodeId) {
        this.currentNodeId = Objects.requireNonNull(newNodeId);
        this.arrival++;
    }

    /** 仅供重建/反序列化：直接设当前节点，不自增 arrival。 */
    private void setCurrentNodeId(String currentNodeId) {
        this.currentNodeId = currentNodeId;
    }

    public TokenStatus getStatus() { return status; }
    public void setStatus(TokenStatus status) { this.status = status; }
    public boolean isActive() { return status == TokenStatus.ACTIVE; }

    public int getArrival() { return arrival; }
    /** 仅供重建/反序列化设值。 */
    public void setArrival(int arrival) { this.arrival = arrival; }

    /**
     * 深拷贝（保持同一 id/arrival）—— 供事务 before-image 与快照使用。
     * Token 的 currentNodeId/arrival 会被 moveTo 原地改写，因此快照必须独立。
     * 注意：走构造 + 反射设值，不经 moveTo，故不会误自增 arrival。
     */
    public Token copy() {
        Token t = new Token(instanceId, currentNodeId);
        try {
            java.lang.reflect.Field fid = Token.class.getDeclaredField("id");
            fid.setAccessible(true);
            fid.set(t, id);
            java.lang.reflect.Field fa = Token.class.getDeclaredField("arrival");
            fa.setAccessible(true);
            fa.set(t, arrival);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("无法重建 Token.id/arrival", ex);
        }
        t.status = this.status;
        return t;
    }

    @Override
    public String toString() {
        return "Token[" + id.substring(0, 8) + "@" + currentNodeId + "/" + status + "/a" + arrival + "]";
    }
}
