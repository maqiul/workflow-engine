package com.workflow.engine;

import com.workflow.WorkflowException;

/**
 * 批处理部分失败异常
 * 
 * <p>当批处理操作中部分任务失败时抛出此异常，
 * 包含详细的失败信息和批处理结果。
 */
public class BatchPartialFailureException extends WorkflowException {
    
    private final BatchResult result;
    
    public BatchPartialFailureException(String message, BatchResult result) {
        super(message);
        this.result = result;
    }
    
    /**
     * 获取批处理结果
     */
    public BatchResult getResult() {
        return result;
    }
    
    @Override
    public String toString() {
        return String.format("BatchPartialFailureException{message='%s', result=%s}",
                getMessage(), result);
    }
}
