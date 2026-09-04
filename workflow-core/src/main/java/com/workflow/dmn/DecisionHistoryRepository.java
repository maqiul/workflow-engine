package com.workflow.dmn;

import java.util.List;

/**
 * 决策历史仓储接口
 * 
 * <p>管理决策节点执行历史的存储和检索。
 */
public interface DecisionHistoryRepository {
    
    /**
     * 保存决策历史
     */
    void save(DecisionHistory history);
    
    /**
     * 根据实例 ID 查找决策历史
     */
    List<DecisionHistory> findByInstanceId(String instanceId);
    
    /**
     * 根据节点 ID 查找决策历史
     */
    List<DecisionHistory> findByNodeId(String nodeId);
    
    /**
     * 查找所有决策历史
     */
    List<DecisionHistory> findAll();
}