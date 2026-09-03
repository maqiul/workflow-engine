package com.workflow.repository;

import com.workflow.definition.ProcessDefinition;

import java.util.List;

/**
 * 流程定义仓储 - 支持多版本
 *
 * 版本语义:
 *  - 每个流程 key 可存多个版本(version 从 1 递增)
 *  - findByKey(key) 返回最新版本(兼容旧代码)
 *  - findByKeyAndVersion(key, version) 精确取指定版本
 */
public interface ProcessRepository {

    /** 保存流程定义(同 key + 同 version 覆盖) */
    void save(ProcessDefinition definition);

    /** 按 key 获取最新版本 */
    ProcessDefinition findByKey(String key);

    /** 按 key + 版本精确获取 */
    ProcessDefinition findByKeyAndVersion(String key, int version);

    /** 获取某 key 的全部版本号(升序) */
    List<Integer> getVersions(String key);

    /** 是否存在(任一版本) */
    boolean exists(String key);
}
