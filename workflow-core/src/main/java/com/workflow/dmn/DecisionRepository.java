package com.workflow.dmn;

import java.util.List;

/**
 * DMN 决策仓储接口
 * 
 * <p>管理决策表的存储和查询。
 */
public interface DecisionRepository {
    
    /**
     * 保存决策表
     */
    void save(DecisionTable decisionTable);
    
    /**
     * 根据 ID 查找决策表
     */
    DecisionTable findById(String id);
    
    /**
     * 根据名称查找决策表
     */
    DecisionTable findByName(String name);
    
    /**
     * 查找所有决策表
     */
    List<DecisionTable> findAll();
    
    /**
     * 删除决策表
     */
    void delete(String id);
}
