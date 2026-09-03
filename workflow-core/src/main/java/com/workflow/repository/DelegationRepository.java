package com.workflow.repository;

import com.workflow.runtime.Delegation;

import java.util.List;

/**
 * 委托关系仓储接口
 */
public interface DelegationRepository {

    /**
     * 保存委托关系
     */
    void save(Delegation delegation);

    /**
     * 查找指定代理人的所有委托关系
     */
    List<Delegation> findByDelegate(String delegate);

    /**
     * 查找指定委托人的所有委托关系
     */
    List<Delegation> findByDelegator(String delegator);

    /**
     * 删除委托关系
     */
    void remove(Delegation delegation);

    /**
     * 清空所有委托关系
     */
    void clear();
}
