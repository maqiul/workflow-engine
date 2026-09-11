# CHANGELOG

自研工作流引擎（workflow-engine）变更日志。格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

项目状态：**v3.9.0 已完成** — 循环回边支持（Token 到达代次机制），约 296 用例、全量 0 失败。

---

## [3.9.0] - 2026-09-11

定位：**循环回边支持**——复刻 Flowable 样本里排他网关回边式循环（`loop_temple_node ↔ gw`），支持驳回后重新提交、审批流中的循环网关等场景。

### 新增
- **Token 到达代次（arrival）机制**：Token 每移动到一个节点 `arrival++`；TaskInstance 记录创建时的 arrival；引擎仅处理「arrival == 当前 token.arrival」的任务，从而隔离不同轮次的执行上下文。
- **循环回边支持**：排他网关回边（条件为真时 token 回到前序节点）会新建任务而非误判上一轮已完成任务，彻底解决 StackOverflowError。
- **V8 迁移脚本** `V8__add_arrival_for_loop_support.sql`：`wf_token`/`wf_task` 加 `arrival INT DEFAULT 0`，老数据向后兼容。

### 变更
- **`Token.setCurrentNodeId` 私有化**：运行时移动只能走 `moveTo()`（自增 arrival），重建/快照走构造 + 反射设值（不自增）。编译期强制所有移动走 moveTo，杜绝"漏设标记"导致的回归。
- **`TaskInstance.reconstruct` 新增含 arrival 的重载**：持久化层 rebuild 时传入 arrival，domain 对象重建后 arrival 与落库一致。
- **`currentTaskOf` 按 arrival 过滤**：只匹配「arrival == token.arrival 且非 TERMINATED/TRANSFERRED」的任务，回边重入时上一轮 COMPLETED 任务不再命中。

### 修复
- **循环回边 StackOverflowError**：引擎处理 BPMN 循环回边时，若 token 回到已处理过的节点，`handleUserTask` 误判任务状态导致无限递归。引入 arrival 机制后，回边重入 arrival 变化→建新任务，彻底解决。
- **`checkAndFinalize` 缺失**：`handleUserTask` 的 `outs.isEmpty()` 分支和 `advanceTokenInternal` 的 `END` 分支未调 `checkAndFinalize`，导致实例无法标记完成。已补。

### 测试
- **启用 `LoopBackEdgeTest`**（原 `@Disabled`）：回边生效（条件为真时重建待办）、退出分支（条件为假时正常完成）2 个用例。
- **全量回归 296/296 通过**：InMemory 262 + JPA 34 + MyBatis 34（含跨库一致性），0 失败。
- **零回归验证**：reject/transfer/timeout/多实例/动态并行等核心流程逻辑不受影响（上次"到达标记"方案导致 5 个回归，本次 arrival 方案天然兼容）。

### 设计文档
- `docs/LOOP_SUPPORT_DESIGN.md`：循环回边设计方案（Token 到达代次），含正确性论证、改动清单、实施节奏、风险与回退。

---

## [3.8.0] - 2026-09-10

定位收敛为**纯基础工作流引擎**：移除业务层的表单集成与附件管理，补齐监控、多租户、批量、通知、性能等企业级基础能力。

### 新增
- **退回到任意节点** `jumpToNode(instanceId, targetNodeId, operator, reason)`：消耗全部活跃 Token、终止 PENDING 任务、在目标节点重建 Token 并推进;并行网关下终止全分支任务;记审计 `PROCESS_JUMPED`;REST `POST /api/instances/{id}/jump`。
- **批处理 API**：`batchCompleteTasks` / `batchTerminateInstances`,原子事务(全成功或全回滚),`BatchResult` 带成败计数与逐条失败详情,`BatchPartialFailureException`。
- **批量启动**：`batchStart(key[,version], List<vars>)` 单事务 `saveBatch` 落库后逐个推进,所有实例继承当前租户;仓储新增 `saveBatch`。
- **监控仪表盘** `com.workflow.monitor`：`DashboardMetrics` + `MonitoringService`,`IWorkflowEngine.dashboard(topN[,tenantId])`,REST `GET /api/metrics/dashboard`,示例看板 `docs/dashboard.html`。
- **多租户隔离**：`TenantContext`(ThreadLocal + `withTenant`),三模型加 `tenantId`,start/批量/任务创建继承租户;`dashboard`/`countGroupByProcessAndStatus`/`countPending` 带 `tenantId` 过滤(null=全局兼容);Flyway `V7__multi_tenant.sql`。
- **通知实现**(零依赖)：`LoggingNotificationService`(SLF4J + 环形缓冲)、`WebhookNotificationService`(JDK HttpClient POST JSON;url 未配置静默跳过、失败吞异常不断流程)。
- **性能基准** `PerformanceBenchmarkTest`,`-Dperf=true` 门控(常规构建跳过,不拖慢 CI)。

