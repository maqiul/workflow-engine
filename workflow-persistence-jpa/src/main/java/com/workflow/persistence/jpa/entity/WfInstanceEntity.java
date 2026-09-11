package com.workflow.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * 流程实例实体
 *
 * 表结构:
 *   wf_instance
 *     id             VARCHAR(64)  PRIMARY KEY
 *     process_key    VARCHAR(64)
 *     status         VARCHAR(16)  -- InstanceStatus 枚举
 *     create_time    BIGINT       -- System.currentTimeMillis()
 *     end_time       BIGINT
 *     variables_json CLOB         -- fastjson2 序列化的 Map<String,Object>
 */
@Entity
@Table(name = "wf_instance")
public class WfInstanceEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "process_key", length = 64, nullable = false)
    private String processKey;

    /** 实例所属流程定义版本;0 表示未知(取最新版) */
    @Column(name = "process_version", nullable = false)
    private int processVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private com.workflow.enums.InstanceStatus status;

    @Column(name = "create_time", nullable = false)
    private long createTime;

    @Column(name = "end_time")
    private Long endTime;

    @Lob
    @Column(name = "variables_json")
    private String variablesJson;

    /** 父流程实例 id - 子流程实例有值 */
    @Column(name = "parent_instance_id", length = 64)
    private String parentInstanceId;

    /** 父流程中等待该子流程的 Token id */
    @Column(name = "parent_token_id", length = 64)
    private String parentTokenId;

    /** 父流程中发起该子流程的 SUB_PROCESS 节点 id */
    @Column(name = "parent_node_id", length = 64)
    private String parentNodeId;

    /**
     * 流程树根实例 id —— 引擎加锁的单位。
     * 必须落库：否则重建后丢失，父子将各持一把锁（ABBA 防护失效）。
     */
    @Column(name = "root_instance_id", length = 64)
    private String rootInstanceId;

    /**
     * 乐观锁版本号，由 Hibernate {@code @Version} 维护（见 V9__optimistic_lock.sql）。
     *
     * <p>每次 UPDATE 时 Hibernate 自动带上 {@code WHERE revision = <读取到的值>} 并自增；
     * 影响 0 行说明该行已被其它事务改过 —— 仓储把它转成
     * {@code WorkflowConflictException}，引擎重读最新状态后有限次重试。
     *
     * <p>插入时由仓储显式写 1，与内存仓储 save 后的版本号对齐。
     */
    @Version
    @Column(name = "revision", nullable = false)
    private long revision;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProcessKey() { return processKey; }
    public void setProcessKey(String processKey) { this.processKey = processKey; }
    public int getProcessVersion() { return processVersion; }
    public void setProcessVersion(int processVersion) { this.processVersion = processVersion; }
    public com.workflow.enums.InstanceStatus getStatus() { return status; }
    public void setStatus(com.workflow.enums.InstanceStatus status) { this.status = status; }
    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }
    public Long getEndTime() { return endTime; }
    public void setEndTime(Long endTime) { this.endTime = endTime; }
    public String getVariablesJson() { return variablesJson; }
    public void setVariablesJson(String variablesJson) { this.variablesJson = variablesJson; }
    public String getParentInstanceId() { return parentInstanceId; }
    public void setParentInstanceId(String parentInstanceId) { this.parentInstanceId = parentInstanceId; }
    public String getParentTokenId() { return parentTokenId; }
    public void setParentTokenId(String parentTokenId) { this.parentTokenId = parentTokenId; }
    public String getParentNodeId() { return parentNodeId; }
    public void setParentNodeId(String parentNodeId) { this.parentNodeId = parentNodeId; }

    public String getRootInstanceId() { return rootInstanceId; }
    public void setRootInstanceId(String rootInstanceId) { this.rootInstanceId = rootInstanceId; }

    public long getRevision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }

    public Instant getCreateTimeAsInstant() {
        return Instant.ofEpochMilli(createTime);
    }
}