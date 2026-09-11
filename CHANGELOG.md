# CHANGELOG

自研工作流引擎（workflow-engine）变更日志。格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

项目状态：**v3.15.0 进行中** — 进生产底盘加固（① 超时调度重启恢复 ✅ / ② REST 鉴权 ✅ / ③ 集群乐观锁 ✅ / ④ 批量迁移）。

---

## [3.15.0] - 2026-09-11

定位：**进生产底盘加固** —— 补齐「进程重启 / 对外暴露 / 多节点部署 / 存量迁移」四类场景下的底盘缺口。本版完成 ① / ② / ③ 三项。

### ① 超时调度重启恢复

> ⚠️ **破坏性变更（SPI 语义）**：`TimeoutScheduler.schedule(...)` 的第三个参数从**相对延时**改为**绝对到期时刻**（epoch millis）。方法签名不变，因此自定义实现仍能编译，但会把绝对时刻当成延时使用（结果近似「永不触发」）。**升级前必须检查所有 `TimeoutScheduler` 实现**。

#### 新增
- **`WorkflowEngine.recoverTimeouts()`**：扫描仍 PENDING 的任务，按 `createTime + 节点超时配置` 重算到期时刻重新注册；已过期的立即触发（异步，不阻塞调用方）。返回恢复的调度数量
- **`WorkflowEngineBuilder.autoRecoverTimeouts(boolean)`**：默认 `true`，`build()` 时自动恢复一次

#### 修复
- **超时调度重启丢失**：`TimeoutScheduler` 的注册表在内存里，进程重启即空 —— 重启前建立的待办会**静默地**永不超时，审批卡死且无人知晓（数据都还在库里，只是再也没人来触发它）
- **`wf_task.create_time` 落库错误**：JPA / MyBatis 两套仓储在 INSERT 分支写的是 `System.currentTimeMillis()`（插入时刻），而不是 `TaskInstance.createTime`（任务创建时刻）。读回路径忠实返回库里的值，所以自洽、现有测试测不出来。实际影响：`orderByCreateTime()` 退化为按插入顺序排序、并行网关同一批 fork 出的任务顺序不确定、耗时统计偏移、恢复算出的到期时刻偏移（写入耗时多少就晚多少）
  - 修正 `JpaTaskRepository` 2 处、`MybatisTaskRepository` 2 处；`JpaInstanceRepository` / `MybatisInstanceRepository` 本来用的就是 `instance.getCreateTime()`，无此问题

#### 设计要点
- **到期时刻不落库，而是派生**：`dueAt = createTime + 节点超时配置` —— 一个在 `wf_task.create_time`，一个在定义的 `nodes_json` 里，重启后可精确重算。因此**不需要新增列**（省掉一次迁移 + 三套仓储改造），也避免造出第二真相来源
- **不引入定期扫描**：启动扫描一次即可覆盖 —— 重启前建立的调度，要么重新注册、要么因已过期而立即触发；此后每个新任务在创建时正常注册
- **多节点恢复的重复触发由幂等兜底**：回调内部会重查任务状态，非 PENDING 即忽略，因此启动扫描与既有调度重叠、或多个节点同时恢复都是安全的
- **挂起的实例不恢复**：恢复只认 `RUNNING` 实例，否则「挂起」会被恢复动作立刻超时，形同虚设

#### 测试
- **`TimeoutRecoveryTest`**（InMemory）/ **`JpaTimeoutRecoveryTest`**（JPA）/ **`MybatisTimeoutRecoveryTest`**（MyBatis）：各 7 个用例，**同一组用例跑三套仓储**
  - 恢复必须重算出与建任务时**完全相同**的到期时刻（`createTime` 需真的从库里读回来 —— 这组用例正是上面那个落库 Bug 的探测器）
  - 停机期间已过期的任务被注册为「已过期」，交给调度器立即触发（补偿路径）
  - 端到端：重启后真实触发 AUTO_APPROVE，任务完成、审批人记为 `__system__`
  - 不该恢复的场景：已完成实例 / 节点未配超时 / 实例已挂起 / 关闭自动恢复