### 变更
- `terminate` 收紧语义:仅 `RUNNING` 可终止(与挂起/恢复一致)。
- 监控指标从 `findAll()` 全表重建**下沉为 SQL 层聚合**:`countGroupByProcessAndStatus`(GROUP BY)、`countPending`(COUNT)、`countGroupByEventTypePrefix`(枚举 IN / 字符串 LIKE);`historyRepo`/`auditLogRepo` 为 null 时降级为空。
- 测试:`workflow-tests` 设 `forkEvery=1`(每类独立 JVM),消除 JPA/MyBatis 事务原子性测试与静态单例/H2 命名库/ThreadLocal 的跨类耦合;此前混跑偶现的 4 个红消失。

### 移除
- **表单集成与附件管理**(`com.workflow.form.*`、`com.workflow.attachment.*`、`FormSubmitResult` 及相关测试):属业务/前端层,移出以保持基础引擎纯粹性;业务系统可自行实现,引擎只需"任务完成"信号。

### 修复（本轮踩坑,均已修 + 记忆留档)
- `ProcessInstance.snapshot()` / `TaskInstance.copy()` 漏传 `tenantId` → 内存仓储丢租户(多租户测试红)。
- 实体引用 `tenant_id` 列但 `V7` 迁移未落地(Flyway 停 v6) → 全量 99 个 DB 测试 `Column TENANT_ID not found`;补 `V7` 并核验文件真实存在。
- JPA `countGroupByEventTypePrefix` 用 `LIKE` 打在枚举列上抛 `QueryArgumentException` → 改为枚举值 `IN` 查询。
- 测试 `assertThatThrownBy`/`Set-Content` 误用致断言/编码问题 → 修正。

### 测试
- 约 270 用例、全量 **0 失败**(跨库 4 项需 Docker 时 skip);新增覆盖:事件网关、DMN、监控聚合三套一致、批处理、批量启动、多租户、通知、退回任意节点。

### 说明
- 性能基准暴露**反直觉事实**:InMemory 下"批量启动"反而慢于逐个(内存写极便宜,批量徒增 GC);批量收益只在真实 DB。基准测试的价值即在于照出这种想当然。

---

## [3.7.0] - 2026-09-03

P0 正确性加固：修的是"会不会出事"，不是"有没有功能"。详见 README §17。

### 新增

- **流程树锁**（`com.workflow.concurrency`）：`InstanceLockProvider` 接口 + `LocalInstanceLocks` 实现
  - 锁粒度是**流程树根**（`ProcessInstance.rootInstanceId`），不是单个实例
  - `tryLock(30s)` + 公平锁 + 引用计数回收；可重入以支持 `advanceToken` 递归与子流程回调
  - `WorkflowConflictException`：乐观锁 CAS 失败的统一信号
- **事务边界抽象**（`com.workflow.tx`）：`TransactionRunner` / `TransactionContext` / `UndoLogTransactionRunner`
  - 三组钩子：undo（逆序回滚）、`beforeCommit`（数据库提交）、`afterCommit`（提交后副作用）
  - 嵌套事务复用最外层，只有最外层提交或回滚
- **实例列表查询跨仓储补齐**：`findByProcessKey` / `findAll` / `findByStatus` / `findByProcessKeyAndVersion`
  在 JPA 与 MyBatis-Plus 上补齐实现（此前只有内存版有）
- **任务全局查询补齐**：`TaskRepository.findAll` / `findByNodeId` / `findByStatus` 补齐两套真实仓储
- `IWorkflowEngine.allTasks()` / `allInstances()` —— 供查询构建器使用
- `ProcessInstance.snapshot()` / `replaceTask()`、`TaskInstance.copy()` / `reconstruct()` / `recordSystemApproval()`、`Token.copy()`
- `tests.support.ExplodingAuditLogRepository`：故意让最后一步写入失败的事务探针

### 修复

