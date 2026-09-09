package com.workflow.tests.support;

import com.workflow.enums.TaskStatus;
import com.workflow.repository.TaskRepository;
import com.workflow.runtime.TaskInstance;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 第 N 次 {@code save} 抛异常的 TaskRepository 装饰器。
 *
 * <p>用来把失败点精确地放在<b>历史活动已经写入之后</b>。
 * 上一轮想用「审计写入失败」当探针来验证历史回滚，结果无效 ——
 * {@code audit} 发生在 {@code advanceToken} <b>之前</b>，异常抛出时历史根本还没开启，
 * 测不到任何东西。
 *
 * <p>而引擎推进到下一节点时会创建新待办（{@code taskRepo.save} 的第 2 次调用），
 * 此刻本节点活动已闭合、下一节点活动已开启，正好构成
 * 「历史写过、业务未完成」的回滚现场。
 */
public class FlakyTaskRepository implements TaskRepository {

    private final TaskRepository delegate;
    private final int failOnSave;
    private final AtomicInteger saves = new AtomicInteger();

    /**
     * @param delegate   真实仓储
     * @param failOnSave 第几次 save 抛异常（从 1 开始）
     */
    public FlakyTaskRepository(TaskRepository delegate, int failOnSave) {
        this.delegate = delegate;
        this.failOnSave = failOnSave;
    }

    /** 已发生的 save 次数，便于断言注入点确实被命中。 */
    public int saveCount() {
        return saves.get();
    }

    @Override
    public void save(TaskInstance task) {
        if (saves.incrementAndGet() == failOnSave) {
            throw new IllegalStateException("模拟任务表写入失败（第 " + failOnSave + " 次 save）");
        }
        delegate.save(task);
    }

    @Override
    public TaskInstance findById(String taskId) {
        return delegate.findById(taskId);
    }

    @Override
    public List<TaskInstance> findByInstanceId(String instanceId) {
        return delegate.findByInstanceId(instanceId);
    }

    @Override
    public List<TaskInstance> findPendingByUser(String userId) {
        return delegate.findPendingByUser(userId);
    }

    @Override
    public List<TaskInstance> findAll() {
        return delegate.findAll();
    }

    @Override
    public List<TaskInstance> findByNodeId(String nodeId) {
        return delegate.findByNodeId(nodeId);
    }

    @Override
    public List<TaskInstance> findByStatus(TaskStatus status) {
        return delegate.findByStatus(status);
    }

    @Override
    public long countPending() {
        return delegate.countPending();
    }
}
