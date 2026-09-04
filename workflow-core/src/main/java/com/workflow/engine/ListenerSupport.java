package com.workflow.engine;

import com.workflow.listener.ExecutionListener;
import com.workflow.listener.TaskListener;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 监听器支持类 - 负责管理和触发执行监听器与任务监听器
 *
 * <p>职责：
 * <ul>
 *   <li>管理 ExecutionListener 列表</li>
 *   <li>管理 TaskListener 列表</li>
 *   <li>触发流程事件：started/completed/terminated/suspended/resumed</li>
 *   <li>触发任务事件：created/completed/rejected/transferred/withdrawn</li>
 * </ul>
 */
public class ListenerSupport {

    private static final Logger log = LoggerFactory.getLogger(ListenerSupport.class);

    /** 执行监听器列表 */
    private final List<ExecutionListener> executionListeners = new ArrayList<>();

    /** 任务监听器列表 */
    private final List<TaskListener> taskListeners = new ArrayList<>();

    /**
     * 添加执行监听器
     */
    public void addExecutionListener(ExecutionListener listener) {
        if (listener != null) {
            executionListeners.add(listener);
        }
    }

    /**
     * 添加任务监听器
     */
    public void addTaskListener(TaskListener listener) {
        if (listener != null) {
            taskListeners.add(listener);
        }
    }

    /**
     * 获取执行监听器列表（只读）
     */
    public List<ExecutionListener> getExecutionListeners() {
        return new ArrayList<>(executionListeners);
    }

    /**
     * 获取任务监听器列表（只读）
     */
    public List<TaskListener> getTaskListeners() {
        return new ArrayList<>(taskListeners);
    }

    // ========== 流程事件触发 ==========

    /**
     * 触发流程启动事件
     */
    public void fireExecutionStarted(ProcessInstance instance) {
        for (ExecutionListener l : executionListeners) {
            try {
                l.onStarted(instance);
            } catch (Exception e) {
                log.warn("[监听器] onStarted 异常", e);
            }
        }
    }

    /**
     * 触发流程完成事件
     */
    public void fireExecutionCompleted(ProcessInstance instance) {
        for (ExecutionListener l : executionListeners) {
            try {
                l.onCompleted(instance);
            } catch (Exception e) {
                log.warn("[监听器] onCompleted 异常", e);
            }
        }
    }

    /**
     * 触发流程终止事件
     */
    public void fireExecutionTerminated(ProcessInstance instance) {
        for (ExecutionListener l : executionListeners) {
            try {
                l.onTerminated(instance);
            } catch (Exception e) {
                log.warn("[监听器] onTerminated 异常", e);
            }
        }
    }

    /**
     * 触发流程挂起事件
     */
    public void fireExecutionSuspended(ProcessInstance instance) {
        for (ExecutionListener l : executionListeners) {
            try {
                l.onSuspended(instance);
            } catch (Exception e) {
                log.warn("[监听器] onSuspended 异常", e);
            }
        }
    }

    /**
     * 触发流程恢复事件
     */
    public void fireExecutionResumed(ProcessInstance instance) {
        for (ExecutionListener l : executionListeners) {
            try {
                l.onResumed(instance);
            } catch (Exception e) {
                log.warn("[监听器] onResumed 异常", e);
            }
        }
    }

    // ========== 任务事件触发 ==========

    /**
     * 触发任务创建事件
     */
    public void fireTaskCreated(TaskInstance task) {
        for (TaskListener l : taskListeners) {
            try {
                l.onCreated(task);
            } catch (Exception e) {
                log.warn("[监听器] onCreated 异常", e);
            }
        }
    }

    /**
     * 触发任务完成事件
     */
    public void fireTaskCompleted(TaskInstance task, String userId) {
        for (TaskListener l : taskListeners) {
            try {
                l.onCompleted(task, userId);
            } catch (Exception e) {
                log.warn("[监听器] onCompleted 异常", e);
            }
        }
    }

    /**
     * 触发任务驳回事件
     */
    public void fireTaskRejected(TaskInstance task, String userId, String reason) {
        for (TaskListener l : taskListeners) {
            try {
                l.onRejected(task, userId, reason);
            } catch (Exception e) {
                log.warn("[监听器] onRejected 异常", e);
            }
        }
    }

    /**
     * 触发任务转办事件
     */
    public void fireTaskTransferred(TaskInstance task, String fromUser, String toUser) {
        for (TaskListener l : taskListeners) {
            try {
                l.onTransferred(task, fromUser, toUser);
            } catch (Exception e) {
                log.warn("[监听器] onTransferred 异常", e);
            }
        }
    }

    /**
     * 触发任务撤回事件
     */
    public void fireTaskWithdrawn(TaskInstance task) {
        for (TaskListener l : taskListeners) {
            try {
                l.onWithdrawn(task);
            } catch (Exception e) {
                log.warn("[监听器] onWithdrawn 异常", e);
            }
        }
    }
}