- **并发丢失更新**：两名审批人同时通过同一 ALL 会签任务，修复前 30 轮中任务仅 24 轮完成、`completedApprovers` 仅 24 轮为 2、下游恰 1 待办仅 **15** 轮
- **通过 vs 驳回竞态**：修复前两者可同时生效并泄漏「Token 不存在或已消耗」类内部异常
- **事务半完成状态**：`completeTask` 中途失败（如审计库故障）后任务永久停在 `COMPLETED` 而 Token 未推进 —— InMemory / JPA / MyBatis 三条路径全部修复
- **JPA stale 实体**：`JpaInstanceRepository.save` 原用 bulk DELETE + 重插同步 Token，绕过 persistence context 导致提交时 `OptimisticLockException`；改差量同步
- **`transferTaskInternal` 漏 `instanceRepo.save`**：实例任务列表变更未落库（旧模型靠共享引用"免费"生效）
- **`TaskQuery` 四处缺陷**：`queryAllTasks()` 永返空列表；`processDefinitionKey` / `processDefinitionVersion` / `processVariable` 从未参与匹配（过滤条件空转）；`orderByCreateTime()` 实为按 id 排序；`count()` 复用带 skip/limit 的 `list()` 导致 `limit(5).count()` 永不超过 5
- **domain 缺字段**：`TaskInstance` 补 `createTime`（DB `wf_task.create_time` 一直存在，读回时被丢弃）
- **流程树根未落库（自我修正）**：`rootInstanceId` 只存在于内存对象，JPA / MyBatis 重建后丢失并退化为"自身即根"，
  使父子各持一把锁 —— §17.2 声称的 ABBA 死锁防护在真实数据库上静默失效，且只在含子流程的流程上发生。
  新增 Flyway `V2__add_instance_root.sql`（用 `ALTER TABLE` 而非改写 `V1`，避免破坏已应用迁移的校验和）；
  `InstanceRootPersistenceTest` 三套仓储逐个看守，并经变异校验确认抽掉读回即红。
- **CI 假红**：无 Docker 环境下 4 个跨库测试由 `FAILED` 改为 `skipped`（`requireDocker()`，零新依赖）

### 破坏性变更

- **InMemory 仓储改为拷贝语义**：`save` 存入副本、`findById` 返回副本。
  直接修改从仓储读出的对象**不再影响库内数据**，必须显式 `save`。
  同理 `TaskQuery` 返回的是查询时刻的快照，任务后续变更不会反映在已取出的对象上。
  受影响写法：`TaskInstance t = engine.getTask(id); t.setStatus(...)` —— 静默无效。
- **`TaskQuery` 的过滤条件现在真的生效了**：此前 `processDefinitionKey` 等条件空转、
  且不带 `processInstanceId` 时永远返回空。修正后同一查询的**结果集可能变化**（通常是从空变成有、从多变少）。
- **`TaskQuery.count()` 语义修正**：不再受 `limit()` / `offset()` 截断，返回真实命中数。
- **`TaskQuery.singleResult()` 命中多条时抛异常**，不再静默返回第一条。
- **引擎默认启用锁与事务**：一次业务动作内跨仓储写入改为整体生效或整体撤销；
  监听器异常仍被吞（不阻断主流程），但仓储写入异常会回滚整个动作。
  如需退回旧行为：`engine.withoutConcurrencyControl()`。
- **超时调度改为事务提交后生效**：`scheduler.cancel/schedule` 不再在事务内直接执行，
  回滚时不会留下"任务已 PENDING 但无超时调度"的不一致。

### 测试

- 新增 `ConcurrencySafetyTest`（3 用例 × 30 轮竞态）、`JpaTransactionAtomicityTest`、
  `MybatisTransactionAtomicityTest`、`JpaConcurrencyTest`（15 轮）、
  `InstanceQueryConsistencyTest`、`TaskQueryTest`（7 用例 × 3 套仓储）
- 总数 **44 类 / 209 用例 / 32 skipped / 0 failed**，可运行 177 全绿；`gradle build` SUCCESSFUL
- 修正 3 处依赖"对象引用泄漏"的旧断言（`SignStrategyTest` 直接断言手中旧快照）

### 说明

- `revision` 乐观锁**未在本版启用**：JPA/H2 并发探针实测 15/15 正确，说明分段锁已覆盖单 JVM；
  乐观锁买的是跨实例/集群安全，与 Phase 7（集群部署）一并实施。
- `ProcessInstance.reconstruct()` 旧签名保留兼容，但持久层应改用带 `rootInstanceId` / `revision` /
  `createTime` 的新签名，否则子实例会丢失流程树根、任务创建时间会被刷成当前时刻。

---

## [3.6.0] - 2026-08-06

