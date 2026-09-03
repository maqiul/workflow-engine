package com.workflow.repository;

import com.workflow.runtime.CarbonCopy;

import java.util.List;

/**
 * 抄送仓储接口
 */
public interface CarbonCopyRepository {

    /**
     * 保存抄送记录
     */
    void save(CarbonCopy cc);

    /**
     * 查询指定接收人的抄送列表（按时间倒序）
     */
    List<CarbonCopy> findByRecipient(String recipient);

    /**
     * 查询指定接收人的未读抄送
     */
    List<CarbonCopy> findUnreadByRecipient(String recipient);

    /**
     * 查询指定流程实例的抄送列表
     */
    List<CarbonCopy> findByInstanceId(String instanceId);

    /**
     * 标记为已读
     */
    void markRead(String ccId);

    /**
     * 清空（测试用）
     */
    void clear();
}
