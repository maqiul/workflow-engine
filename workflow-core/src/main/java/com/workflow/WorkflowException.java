package com.workflow;

/**
 * 引擎「可预期失败」的共同基类。
 *
 * <p><b>分界在哪</b>：凡是调用方<b>可以理解、也应当处理</b>的失败都继承它 ——
 * 并发冲突（{@code WorkflowConflictException}）、候选组展开失败（{@code GroupResolutionException}）、
 * 批处理部分失败（{@code BatchPartialFailureException}）、BPMN 定义非法（{@code BpmnException}）、
 * 拿不到实例锁（{@code LockAcquisitionException}）。
 *
 * <p>反过来，<b>NPE / 非法参数 / 状态机被违反</b>这类，要么是「调用方用错了 API」、
 * 要么是「引擎自己坏了」，一律保持 JDK 原生异常类型不动。
 * 把编程错误伪装成业务失败，只会让调用方误加 catch 把 bug 吞掉 ——
 * 那比不分类更糟。
 *
 * <p><b>为什么必须有这个基类</b>：调用方真正想要的通常是一个<b>边界</b> ——
 * 「引擎抛出的一切可预期失败，在这里统一转成 HTTP 4xx / 记一条告警 / 触发一次重试」。
 * 没有基类时，这个边界只能写成 {@code catch (RuntimeException e)}，
 * 于是连引擎自己的空指针也一起被当成业务失败咽下去。有了它，边界是明确的：
 * {@code catch (WorkflowException e)} 兜住全部业务失败，
 * 剩下的 RuntimeException 就该冒到日志里去。
 *
 * @since 3.18.0
 */
public class WorkflowException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public WorkflowException(String message) {
        super(message);
    }

    public WorkflowException(String message, Throwable cause) {
        super(message, cause);
    }

    public WorkflowException(Throwable cause) {
        super(cause);
    }
}