### 新增
- **抄送功能**（v3.6）
  - `CarbonCopy` 领域对象：instanceId / taskId / nodeId / recipient / operator / message / read
  - `CarbonCopyRepository` 接口：save / findByRecipient / findUnreadByRecipient / findByInstanceId / markRead / clear
  - `InMemoryCarbonCopyRepository` 实现
  - `IWorkflowEngine.carbonCopy(instanceId, taskId, nodeId, recipients, operator, message)` 发送抄送
  - `IWorkflowEngine.getCarbonCopies(recipient)` 查询抄送列表
  - `IWorkflowEngine.getUnreadCarbonCopies(recipient)` 查询未读抄送
  - `IWorkflowEngine.markCarbonCopyRead(ccId)` 标记已读
  - 支持多人抄送、已读/未读状态、按接收人/实例查询

- **抄送测试套件**（1 个测试类，4 个用例）
  - `CarbonCopyTest`：多人抄送 / 未读过滤 / 任务完成后抄送 / 未启用时返回空

### 变更
- `WorkflowEngine` 构造器新增 `CarbonCopyRepository` 参数（可选）
- `IWorkflowEngine` 新增 4 个抄送相关方法

### 测试
- **181/181 PASSED**（InMemory 67 + JPA 41 + MyBatis 41 + 跨库 32，七套件）
- `gradle :workflow-tests:test --no-daemon --no-build-cache` BUILD SUCCESSFUL

---

## [3.5.0] - 2026-08-06

### 新增
- **流程撤回**（v3.5）
  - `IWorkflowEngine.withdraw(instanceId, initiator)` 发起人撤回未审批的申请
  - 校验规则：仅 RUNNING 状态可撤回 / 发起人必须匹配 / 无已审批任务
  - 撤回后任务状态置为 WITHDRAWN，实例状态置为 TERMINATED
  - 审计日志记录 PROCESS_WITHDRAWN 事件

- **流程委托**（v3.5）
  - `Delegation` 领域对象：delegator（委托人）/ delegate（代理人）/ nodeId（可选）/ processKey（可选）
  - `DelegationRepository` 接口 + `InMemoryDelegationRepository` 实现
  - `IWorkflowEngine.delegate(delegator, delegate)` 设置委托
  - `IWorkflowEngine.revokeDelegate(delegator, delegate)` 撤销委托
  - `completeTask` 支持代理人审批：检查委托关系，记录实际审批人为委托人
  - 支持全局委托 / 指定节点委托 / 指定流程委托

- **流程催办**（v3.5）
  - `NotificationService` 接口：notify / urge / timeoutReminder
  - `IWorkflowEngine.urge(taskId, operator, reason)` 向候选人发送催办通知
  - 超时调度集成：超时触发时自动调用 timeoutReminder

- **动态多实例**（v3.5）
  - `NodeType.DYNAMIC_PARALLEL` 新节点类型
  - `NodeDefinition.dynamicParallel(id, name, variable, strategy)` 工厂方法
  - `ProcessBuilder.dynamicParallel(id, name, variable, strategy)` DSL
  - 运行时从变量获取候选人列表（List<String>），动态创建任务
  - 支持 ANY（或签）/ ALL（会签）策略

- **企业级功能测试套件**（1 个测试类，9 个用例）
  - `EnterpriseFeaturesTest`：撤回成功/撤回失败（已审批）/撤回失败（非发起人）/委托审批/撤销委托/催办通知/动态多实例创建/动态多实例ANY/动态多实例ALL

### 变更
- `TaskStatus` 新增 `WITHDRAWN` 状态
- `AuditEventType` 新增 `PROCESS_WITHDRAWN` 事件
- `WorkflowEngine` 构造器新增 `DelegationRepository` 和 `NotificationService` 参数（可选）
- `IWorkflowEngine.start` 新增 `initiator` 参数重载（用于撤回校验）
- `NodeDefinition` 新增 `dynamicParallelVariable` / `dynamicParallelStrategy` 字段
- `ProcessBuilder.build()` 校验新增 DYNAMIC_PARALLEL 节点检查

### 测试
- **177/177 PASSED**（InMemory 63 + JPA 41 + MyBatis 41 + 跨库 32，七套件）
- `gradle :workflow-tests:test --no-daemon --no-build-cache` BUILD SUCCESSFUL

---

## [3.4.0] - 2026-08-06

### 新增
- **流程变量强类型**（README 候选第 5 项）
  - `VariableType` 枚举：STRING / INTEGER / LONG / DOUBLE / BOOLEAN / OBJECT
  - `VariableDefinition` 不可变对象：name / type / required / defaultValue / description，Builder 模式构造
  - `ProcessBuilder.variable(name, type)` 与 `variable(VariableDefinition)` DSL 链式声明变量 schema
  - `ProcessDefinition` 新增 `variableDefinitions` 字段 + `getVariableDefinitions()` / `hasVariableDefinitions()`
  - `VariableValidator` 启动时校验：必填缺失报错 / 默认值自动填充 / 类型不匹配报错；未定义 schema 跳过（向后兼容）
  - `WorkflowEngine.start` 接入校验（`startWithDefinition` 内调用 `VariableValidator.validate`）
