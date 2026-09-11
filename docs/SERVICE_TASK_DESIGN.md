# 设计方案：serviceTask/delegate 自动节点

> 状态：**待评审** · 目标版本 v3.11.0 · 关联：OA 能力对标表 #6、Flowable 样本 `customer_order_flow.bpmn` 的 `cc_node`/`loop_cc_node` 自动抄送

---

## 1. 问题

Flowable 样本 `customer_order_flow.bpmn` 里有 `serviceTask` + `flowable:delegateExpression="${ccNotificationDelegate}"`（`cc_node`、`loop_cc_node` 自动抄送）。当前引擎无 serviceTask/delegate 节点；抄送是运行时 API 调用，不是流程内自动节点。

**典型场景**：
- 自动抄送：审批通过后自动发通知给相关人员
- 自动回调：流程结束后自动调用外部系统接口
- 数据转换：节点间自动处理数据格式
- 定时任务：触发定时执行的自动节点

## 2. 方案：serviceTask + ServiceTaskDelegate

给引擎加 `SERVICE_TASK` 节点类型，执行时调用注册的 `ServiceTaskDelegate`，执行完自动推进到下一个节点。

### 2.1 设计要点

- **`ServiceTaskDelegate` 函数式接口**：`void execute(DelegateExecution execution)`，轻量无依赖
- **`DelegateExecution` 上下文**：提供 `instanceId`、`currentNodeId`、`variables`（只读）、`getProcessDefinition()`
- **注册机制**：`WorkflowEngine.registerDelegate(String key, ServiceTaskDelegate delegate)`，启动时注册
- **节点定义**：`NodeDefinition` 加 `delegateKey` 字段，指向注册的 delegate
- **执行语义**：Token 到达 serviceTask → 执行 delegate → 自动推进（不创建 TaskInstance）
- **异常处理**：delegate 抛异常 → 流程挂起（SUSPENDED），记录错误日志，不自动重试

### 2.2 正确性论证

| 场景 | 行为 | 结果 |
|---|---|---|
| 正常执行 | delegate 执行成功 | 自动推进到下一节点 |
| delegate 未注册 | 抛 `IllegalStateException("delegate 'xxx' not registered")` | 流程挂起 |
| delegate 抛异常 | 捕获异常，记录日志 | 流程挂起，可人工干预 |
| 并行网关后 | 多个 serviceTask 并行执行 | 各自独立执行，汇聚网关等待全部完成 |
| 循环回边 | serviceTask 在循环中 | 每次到达都执行，arrival 机制保证正确性 |

### 2.3 与现有能力兼容

- **静态 Candidate**：serviceTask 不需要候选人，与 USER_TASK 互斥
- **会签/或签（MULTI_INSTANCE）**：serviceTask 不参与会签，独立节点类型
- **加签/减签**：serviceTask 不支持加签/减签（自动节点无人工干预）
- **驳回/转办**：serviceTask 不支持驳回/转办（自动节点无办理人）
- **循环回边**：serviceTask 在循环中每次到达都执行，arrival 机制保证正确性

## 3. 改动清单

### 3.1 领域模型（workflow-core）
- 新增 `ServiceTaskDelegate` 函数式接口：`void execute(DelegateExecution execution)`
- 新增 `DelegateExecution` 上下文类：提供 `instanceId`、`currentNodeId`、`variables`、`getProcessDefinition()`
- `NodeType` 枚举加 `SERVICE_TASK`
- `NodeDefinition` 加 `delegateKey` 字段（可选，默认 null）
- `ProcessBuilder` 加 `serviceTask(id, name, delegateKey)` 方法
- `ProcessDefinition` 加 `delegates` Map（运行时注册表，不序列化）

### 3.2 引擎核心（workflow-core）
- `WorkflowEngine` 加 `registerDelegate(key, delegate)` 方法
- `TokenAdvancer` 加 `handleServiceTask` 方法：执行 delegate，自动推进
- 异常处理：delegate 抛异常 → 流程挂起（SUSPENDED），记录错误日志

### 3.3 序列化
- `NodeDefinition` 的 `delegateKey` 字段自动被 fastjson2 序列化
- `ProcessDefinition` 的 `delegates` Map 不序列化（运行时注册）

### 3.4 持久化（三套）
- `NodeDefinition` 整体 JSON 序列化，新字段自动处理
- 无需改仓储代码（`delegateKey` 在 `nodes_json` 里）

### 3.5 BPMN 导出/导入
- 导出：`serviceTask` 节点导出为 `<serviceTask id="..." name="..."><extensionElements><wf:delegate key="..."/></extensionElements></serviceTask>`
- 导入：解析 `<serviceTask>` 标签，从 `wf:delegate` 读 `key`

### 3.6 测试
- `ServiceTaskTest`（InMemory）：正常执行、delegate 未注册、delegate 抛异常、并行网关后、循环回边
- `JpaServiceTaskTest`（JPA）：正常执行、delegate 未注册
- `MybatisServiceTaskTest`（MyBatis）：正常执行、delegate 未注册
- `BpmnServiceTaskRoundTripTest`：导出/导入往返

## 4. 分步实施（每步编译+相关测试绿再进）
1. **步骤1 核心逻辑**：`ServiceTaskDelegate` + `DelegateExecution` + `NodeType.SERVICE_TASK` + `NodeDefinition.delegateKey` + `ProcessBuilder.serviceTask` + `WorkflowEngine.registerDelegate` + `TokenAdvancer.handleServiceTask` + InMemory 测试。验收：InMemory 全量绿 + `ServiceTaskTest` 绿。
2. **步骤2 持久化验证**：JPA/MyBatis 跨仓储一致性测试。验收：三套全量绿。
3. **步骤3 BPMN 适配**：导出/导入 + CI 绿 + 文档（README/OPERATIONS/CHANGELOG v3.11.0）。

## 5. 风险与回退
- 风险：delegate 执行时间长 → 阻塞 Token 推进。**缓解**：文档明确 delegate 应异步化（内部用线程池）。
- 风险：delegate 抛异常 → 流程挂起。**缓解**：提供 `resumeInstance` API 人工干预，或自动重试（未来扩展）。
- 每步独立 commit，任一步回归即回退该步，不累积半成品。

## 6. 不做什么
- 不做 Spring 集成（`@Component` 自动扫描）——过度设计，保持轻量
- 不做 delegate 热加载（运行时动态注册/注销）——复杂度高，未来按需扩展
- 不做 delegate 事务隔离（独立事务）——当前引擎无分布式事务支持
