# CHANGELOG

自研工作流引擎（workflow-engine）变更日志。格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

项目状态：**v3.6.0 已完成** — 抄送功能落地，181/181 测试全绿。

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