- **持久化**：`wf_process_def` 新增 `variable_definitions_json` 列（Flyway `V1__init.sql`），JPA / MyBatis 仓储读写 schema
- **变量测试套件**（3 个测试类，16 个用例）
  - `VariableTypeTest`（InMemory，8 用例）：校验通过/必填缺失/类型不匹配/默认值填充/全类型支持/整型-长整型兼容/数值-DOUBLE 兼容/无 schema 跳过
  - `JpaVariableTypeTest`（JPA，4 用例）+ `MybatisVariableTypeTest`（MyBatis，4 用例）：校验通过/必填缺失/类型不匹配/默认值填充

### 修复
- **MyBatis `WfProcessDefMapper` 手写 SQL 缺 `variable_definitions_json` 列**：INSERT/UPDATE 语句补列，否则变量 schema 保存后读回为 null，MyBatis 套件变量校验全挂

### 变更
- README：新增 7.6 变量强类型章节 / 表结构 wf_process_def 加列 / 测试覆盖 168 用例 / 扩展方向第 5 项划掉
- CHANGELOG：v3.4.0 归档

### 测试
- **168/168 PASSED**（InMemory 54 + JPA 41 + MyBatis 41 + 跨库 32，七套件）
- `gradle :workflow-tests:test --no-daemon --no-build-cache` BUILD SUCCESSFUL

---

## [3.3.0] - 2026-08-06

### 新增
- **审计日志功能**（README 候选第 4 项）
  - `AuditEventType` 枚举：11 种事件类型（流程发起/任务完成/任务驳回/任务转办/流程终止/挂起/恢复/超时自动通过/超时自动驳回/超时自动终止/超时自动转办）
  - `AuditLog` 领域对象：不可变，包含 instanceId / taskId / eventType / operator / timestamp / detail
  - `AuditLogRepository` 接口：save / findByInstanceId / findByTaskId / findByTimeRange / clear
  - 三仓储实现：`InMemoryAuditLogRepository` / `JpaAuditLogRepository` / `MybatisAuditLogRepository`
  - 引擎埋点：start / completeTask / rejectTask / transferTask / terminate / suspend / resume / 超时触发 8 个关键路径自动记录
  - 可选功能：审计日志仓储可为 null，不传入则不记录审计日志，零性能开销
- **数据库表**：`wf_audit_log` 表，Flyway 迁移脚本 `V1__init.sql` 已更新
- **审计日志测试套件**（3 个测试类，27 个用例）
  - `AuditLogTest`（InMemory，9 用例）：流程发起/任务完成/任务驳回/任务转办/流程终止/挂起恢复/超时自动通过/按任务查询/按时间范围查询
  - `JpaAuditLogTest`（JPA，9 用例）：同上
  - `MybatisAuditLogTest`（MyBatis，9 用例）：同上

### 变更
- `WorkflowEngine` 构造器新增 `AuditLogRepository` 参数（可选，传 null 不启用审计）
- `JpaPersistence` 新增 `auditLogRepo()` 方法
- `MybatisPersistence` 新增 `auditLogRepo()` 方法
- `MybatisPersistence.clearTables()` 新增清理 `wf_audit_log` 表
- `JpaEngineTestBase` / `MybatisEngineTestBase` 测试基类注入 `auditLogRepo`
- README：新增第 11 章审计日志 / 表结构新增 wf_audit_log / Domain 映射新增 AuditLog / 测试覆盖 170 用例 / 扩展方向第 4 项划掉
- CHANGELOG：v3.3.0 归档

### 测试
- **152/152 PASSED**（InMemory 46 + JPA 37 + MyBatis 37 + 跨库 32，六套件）
- `gradle :workflow-tests:test --no-daemon --no-build-cache` BUILD SUCCESSFUL in 1m 9s

---

## [3.2.0] - 2026-08-06