- 新增测试基建 `RecordingTimeoutScheduler`（只记录不触发 —— 断言到期时刻比睡眠若干毫秒去猜触发时间稳定得多）
- **全量回归 360 用例 0 失败**

---

### ② REST 鉴权

定位：REST 层此前**完全敞口** —— 端口一旦对外可达，任何人都能启动 / 终止 / 改派任意流程实例。本项补上可插拔鉴权。

#### 新增
- **`RequestAuthenticator`**（函数式接口，`com.workflow.rest`）：`AuthResult authenticate(RestRequest)` + 常量 `NONE`（全放行，默认值）。本模块**不引入任何安全框架** —— 引擎定位是嵌入式 jar，塞一整套安全栈进去会逼所有调用方接受它，只留一个钩子，怎么鉴权由部署方决定
- **`ApiKeyAuthenticator`**：随包提供的最小可用实现，三个静态工厂
  - `of(String... keys)` —— 默认请求头 `X-API-Key`
  - `ofHeader(String headerName, String... keys)` —— 自定义头名
  - `bearer(String... tokens)` —— 标准 `Authorization: Bearer <token>`
- **`AuthResult`**（record）：`granted` / `status` / `message`，配 `allowed()` / `unauthorized(msg)` / `forbidden(msg)`
- **`WorkflowRestApi` 第 4 个构造参数**接受鉴权器；原三参构造保留，等价于不鉴权（零配置行为不变）
- **`RestServer.readHeaders`**：把请求头交给路由层（此前只解析 method / path / body）

#### 变更
- **`RestRequest.headers` 大小写不敏感**：HTTP 规定头名不分大小写，客户端发 `x-api-key` 必须与 `X-API-Key` 等价 —— 否则就成了"文档写一种、实际要另一种"的隐坑

#### 设计要点
- **fail-closed**：鉴权器自身抛异常 → 500 且不外泄异常细节，绝不"异常即放行"；返回 `null` 视为放行（容忍懒实现），显式传 `null` 鉴权器同理
- **401 与 403 分开**：401 = 没带 / 凭证无效（该去换凭证），403 = 身份已知但无权限（该找管理员）。压成 400 / 500 会让调用方无从判断 —— 与 409 之所以重要的理由相同
- **常量时间比较**：用 `MessageDigest.isEqual`，且多密钥**不短路、全部比完**再下结论，避免用响应时间泄漏命中的是哪一个
- **构造即校验**：空密钥白名单在构造期就抛 `IllegalArgumentException`，不留"运行时全放行"的后门
- **健康检查不豁免**：`/api/health` 同样走鉴权。要匿名探针请在网关放行 —— 引擎说不出哪些端点对某个部署是"安全的"，不做特例
- **写请求先鉴权后落库**：401 之后引擎**不被触碰**（有用例专门断言"副作用为零"）
- **为什么用静态工厂而非重载构造器**：`(String...)` 与 `(String, String...)` 对最常见的 `new ApiKeyAuthenticator("k")` 是**歧义的**，编译器直接报错（实测踩到，9 个编译错误）。工厂方法名字不同，调用处一眼看得出用的哪种

#### 测试
- **`RestAuthenticationTest`**（16 用例）：401 / 403 区分、头名大小写不敏感、Bearer 前缀大小写不敏感、多密钥任一通过、fail-closed、空白名单构造失败、`null` 鉴权器放行、未配鉴权器保持零配置放行、写操作 401 后无副作用、健康检查需鉴权
- **`RestAuthHttpTest`**（5 用例）：**真 HTTP 往返** —— 无凭证 / 错凭证经 socket → 401、带凭证的请求头确实被传输层捞到并放行、被拒的写请求不产生副作用、带凭证的启动请求正常创建实例
  - 特意写在真 socket 上而非直接调 API：头是**传输层**解析的，"我构造的 `RestRequest` 里有这个头"证明不了客户端能把它送到
