# 设计方案：动态 assignee（运行时从变量取办理人）

> 状态：**待评审** · 目标版本 v3.10.0 · 关联：OA 能力对照表 #5、Flowable 样本 `customer_order_flow.bpmn` 8+ 个 userTask 用 `flowable:assignee="${xxxApprover}"`

---

## 1. 问题

Flowable 样本 `customer_order_flow.bpmn` 里大量 userTask 用 `flowable:assignee="${xxxApprover}"`，运行时从流程变量取办理人。当前引擎 `USER_TASK` 的候选人是**定义时静态列表**（`Candidate.ofAny("user1","user2")`），无法表达"办理人由启动时/运行时变量决定"。

**典型场景**：
- 请假审批：申请人 → 直属经理（`managerId` 变量）→ HR（`hrId` 变量）
- 采购审批：部门经理（`deptManagerId`）→ 财务总监（`cfoId`）
- 驳回重提：回到原节点，办理人可能变了（`originalAssignee` 变量）

## 2. 方案：USER_TASK 加 `assigneeVariable` 字段

给 `NodeDefinition` 加可选字段 `assigneeVariable`（运行时变量名），与静态 `Candidate` **二选一**：
- 若 `assigneeVariable != null`：运行时从 `instance.getVariable(varName)` 取办理人（String），动态生成 `Candidate.ofAny(assignee)`。
- 若 `assigneeVariable == null`：走原有静态 `Candidate` 逻辑（向后兼容）。

### 2.1 正确性论证

| 场景 | 行为 | 结果 |
|---|---|---|
| 启动时设变量 | `engine.start("flow", Map.of("applyApprover", "user1"))` | 变量落库，handleUserTask 读到 |
| handleUserTask 取办理人 | `String assignee = (String) instance.getVariable(node.getAssigneeVariable())` | 取到 "user1" |
| 动态生成 Candidate | `Candidate.ofAny(assignee)` | 任务候选人 = "user1" |
| 变量未设 | `getVariable` 返回 null | 抛 `IllegalStateException("assignee variable 'xxx' is null")`，明确报错 |
| 变量类型非 String | `instanceof String` 检查 | 抛 `IllegalStateException("assignee variable 'xxx' must be String, got Integer")` |

### 2.2 与现有能力兼容

- **静态 Candidate**：`assigneeVariable == null` 时走原逻辑，零影响。
- **会签/或签（MULTI_INSTANCE）**：多实例的集合变量是 `dynamicParallelVariable`，与 `assigneeVariable` 正交。
- **加签/减签**：加签时动态生成 Candidate 已落库为静态，减签不受影响。
- **驳回/转办**：reject 回退到 prev 节点，重新 handleUserTask → 重新读变量（若变量变了，办理人变了，符合预期）。
- **循环回边**：回边重入 arrival 变化 → 建新任务 → 重新读变量（若变量变了，办理人变了，符合预期）。

## 3. 改动清单

### 3.1 领域模型（workflow-core）
- `NodeDefinition`：加 `String assigneeVariable`（可选，默认 null）；构造器加参数；`getAssigneeVariable()` getter。
- `ProcessBuilder.userTask`：新增重载 `.userTask(id, name, assigneeVar)`；原 `.userTask(id, name, Candidate)` 保留（向后兼容）。
- `TokenAdvancer.handleUserTask`：若 `node.getAssigneeVariable() != null`，从 `instance.getVariable(varName)` 取办理人，动态生成 `Candidate.ofAny(assignee)`；否则走原有 `node.getCandidate()`。

### 3.2 序列化
- `NodeDefinition` 的 fastjson2 序列化自动处理新字段（`assignee_variable` 列）。
- `BpmnExporter`：若 `assigneeVariable != null`，导出为 `flowable:assignee="${varName}"`（Flowable 兼容）。
- `BpmnImporter`：解析 `flowable:assignee="${varName}"` → 设 `assigneeVariable`（剥 `${}`）。

### 3.3 持久化（三套 + 迁移）
- **V9 迁移**：`wf_node ADD COLUMN assignee_variable VARCHAR(128)`。老数据 null，向后兼容。
- JPA/MyBatis `WfNodeEntity` 加 `assigneeVariable` 字段 + getter/setter。
- `JpaProcessRepository`/`MybatisProcessRepository` 的 save/rebuild 读写 `assigneeVariable`。

### 3.4 测试
- `DynamicAssigneeTest`：启动时设变量 → 任务候选人 = 变量值；变量未设 → 抛异常；变量非 String → 抛异常；驳回后变量变了 → 新任务候选人 = 新变量值；循环回边后变量变了 → 新任务候选人 = 新变量值。
- 回归：静态 Candidate 测试不受影响（向后兼容）。

## 4. 分步实施（每步编译+相关测试绿再进）
1. **步骤1 核心逻辑**：`NodeDefinition` 加 `assigneeVariable` + `ProcessBuilder.userTask(id,name,assigneeVar)` + `handleUserTask` 动态取办理人 + InMemory 测试。验收：InMemory 全量绿 + `DynamicAssigneeTest` 绿。
2. **步骤2 持久化**：V9 迁移 + JPA/MyBatis `WfNodeEntity` 加字段 + save/rebuild 读写 + 跨仓储一致性测试。验收：三套全量绿。
3. **步骤3 收尾**：BPMN 导出/导入适配（`flowable:assignee="${var}"`）+ CI 绿 + 文档（README/OPERATIONS/CHANGELOG v3.10.0）。

## 5. 风险与回退
- 风险：`assigneeVariable` 与静态 `Candidate` 同时设 → 校验报错（`build()` 时检查互斥）。
- 风险：变量未设或类型非 String → 运行时抛明确异常（不静默失败）。
- 每步独立 commit，任一步回归即回退该步，不累积半成品。

## 6. 不做什么
- 不做 `candidateExpression`（复杂表达式如 `${users.stream().map(...).toList()}`）——过度设计，样本里只需单办理人。
- 不做多实例动态 assignee（`MULTI_INSTANCE` 的集合变量已覆盖）。
