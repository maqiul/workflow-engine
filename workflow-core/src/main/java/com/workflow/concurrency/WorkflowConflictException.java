package com.workflow.concurrency;

import com.workflow.WorkflowException;

/**
 * 并发冲突：同一份数据被其它线程 / 其它 JVM 先行修改。
 *
 * <p>由仓储层在乐观锁 CAS 失败（{@code update ... where REV_ = ?} 影响 0 行）时抛出。
 * 引擎捕获后在<b>重新读取最新状态</b>的前提下有限次重试；重试耗尽仍失败则向调用方透出，
 * 由上层业务决定提示用户刷新还是静默放弃。
 */
public class WorkflowConflictException extends WorkflowException {

    private final String entityKey;

    public WorkflowConflictException(String message) {
        this(message, null);
    }

    public WorkflowConflictException(String message, String entityKey) {
        super(message);
        this.entityKey = entityKey;
    }

    public WorkflowConflictException(String message, String entityKey, Throwable cause) {
        super(message, cause);
        this.entityKey = entityKey;
    }

    /** 冲突的实体标识（实例 id 或任务 id），可能为 null。 */
    public String getEntityKey() {
        return entityKey;
    }
}
