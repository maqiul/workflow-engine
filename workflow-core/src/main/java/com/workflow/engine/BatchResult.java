package com.workflow.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * 批处理结果
 * 
 * <p>记录批处理操作的执行结果，包括成功/失败数量、失败详情等。
 */
public class BatchResult {
    
    /** 总数量 */
    private final int total;
    
    /** 成功数量 */
    private final int successCount;
    
    /** 失败数量 */
    private final int failureCount;
    
    /** 失败详情列表 */
    private final List<FailureDetail> failures;
    
    /** 是否全部成功 */
    private final boolean allSuccess;
    
    public BatchResult(int total, int successCount, List<FailureDetail> failures) {
        this.total = total;
        this.successCount = successCount;
        this.failureCount = failures.size();
        this.failures = new ArrayList<>(failures);
        this.allSuccess = (failureCount == 0);
    }
    
    /** 创建全部成功的结果 */
    public static BatchResult allSuccess(int count) {
        return new BatchResult(count, count, new ArrayList<>());
    }
    
    /** 创建部分失败的结果 */
    public static BatchResult partialFailure(int total, int successCount, List<FailureDetail> failures) {
        return new BatchResult(total, successCount, failures);
    }
    
    public int getTotal() {
        return total;
    }
    
    public int getSuccessCount() {
        return successCount;
    }
    
    public int getFailureCount() {
        return failureCount;
    }
    
    public List<FailureDetail> getFailures() {
        return new ArrayList<>(failures);
    }
    
    public boolean isAllSuccess() {
        return allSuccess;
    }
    
    @Override
    public String toString() {
        return String.format("BatchResult{total=%d, success=%d, failure=%d, allSuccess=%s}",
                total, successCount, failureCount, allSuccess);
    }
    
    /**
     * 失败详情
     */
    public static class FailureDetail {
        
        /** 失败的 ID（任务 ID 或实例 ID） */
        private final String id;
        
        /** 错误消息 */
        private final String errorMessage;
        
        /** 异常类型 */
        private final String exceptionType;
        
        public FailureDetail(String id, String errorMessage, String exceptionType) {
            this.id = id;
            this.errorMessage = errorMessage;
            this.exceptionType = exceptionType;
        }
        
        public FailureDetail(String id, Exception e) {
            this(id, e.getMessage(), e.getClass().getSimpleName());
        }
        
        public String getId() {
            return id;
        }
        
        public String getErrorMessage() {
            return errorMessage;
        }
        
        public String getExceptionType() {
            return exceptionType;
        }
        
        @Override
        public String toString() {
            return String.format("FailureDetail{id='%s', error='%s', type='%s'}",
                    id, errorMessage, exceptionType);
        }
    }
}