- **全量回归（`gradle build --rerun-tasks`）：391 PASSED / 0 FAILED / 35 SKIPPED**（跳过的是跨库 Testcontainers 套件，本机 Docker 未启用）

### ③ 集群乐观锁（CAS）

多节点部署下，两个节点同时改写同一实例行会互相覆盖 —— 会签场景最典型：两个审批人同时点通过，一方的写入凭空消失，另一方永远不知道。本项给运行态表引入版本号 + CAS。

#### 新增
- **迁移 `V9__optimistic_lock.sql`**：`wf_instance` / `wf_task` 各加 `revision BIGINT NOT NULL DEFAULT 0`
- **`JpaPersistence.asConflictIfOptimisticLock(ex, conflictId)`**：顺 cause 链把乐观锁冲突统一转成 `WorkflowConflictException`

#### 变更
- **JPA**：`WfInstanceEntity` / `WfTaskEntity` 加 `@Version`；两仓储 `rebuild` 改为从 `e.getRevision()` 回读（原先硬编码 `0L`）
- **MyBatis**：两实体加 `@Version`；`MybatisPersistence` 注册 `OptimisticLockerInnerInterceptor`（此前**一个拦截器都没注册**，`@Version` 仅仅是个普通字段）
- **`JpaPersistence.commitAndUnbind()`**：提交点的冲突同样接住
- **迁移编号取 V9 而非 V8**：V8 已被 `workflow-persistence-mybatis` 模块自带的 `V8__add_arrival_for_loop_support.sql` 占用

#### 修复
- **共享事务下的乐观锁冲突逃逸**：CAS 条件在 flush 时判定，而共享事务（引擎 exclusive 段、跨仓储合并事务）的 flush + commit 都发生在调用方手里 —— 不在 `JpaPersistence.commitAndUnbind()` 转换，`conflictRetries` 的重试逻辑**永不触发**，一次寻常的并发冲突会直接冒泡成 500
  - 且必须**顺 cause 链**找：Hibernate 提交失败抛的是 `RollbackException`，真正的 `OptimisticLockException` / `StaleObjectStateException` 藏在 cause 里；只判最外层会漏掉**全部**提交期冲突
- **MyBatis 乐观锁插件装载方式**：`OptimisticLockerInnerInterceptor` **不是** MyBatis 原生 `Interceptor`，必须先由 `MybatisPlusInterceptor` 包一层再 `configuration.addInterceptor(...)`；直接挂则编译期即报类型不兼容
- **V8 迁移号撞号**：两个模块各有一份 `V8__*.sql`，Flyway 扫描合并 classpath 后抛 `Found more than one migration with version 8`，导致 41 个持久化用例集体 `initializationError`

#### 测试
- **`OptimisticLockTest`**（2 用例，跨三套仓储）：
  - `revisionAdvancesOnEveryWrite`：三仓储版本号 1→2→3。这条看着平凡，实为分水岭 —— 漏 `@Version`、漏注册插件、漏迁移列，写回都会**静默成功**、版本号纹丝不动
  - `concurrentWritersOnSameRevisionExactlyOneWins`：`CyclicBarrier` 把两个线程卡在"已读完、尚未写"，JPA 侧用 `bindCurrentEm` 保证读写同事务 —— 否则 `save` 内部会重查库拿到最新版本、条件恒成立、CAS 永不触发，测试将以"两个都成功"的**假绿**通过。实测：恰好 1 成功 + 1 冲突
- 内存仓储的真实并发**有意不测**：其 CAS 是"检查后写入"两步、非原子，单 JVM 的并发保护在引擎 `LocalInstanceLocks` 那层
- **全量回归（`gradle build --rerun-tasks`）：393 PASSED / 0 FAILED / 35 SKIPPED**

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