### 新增
- **UserTask 超时调度**（README 候选第 3 项）
  - `TimeoutPolicy` 枚举：`AUTO_APPROVE` / `AUTO_REJECT` / `AUTO_TERMINATE` / `AUTO_TRANSFER`
  - `NodeDefinition.withTimeout(millis, policy)` 不可变副本 + `hasTimeout()` 查询
  - `ProcessBuilder.timeout(id, millis, policy[, targetUserId])` DSL 链式配置
  - `TimeoutScheduler` 接口 + `ScheduledTimeoutScheduler` 默认实现（单线程 daemon `ScheduledExecutorService`，不引 Quartz）
  - 引擎集成：任务创建时 `schedule`、完成/驳回/转办/终止时 `cancel`
  - 超时回调 `onTaskTimeout`：幂等保证（重查任务状态，非 PENDING 忽略）；按策略执行自动动作
  - AUTO_APPROVE：反射写 `completedApprovers` 加 `__system__` 标记，绕过候选人校验
  - AUTO_REJECT：退回上一 UserTask（无上一节点则终止）
  - AUTO_TERMINATE：关闭实例 + 所有 PENDING 任务
  - AUTO_TRANSFER：原任务置 TRANSFERRED，新建目标用户任务，继承原节点超时配置
  - 会签场景：部分完成不取消调度，整体完成才取消
  - 转办继承：新任务继承原节点超时配置，重新注册调度
- **超时测试套件**（3 个测试类，14 个用例）
  - `TimeoutSchedulerTest`（InMemory，6 用例）：4 策略 + 手动完成取消 + 会签部分完成
  - `JpaTimeoutSchedulerTest`（JPA，4 用例）：4 策略
  - `MybatisTimeoutSchedulerTest`（MyBatis，4 用例）：4 策略

### 修复
- **JPA/MyBatis 仓储 completedApprovers 序列化**：直接反射读底层 `HashSet` 序列化，避免 `Collections.unmodifiableSet` 包装类型的序列化问题
- **超时回调 instance 视图同步**：AUTO_APPROVE/REJECT/TRANSFER 三个分支都同步 `instance.tasks` 中对应 task 的状态，与 `completeAndAdvance` 同理，解决 JPA/MyBatis 下 advanceToken 拿旧 PENDING 状态误判的 bug

### 变更
- `NodeDefinition` 新增 `timeoutMillis` / `timeoutPolicy` / `timeoutTargetUserId` 字段，`@JSONCreator` 完整参数构造器兼容旧数据
- `WorkflowEngine` 构造器新增 `TimeoutScheduler` 参数（可选，默认 `ScheduledTimeoutScheduler`）
- `WorkflowEngine.shutdown()` 方法：释放调度线程
- README：DSL 7.5 超时策略 / 测试覆盖 125 用例 / 扩展方向第 3 项划掉
- CHANGELOG：v3.2.0 归档

### 测试
- **125/125 PASSED**（InMemory 31 + JPA 31 + MyBatis 31 + 跨库 32，六套件）
- `gradle :workflow-tests:test --no-daemon --no-build-cache` BUILD SUCCESSFUL in 1m 12s

---

## [3.1.0] - 2026-08-05

### 新增
- **MyBatis-Plus 持久化模块** `workflow-persistence-mybatis`（第 5 个 Gradle 模块，国产 ORM）
  - 依赖：`com.baomidou:mybatis-plus:3.5.17`（苞米豆，国产），无 Spring 轻量用法
  - 4 个 Entity（`@TableName` 注解）：`WfProcessDefEntity` / `WfInstanceEntity` / `WfTokenEntity` / `WfTaskEntity`
  - 4 个 Mapper：单主键表（instance/token/task）继承 `BaseMapper` 通用 CRUD + 注解 SQL；
    `wf_process_def` 复合主键不支持 BaseMapper，全部手写 `@Insert`/`@Delete`/`@Select`，
    并利用 H2 `MERGE INTO ... KEY(key_, version)` 实现同 key+version 覆盖（upsert）
  - 3 个仓储实现：`MybatisProcessRepository` / `MybatisInstanceRepository` / `MybatisTaskRepository`
  - `MybatisPersistence` 单例工厂：`init`（内嵌 DDL 建表）/ `close` / `inSession`（事务模板）/ `clearTables` / 三仓储入口
  - 表结构沿用 JPA 版 4 张表（`wf_process_def` / `wf_instance` / `wf_token` / `wf_task`），DDL 由 `MybatisPersistence.init()` 内嵌执行
- **MyBatis 测试套件**（6 个测试类，19 个用例，继承 `MybatisEngineTestBase`）
  - `MybatisSerialFlowTest` / `MybatisParallelGatewayTest` / `MybatisSignStrategyTest` /
    `MybatisRejectTransferTerminateTest` / `MybatisConditionGatewayTest` / `MybatisProcessVersionTest`
  - 与 InMemory / JPA 套件**完全同一组用例**，验证三仓储可替换性

