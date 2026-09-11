# CHANGELOG

自研工作流引擎（workflow-engine）变更日志。格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

项目状态：**v3.14.0 已完成** — 拓扑自省（TopologyView / InstanceTopologyView），339 用例、全量 0 失败。

---

## [3.14.0] - 2026-09-11

定位：**拓扑自省（getBpmnModel 等价能力）**——只读视图暴露流程定义的节点/连线与运行实例的当前 Token 位置，用于流程图高亮、待办显示、审批路径展示。

### 新增
- **`getTopology(processKey, version)`**：返回 `TopologyView`（流程 key/版本/名称 + 节点 + 连线）；`version < 0` 取最新版
- **`getInstanceTopology(instanceId)`**：返回 `InstanceTopologyView`（基础拓扑 + `activeNodeIds` 当前 Token 位置 + `completedNodeIds` 历史路径 + 实例状态）
- **`com.workflow.topology` 包**：`TopologyView` / `NodeView` / `TransitionView` / `InstanceTopologyView`，全部只读、不可变集合
- **`NodeView`**：`id` / `name` / `type` / `userIds`（审批人列表）/ `assigneeVariable`（动态 assignee）/ `delegateKey`（serviceTask）；只暴露必要字段，不暴露 `Candidate` 内部结构

### 设计要点
- 直接从 `ProcessDefinition` 构建，不依赖 BPMN XML 解析
- 返回不可变集合，可序列化为 JSON 供前端渲染流程图
- 与 Flowable `repositoryService.getBpmnModel()` 能力对齐（更轻量）

### 测试
- **`TopologyViewTest`**（InMemory）：6 个用例（基本拓扑 / 条件网关连线 / 动态 assignee 与 serviceTask 字段 / 实例高亮 / `version=-1` 最新版 / 不存在抛异常）
- **`JpaTopologyViewTest`**（JPA）：2 个用例
- **`MybatisTopologyViewTest`**（MyBatis）：2 个用例
- **全量回归 339/339 通过**，0 失败

### 设计文档
- `docs/TOPOLOGY_VIEW_DESIGN.md`

### OA 能力对标进度（9/9 全部完成 🎉）
- ✅ #1 多实例会签/或签（v3.7.0）
- ✅ #2 加签/减签（v3.7.0）
- ✅ #3 并行逐支跳转（v3.7.0）
- ✅ #4 循环回边（v3.9.0）
- ✅ #5 动态 assignee（v3.10.0）
- ✅ #6 serviceTask 自动节点（v3.11.0）
- ✅ #7 Flowable 导入适配层（v3.12.0）
- ✅ #8 运行中实例版本迁移（v3.13.0）
- ✅ **#9 getBpmnModel 拓扑自省（v3.14.0）** ← 本轮完成，OA 能力对照表全部补齐

---

## [3.13.0] - 2026-09-11

定位：**运行中实例版本迁移**——支持将运行中的旧版本实例迁移到新版本流程定义，节点映射 + 类型兼容性校验。

### 新增
- **`migrateInstance` API**：`IWorkflowEngine.migrateInstance(instanceId, targetProcessKey, targetVersion, nodeMapping, operator)`
- **节点类型兼容性校验**：USER_TASK→USER_TASK、GATEWAY→GATEWAY，不允许跨类型迁移
- **节点映射**：`Map<String, String>` 旧节点 ID → 新节点 ID，未映射的节点必须在新版中 ID 相同
- **审计事件**：`AuditEventType.INSTANCE_MIGRATED`

### 变更
- **`WorkflowEngine` 反射更新 final 字段**：新增 `setFinal` 工具方法，用于迁移场景更新 `processKey`/`processVersion`/`currentNodeId`

### 测试
- **`InstanceMigrationTest`**（InMemory）：6 个用例（基本迁移、最新版、类型不兼容、版本不存在、非 RUNNING、节点映射）
- **`JpaInstanceMigrationTest`**（JPA）：1 个用例（基本迁移后继续推进）
- **`MybatisInstanceMigrationTest`**（MyBatis）：1 个用例（同上）
- **全量回归 327/327 通过**：InMemory 279 + JPA 40 + MyBatis 40（含跨库一致性），0 失败

### 设计文档
- `docs/INSTANCE_MIGRATION_DESIGN.md`：实例迁移设计方案，含 API 设计、节点映射、类型兼容性、在途任务处理策略

### OA 能力对标进度
- ✅ #1 多实例会签/或签（v3.7.0）
- ✅ #2 加签/减签（v3.7.0）
- ✅ #3 并行逐支跳转（v3.7.0）
- ✅ #4 循环回边（v3.9.0）
- ✅ #5 动态 assignee（v3.10.0）
- ✅ #6 serviceTask 自动节点（v3.11.0）
- ✅ #7 Flowable 导入适配层（v3.12.0）
- ✅ **#8 运行中实例版本迁移（v3.13.0）** ← 本轮完成
-  #9 getBpmnModel 拓扑自省（待补）

---

## [3.12.0] - 2026-09-11