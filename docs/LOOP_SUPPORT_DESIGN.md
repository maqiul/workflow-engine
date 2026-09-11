# 设计方案：循环回边支持（Token 到达代次）

> 状态：**待评审** · 目标版本 v3.9.0 · 关联：OA 能力对照表「退回任意节点/循环」缺口
> 触发样本：`customer_order_flow.bpmn` 的 `loop_temple_node ↔ loop_node_loop_gw`（`loopContinue` 驱动的循环）

---

## 1. 问题

Flowable 用「排他网关 + 回边」实现循环：`tpl → gw →(loopContinue==true)→ tpl`。
当前引擎**跑不了**：token 回到它已完成过的节点时，`handleUserTask` 用 `currentTaskOf`（按 token+node 找任意非终止任务）命中**上一轮的 COMPLETED 任务**，误判「已完成→推进」→ 又回 gw → 又回 tpl → **无限递归 StackOverflowError**。

**上一版失败教训**：用「到达标记」`__ut_<token>_<node>` 记录本轮任务 id，但 transfer/超时转办在 `handleUserTask` 之外建任务、没同步标记 → 重入时误判重复建任务 → 5 个回归（RejectTransfer/VariableType/HistoryConsistency）。**结论：靠"每个建任务点记得设标记"不可靠，必须用不依赖人工同步的机制。**

## 2. 方案：Token 到达代次（arrival）

给每个 Token 一个单调递增的**到达计数 `arrival`**：Token 每"移动到一个节点"就 `arrival++`。每个 TaskInstance 记录**创建时所属 token 的 arrival**。`handleUserTask` 只认「arrival == 当前 token.arrival」的任务。

- 回边重入：`setCurrentNodeId` 使 `arrival` 变化 → 上一轮 COMPLETED 任务的 arrival 不再匹配 → **建新任务**。
- 正常完成推进：token 未移动、arrival 不变 → 命中本轮 COMPLETED → 推进。
- 无需"记得设标记"：arrival 由**移动动作本身**驱动，建任务处统一读 `token.arrival`。

### 2.1 正确性论证（关键路径）

| 场景 | arrival 变化 | handleUserTask 行为 | 结果 |
|---|---|---|---|
| 首次到 tpl | token.arrival=N | 无 arrival==N 任务 | 建 T1(arrival=N) |
| T1 完成→推进 | setCurrentNodeId(gw)→arrival=N+1 | (在 gw 不查) | 前进 |
| gw 回边到 tpl | setCurrentNodeId(tpl)→arrival=N+2 | 无 arrival==N+2 任务 | 建 T2(arrival=N+2) ✅ 不再死循环 |
| reject | consume 旧 token，**新建 token**(arrival=0) | prev 无 arrival==0 任务 | 建 prev 任务（新 token 天然干净）|
| transfer | token **不移动**、arrival 不变；建的新任务记 arrival=当前 | 命中该 PENDING 新任务 | 停等，不重复建 ✅ |
| 多实例/动态并行 | 各自 handle 方法（`__mi_`/`__dynamic_` 标记），不依赖 arrival | 不受影响 | 兼容 |

### 2.2 `arrival` 自增的唯一入口

`arrival++` 只在 **`Token.moveTo(newNodeId)`** 里发生（= setCurrentNodeId + arrival++）。所有"运行时把 token 移到下一节点"的点统一用 `moveTo`：
- `handleUserTask` 完成推进、`handleExclusiveGateway`、`handleStart`、`handleMultiInstance`/`handleDynamicParallel` 推进、`jumpToNode`/`jumpTokenToNode`。
**重建/快照**（`reconstruct`/`copy`）用反射**直接设字段**、**不触发自增**（避免重建把 arrival 抬高）。

> 评审要点：需逐一核对现有 `setCurrentNodeId` 调用点，运行时移动→`moveTo`；重建/反序列化→字段直设。这是本方案唯一"要记全"的地方，但**集中在 Token 一处 + 一次调用点普查**，比"每个建任务点设标记"可控得多。

