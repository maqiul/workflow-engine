# 设计方案：getBpmnModel 拓扑自省

> 状态：**已实现（v3.14.0）** · 关联：OA 能力对照表 #9、Flowable BpmnModel API

---

## 1. 问题

OA 系统常需要"拓扑自省"能力——运行时获取流程定义的节点/连线信息，用于：
- 流程图高亮（当前 Token 在哪个节点）
- 待办列表显示节点名称
- 审批历史展示流转路径
- 流程模拟器/预览

**Flowable 做法**：`repositoryService.getBpmnModel(processDefinitionId)` 返回完整 `BpmnModel` 对象图（Process → FlowElement → SequenceFlow）。

**我们的现状**：
- 已有 `ProcessDefinition`（节点 + 出口转移）
- 已有 `BpmnExporter`（导出 BPMN XML）
- 缺一个**轻量只读视图**，暴露节点/连线/当前 Token 位置

## 2. 方案：`TopologyView` 只读视图

### 2.1 核心 API

```java
/**
 * 获取流程定义的拓扑只读视图
 * 
 * @param processKey 流程定义 key
 * @param version 版本号（-1 表示最新版）
 * @return 拓扑视图
 */
TopologyView getTopology(String processKey, int version);

/**
 * 获取运行中实例的拓扑视图（含当前 Token 位置）
 * 
 * @param instanceId 实例 ID
 * @return 实例拓扑视图（含高亮节点）
 */
InstanceTopologyView getInstanceTopology(String instanceId);
```

### 2.2 `TopologyView` 结构

```java
public final class TopologyView {
    private final String processKey;
    private final int version;
    private final String name;
    private final List<NodeView> nodes;      // 节点列表
    private final List<TransitionView> transitions;  // 连线列表
}

public final class NodeView {
    private final String id;
    private final String name;
    private final NodeType type;
    private final Candidate candidate;       // 仅 USER_TASK 有值
    private final String assigneeVariable;   // 仅动态 assignee 有值
    private final String delegateKey;        // 仅 SERVICE_TASK 有值
}

public final class TransitionView {
    private final String from;
    private final String to;
    private final String condition;          // 条件表达式（可为 null）
}
```

### 2.3 `InstanceTopologyView` 结构

```java
public final class InstanceTopologyView {
    private final TopologyView topology;     // 基础拓扑
    private final List<String> activeNodeIds;  // 当前 Token 所在节点 ID（高亮用）
    private final List<String> completedNodeIds;  // 已完成节点 ID（历史路径）
    private final InstanceStatus status;
}
```

### 2.4 实现要点

- **只读**：所有字段 final，返回不可变集合
- **轻量**：不依赖 BPMN XML 解析，直接从 `ProcessDefinition` 构建
- **兼容**：`TopologyView` 可序列化为 JSON，供前端流程图渲染

## 3. 改动清单

### 3.1 领域模型（workflow-core）
- 新增 `TopologyView`、`NodeView`、`TransitionView`、`InstanceTopologyView` 类
- `IWorkflowEngine` 加 `getTopology` / `getInstanceTopology` 方法
- `WorkflowEngine` 实现

### 3.2 测试
- `TopologyViewTest`（InMemory）：
  - 基本拓扑（节点 + 连线）
  - 条件网关连线
  - 实例拓扑（含高亮节点）
- `JpaTopologyViewTest`（JPA）：基本拓扑
- `MybatisTopologyViewTest`（MyBatis）：基本拓扑

### 3.3 BPMN 适配
- 无改动（拓扑视图与 BPMN 无关）

## 4. 分步实施
1. **步骤1 核心逻辑**：`TopologyView` 类 + `IWorkflowEngine.getTopology` + `getInstanceTopology` + InMemory 测试。验收：InMemory 全量绿。
2. **步骤2 持久化验证**：JPA/MyBatis 跨仓储一致性测试。验收：三套全量绿。
3. **步骤3 收尾**：CI 绿 + 文档（README/OPERATIONS/CHANGELOG v3.14.0）。

## 5. 风险与回退
- **风险**：拓扑视图暴露内部结构（如 `Candidate` 对象）。**缓解**：`NodeView` 只暴露必要字段（userIds 列表），不暴露 `Candidate` 内部。
- 每步独立 commit，任一步回归即回退该步。

## 6. 不做什么
- 不做 BPMN XML 解析（已有 `BpmnImporter`）
- 不做流程图渲染（前端的事）
- 不做流程模拟（独立大功能）