### 变更
- `settings.gradle.kts`：模块声明 4 → 5（新增 `workflow-persistence-mybatis`）
- `workflow-tests/build.gradle.kts`：新增 `testImplementation(project(":workflow-persistence-mybatis"))`
- README：模块结构 / 技术栈 / 快速上手（5.3 MyBatis 切换示例）/ 仓储章节（10.4）/ 持久化细节（11.5）/ 测试覆盖（57 用例）/ 文件清单全部更新
- CHANGELOG：v3.0.0 归档

### 测试
- **57/57 PASSED**（InMemory 19 + JPA 19 + MyBatis-Plus 19，三套件同一组用例）
- `gradle :workflow-tests:test` BUILD SUCCESSFUL（27 个 task）

---

## [2.1.0] - 2026-07-28

### 新增
- **条件网关（EXCLUSIVE_GATEWAY）运行时路由**
  - `Transition.condition` 字段落地：`connect(from, to, "amount >= 1000")` 携带条件
  - 自研 `ConditionEvaluator`：支持数字比较（`<` / `<=` / `>` / `>=` / `==`）、字符串比较（`==` / `!=`）、逻辑运算（`&&` / `||` / `!`）、括号分组，从流程变量取值
  - `PathNavigator` 网关路由改造：按序匹配第一条条件满足的出边；无匹配时走无条件的默认出边；连默认出边都没有则消费 Token（流程结束）
  - 非法表达式在节点构建时 / 路由时抛出异常
- **流程版本管理（复合主键 key + version）**
  - `wf_process_def` 改复合主键 `(key, version)`：`ProcessDefinition.version` 落库
  - `ProcessRepository` 扩展：`findByKeyAndVersion(key, version)` / `getVersions(key)` / `exists(key)`；`findByKey(key)` 语义改为返回最新版本
  - `ProcessInstance` 固化 `processVersion`：`engine.start(key, version, vars)` 支持按指定版本发起，实例运行期间版本变化不影响已发起实例
  - `WfInstanceEntity` 增加 `process_version` 列
- **测试扩展**：InMemory + JPA 各增加 `ConditionGatewayTest`（5 用例）+ `ProcessVersionTest`（5 用例）

### 测试
- **38/38 PASSED**（InMemory 19 + JPA 19）

---

## [2.0.0] - 2026-07-20

### 新增
- **JPA 持久化模块** `workflow-persistence-jpa`（第 4 个 Gradle 模块）
  - 4 个 JPA Entity：`WfProcessDefEntity` / `WfInstanceEntity` / `WfTokenEntity` / `WfTaskEntity`
  - 3 个 JPA 仓储实现：`JpaProcessRepository` / `JpaInstanceRepository` / `JpaTaskRepository`
  - `JpaPersistence` 单例工厂：`init` / `close` / `newEntityManager` / `processRepo` / `instanceRepo` / `taskRepo` / `inTransaction` / `bindCurrentEm`（ThreadLocal 事务复用）
  - `META-INF/persistence.xml`：H2 内存库 `jdbc:h2:mem:workflow_db` + `hbm2ddl.auto=create-drop`
- **JPA 测试套件**（4 个测试类，9 个用例，继承 `JpaEngineTestBase`）
  - `JpaSerialFlowTest` / `JpaParallelGatewayTest` / `JpaSignStrategyTest` / `JpaRejectTransferTerminateTest`
  - 与 InMemory 套件**完全同一组用例**，验证仓储可替换性
- **交付文档**
  - `README.md`（15 章节：背景/原则/技术栈/模块/API/DSL/领域模型/调度核心/仓储/JPA 细节/测试/构建/决策复盘/扩展方向）
  - `docs/architecture.md`（6 张 Mermaid 图：模块依赖/DSL/领域模型/调度状态机/JPA 映射/completeTask 时序）

### 修复
- **引擎与 JPA 仓储的对象引用不一致（关键 Bug）**
  - 现象：InMemory 9/9 通过，JPA 6/9 失败（serial_flow / parallel_fork_join / any_sign / all_sign / transfer / reject）
  - 根因：InMemory 下 `taskRepo.findById` 与 `instance.tasks[0]` 是同一对象引用，`recordCompletion` 改状态后实例视图自动跟随；JPA 下每次 `findById` 都反射重建**新对象**，`completeAndAdvance` 里改的是 task 副本，`instance.tasks[0]` 仍是旧 PENDING 状态，`advanceToken` 误判走"PENDING 跳过"分支
  - 修法：`completeAndAdvance` 在 `recordCompletion` 后显式同步 instance 视图中同一 taskId 的 `status` + `completedApprovers`