## 3. 改动清单

### 3.1 运行时模型（workflow-core）
- `Token`：加 `int arrival`；`moveTo(nodeId)`（setCurrentNodeId+arrival++）；`getArrival()`；`copy()` 反射带 arrival（不自增）。
- `TaskInstance`：加 `int arrival`；构造/`reconstruct` 增 arrival 参数（旧签名重载委托，向后兼容）；`copy()` 带 arrival。
- `ProcessInstance`：snapshot/reconstruct 透传 token/task 的 arrival（经 copy/reconstruct）。

### 3.2 推进逻辑（workflow-core）
- `TokenAdvancer.handleUserTask`：`existing` = 该 token+node 且 `arrival==token.getArrival()` 且非 TERMINATED/TRANSFERRED 的任务；建任务时 `task.arrival = token.arrival`；推进用 `token.moveTo(...)`。
- 其余 `setCurrentNodeId` 调用点 → `moveTo`（普查清单见 2.2）。
- `transferTaskInternal`/超时 AUTO_TRANSFER 建的新任务：`setArrival(token.arrival)`（同 token 不移动，arrival 不变）。

### 3.3 持久化（三套 + 迁移）
- **V8 迁移**：`wf_token ADD COLUMN arrival INT DEFAULT 0`；`wf_task ADD COLUMN arrival INT DEFAULT 0`。老数据 arrival=0，与"单到达"旧行为一致 → **向后兼容**。
- JPA：`WfTokenEntity`/`WfTaskEntity` 加 `arrival` 列；save 写、rebuild 读。
- MyBatis：对应 entity 加 `@TableField("arrival")`；save/rebuild 读写。
- InMemory：靠 copy/snapshot，无需库。

### 3.4 序列化
- fastjson2 对 int 字段自动处理；`NodeDefinition` 不变（arrival 不在定义层，在实例/任务层）。

## 4. 测试计划
- `LoopBackEdgeTest`：回边重入建**新**任务、两轮后退出完成（去掉 @Disabled）。
- 回归：`RejectTransferTerminateTest`（JPA/MyBatis/InMemory）、`VariableTypeTest`、`HistoryConsistencyTest` 必须**保持绿**（上次就是它们暴露冲突）。
- 新增：transfer 后完成不重复建任务；reject 回退到已完成节点能建新任务；多实例/动态并行不受影响。
- 跨仓储一致性：token/task arrival 落库读回后，回边在 JPA/MyBatis 同样正确（用真实样本 loop 片段）。
- 全量 `:workflow-tests:test` 0 失败 + CI 绿。

## 5. 分步实施（每步编译+相关测试绿再进）
1. **步骤1 核心逻辑**：Token/TaskInstance arrival + handleUserTask + moveTo 普查 + InMemory 测试（含 LoopBackEdge 转绿）。验收：InMemory 全量绿。
2. **步骤2 持久化**：V8 迁移 + JPA/MyBatis arrival 读写 + 跨仓储一致性测试。验收：三套全量绿。
3. **步骤3 收尾**：CI 绿 + 文档（README 回边能力、OPERATIONS 循环用法、CHANGELOG v3.9.0）。

## 6. 风险与回退
- 风险：`setCurrentNodeId` 调用点普查遗漏 → 某路径 arrival 不自增 → 该路径回边仍死循环或不回边。**缓解**：编译期把 `setCurrentNodeId` 设私有、只暴露 `moveTo`，强制所有调用点显式选择语义（移动 or 重建），漏不了。
- 风险：arrival 落库后老库升级。**缓解**：DEFAULT 0 兼容。
- 每步独立 commit，任一步回归即回退该步，不累积半成品。

## 7. 不做什么
- 不做 Flowable 的 `loopData`/标准 multi-instance 循环标记（我们用"网关回边 + arrival"覆盖你们样本的循环形态）。
- 不引入并行子流程实例（arrival 是 token 级，天然支持并行各支独立计数）。