- **JPA 仓储事务模式**
  - `JpaInstanceRepository` 从裸 `inTransaction` 改为 `runInOrOpenTx`（优先复用 ThreadLocal 绑定 EM，无则开新事务），与 `JpaTaskRepository` 行为一致
- **Gradle Wrapper 损坏**
  - `gradle/wrapper/gradle-wrapper.jar` 自举复制不完整，`gradlew.bat` 报 `NoClassDefFoundError: IDownload`
  - 修法：用 `gradle wrapper --gradle-version 8.5` 重新生成 wrapper jar + properties
  - `gradlew.bat` 增加**离线优先**逻辑：存在本地 `gradle-8.5\bin\gradle.bat` 时直接调用，否则走标准 wrapper 下载

### 变更
- `JpaTaskRepository.rebuildFromEntity` 改为 `public static`（供 `JpaInstanceRepository` 复用）
- 移除调试用 trace 日志（`[JPA-Task]` / `[JPA-Rebuild]` / `[引擎] advanceToken` 高冗余 INFO）

### 测试
- **18/18 PASSED**（InMemory 9 + JPA 9）
- `gradle build` BUILD SUCCESSFUL（21 个 task）

---

## [1.0.0] - 2026-07-06

### 新增（v1 最小可用版：L3 审批流引擎）

#### 子任务 0：Gradle 多模块骨架
- `settings.gradle.kts` / `build.gradle.kts`（根）/ `gradle.properties` / `gradle/wrapper/*` / `gradlew.bat`
- 4 模块：`workflow-core` / `workflow-sample` / `workflow-tests`（+ 后增 `workflow-persistence-jpa`）
- Gradle 8.5 本地解压到 `workflow-engine/gradle-8.5/`（离线环境自举）

#### 子任务 1：流程定义模型
- `enums/NodeType.java`：START / END / USER_TASK / EXCLUSIVE_GATEWAY / PARALLEL_GATEWAY
- `enums/CandidateStrategy.java`：ANY / ALL
- `definition/Transition.java` / `Candidate.java` / `NodeDefinition.java` / `ProcessDefinition.java`
- `builder/ProcessBuilder.java`：链式 DSL（start/end/userTask/parallelGateway/connect + build 一次性校验）

#### 子任务 2：执行实例与 Token 机制
- `enums/InstanceStatus` / `TaskStatus` / `TokenStatus`
- `runtime/Token.java` / `TaskInstance.java`（含 `recordCompletion` 按 ANY/ALL 判定）/ `ProcessInstance.java`

#### 子任务 3：引擎核心
- `engine/IWorkflowEngine.java` / `WorkflowEngine.java` / `GatewayKind.java` / `PathNavigator.java`
- API：`start` / `completeTask` / `rejectTask` / `transferTask` / `suspend` / `resume` / `terminate` / `getInstance` / `getTask`
- **修复**：`advanceToken` 在 UserTask 节点完成任务后要把 Token 推进到下一节点，而非停留

#### 子任务 4：仓储抽象与 InMemory 实现
- 3 接口：`ProcessRepository` / `InstanceRepository` / `TaskRepository`
- 3 InMemory 实现：`ConcurrentHashMap` 线程安全

#### 子任务 5：请假审批 Demo
- `workflow-sample/.../LeaveDemo.java`：`start → apply → manager(ANY) → hr → end` 全链路演示

#### 子任务 6：单元测试
- 9 个 InMemory 测试全 PASSED
- `SerialFlowTest` / `ParallelGatewayTest` / `SignStrategyTest` / `RejectTransferTerminateTest`

### 技术栈（v1 定稿，v2 沿用）
- Java 17 + Gradle 8.5（Kotlin DSL）
- 国产库：fastjson2 2.0.49（JSON）/ hutool 5.8.27（工具）/ SLF4J 2.0.13 + Logback 1.5.6
- 测试：JUnit 5.10.2 + AssertJ 3.25.3
- 持久化：JPA 3.1.0 + Hibernate 6.4.4 + H2 2.2.224 + HikariCP 5.1.0

---

## [0.1.0] - 2026-07-05

### 初始
- 项目立项：自研工作流引擎，明确**不使用第三方工作流框架**（Flowable / Camunda / Activiti）
- 确认技术选型：Java 17 + Gradle 8.5（Kotlin DSL，不用 Maven）+ JUnit 5 + AssertJ
- 明确持久化路线：先 InMemory，后补 JPA（H2 演示）
- 明确不搞可视化设计器，纯代码 DSL
- 基础设施国产化：fastjson2 + hutool + SLF4J/Logback
