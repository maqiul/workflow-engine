# 自研工作流引擎 (Workflow Engine)

![Java](https://img.shields.io/badge/Java-17-orange)
![CI](https://github.com/maqiul/workflow-engine/actions/workflows/ci.yml/badge.svg?branch=main)
![License](https://img.shields.io/badge/License-Apache--2.0-blue)

> 一个**纯代码 DSL**、**零第三方工作流框架依赖**、**国产基础库 + Java 17** 的轻量级审批流引擎。
> 支持串行 / 并行网关 / 会签（ANY/ALL）/ 驳回 / 转办 / 暂停-恢复 / 终止 / 退回到任意节点 / **循环回边**（排他网关回边式循环）/ **动态 assignee**（运行时从变量取办理人）/ **serviceTask 自动节点**（自动执行 delegate）/ **拓扑自省**（节点/连线 + 实例 Token 高亮只读视图）/ **实例版本迁移**，
> 事件网关（消息·信号·定时器）· DMN 决策表 · 监控仪表盘 · 多租户隔离 · 批处理与批量启动 · 通知服务 · **审批意见**（一等存储，不随历史保留策略清理）,
> 三仓储实现（InMemory + JPA + MyBatis-Plus）。
>
> 📘 **要用起来？** 看面向接入方的操作手册 → **[OPERATIONS.md](OPERATIONS.md)**（跑 Demo、五步接入、DSL 速查、REST 全表、多租户/监控/DB 接入、排错）。变更记录见 [CHANGELOG.md](CHANGELOG.md)。

---

## 📑 目录

- [1. 项目背景与目标](#1-项目背景与目标)
- [2. 关键设计原则](#2-关键设计原则)
- [3. 技术栈](#3-技术栈)
- [4. 模块结构](#4-模块结构)
- [5. 快速上手](#5-快速上手)
- [6. 核心 API](#6-核心-api)
- [7. 流程定义 DSL](#7-流程定义-dsl)
- [8. 领域模型](#8-领域模型)
- [9. 引擎调度核心](#9-引擎调度核心)
- [10. 仓储抽象与三实现](#10-仓储抽象与三实现)
- [11. 审计日志](#11-审计日志)
- [12. 持久化细节（JPA / MyBatis-Plus）](#12-持久化细节jpa--mybatis-plus)
- [13. 测试覆盖](#13-测试覆盖)
- [14. 构建与运行](#14-构建与运行)
- [15. 关键工程决策与坑](#15-关键工程决策与坑)
- [16. 后续可扩展方向](#16-后续可扩展方向)
- [17. 并发与事务模型](#17-并发与事务模型)
- [18. 监控仪表盘](#18-监控仪表盘)
- [19. 多租户隔离](#19-多租户隔离)
- [20. 批处理与批量启动](#20-批处理与批量启动)
- [21. 通知服务](#21-通知服务)
- [22. 性能基准](#22-性能基准)
- [23. 循环回边支持](#23-循环回边支持)
- [24. 动态 assignee 支持](#24-动态-assignee-支持)
- [25. serviceTask 自动节点](#25-servicetask-自动节点)
- [26. 拓扑自省](#26-拓扑自省)
- [27. Flowable BPMN 导入兼容性](#27-flowable-bpmn-导入兼容性)
- [28. 审批意见](#28-审批意见v320)

---

## 1. 项目背景与目标

### 1.1 目标
- ✅ **不依赖任何第三方工作流框架**（❌ Flowable / Camunda / Activiti）
- ✅ **基础设施库国产化**：fastjson2（阿里，JSON 序列化）、SLF4J/Logback
- ✅ **Java 17 + Gradle 8.5**（Kotlin DSL），不用 Maven
- ✅ **测试**：JUnit 5 + AssertJ
- ✅ **持久化抽象**：先 InMemory，后补 JPA（H2 内存库演示）
- ✅ **不要可视化设计器**，纯代码 DSL

### 1.2 适用场景
- L3 审批流引擎：请假、报销、用印、合同审批等企业内常见业务流程
- 项目需嵌入工作流能力但不想引入 Flowable / Camunda 等重型框架
- 需要对流程引擎本身有完全控制权（定制、改写、调试）

---

## 2. 关键设计原则

| 原则 | 落地方式 |
|---|---|
| **不可变 Domain 对象** | `ProcessDefinition` / `Token` / `TaskInstance` 字段 final，仅通过反射在 JPA 仓储重建时写入 |
| **仓储抽象** | `ProcessRepository` / `InstanceRepository` / `TaskRepository` 三接口，引擎无感知具体实现 |
| **Token 机制** | 每个流程实例持一组 `Token`，并行网关 fork 出多个 Token，join 等待汇聚 |
| **路径导航（PathNavigator）** | 网关节点判定 fork/join，UserTask 节点判定创建/推进/终止任务 |
| **状态机驱动** | `NodeType`（START/END/USER_TASK/EXCLUSIVE_GATEWAY/PARALLEL_GATEWAY）分派调度逻辑 |
| **国产库 + 零外部工作流框架** | 不引入 Flowable / Camunda / Activiti；序列化用 fastjson2（阿里）、持久化可用 MyBatis-Plus（苞米豆） |
| **仓储事务内聚** | 仓储方法内部包事务，引擎 API 与持久化实现解耦 |

---

## 3. 技术栈

| 类别 | 选型 | 版本 |
|---|---|---|
| 语言 | Java | 17 |
| 构建 | Gradle (Kotlin DSL) | 8.5 |
| 日志 | SLF4J + Logback | 2.0.13 / 1.5.6 |
| JSON | fastjson2 | 2.0.49 |
| 持久化（JPA 路线） | Jakarta Persistence API + Hibernate Core | 3.1.0 / 6.4.4.Final |
| 持久化（MyBatis 路线） | MyBatis-Plus（国产） | 3.5.17 |
| Schema 迁移 | Flyway（统一 DDL，跨库） | 12.8.1 |
| 数据库 | H2 (内存模式) | 2.2.224 |
| 数据库 | MySQL / PostgreSQL（Testcontainers 集成测试） | 8.4 / 16 |
| 连接池 | HikariCP | 5.1.0 |
| 集成测试 | Testcontainers | 1.21.4 |
| 测试 | JUnit Jupiter | 5.10.2 |
| 断言 | AssertJ | 3.25.3 |

---

## 4. 模块结构

```
workflow-engine/
├── build.gradle.kts                  # 根 build
├── settings.gradle.kts               # 7 模块声明
├── gradle.properties
├── gradle/wrapper/                   # Gradle 8.5 wrapper
├── gradle-8.5/                       # 本地解压的 Gradle 发行版（已 gitignore，不入库）
│
├── workflow-core/                    # 【核心引擎 + 仓储接口 + InMemory 实现】
│   └── src/main/java/com/workflow/
│       ├── enums/                    # NodeType, TaskStatus, InstanceStatus, TimeoutPolicy, HistoryKind...
│       ├── definition/               # ProcessDefinition, NodeDefinition, Candidate, Transition, VariableDefinition, MessageEvent/SignalEvent/TimerBoundaryEvent
│       ├── builder/                  # 链式 DSL: ProcessBuilder
│       ├── runtime/                  # ProcessInstance, Token, TaskInstance, AuditLog, HistoricActivity/TaskInstance, CarbonCopy, Delegation
│       ├── engine/                   # WorkflowEngine, IWorkflowEngine, WorkflowEngineBuilder, TokenAdvancer, SubProcessHandler, ListenerSupport, TimeoutHandler, GatewayKind, PathNavigator, ConditionEvaluator, TenantContext, NotificationService(Logging/Webhook), BatchResult
│       ├── repository/               # 仓储接口 + InMemory 实现（Process/Instance/Task/Audit/History/Event/Decision/DecisionHistory/Delegation/CarbonCopy）
│       ├── concurrency/              # InstanceLockProvider, LocalInstanceLocks, WorkflowConflictException
│       ├── tx/                       # TransactionRunner, TransactionContext, UndoLogTransactionRunner
│       ├── listener/                 # ExecutionListener, TaskListener
│       ├── history/                  # HistoryKind, HistoryRetention
│       ├── query/                    # TaskQuery
│       ├── bpmn/                     # BpmnExporter / BpmnImporter
│       ├── dmn/                      # DecisionTable, DecisionTableExecutor, DecisionRepository, DecisionHistory(+InMemory)
│       └── monitor/                  # DashboardMetrics, MonitoringService
│
├── workflow-persistence-flyway/      # 【Flyway 统一 DDL 迁移模块】
│   ├── java/com/workflow/persistence/migrate/FlywayMigrator.java
│   └── resources/db/migration/       # V1__init … V7__multi_tenant（三库同一份）
│
├── workflow-persistence-jpa/         # 【JPA 持久化实现（Hibernate）】
│   ├── .../jpa/JpaPersistence.java   # 入口/工厂（init 时跑 Flyway）
│   ├── .../jpa/entity/               # Wf*Entity（instance/token/task/hist*/event/decision*/audit…）
│   └── .../jpa/repository/            # 各 JpaRepository（与 InMemory 行为一致）
│
├── workflow-persistence-mybatis/     # 【MyBatis-Plus 持久化实现（国产 ORM）】
│   ├── .../mybatis/MybatisPersistence.java
│   ├── .../mybatis/entity/  ·  mapper/（BaseMapper + 注解 SQL）  ·  repository/
│   └── （与 JPA 版实现同一批仓储接口）
│
├── workflow-rest/                    # 【REST API —— 零依赖（JDK HttpServer + fastjson2）】
│   └── .../rest/                      # RestServer, WorkflowRestApi, RestRequest, RestResponse
│
├── workflow-sample/                  # 【请假审批 Demo: LeaveDemo（gradlew :workflow-sample:run）】
│   └── src/main/java/com/workflow/sample/LeaveDemo.java
│
└── workflow-tests/                   # 【约 270 用例；跨库需 Docker，否则 skip】
    └── src/test/java/com/workflow/
        ├── tests/engine/             # InMemory 核心用例
        ├── tests/jpa/  tests/mybatis/ # 同套用例 × JPA / MyBatis-Plus
        ├── tests/crossdb/            # 跨库(MySQL/PostgreSQL × JPA/MyBatis, Testcontainers)
        ├── tests/dmn/  tests/concurrency/  tests/perf/  tests/support/ …
        ├── monitor/                  # MonitoringServiceTest（聚合）
        └── tests/{EngineTestBase, JpaEngineTestBase, MybatisEngineTestBase}
```

---

## 5. 快速上手

### 5.1 30 秒体验 InMemory 版

```java
import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.*;
import com.workflow.engine.WorkflowEngine;
import com.workflow.repository.*;

// 1. 定义流程（链式 DSL）
ProcessDefinition leave = ProcessBuilder.create("leave", "请假审批")
        .start("start")
        .userTask("apply", "提交申请", Candidate.ofAny("employee"))
        .userTask("manager", "经理审批", Candidate.ofAny("managerA", "managerB"))
        .userTask("hr", "HR 审批", Candidate.ofAny("hr"))
        .end("end")
        .connect("start", "apply")
        .connect("apply", "manager")
        .connect("manager", "hr")
        .connect("hr", "end")
        .build();

// 2. 装配引擎（InMemory 仓储）
InMemoryProcessRepository procRepo = new InMemoryProcessRepository();
InMemoryInstanceRepository instRepo = new InMemoryInstanceRepository();
InMemoryTaskRepository taskRepo = new InMemoryTaskRepository();
procRepo.save(leave);

WorkflowEngine engine = new WorkflowEngine(procRepo, instRepo, taskRepo);

// 3. 发起 + 审批
String instanceId = engine.start("leave", Map.of("days", 3));
String applyTaskId = engine.getInstance(instanceId).getTasks().get(0).getId();
engine.completeTask(applyTaskId, "employee", true);    // 员工提交
engine.completeTask(applyTaskId, "managerA", true);    // 经理审批（ANY 一人通过）
// ... 依次完成 hr 任务

// 4. 查询结果
ProcessInstance finalInst = engine.getInstance(instanceId);
System.out.println(finalInst.getStatus());  // COMPLETED
```

### 5.2 切换到 JPA 仓储（同一套 API）

```java
import com.workflow.persistence.jpa.JpaPersistence;

// 一次性初始化
JpaPersistence jpa = JpaPersistence.getDefault();
jpa.init();   // 默认连 H2 内存库 jdbc:h2:mem:workflow_db

// 拿 3 个仓储，构造引擎（API 与 InMemory 完全一致）
ProcessRepository procRepo = jpa.processRepo();
InstanceRepository instRepo = jpa.instanceRepo();
TaskRepository taskRepo = jpa.taskRepo();
WorkflowEngine engine = new WorkflowEngine(procRepo, instRepo, taskRepo);

// ... 同样的 start / completeTask / transferTask 调用

jpa.close();  // 应用退出时关闭
```

### 5.3 切换到 MyBatis-Plus 仓储（同一套 API）

```java
import com.workflow.persistence.mybatis.MybatisPersistence;

// 一次性初始化（无 Spring 轻量用法）
MybatisPersistence mb = MybatisPersistence.getDefault();
mb.init();   // 默认连 H2 内存库 jdbc:h2:mem:workflow_mybatis

// 拿 3 个仓储，构造引擎（API 与其他实现完全一致）
ProcessRepository procRepo = mb.processRepo();
InstanceRepository instRepo = mb.instanceRepo();
TaskRepository taskRepo = mb.taskRepo();
WorkflowEngine engine = new WorkflowEngine(procRepo, instRepo, taskRepo);

// ... 同样的 start / completeTask / transferTask 调用

mb.close();  // 应用退出时关闭
```

> **三仓储可替换性**：引擎只依赖 `ProcessRepository` / `InstanceRepository` / `TaskRepository` 三接口。
> 从 InMemory 换到 JPA 或 MyBatis-Plus，只改装配代码，业务调用零改动。

### 5.4 切换到 MySQL / PostgreSQL 生产库（同一套 API）

建表统一由 Flyway 迁移脚本（`V1__init.sql`）负责，两种 ORM 在初始化时自动执行，无需手动建表：

```java
// JPA 路线切 MySQL：init 时覆盖 JDBC 属性 + 方言，Flyway 自动建表
Map<String, String> props = new HashMap<>();
props.put("jakarta.persistence.jdbc.driver", "com.mysql.cj.jdbc.Driver");
props.put("jakarta.persistence.jdbc.url", "jdbc:mysql://localhost:3306/workflow_db");
props.put("jakarta.persistence.jdbc.user", "wfuser");
props.put("jakarta.persistence.jdbc.password", "wfpass");
props.put("hibernate.dialect", "org.hibernate.dialect.MySQLDialect");
props.put("hibernate.hbm2ddl.auto", "none");   // 建表交给 Flyway
JpaPersistence jpa = JpaPersistence.getDefault();
jpa.init(props);
WorkflowEngine engine = new WorkflowEngine(jpa.processRepo(), jpa.instanceRepo(), jpa.taskRepo());
```

```java
// MyBatis-Plus 路线切 PostgreSQL：传 JDBC URL 即可，驱动自动识别，Flyway 自动建表
MybatisPersistence mb = MybatisPersistence.getDefault();
mb.init("jdbc:postgresql://localhost:5432/workflow_db", "wfuser", "wfpass");
WorkflowEngine engine = new WorkflowEngine(mb.processRepo(), mb.instanceRepo(), mb.taskRepo());
```

> **跨库验证**：`workflow-tests` 里用 Testcontainers 起真实 MySQL 8.4 / PostgreSQL 16 容器，
> 同一套核心用例在「JPA×MySQL / JPA×PostgreSQL / MyBatis×MySQL / MyBatis×PostgreSQL」
> 四种组合下全量跑通（32 个跨库用例），证明仓储层真正与数据库无关。
>
> ⚠️ 但主 CI 用 `-PskipCrossDb=true` 跳过这 32 个用例（拉镜像慢、环境易抖动，不该阻塞主构建），
> 所以**它们的绿是本地一次性的，不是持续保障** —— 持续覆盖交给 nightly 任务（见 §14）。

---

### 5.5 引入到自己的工程（发布坐标）

引擎是**嵌入式 jar** —— 不引 Spring、不带容器，加两行依赖即可。

```bash
# 1. 先发布到本地 Maven 仓库（~/.m2）
gradlew publishToMavenLocal
# 想直接验证产物、或做离线归档：gradlew publish → build/local-repo
```

```groovy
// 2. 消费方
repositories {
    mavenLocal()   // 或指向 publish 产出的 build/local-repo 目录
}

dependencies {
    implementation("com.workflow:workflow-core:3.18.0")             // 只用 InMemory 仓储，仅此一个依赖
    implementation("com.workflow:workflow-persistence-jpa:3.18.0")  // 需要 JPA 再加（或 mybatis）
}
```

发布 5 个库模块：`workflow-core`、`workflow-persistence-flyway`、`workflow-persistence-jpa`、
`workflow-persistence-mybatis`、`workflow-rest`；`workflow-sample`（Demo）与 `workflow-tests`（测试）不发布。
版本号唯一来源是 `gradle.properties` 的 `projectVersion` —— 改一处，全工程跟着走。
要发 Maven Central 或私有 Nexus，在根 `build.gradle.kts` 的 `localStaging` 旁边追加一个 maven 仓库即可。

---

### 5.6 接入自己的数据源与事务（嵌入式集成）

引擎作为 jar 进宿主进程时，**别让引擎用自己的连接池** —— 宿主（Spring 等）的事务与连接池
才是唯一的那一套。接上分两步：

```java
// 1. 用宿主的 DataSource 初始化引擎(不调 init(),不用引擎自建池)
MybatisPersistence mb = MybatisPersistence.getDefault();
mb.withDataSource(springDataSource);

// 2. 把「当前线程的宿主事务连接」告诉引擎 —— 适配器由宿主自己写,引擎零 Spring 依赖
mb.externalTransactionProvider(new ExternalTransactionProvider() {
    @Override
    public Connection currentConnection() {
        return TransactionSynchronizationManager.isActualTransactionActive()
                ? DataSourceUtils.getConnection(springDataSource)
                : null;
    }
});

// 3. 照常装配引擎
WorkflowEngine engine = WorkflowEngineBuilder
        .builder(mb.processRepo(), mb.instanceRepo(), mb.taskRepo())
        .auditLogRepository(mb.auditLogRepo())
        .build();
```

接上之后，宿主的 `@Transactional` 回滚会**连带回滚引擎写入**。不接的时候两边是两笔独立事务 ——
宿主业务失败而流程已推进，这类脏数据无法自愈。

**三个必须知道的点**：

| 点 | 说明 |
|----|------|
| 建表历史表独立 | `withDataSource(ds)` 用引擎专属的 `wf_schema_history`，不与宿主的 `flyway_schema_history` 抢表（同库时抢表的后果：引擎建表被静默跳过，或启动即报版本冲突）。宿主由 DBA 统一管 DDL 时用 `withDataSource(ds, false)` 跳过迁移 |
| 乐观锁插件仍归引擎 | 引擎**不共享**宿主的 `SqlSessionFactory`：宿主的插件链里没有 `OptimisticLockerInnerInterceptor`，共享会让 `@Version` 乐观锁静默失效。引擎复用的只是宿主的**连接** |
| `close()` 不关宿主的池 | 引擎只关自己建的池；宿主注入的 DataSource 归宿主所有 |

依赖上引擎**不传递** HikariCP 与 H2（声明为 `compileOnly`）：宿主用 Spring Boot 3.5 时它管的是
HikariCP 6.x，引擎若把 5.1.0 传出去，Maven 的 nearest-wins 会让宿主用上低版本。走
`withDataSource()` 时你本来也不需要引擎的池。

---

## 6. 核心 API

`com.workflow.engine.IWorkflowEngine` —— 以下是**最常用的 9 个入口**（完整接口另含批处理 §20、拓扑自省 §26、实例迁移 `migrateInstance` / `migrateInstances`、超时调度、监控 `dashboard` 等）：

| 方法 | 说明 |
|---|---|
| `String start(String processKey, Map<String,Object> variables)` | 发起新流程,返回 instanceId |
| `ProcessInstance getInstance(String instanceId)` | 查询实例（含 tokens + tasks + variables） |
| `TaskInstance getTask(String taskId)` | 查询单个任务 |
| `void completeTask(String taskId, String userId, boolean approved)` | 完成任务。`approved=false` 等同 `rejectTask`（带 reason=null） |
| `void rejectTask(String taskId, String userId, String reason)` | 驳回到上一个 UserTask |
| `void transferTask(String taskId, String fromUserId, String toUserId)` | 转办（把任务候选人换成新用户,旧任务标记 TRANSFERRED）。要求 `fromUserId` 本身是候选人 |
| `void adminTransferTask(String taskId, String toUserId, String operator)` | 管理员强制改派：**不校验候选人**，供候选人离职/长期不在/组织架构故障时兜底；`operator` 进审计。引擎不判断权限，鉴权属调用方责任 |
| `void suspend(String instanceId)` | 暂停实例（状态置 SUSPENDED） |
| `void resume(String instanceId)` | 恢复实例（状态置 RUNNING） |
| `void terminate(String instanceId)` | 终止实例（状态置 TERMINATED,所有 PENDING 任务标 TERMINATED） |

---

## 7. 流程定义 DSL

### 7.1 节点类型 `NodeType`

| 值 | 说明 |
|---|---|
| `START` | 起始节点（每个流程必须有且只有一个） |
| `END` | 结束节点 |
| `USER_TASK` | 用户任务节点（需要审批） |
| `EXCLUSIVE_GATEWAY` | 排他网关（预留，当前实现取第一条出口） |
| `PARALLEL_GATEWAY` | 并行网关（fork 多出口 / join 汇聚） |

### 7.2 候选人策略 `CandidateStrategy`

| 值 | 说明 |
|---|---|
| `ANY` | 或签——任一候选人通过即任务完成（default） |
| `ALL` | 会签——所有候选人都通过才任务完成 |

### 7.3 DSL 用法

```java
ProcessBuilder.create(key, name)
    .start("start")                                    // 起始节点
    .userTask("node1", "标题", Candidate.ofAny("u1"))   // 用户任务 + ANY
    .userTask("node2", "会签节点", Candidate.ofAll("u1", "u2", "u3"))  // 用户任务 + ALL
    .exclusiveGateway("gw1")                           // 排他网关
    .parallelGateway("fork")                           // 并行 fork
    .parallelGateway("join")                           // 并行 join
    .subProcess("sub1", "子流程", "subProcessKey")      // 子流程嵌入（调用另一流程定义）
    .end("end")                                        // 结束节点
    .connect("start", "node1")                         // 边
    .connect("node1", "gw1")
    .connect("gw1", "fork")
    .connect("fork", "branchA")
    .connect("fork", "branchB")
    .connect("branchA", "join")
    .connect("branchB", "join")
    .connect("join", "end")
    .build();                                          // 一次性校验（DAG/唯一 START/出口存在性）
```

### 7.4 候选人构造器

```java
Candidate.ofAny("u1", "u2")           // 或签
Candidate.ofAll("u1", "u2", "u3")     // 会签
```

### 7.5 超时策略（v3.2）

UserTask 节点支持超时自动处理，避免流程卡在某个节点：

```java
ProcessBuilder.create("leave")
    .start("start")
    .userTask("review", "经理审批", Candidate.ofAny("manager"))
    .end("end")
    .connect("start", "review")
    .connect("review", "end")
    .timeout("review", 86400000, TimeoutPolicy.AUTO_APPROVE)  // 24小时未审批自动通过
    .build();
```

**超时策略枚举**：

| 策略 | 说明 |
|---|---|
| `AUTO_APPROVE` | 超时后自动通过，流程继续推进 |
| `AUTO_REJECT` | 超时后自动驳回，流程回退到上一个 UserTask |
| `AUTO_TERMINATE` | 超时后终止整个流程实例 |
| `AUTO_TRANSFER` | 超时后自动转办给指定用户 |

**自动转办示例**：

```java
.timeout("review", 3600000, TimeoutPolicy.AUTO_TRANSFER, "backup_manager")  // 1小时未审批转办给备用经理
```

**调度机制**：
- 任务创建时自动注册超时调度（基于 `ScheduledExecutorService`）
- 任务完成/驳回/转办时自动取消调度
- 会签场景：部分完成不取消调度，整体完成才取消
- 超时回调幂等：重查任务状态，非 PENDING 则忽略

**重启恢复（v3.15）**：

调度表活在 JVM 内存里，进程一重启就是空的 —— 重启前建立的待办会**静默地**永不超时，审批卡死且无人知晓（数据都在库里，只是再也没人来触发它）。

```java
WorkflowEngine engine = WorkflowEngineBuilder.builder(procRepo, instRepo, taskRepo)
        .autoRecoverTimeouts(true)   // 默认就是 true，这里显式写出来
        .build();                    // build() 时自动恢复一次
```

| 设计点 | 落地 |
|---|---|
| **到期时刻不落库，而是派生** | `dueAt = createTime + 节点超时配置`：一个在 `wf_task.create_time`，一个在定义的 `nodes_json` 里，重启后可精确重算。因此**不需要新增列**（省掉一次迁移 + 三套仓储改造），也避免造出第二真相来源 |
| **不引入定期扫描** | 启动扫描一次即可覆盖：重启前的调度要么重新注册、要么因已过期而立即触发；此后新任务在创建时正常注册 |
| **补偿停机期间到点的待办** | 已过期的注册为「已过期」，交给调度器立即触发（异步，不阻塞启动） |
| **挂起的实例不恢复** | 只认 `RUNNING` 实例；否则「挂起」会被恢复动作立刻超时，形同虚设 |
| **多节点重复触发由幂等兜底** | 回调内部重查任务状态，非 `PENDING` 即忽略 —— 启动扫描与既有调度重叠、多节点同时恢复都安全 |

> ⚠️ **SPI 语义变更**：`TimeoutScheduler.schedule(...)` 第三个参数从**相对延时**改为**绝对到期时刻**（epoch millis）。签名不变所以自定义实现仍能编译，但会把绝对时刻当延时用（结果近似「永不触发」），升级前务必检查所有实现。

### 7.6 流程变量强类型（v3.4）

为流程变量定义 schema（类型/必填/默认值），发起流程时自动校验，避免 `Map<String, Object>` 裸传导致的类型错误：

```java
ProcessBuilder.create("purchase", "采购审批")
    .start("start")
    .userTask("apply", "申请", Candidate.ofAny("u1"))
    .userTask("manager", "经理审批", Candidate.ofAny("m1"))
    .end("end")
    .connect("start", "apply")
    .connect("apply", "manager")
    .connect("manager", "end")
    // 变量 schema
    .variable("amount", VariableType.INTEGER)                       // 简单声明
    .variable(VariableDefinition.builder("reason", VariableType.STRING)
            .required(true)                                          // 必填
            .description("申请事由")
            .build())
    .variable(VariableDefinition.builder("priority", VariableType.STRING)
            .defaultValue("normal")                                  // 默认值
            .build())
    .build();
```

**变量类型枚举 `VariableType`**：

| 类型 | 说明 | 接受的实际 Java 类型 |
|---|---|---|
| `STRING` | 字符串 | `String` |
| `INTEGER` | 整数 | `Integer` / `Long` |
| `LONG` | 长整数 | `Long` / `Integer` |
| `DOUBLE` | 浮点数 | `Double` / `Float` / `Integer` / `Long` |
| `BOOLEAN` | 布尔 | `Boolean` |
| `OBJECT` | 任意对象 | 任意类型（JSON 序列化） |

**校验规则**（`engine.start` 时自动执行，失败抛 `IllegalArgumentException`）：
1. **必填**：`required=true` 且未提供且无默认值 → 报错
2. **默认值**：未提供但有默认值 → 自动填入默认值
3. **类型**：提供的值与 schema 类型不匹配 → 报错（含期望/实际类型）
4. 未定义变量 schema 的流程定义 → 跳过校验，行为与旧版一致（向后兼容）

**三套持久化**均支持：schema 以 `variable_definitions_json` 列存入 `wf_process_def`，跨 JPA / MyBatis / InMemory 一致生效。

---

## 8. 领域模型

```
definition/                         runtime/
├── ProcessDefinition ────────────► ├── ProcessInstance
│   ├── key, name, version            ├── id, processKey, status, createTime, endTime
│   ├── nodes: Map<String, Node>     ├── activeTokens: Map<String, Token>
│   └── outgoing: Map<String,        ├── tasks: List<TaskInstance>
│                List<Transition>>   └── variables: Map<String, Object>
│
├── NodeDefinition                   ├── Token
│   ├── id, type, name, candidate    │   ├── id, instanceId, currentNodeId, status
│   └── (Transition 边)              │
│                                    ├── TaskInstance
├── Transition                       │   ├── id, instanceId, tokenId, nodeId
│   ├── from, to                     │   ├── candidate: Candidate
│   └── (condition - 预留)           │   ├── completedApprovers: Set<String>
│                                    │   └── status: TaskStatus
└── Candidate
    ├── strategy: ANY | ALL
    └── userIds: List<String>
```

### 关键枚举

| 枚举 | 值 |
|---|---|
| `InstanceStatus` | `RUNNING` / `SUSPENDED` / `COMPLETED` / `TERMINATED` |
| `TaskStatus` | `PENDING` / `COMPLETED` / `REJECTED` / `TRANSFERRED` / `TERMINATED` |
| `TokenStatus` | `ACTIVE` / `CONSUMED` |

---

## 9. 引擎调度核心

### 9.1 核心循环：`advanceToken(instance, def, tokenId)`

```text
                          ┌─────────────────────┐
                          │ 取 Token 当前所在节点 │
                          └──────────┬──────────┘
                                     ▼
        ┌──────────┬─────────────┬──────────────┬────────────────┐
        ▼          ▼             ▼              ▼                ▼
      START     USER_TASK     EXCLUSIVE_     PARALLEL_         END
      (无)     (创建/推进)    GATEWAY        GATEWAY        (消耗 Token)
                            (取首出口)    (fork 多出/join 汇聚)
```

### 9.2 UserTask 节点的 3 个分支

```text
进入 USER_TASK 节点,先查 instance.tasks 里是否已有 (tokenId + nodeId) 的非终止任务:

  ┌──────────────────┐    ┌─────────────────────┐    ┌──────────────────┐
  │ existing == null │    │ existing.COMPLETED  │    │  existing.PENDING │
  │  → 创建新任务     │    │  → 推进 Token 到下个 │    │  → 忽略(异常路径)  │
  │    并 save       │    │    节点并递归调用     │    │                  │
  └──────────────────┘    └─────────────────────┘    └──────────────────┘
```

### 9.3 并行网关 fork/join

- **fork**（多出口）：克隆当前 Token 到每个出口节点
- **join**（多入口）：消耗到达的 Token，等所有兄弟 Token 都到达后创建后续 Token
- 通过 `GatewayKind.isJoin(def, nodeId)` 判定：节点入度 > 1 即 join

### 9.5 子流程节点 SUB_PROCESS（v3.1）

- Token 进入 SUB_PROCESS 节点时**停留等待**：引擎发起一个子流程实例（`parentInstanceId` / `parentTokenId` / `parentNodeId` 记录归属），并把子流程实例 ID 写入父实例变量 `__sub_<tokenId>`（`__sub_depth` 记录嵌套深度）
- 子流程实例是**独立实例**：有自己的 ProcessInstance / Token / Task，可独立查询、驳回、转办
- 子流程**变量继承**：子流程启动时快照父实例变量（含流程变量），可让子流程的条件网关读取父流程金额等
- 子流程完成（实例状态 COMPLETED）后回调 `onSubProcessCompleted` → 再次 `advanceToken` 父 Token → 此刻检查子流程已 COMPLETED → **推进父 Token 到出口**
- 支持任意层嵌套：`main → mid → leaf` 逐层完成回卷
- 子流程目标定义不存在时 `start` 抛 `IllegalArgumentException`

### 9.4 关键 bug 修复

- **修复点（v1 子任务 3）**：`advanceToken` 在 UserTask 节点的"防重"逻辑——已完成任务要把 Token 推进到下一节点,不能停留
- **修复点（v2 子任务 4）**：`completeAndAdvance` 在 `task.recordCompletion` 后必须**同步 instance 视图里那份 task 的 status + completedApprovers**——JPA 仓储下 task 与 instance.tasks 是不同对象引用,不更新会导致 advanceToken 拿旧 PENDING 状态误判
- **修复点（v3.1）**：SUB_PROCESS 分支原先只区分"已发起/未发起",子流程完成后再次进入该分支会被误判为"等待中"而空转,父 Token 永远停在子流程节点——改为检查子流程实例状态,COMPLETED 则推进父 Token 到出口

---

## 10. 仓储抽象与三实现

### 10.1 三接口

```java
public interface ProcessRepository {
    void save(ProcessDefinition def);
    ProcessDefinition findByKey(String key);
    ProcessDefinition findByKeyAndVersion(String key, int version);
    List<Integer> getVersions(String key);
    boolean exists(String key);
}

public interface InstanceRepository {
    void save(ProcessInstance instance);
    ProcessInstance findById(String instanceId);
    void delete(String instanceId);
}

public interface TaskRepository {
    void save(TaskInstance task);
    TaskInstance findById(String taskId);
    List<TaskInstance> findByInstanceId(String instanceId);
    List<TaskInstance> findPendingByUser(String userId);
}
```

### 10.2 InMemory 实现（v1）

- `ConcurrentHashMap` 线程安全
- 适合单元测试 / Demo

### 10.3 JPA 实现（v2）

- 4 张表：`wf_process_def` / `wf_instance` / `wf_token` / `wf_task`
- Entity ↔ Domain 通过反射（Domain 不可变 + 无 setter）
- 仓储内部 `JpaPersistence.inTransaction(...)` 包事务,引擎无感知
- 建表统一由 Flyway 迁移脚本负责（`hbm2ddl.auto=none`）

### 10.4 MyBatis-Plus 实现（v3）

- **同一套 4 张表**：`wf_process_def` / `wf_instance` / `wf_token` / `wf_task`
- 无 Spring 轻量用法：`MybatisConfiguration` + `MybatisSqlSessionFactoryBuilder` 纯代码装配
- 单主键表（instance/token/task）走 `BaseMapper` 通用 CRUD；复合主键表 `wf_process_def` 走手写注解 SQL
- 仓储内部 `MybatisPersistence.inSession(...)` 包事务,引擎无感知
- 建表统一由 Flyway 迁移脚本负责（去掉 v3.0 内嵌 DDL）

---

## 11. 审计日志

### 11.1 功能概述

引擎内置审计日志功能，自动记录所有关键状态变更事件，用于流程追踪、合规审计和问题排查。

**记录的事件类型**：
- `PROCESS_STARTED` - 流程发起
- `TASK_COMPLETED` - 任务完成
- `TASK_REJECTED` - 任务驳回
- `TASK_TRANSFERRED` - 任务转办
- `PROCESS_TERMINATED` - 流程终止
- `PROCESS_SUSPENDED` - 流程挂起
- `PROCESS_RESUMED` - 流程恢复
- `TIMEOUT_AUTO_APPROVED` - 超时自动通过
- `TIMEOUT_AUTO_REJECTED` - 超时自动驳回
- `TIMEOUT_AUTO_TERMINATED` - 超时自动终止
- `TIMEOUT_AUTO_TRANSFERRED` - 超时自动转办

### 11.2 仓储接口

```java
public interface AuditLogRepository {
    void save(AuditLog log);
    List<AuditLog> findByInstanceId(String instanceId);
    List<AuditLog> findByTaskId(String taskId);
    List<AuditLog> findByTimeRange(Instant from, Instant to);
    void clear();
}
```

### 11.3 使用示例

```java
// 1. 创建审计日志仓储
AuditLogRepository auditLogRepo = new InMemoryAuditLogRepository();
// 或 JPA 版：AuditLogRepository auditLogRepo = jpaPersistence.auditLogRepo();
// 或 MyBatis 版：AuditLogRepository auditLogRepo = mybatisPersistence.auditLogRepo();

// 2. 装配引擎时传入审计仓储
WorkflowEngine engine = new WorkflowEngine(
    processRepo, instanceRepo, taskRepo, 
    timeoutScheduler,  // 可为 null
    auditLogRepo       // 可为 null（不启用审计）
);

// 3. 引擎自动记录所有事件（无需手动调用）
String instanceId = engine.start("leave-flow", variables);
engine.completeTask(taskId, "user1", true);
engine.rejectTask(taskId, "user2", "不符合要求");

// 4. 查询审计日志
List<AuditLog> logs = auditLogRepo.findByInstanceId(instanceId);
for (AuditLog log : logs) {
    System.out.printf("[%s] %s - %s (操作人: %s)%n",
        log.getTimestamp(),
        log.getEventType(),
        log.getDetail(),
        log.getOperator()
    );
}

// 按任务 ID 查询
List<AuditLog> taskLogs = auditLogRepo.findByTaskId(taskId);

// 按时间范围查询
List<AuditLog> rangeLogs = auditLogRepo.findByTimeRange(
    Instant.now().minus(7, ChronoUnit.DAYS),
    Instant.now()
);
```

### 11.4 数据库表结构

```sql
CREATE TABLE wf_audit_log (
    id           VARCHAR(64) PRIMARY KEY,
    instance_id  VARCHAR(64) NOT NULL,
    task_id      VARCHAR(64),
    event_type   VARCHAR(32) NOT NULL,
    operator     VARCHAR(64) NOT NULL,
    timestamp    BIGINT      NOT NULL,
    detail       TEXT
);
```

### 11.5 设计要点

- **可选功能**：审计日志仓储可为 null，不传入则不记录审计日志，零性能开销
- **自动埋点**：引擎在所有关键操作点自动调用 `audit()` 方法，无需业务代码干预
- **三仓储实现**：InMemory / JPA / MyBatis-Plus 均提供审计日志仓储实现
- **查询灵活**：支持按实例 ID、任务 ID、时间范围查询
- **线程安全**：InMemory 实现使用 `CopyOnWriteArrayList`，JPA/MyBatis 依赖数据库事务

---

## 12. 持久化细节（JPA / MyBatis-Plus）

### 12.0 Flyway 统一 DDL（v3.1）

- 新增 `workflow-persistence-flyway` 模块：共享迁移脚本 + `FlywayMigrator` 工具
- 迁移脚本 `db/migration/V1__init.sql` 用**跨数据库兼容 DDL**（`TEXT` / `VARCHAR` / `BIGINT` 三库通用），一份脚本在 H2 / MySQL / PostgreSQL 上行为一致
- JPA 的 `JpaPersistence.init()` 与 MyBatis 的 `MybatisPersistence.init()` 初始化时都会先执行 Flyway 迁移，再建会话工厂/EMF——两种 ORM、三数据库共用同一份 schema
- 版本表 `flyway_schema_history` 保证幂等：已迁移的库直接跳过，不会重复建表
- 切换数据库只需改 JDBC 连接参数（JPA 额外覆盖方言），无需改任何建表代码

#### 迁移脚本只有一个家（v3.15 归一）

> **新增迁移前，先 `dir workflow-persistence-flyway\src\main\resources\db\migration` 看一眼现有版本号。**

- 迁移脚本**全部**收在 `workflow-persistence-flyway/src/main/resources/db/migration`，其余模块的 `resources` 下不再存放 `.sql`。
- 曾踩过的坑：`V8__add_arrival_for_loop_support.sql` 一度错放在 `workflow-persistence-mybatis` 模块自己的 `resources` 下。
  Flyway 扫的是**合并后的 classpath**（`locations("classpath:db/migration")` 会命中所有 jar 的同名目录），
  两处各有一份 V8 时直接抛 `Found more than one migration with version 8`，
  导致 **41 个持久化用例集体 `initializationError`** —— 迁移号撞一次，半套测试直接起不来。
- v3.15 已把那份 V8 **原样**挪回 flyway 模块（`git` 识别为 rename），mybatis 模块的 `src/main/resources/db` 整个删除，
  `build/resources` 残留也一并清掉 —— 残留不删的话，`processResources` 的增量判断可能把旧文件重新打进 jar。
- **移动时一个字节都不要改**：Flyway 的 checksum 只认**文件内容**，顺手"改个注释"就会让存量库校验失败；
  而记在 `flyway_schema_history.script` 里的路径是 `db/migration/xxx.sql`（**不含模块名**），
  所以纯移动不影响已应用的库。
- 编号顺延先例：V8 被占用时，乐观锁脚本顺延为 **V9**（而非 V10）。
  当前最高号为 **V10**（`V10__comment.sql`，审批意见表 `wf_comment`）—— **下一个新迁移从 V11 起**。

### 12.1 表结构（两路线共用）

| 表 | 关键列 |
|---|---|
| `wf_process_def` | `key_` (H2 保留字)、`name`、`start_node_id`、`nodes_json`、`outgoing_json`、`variable_definitions_json`、`version` |
| `wf_instance` | `id`、`process_key`、`status`、`create_time`、`end_time`、`variables_json` |
| `wf_token` | `id`、`instance_id`、`current_node_id`、`status` |
| `wf_task` | `id`、`instance_id`、`token_id`、`node_id`、`candidate_json`、`completed_approvers_json`、`status`、`create_time` |
| `wf_audit_log` | `id`、`instance_id`、`task_id`、`event_type`、`operator`、`timestamp`、`detail` |
| `wf_comment` | `id`、`instance_id`、`task_id`、`node_id`、`user_id`、`type`、`message`、`create_time`、`seq`（v3.20） |

### 12.2 Domain ↔ Entity 映射

| Domain | Entity | 映射方式 |
|---|---|---|
| `ProcessDefinition` | `WfProcessDefEntity` | JSON 序列化 nodes/outgoing 到 TEXT 列 |
| `ProcessInstance` | `WfInstanceEntity` | 主体字段映射,variables JSON 化 |
| `Token` | `WfTokenEntity` | 直接字段映射 |
| `TaskInstance` | `WfTaskEntity` | candidate / completedApprovers JSON 化;`status` 用枚举字符串 |
| `AuditLog` | `WfAuditLogEntity` | 直接字段映射,`event_type` 用枚举字符串 |
| `Comment` | `WfCommentEntity` | 直接字段映射,`type` 用枚举字符串;`createTime + seq` 双键定序 |

### 12.3 反射重建不可变 Domain 对象

Domain 字段都是 `final`,无 setter——JPA / MyBatis 仓储都通过反射写 final 字段:

```java
java.lang.reflect.Field f = ProcessInstance.class.getDeclaredField("tasks");
f.setAccessible(true);  // JDK 17 + Hibernate 6.4 / MyBatis-Plus 3.5 + H2 验证可用
f.set(instance, taskList);
```

### 12.4 JPA 仓储事务策略

```java
// 仓储内部包事务,引擎无感知
jpa.inTransaction(em -> {
    WfProcessDefEntity e = em.find(WfProcessDefEntity.class, key);
    // ... 写逻辑
    return null;
});
```

`hibernate.hbm2ddl.auto=none`——建表完全交给 Flyway（`V1__init.sql`），Hibernate 只做实体映射。

### 12.5 MyBatis-Plus 仓储事务策略

```java
// 仓储内部包事务,引擎无感知(与 JPA inTransaction 对齐)
mb.inSession(session -> {
    WfProcessDefMapper mapper = session.getMapper(WfProcessDefMapper.class);
    // 逻辑 upsert:先 findByKeyVersion 判断存在,存在 update / 不存在 insert
    return null;
});
```

- **连接来源（v3.19.0）**：`init()` / `init(url, user, pass)` 用引擎自建的 HikariCP；
  `withDataSource(ds)` 用宿主的连接池（嵌入式集成，见 §5.6）
- **事务三分支（v3.19.0）**：`inSession` 依次判断 —— ① 宿主事务连接
  （由 `ExternalTransactionProvider` 提供，引擎**不提交、不回滚、不关闭**它）
  → ② 引擎自身事务（`TransactionContext` 活跃时复用同一 SqlSession）
  → ③ 独立短事务（自开自提交，v3.18 及以前的行为）
- **建表方式**：`MybatisPersistence.init()` 调用 Flyway 统一迁移（v3.1 起，替代 v3.0 的内嵌 DDL）
- **驱动自动识别**：按 JDBC URL 前缀选择驱动（`jdbc:mysql:` → MySQL / `jdbc:postgresql:` → PG / 其余 → H2）
- **复合主键处理**：`BaseMapper` 只支持单主键,`wf_process_def` 的 Mapper 全部手写注解 SQL
  （`@Insert` / `@Update` / `@Delete` / `@Select`）
- **跨库 upsert**：v3.0 用 H2 `MERGE INTO ... KEY()` 方言,MySQL 不支持——v3.1 改为
  **逻辑 upsert**（先 SELECT 判断再 INSERT / UPDATE），三库标准 SQL 通用
- **Token 全量同步**：`save(instance)` 采用 delete-all + insert 策略,与 JPA 版一致

---

## 13. 测试覆盖

### 13.1 核心套件 168 用例 + v3.8 增量套件（全量 0 失败）

> 下表是流程引擎**核心能力**的历史套件快照（168）。v3.8 起另增以下能力套件，随核心一起计入总用例：
> `EventGatewayTest`(事件网关) · `DecisionTableTest`(DMN) · `Jpa/Mybatis*AggregationTest`(监控聚合三套一致) ·
> `BatchStartTest`/`BatchApiTest`(批处理) · `MultiTenantTest`(多租户) · `NotificationServiceTest`(通知) ·
> `JumpToNodeTest`(退回任意节点) · `PerformanceBenchmarkTest`(性能基准,`-Dperf=true` 才跑)。
> 实测全量 `gradle build --rerun-tasks`：**469 PASSED / 0 FAILED / 35 SKIPPED**（总 504；skipped 均为需 Docker 的跨库套件）。

| 套件 | 测试类数 | 用例数 | 继承基类 |
|---|---|---|---|
| InMemory（H2） | 11 | 54 | `EngineTestBase` |
| JPA（H2） | 10 | 41 | `JpaEngineTestBase` |
| MyBatis-Plus（H2） | 10 | 41 | `MybatisEngineTestBase` |
| JPA × MySQL（Testcontainers） | 1 | 8 | `AbstractCrossDbTest` |
| JPA × PostgreSQL（Testcontainers） | 1 | 8 | `AbstractCrossDbTest` |
| MyBatis × MySQL（Testcontainers） | 1 | 8 | `AbstractCrossDbTest` |
| MyBatis × PostgreSQL（Testcontainers） | 1 | 8 | `AbstractCrossDbTest` |
| **合计** | **35** | **168** | — |

三套 H2 套件**使用完全相同的核心用例**（仅包名 / 仓储装配不同），验证仓储可替换性；
跨库套件用**同一份核心用例**（`AbstractCrossDbTest`）在真实 MySQL / PostgreSQL 上跑通，
验证引擎与 ORM 层与数据库无关。

### 13.2 覆盖能力

| 测试类 | InMemory | JPA | MyBatis | 覆盖能力 |
|---|---|---|---|---|
| `SerialFlowTest` | 1 | 1 | 1 | start→approve→end 串行 |
| `ParallelGatewayTest` | 1 | 1 | 1 | fork 多分支 + join 汇聚 |
| `SignStrategyTest` | 2 | 2 | 2 | 或签(ANY)/会签(ALL) |
| `RejectTransferTerminateTest` | 5 | 5 | 5 | 驳回/转办/终止/暂停-恢复/非候选人校验 |
| `ConditionGatewayTest` | 5 | 5 | 5 | 数字/字符串条件路由、默认分支、无匹配吞 Token、非法表达式报错 |
| `ProcessVersionTest` | 5 | 5 | 5 | 最新版查询、指定版本发起、版本不存在报错、同版本覆盖、多版本实例独立 |
| `SubProcessTest` | 5 | 5 | 5 | 子流程嵌入、变量继承+条件路由、子流程内驳回、多层嵌套、目标定义缺失报错 |
| `TimeoutSchedulerTest` | 6 | 4 | 4 | 自动通过/自动驳回/自动终止/自动转办/手动完成取消调度/会签部分完成保持调度 |
| `TimeoutTest` | 7 | — | — | （InMemory 专属）超时转办继承/驳回取消/自动动作推进流程等集成场景 |
| `AuditLogTest` | 9 | 9 | 9 | 流程发起/任务完成/任务驳回/任务转办/流程终止/挂起恢复/超时自动通过/按任务查询/按时间范围查询 |
| `VariableTypeTest` | 8 | 4 | 4 | 变量 schema 校验通过/必填缺失报错/类型不匹配报错/默认值填充/全类型支持/整型-长整型兼容/数值-DOUBLE 兼容/无 schema 跳过 |
| **小计** | **54** | **41** | **41** | |

跨库套件（`AbstractCrossDbTest`，8 个用例）覆盖同一功能域的精简集合：
串行 / 并行 / 会签 / 驳回转办终止挂起 / 非候选拦截 / 条件路由 / 版本隔离 / 子流程嵌入。

**v3.15 增量套件**（进生产底盘加固）：

| 套件 | 用例 | 覆盖 |
|---|---|---|
| `TimeoutRecoveryTest` / `JpaTimeoutRecoveryTest` / `MybatisTimeoutRecoveryTest` | 7 × 3 套仓储 | 到期时刻重算与建任务时**完全一致**、停机期间过期任务的补偿、重启后真实触发 AUTO_APPROVE、不该恢复的四种场景（已完成 / 无超时配置 / 实例挂起 / 关闭开关） |
| `RestAuthenticationTest` | 16 | 鉴权语义：401/403 区分、头名大小写、Bearer 前缀、多密钥、fail-closed、空白名单构造失败、写操作无副作用 |
| `RestAuthHttpTest` | 5 | 真 HTTP 往返：无凭证/错凭证 → 401、带凭证放行、被拒请求不产生副作用 |

**v3.16 增量套件**（Flowable BPMN 导入兼容性）：

| 套件 | 用例 | 覆盖 |
|---|---|---|
| `FlowableImportCompatibilityTest` | 14 | 静态/动态 `flowable:assignee`、`candidateUsers`、`candidateGroups`（组名保留 + 诊断）、assignee 与候选池并存时的优先级、`flowable:collection` → `MULTI_INSTANCE` 映射、或签/会签判定、导入导出往返对称、顺序多实例诊断、未知属性/元素诊断、被忽略节点导致的校验失败报错、无审批人来源时的错误可操作性 |

**v3.20 增量套件**（审批意见）：

| 套件 | 用例 | 覆盖 |
|---|---|---|
| `CommentTest`（InMemory） | 11 | 意见落库与节点推导、类型缺省、流程级意见、同毫秒按写入顺序、完成带意见同事务、驳回理由进一等存储、空意见不写、**保留策略清空历史后意见一条不少**、清理是独立开关、未注入时快速失败、未注入时审批动作零回归 |
| `JpaCommentTest` / `MybatisCommentTest` | 8 × 2 | 同一套语义在真库上逐条对应：字段往返完整、按人查最近在前、显式清理只删早于 cutoff 的 |
| `RestCommentApiTest` | 8 | 201/400/501 语义、类型大小写不敏感、任务级 POST 由服务端推导实例与节点、任务列表不混入流程级意见、**未启用部署给 501 而非 409** |

### 13.3 测试运行

```bash
cd D:\project\workflow-engine
set PATH=%CD%\gradle-8.5\bin;%PATH%

# 全部测试（跨库需本机 Docker）
gradle :workflow-tests:test --no-daemon

# 仅 InMemory
gradle :workflow-tests:test --no-daemon --tests "com.workflow.tests.engine.*"

# 仅 JPA
gradle :workflow-tests:test --no-daemon --tests "com.workflow.tests.jpa.*"

# 仅 MyBatis-Plus
gradle :workflow-tests:test --no-daemon --tests "com.workflow.tests.mybatis.*"

# 仅跨库(MySQL / PostgreSQL × JPA / MyBatis,需 Docker)
gradle :workflow-tests:test --no-daemon --tests "com.workflow.tests.crossdb.*"
```

---

## 14. 构建与运行

### 14.0 文档索引

| 文档 | 说明 |
|---|---|
| `README.md` | 本文档 |
| `CHANGELOG.md` | 版本变更日志（v0.1.0 → v3.1.0 全量归档） |
| `docs/architecture.md` | 6 张 Mermaid 架构图（模块依赖/DSL/领域模型/调度状态机/JPA 映射/时序） |

### 14.1 全工程构建

**方式 A：`gradlew.bat`（推荐，离线优先）**

```cmd
cd /d D:\project\workflow-engine
gradlew.bat build --no-daemon
```

> `gradlew.bat` 已内置离线优先逻辑：检测到本地 `gradle-8.5\bin\gradle.bat` 就直接使用，无需联网；在线环境删除该段后走标准 wrapper 下载。

**方式 B：本地 Gradle 发行版**

```cmd
cd /d D:\project\workflow-engine
set PATH=%CD%\gradle-8.5\bin;%PATH%
gradle build --no-daemon
```

预期输出:

```
BUILD SUCCESSFUL in 20s
27 actionable tasks: 15 executed, 12 up-to-date
```

### 14.2 跑 Demo

```cmd
gradlew.bat :workflow-sample:run --no-daemon
```

预期日志（节选）:

```
===== 自研工作流引擎 - 请假审批 Demo =====
流程定义: ProcessDefinition{key='leave', nodes=5}
节点数: 5

----- 步骤 1: 员工发起请假 -----
流程发起成功 instanceId=...

----- 步骤 2: 员工提交申请 -----
待办任务: ...
员工提交完成

----- 步骤 3: 经理 A 审批 -----
经理 A 审批完成(会签节点一人通过即过)

----- 步骤 4: HR 审批 -----
HR 审批完成

===== Demo 完成 =====
最终状态: COMPLETED
✅ 流程正常结束
```

---

## 15. 关键工程决策与坑

### 15.1 已落地的决策

| 决策 | 原因 |
|---|---|
| `ProcessDefinition` 构造器改 `public` | 让 `ProcessBuilder` 跨包能访问 |
| `sourceCompatibility` 放在 `JavaPluginExtension` 内 | 不再使用废弃的 `JavaPluginConvention` |
| Gradle Wrapper 自举 | 环境无 gradle 命令,下载 zip → 解压 → 复制 wrapper jar/properties → 写 `gradlew.bat` |
| `subprojects { dependencies { add(...) } }` | 根 build.gradle.kts 统一注入依赖 |
| 国产库选型 | fastjson2(JSON 序列化) + MyBatis-Plus(持久化) + SLF4J/Logback |
| JPA 仓储事务策略 | 仓储内部 `JpaPersistence.inTransaction` 包事务,引擎 API 与 InMemory 完全一致 |
| 反射重建 Domain 对象 | Domain 不可变 + 无 setter,JPA 仓储用反射写 final 字段 |
| `runInOrOpenTx` 双模式 | JPA 仓储支持外部 ThreadLocal EM 与独立事务两种模式 |
| Flyway 统一 DDL | 新建 `workflow-persistence-flyway` 共享迁移模块,两种 ORM 初始化时同一份脚本建表,消除 JPA hbm2ddl 与 MyBatis 内嵌 DDL 两套建表 |
| MyBatis 逻辑 upsert | 复合主键 `wf_process_def` 不用数据库方言 MERGE,改「先 SELECT 再 INSERT/UPDATE」标准 SQL,跨库通用 |
| Testcontainers 跨库验证 | 真实 MySQL 8.4 / PostgreSQL 16 容器,同一份核心用例跑 4 种「ORM × 数据库」组合 |

### 15.2 踩过的坑

#### 坑 1：JPA 下 instance 视图与 task 引用不一致

**现象**：InMemory 9/9 通过,JPA 6/9 失败（serial_flow / parallel_fork_join / any_sign / all_sign / transfer_should_redirect / reject_should_jump_back）。

**根因**：InMemory 下 `taskRepo.findById` 返回的 task 与 `instance.tasks[0]` 是同一对象引用——`task.recordCompletion` 改了 status,instance 视图自动跟随。JPA 下每次 `findById` 都重建新对象——`completeAndAdvance` 里 line 100 `task.recordCompletion` 改了 line 91 task 的 status,但 line 92 `instance.tasks[0]` 仍是**旧 PENDING 对象**,advanceToken 拿到旧状态误判走"PENDING 跳过"分支。

**修法**：在 `completeAndAdvance` 的 recordCompletion 后,显式同步 instance 视图里同一 taskId 的 TaskInstance 的 status + completedApprovers。

```java
TaskInstance taskInInstance = instance.getTasks().stream()
        .filter(t -> t.getId().equals(taskId))
        .findFirst().orElse(null);

boolean taskCompleted = task.recordCompletion(userId);
taskRepo.save(task);
if (taskInInstance != null) {
    taskInInstance.setStatus(task.getStatus());
    // ... 反射同步 completedApprovers
}
```

#### 坑 2：Gradle 9.0 兼容性

`subprojects { sourceCompatibility = JavaVersion.VERSION_17 }` 这种写法在新版 Gradle 被标记为 deprecated,但当前 8.5 还能用。生产环境升级 Gradle 时需迁移到 `JavaPluginExtension`。

#### 坑 3：H2 关键字 `key`

H2 中 `key` 是保留字,`WfProcessDefEntity.key` 字段必须映射到 `key_` 列。

#### 坑 4：H2 `MERGE INTO ... KEY()` 是方言 SQL,MySQL 不支持

**现象**：MyBatis 版切 MySQL 后全部用例失败,报 `You have an error in your SQL syntax ... near 'MERGE INTO wf_process_def'`。

**根因**：v3.0 用 H2 原生 upsert `MERGE INTO ... KEY(key_, version)` 实现同 key+version 覆盖——H2 专属语法,MySQL 没有 MERGE 语句。

**修法**：改为**逻辑 upsert**——`save()` 先 `findByKeyVersion` 判断记录是否存在,存在走 `UPDATE`、不存在走 `INSERT`。全部标准 SQL,三库通用。（PostgreSQL 的 `ON CONFLICT` 与 MySQL 的 `ON DUPLICATE KEY` 也各不相同,逻辑 upsert 从根上绕开方言。）

#### 坑 5：Flyway 12 把数据库支持拆成独立模块

**现象**：切 PostgreSQL 报 `No Flyway database plugin found to handle jdbc:postgresql:...`。

**根因**：Flyway 10+ 起数据库支持模块化拆分——`flyway-core` 只内置 H2（及部分库）支持,MySQL 需 `flyway-mysql`、PostgreSQL 需 `flyway-database-postgresql`。

**修法**：`workflow-persistence-flyway` 模块同时声明三个依赖（core + mysql + database-postgresql）,H2 / MySQL / PostgreSQL 三库迁移全可用。

#### 坑 6：Testcontainers 2.x 拆掉 mysql/postgresql 模块

**现象**：`testcontainers-bom:2.0.5` 解析不到 `org.testcontainers:mysql`。

**根因**：Testcontainers 2.0 重构后不再发布 mysql / postgresql 专用容器模块（Maven Central 上这两模块最新版停留在 1.21.4）。

**修法**：降级使用 `testcontainers-bom:1.21.4`（1.x 最新,模块生态完整,`MySQLContainer` / `PostgreSQLContainer` 开箱即用）。

---

## 16. 后续可扩展方向

按优先级排序:

1. ~~子流程嵌入~~ ✅ **v3.1 已完成**——`SUB_PROCESS` 节点 + 变量继承 + 多层嵌套 + 子流程内驳回/转办
2. ~~生产级数据库迁移~~ ✅ **v3.1 已完成**——Flyway 统一 DDL,H2 / MySQL / PostgreSQL 三库同一份脚本
3. ~~超时与定时器~~ ✅ **v3.2 已完成**——UserTask 超时策略(AUTO_APPROVE/AUTO_REJECT/AUTO_TERMINATE/AUTO_TRANSFER)+ 自研调度器
4. ~~审计日志~~ ✅ **v3.3 已完成**——自动记录 11 种事件类型，三仓储实现，支持按实例/任务/时间范围查询
5. ~~流程变量强类型~~ ✅ **v3.4 已完成**——`VariableDefinition` schema（类型/必填/默认值），启动时自动校验，三仓储持久化
6. ~~多租户隔离~~ ✅ **v3.8 已完成**——所有运行态表加 `tenant_id`,`TenantContext` + 仓储聚合过滤,见 §19
7. ~~REST API 化~~ ✅ **已完成**——零依赖 `workflow-rest`(JDK HttpServer + fastjson2),含监控端点
8. ~~监控仪表盘~~ ✅ **v3.8 已完成**——聚合服务 + 三套一致 + 全量下沉 SQL + 示例看板,见 §18
9. ~~企业级补充~~ ✅ **v3.8 已完成**——退回到任意节点 / 批处理 API / 批量启动 / 通知实现,见 §20-§21
10. **可视化设计器**——若需要,可用 bpmn.js + 后端导出 ProcessBuilder JSON（引擎本体刻意不含,保持"纯基础库"定位）
11. ~~流程版本迁移~~ ✅ **v3.13 / v3.15 已完成**——`migrateInstance`（单实例：节点映射 + 类型兼容校验，
    见 `docs/INSTANCE_MIGRATION_DESIGN.md`）；v3.15 补 `migrateInstances` 批量版，**逐实例独立事务**、部分成功保留，见 §20.1

> 除第 10 条（可视化设计器，**刻意不做**以守住"纯基础库"定位）外，上表已全部落地。

---

## 17. 并发与事务模型

v3.7 加入。这一章讲的是**正确性保证**，不是功能清单 —— 决定引擎能不能给别人用的正是这部分。

### 17.1 三层防线

一次 `completeTask` 会跨 `instance` / `task` / `token` / `audit_log` 多张表写入。三层各解决一件事：

| 层 | 解决什么 | 不做会怎样（实测数据，30 轮竞态） |
|----|----------|-----------------------------------|
| **流程树锁** | 同一 JVM 内同树操作串行化 | 会签任务仅 **24/30** 轮完成、下游恰 1 待办仅 **15/30** 轮 |
| **事务边界** | 一次动作内多表写入整体生效或整体撤销 | 审计库故障后任务**永久停在 COMPLETED**、Token 未推进，实例不可自愈 |
| **乐观锁重试** | 跨 JVM 的 CAS 失败重试 | 当前未启用，见 17.5 |

### 17.2 为什么锁粒度是"流程树根"而不是"单个实例"

引擎有两条方向相反的嵌套路径：

```
startSubProcess        父实例 ──推进──▶ 子实例
onSubProcessCompleted  子实例 ──回写──▶ 父实例
```

若按 `instanceId` 各自加锁，线程 A 走第一条、线程 B 走第二条即构成 **ABBA 死锁**。

因此 `ProcessInstance` 携带 `rootInstanceId`，子实例创建时继承父树的根，**父子共用一把锁**：同一棵流程树串行，不同树并行。

```java
ProcessInstance child = new ProcessInstance(subKey, ver, parent.getId(), ...);
child.assignRootInstanceId(parent.getRootInstanceId());   // 关键
```

> ⚠️ **`root_instance_id` 必须落库**（`wf_instance` 表，见 Flyway `V2`）。
> 它是锁的单位，不是展示字段：一旦某套仓储读回时丢掉它，`getRootInstanceId()` 会
> 退化成"自身即根"，父子于是各持一把锁 —— ABBA 防护**静默失效**，而且只在真实数据库、
> 只在含子流程的流程上发生，内存测试看不见。由 `InstanceRootPersistenceTest` 三套仓储
> 逐个看守（该用例已做变异校验：抽掉 JPA 读回即红）。
>
> 通用规矩：**凡参与并发控制或路由决策的字段，加在 domain 上就必须同时落到三套仓储**，
> 并为它写一条跨仓储往返测试。

锁本身：`ReentrantLock`（可重入，支持 `advanceToken` 递归）、`tryLock(30s)`（防监听器卡死把整棵树永久钉住）、引用计数回收（防锁对象按流程实例数无界堆积）。

### 17.3 顺序：必须先拿锁，再开事务

```java
locks.executeLocked(root, () -> tx.execute(body));   // ✅
tx.execute(() -> locks.executeLocked(root, body));   // ❌ 留下"已提交但锁已释放"窗口
```

反过来会让并发者读到未完成的中间态。

### 17.4 调度器动作：提交后才生效

超时调度器活在 JVM 内存里，**不受数据库事务保护**。若在事务内直接 `scheduler.cancel(taskId)`，一旦回滚，库里任务恢复 `PENDING` 而调度已被取消 —— 产生"永不过期的待办"。

```java
// 登记到提交后播放，回滚即丢弃
afterCommitSchedule(() -> scheduler.cancel(taskId));
```

同理适用于外部通知等副作用。

### 17.5 InMemory 仓储是拷贝语义（重要）

`save` 存入副本，`findById` 也返回副本 —— 仓库持有的对象绝不外泄。这是事务 before-image 可信的前提。

```java
TaskInstance t = engine.getTask(taskId);
t.setStatus(TaskStatus.COMPLETED);      // ❌ 静默无效，改的是副本
engine.completeTask(taskId, "u1", true); // ✅ 必须走引擎
```

这也是 `TaskQuery` 返回值的语义：**查询时刻的快照**，任务后续变更不会反映在已取出的对象上，要再查。

> 旧实现共享对象引用，"改引用即改库"，掩盖了三处 bug（含 `transferTask` 漏 `instanceRepo.save`）并导致 JPA 与内存版行为不一致 —— 这正是当初那批反射双写代码出现的根因。

### 17.6 跨 JVM / 集群部署

分段锁只在单 JVM 内有效。多实例部署**必须**叠加数据库乐观锁：

- 表加 `REV_` 列，`update ... where REV_ = ?`，影响 0 行即冲突
- 抛 `WorkflowConflictException`，引擎按 `conflictRetries` 重读最新状态后重试
- 领域对象 `revision` 字段与仓储 CAS 开关已就位，DDL 与映射待集群阶段启用

实测：JPA/H2 双线程抢同一会签任务 **15/15 正确**，说明单 JVM 下分段锁已足够；乐观锁买的是跨实例安全，不是单进程正确性。

### 17.7 退回旧行为

```java
WorkflowEngine legacy = engine.withoutConcurrencyControl();  // 无锁 + 无事务
```

仅用于压测对比。`concurrency` 包另提供 `LocalInstanceLocks(long timeoutMillis)` 自定义等锁上限。

### 17.8 并发下的异常语义

迟到者会被前置校验拒绝，抛 `IllegalStateException: 任务非 PENDING 状态: X` —— 这是**预期行为**（告知调用方"这条待办已被他人处理"），不是引擎缺陷。上层应捕获并提示刷新，而非当作崩溃。

不可接受的是内部一致性异常（`ConcurrentModificationException`、`Token 不存在或已消耗`）—— `ConcurrencySafetyTest` 正是按这条界线设计断言的。

---

## 18. 监控仪表盘

`com.workflow.monitor` 提供**内生只读**的运行态聚合，UI 由业务系统据此自建。`DashboardMetrics`（record）字段：

- 实例总数 / 按状态分布 / 按流程 key 分布
- 待办总数 + 按 `(流程, 节点)` 分布
- 瓶颈节点 TopN（节点平均耗时降序）
- 超时自动处理事件计数（`TIMEOUT_*` 审计事件）

关键设计：

| 点 | 落地 |
|---|---|
| **不新增 SQL 聚合层** | 全部复用既有仓储：`countGroupByProcessAndStatus` / `countPending` / `averageClosedDuration` / `countGroupByEventTypePrefix` |
| **聚合下沉，不 findAll** | 早期版本 `findAll()` 会为每个实例重建 Token/Task/变量（JPA 下 N 次子查询 + 反射），大表即热点;已改为 `GROUP BY` / `COUNT` |
| **缺失即降级** | `historyRepo`/`auditLogRepo` 为 null 时对应指标返回空，不抛异常、不返回误导性的 0 |
| **三套一致** | 每个聚合方法都是抽象方法，InMemory / JPA / MyBatis 各实现 + `*AggregationTest` 跨仓储对齐（吸取"只有 InMemory 能用、真库缺席"的教训） |

入口：`IWorkflowEngine.dashboard(int topN)` / `dashboard(int topN, String tenantId)`；REST `GET /api/metrics/dashboard?topN=N`；示例看板 `docs/dashboard.html`（自包含，填 API 地址即用）。

> **JPA 枚举 LIKE 坑**：`countGroupByEventTypePrefix` 在 JPA 里 `eventType` 是枚举字段，不能 `LIKE 'TIMEOUT_%'`,改为先列匹配前缀的枚举值再 `WHERE ... IN :types`。MyBatis 侧存的是字符串，`LIKE` 直接可用。

---

## 19. 多租户隔离

- `TenantContext`（`ThreadLocal`）承载当前租户，`withTenant(id, supplier)` 临时切换并自动恢复。
- `ProcessDefinition` / `ProcessInstance` / `TaskInstance` 带 `tenantId`（null = 全局，兼容老数据）。
- 引擎 `start` / `batchStart` 设置实例租户（定义优先，其次上下文）；`TokenAdvancer` 创建任务时继承实例租户。
- 查询隔离：`countGroupByProcessAndStatus(tenantId)` / `countPending(tenantId)` / `dashboard(topN, tenantId)`,`tenantId` 传 null 即跨租户(全局视图)。
- DDL：Flyway `V7__multi_tenant.sql` 给 `wf_instance` / `wf_task` 加 `tenant_id`。

> ⚠️ **两个真实踩坑**：① `ProcessInstance.snapshot()` 与 `TaskInstance.copy()` 重建时曾漏传 `tenantId`,导致内存仓储丢租户（多租户测试全红）——凡新增字段，**快照/拷贝/重建三处都要同步带上**;② 实体加了 `tenant_id` 列进入 SELECT,但 `V7` 迁移没落地,Flyway 仍停 v6 → 全量 99 个 DB 测试报 `Column TENANT_ID not found`。写完迁移脚本必须用 `dir` 真实核验文件存在。

---

## 20. 批处理与批量启动

| 能力 | 说明 |
|---|---|
| `batchCompleteTasks(taskIds, userId, approved)` | 原子事务:全成功或全回滚,失败抛 `BatchPartialFailureException` 带 `BatchResult`（成功/失败计数 + 逐条失败详情） |
| `batchTerminateInstances(instanceIds, operator, reason)` | 同上,批量终止 |
| `batchStart(key[, version], List<vars>)` | 批量发起:先建全部实例 → 仓储 `saveBatch` 单事务插入 → 逐个推进 Token;所有实例继承当前租户 |
| `migrateInstances(instanceIds, key, version, mapping, operator)` | 批量版本迁移:**逐实例独立事务**,失败不回滚已成功的,返回 `BatchResult`(v3.15) |
| `saveBatch` | 仓储新增,JPA/MyBatis 单事务批量落库;InMemory 默认循环 |

> **terminate 语义收紧**：`terminate` 现在校验实例状态,仅 `RUNNING` 可终止(与挂起/恢复一致),避免对终态实例重复操作。

### 20.1 两种批量语义 —— 别把它们"统一"了

| API | 语义 | 失败时 |
|---|---|---|
| `batchCompleteTasks` / `batchTerminateInstances` | **全或无** | 抛 `BatchPartialFailureException`，整个事务回滚 |
| `migrateInstances` | **逐个提交、部分成功保留** | 只记进 `BatchResult.failures`，不中断整批 |

看着不一致，但这是**有意为之**：

- 批量终止/完成是一批**同质**操作，调用方期望"要么都成、要么都别动"，全或无最不容易留下半截状态；
- 批量迁移的诉求是"**尽量多迁成功**"。迁 100 个实例时第 37 个失败，把前 36 个一起回滚纯属倒退；
  运维真正要的是"哪几个没成、各自为什么"，然后拿 `failures` 里的 id 重试。

实现上 `migrateInstances` 只是循环调用既有的 `migrateInstance` —— 后者内部是 `exclusive → tx.execute`，
每次调用天然构成一个独立事务；**批方法自身刻意不开事务**，否则 N 个实例会被合并进同一个事务，独立事务就白设计了。

```java
BatchResult result = engine.migrateInstances(
        List.of("inst-1", "inst-2", "inst-3"), "leave-flow", 2, nodeMapping, "admin");

if (!result.isAllSuccess()) {
    // 失败不抛异常，只逐个点名 —— 拿 id 重试即可
    result.getFailures().forEach(f ->
            log.warn("实例 {} 迁移失败({}): {}", f.getId(), f.getExceptionType(), f.getErrorMessage()));
}
```

测试 `BatchMigrationTest` 从两侧夹住这条语义：失败实例**之前**的成果必须保住（防连坐回滚）、
失败实例**之后**的照常处理（防一处失败就中断整批）—— 顺序 `[成功, 失败, 成功]`，终结中间那个实例使其非 `RUNNING`。

---

## 21. 通知服务

接口 `NotificationService`（`notify` + `urge`/`timeoutReminder` default）。引擎内置两个**零依赖**实现：

- `LoggingNotificationService`：SLF4J 记录 + 有界环形缓冲（演示/测试可查最近通知）。默认兜底。
- `WebhookNotificationService`：JDK `HttpClient` POST JSON 到配置端点,业务侧用一个 HTTP 网关即可转发到邮件/短信/钉钉/企微——引擎自身不耦合任何渠道 SDK 与凭据。`url` 未配置静默跳过、发送失败吞异常（**通知失败绝不影响流程**,与 §17.4 "提交后才生效"同源）。

---

## 22. 性能基准

`workflow-tests` 里 `PerformanceBenchmarkTest`,用 `@EnabledIfSystemProperty(named="perf")` 门控——**常规构建默认跳过**（不拖慢 CI）,`gradle :workflow-tests:test -Dperf=true` 才跑,输出耗时报表。

实测（InMemory,机器相关,看趋势不看绝对值）：

```
逐个启动 2000  :   815 ms
批量启动 2000  :  2262 ms   ← 反而更慢
批量完成 1000  :   882 ms
dashboard 3000 :    31 ms   ← 聚合下沉有效的佐证
```

> **诚实的发现**：InMemory 下"批量启动"不比逐个快,反而慢——内存写本就极便宜,批量还额外常驻整批实例对象徒增 GC。批量的真正收益只在**真实数据库**(省网络往返与事务开销)。基准测试最大的价值,就是把这种"想当然"照出来。

---

## 📎 附录

### A. 完整文件清单

```
workflow-engine/
├── build.gradle.kts                                          # 根 build
├── settings.gradle.kts                                       # 7 模块声明
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.jar
├── gradle/wrapper/gradle-wrapper.properties
│
├── workflow-core/                                            # 核心引擎（各子包职责见 §4）
│   ├── enums/  definition/  builder/  runtime/
│   ├── engine/  repository/  concurrency/  tx/  listener/
│   └── history/  query/  bpmn/  dmn/  monitor/
│
├── workflow-persistence-flyway/                              # Flyway 统一 DDL
│   ├── FlywayMigrator.java
│   └── resources/db/migration/  V1__init … V7__multi_tenant
│
├── workflow-persistence-jpa/                                 # JPA 实现
│   ├── JpaPersistence.java
│   ├── entity/  ·  repository/
│   └── resources/META-INF/persistence.xml
│
├── workflow-persistence-mybatis/                             # MyBatis-Plus 实现
│   ├── MybatisPersistence.java
│   └── entity/  ·  mapper/  ·  repository/
│
├── workflow-rest/                                            # REST API（零依赖，JDK HttpServer）
│   ├── RestServer.java  ·  WorkflowRestApi.java
│   └── RestRequest.java  ·  RestResponse.java
│
├── workflow-sample/                                          # 请假审批 Demo
│   └── LeaveDemo.java
│
└── workflow-tests/                                           # 约 270 用例（分类见 §13）
    ├── EngineTestBase / JpaEngineTestBase / MybatisEngineTestBase
    ├── engine/  jpa/  mybatis/  crossdb/  dmn/  monitor/
    └── concurrency/  perf/  runtime/  definition/  support/
```

### B. 依赖坐标

```kotlin
// 根 build.gradle.kts 统一版本
val fastjson2Version = "2.0.49"
val slf4jVersion = "2.0.13"
val logbackVersion = "1.5.6"
val jakartaPersistenceVersion = "3.1.0"
val hibernateVersion = "6.4.4.Final"
val h2Version = "2.2.224"
val hikariVersion = "5.1.0"
val junitVersion = "5.10.2"
val assertjVersion = "3.25.3"

// 模块内声明
val mybatisPlusVersion = "3.5.17"                       // workflow-persistence-mybatis
val flywayVersion = "12.8.1"                            // workflow-persistence-flyway
val testcontainersVersion = "1.21.4"                    // workflow-tests(跨库)
val mysqlDriverVersion = "8.4.0"                        // workflow-tests(跨库)
val postgresDriverVersion = "42.7.4"                    // workflow-tests(跨库)
```

### C. 联系与维护

- 命名空间根：`com.workflow`
- 测试基类包：`com.workflow.tests`（InMemory）+ `com.workflow.tests.jpa`（JPA）+ `com.workflow.tests.mybatis`（MyBatis-Plus）+ `com.workflow.tests.crossdb`（跨库）
- 所有公开类均有 Javadoc

---

## 23. 循环回边支持

### 23.1 场景

Flowable 用「排他网关 + 回边」实现循环：`tpl → gw →(loopContinue==true)→ tpl`。典型用例：
- 驳回后重新提交（申请人修改后重新走审批）
- 审批流中的循环网关（条件不满足时回到前序节点补充材料）
- 模板填写 → 审核 → 不通过则回到模板填写

### 23.2 设计：Token 到达代次（arrival）

给每个 Token 一个单调递增的**到达计数 `arrival`**：Token 每"移动到一个节点"就 `arrival++`。每个 TaskInstance 记录**创建时所属 token 的 arrival**。引擎仅处理「arrival == 当前 token.arrival」的任务。

| 场景 | arrival 变化 | handleUserTask 行为 | 结果 |
|---|---|---|---|
| 首次到 tpl | token.arrival=N | 无 arrival==N 任务 | 建 T1(arrival=N) |
| T1 完成→推进 | setCurrentNodeId(gw)→arrival=N+1 | (在 gw 不查) | 前进 |
| gw 回边到 tpl | setCurrentNodeId(tpl)→arrival=N+2 | 无 arrival==N+2 任务 | 建 T2(arrival=N+2) ✅ 不再死循环 |
| reject | consume 旧 token，**新建 token**(arrival=0) | prev 无 arrival==0 任务 | 建 prev 任务（新 token 天然干净）|
| transfer | token **不移动**、arrival 不变；建的新任务记 arrival=当前 | 命中该 PENDING 新任务 | 停等，不重复建 ✅ |

### 23.3 关键实现

- **`Token.moveTo(newNodeId)`**：运行时移动的唯一入口，`setCurrentNodeId + arrival++`。
- **`Token.setCurrentNodeId` 私有化**：编译期强制所有移动走 `moveTo`，杜绝"漏设标记"导致的回归。
- **`currentTaskOf` 按 arrival 过滤**：只匹配「arrival == token.arrival 且非 TERMINATED/TRANSFERRED」的任务。
- **V8 迁移**：`wf_token`/`wf_task` 加 `arrival INT DEFAULT 0`，老数据向后兼容。

### 23.4 DSL 用法

```java
ProcessDefinition def = ProcessBuilder.create("loop-flow")
    .start("start")
    .userTask("tpl", "模板填写", Candidate.ofAny("u1"))
    .exclusiveGateway("gw")
    .end("end")
    .connect("start", "tpl")
    .connect("tpl", "gw")
    .connect("gw", "tpl", "${loopContinue == true}")    // 回边：继续循环
    .connect("gw", "end", "${loopContinue == false}")   // 退出
    .build();

String instanceId = engine.start("loop-flow", Map.of("loopContinue", true));
// 第一次 tpl 待办 → 完成 → 到 gw → 条件为真 → 回边到 tpl → 第二次 tpl 待办（新任务）
```

### 23.5 测试

- `LoopBackEdgeTest`：回边生效（条件为真时重建待办）、退出分支（条件为假时正常完成）。
- 全量回归 296/296 通过，零回归（reject/transfer/timeout/多实例/动态并行不受影响）。

### 23.6 设计文档

详见 `docs/LOOP_SUPPORT_DESIGN.md`。

---

## 24. 动态 assignee 支持

### 24.1 场景

Flowable 样本 `customer_order_flow.bpmn` 里大量 userTask 用 `flowable:assignee="${xxxApprover}"`，运行时从流程变量取办理人。典型用例：
- 请假审批：申请人 → 直属经理（`managerId` 变量）→ HR（`hrId` 变量）
- 采购审批：部门经理（`deptManagerId`）→ 财务总监（`cfoId`）
- 驳回重提：回到原节点，办理人可能变了（`originalAssignee` 变量）

### 24.2 设计：`assigneeVariable` 字段

给 `NodeDefinition` 加可选字段 `assigneeVariable`（运行时变量名），与静态 `Candidate` **二选一**：
- 若 `assigneeVariable != null`：运行时从 `instance.getVariable(varName)` 取办理人（String），动态生成 `Candidate.ofAny(assignee)`。
- 若 `assigneeVariable == null`：走原有静态 `Candidate` 逻辑（向后兼容）。

### 24.3 关键实现

- **`ProcessBuilder.userTask(id, name, assigneeVar)`**：DSL 语法，与 `.userTask(id, name, Candidate)` 互斥。
- **`TokenAdvancer.handleUserTask` 动态取办理人**：若 `hasAssigneeVariable()`，从变量取 String 值生成 Candidate；否则走原静态 Candidate。
- **互斥校验**：`build()` 时校验 `candidate` 和 `assigneeVariable` 不能同时有值，也不能都没有。
- **BPMN 兼容**：导出为 `flowable:assignee="${varName}"`，导入时自动识别。

### 24.4 DSL 用法

```java
ProcessDefinition def = ProcessBuilder.create("leave-flow")
    .start("start")
    .userTask("apply", "申请", "applyApprover")      // 动态 assignee
    .userTask("review", "审核", "reviewApprover")    // 动态 assignee
    .end("end")
    .connect("start", "apply")
    .connect("apply", "review")
    .connect("review", "end")
    .build();

// 启动时设变量
String instanceId = engine.start("leave-flow", Map.of(
    "applyApprover", "user1",
    "reviewApprover", "manager1"
));
```

### 24.5 测试

- `DynamicAssigneeTest`（InMemory）：6 个用例。
- `JpaDynamicAssigneeTest`（JPA）：3 个用例。
- `MybatisDynamicAssigneeTest`（MyBatis）：3 个用例。
- `BpmnDynamicAssigneeRoundTripTest`：2 个用例（导出/导入往返）。
- 全量回归 300/300 通过，零回归（静态 Candidate 测试不受影响）。

### 24.6 设计文档

详见 `docs/DYNAMIC_ASSIGNEE_DESIGN.md`。

---

## 25. serviceTask 自动节点

### 25.1 场景

Flowable 样本 `customer_order_flow.bpmn` 里有 `serviceTask` + `flowable:delegateExpression="${ccNotificationDelegate}"`（`cc_node`、`loop_cc_node` 自动抄送）。典型用例：
- 自动抄送：审批通过后自动发通知给相关人员
- 数据转换：节点间自动处理数据格式
- 外部系统回调：流程结束后自动调用外部系统接口

### 25.2 设计：`ServiceTaskDelegate` 函数式接口

- **`ServiceTaskDelegate`**：`void execute(DelegateExecution execution)`，轻量无依赖
- **`DelegateExecution`**：提供 `instanceId`、`currentNodeId`、`variables`（只读）、`processDefinition`
- **注册机制**：`WorkflowEngine.registerDelegate(key, delegate)`，启动时注册
- **执行语义**：Token 到达 serviceTask → 执行 delegate → 自动推进（不创建 TaskInstance）
- **异常处理**：delegate 抛异常 → 流程挂起（SUSPENDED），需人工干预

### 25.3 DSL 用法

```java
// 1. 注册 delegate
engine.registerDelegate("sendNotification", execution -> {
    String assignee = (String) execution.getVariable("assignee");
    System.out.println("发送通知给：" + assignee);
});

// 2. 流程定义中使用
ProcessDefinition def = ProcessBuilder.create("leave-flow")
    .start("start")
    .userTask("apply", "申请", Candidate.ofAny("user1"))
    .serviceTask("notify", "发送通知", "sendNotification")  // 自动节点
    .end("end")
    .connect("start", "apply")
    .connect("apply", "notify")
    .connect("notify", "end")
    .build();
```

### 25.4 测试

- `ServiceTaskTest`（InMemory）：5 个用例。
- `JpaServiceTaskTest`（JPA）：2 个用例。
- `MybatisServiceTaskTest`（MyBatis）：2 个用例。
- `BpmnServiceTaskRoundTripTest`：2 个用例（导出/导入往返）。
- 全量回归 319/319 通过，零回归（现有节点不受影响）。

### 25.5 设计文档

详见 `docs/SERVICE_TASK_DESIGN.md`。

---

## 26. 拓扑自省

### 26.1 场景

运行时获取流程定义拓扑与实例当前位置，用于：

- 流程图高亮（当前 Token 在哪个节点）
- 待办列表显示节点名称
- 审批历史展示流转路径
- 流程预览 / 模拟

### 26.2 API

```java
// 流程定义拓扑（version < 0 取最新版）
TopologyView getTopology(String processKey, int version);

// 运行中实例拓扑（含当前 Token 位置与历史路径）
InstanceTopologyView getInstanceTopology(String instanceId);
```

### 26.3 视图结构

- **`TopologyView`**：`processKey` / `version` / `name` / `nodes` / `transitions`
- **`NodeView`**：`id` / `name` / `type` / `userIds`（审批人列表）/ `assigneeVariable`（动态 assignee）/ `delegateKey`（serviceTask）
- **`TransitionView`**：`from` / `to` / `condition`（条件表达式，可为 null）
- **`InstanceTopologyView`**：基础拓扑 + `activeNodeIds`（当前 Token 位置）+ `completedNodeIds`（已完成节点）+ `status`

所有字段 final、集合不可变，可直接序列化为 JSON 供前端渲染。

### 26.4 用法

```java
// 流程图渲染数据
TopologyView topo = engine.getTopology("leave-flow", -1);
for (NodeView n : topo.getNodes()) {
    System.out.println(n.getId() + " / " + n.getName() + " / " + n.getType());
}

// 高亮当前节点
InstanceTopologyView view = engine.getInstanceTopology(instanceId);
List<String> active = view.getActiveNodeIds();      // 当前 Token 所在节点
List<String> done = view.getCompletedNodeIds();     // 已完成节点
```

### 26.5 与 Flowable 对齐

Flowable 用 `repositoryService.getBpmnModel()` 返回完整 `BpmnModel` 对象图；本引擎直接从 `ProcessDefinition` 构建轻量只读视图，不依赖 BPMN XML 解析，语义等价、更轻。

### 26.6 测试

- `TopologyViewTest`（InMemory）：6 个用例。
- `JpaTopologyViewTest`（JPA）：2 个用例。
- `MybatisTopologyViewTest`（MyBatis）：2 个用例。
- 全量回归 339/339 通过，零回归。

### 26.7 设计文档

详见 `docs/TOPOLOGY_VIEW_DESIGN.md`。

---

## 27. Flowable BPMN 导入兼容性

### 27.1 支持的 Flowable 审批人写法

`BpmnImporter` 按以下优先级解析 `userTask` 的办理人：

| 写法 | 映射结果 |
|---|---|
| `flowable:assignee="${var}"` | 动态办理人（运行时从变量取） |
| `flowable:assignee="zhangsan"` | 静态单人，ANY |
| `wf:candidate` 扩展 | 本项目原生格式，含 ANY/ALL 策略 |
| `flowable:candidateUsers="u1,u2"` | 候选池，ANY |
| `flowable:candidateGroups="g1"` | 候选组 → `Candidate.groupIds`，建任务时经 `GroupResolver` 展开，ANY |
| `wf:cardinality` 占位 | 兜底，生成 `u1..uN` 占位用户 |

**多实例**：`multiInstanceLoopCharacteristics` + `flowable:collection="${approvers}"` → `MULTI_INSTANCE` 节点；
`completionCondition` 为 `nrOfCompletedInstances >= 1` 判**或签**（ANY），其余（含缺省）判**会签**（ALL）。
`flowable:elementVariable` 无需映射 —— 引擎按人为单位建任务，天然具备该语义。

### 27.2 组织架构：引擎留钩子，不内置存储

`candidateGroups` 的组名**不会被当成用户** —— 早期版本曾把它们混进候选用户集合，结果是任务对谁都不可办。
导入后组名进 `Candidate.groupIds`，再由调用方注入的 `GroupResolver` 在**任务创建时**展开成具体候选用户：

```java
engine.setGroupResolver(groupId -> orgService.membersOf(groupId));
```

展开结果作为**快照**写入任务候选（此后组成员变动不影响在途任务 —— 实时解析会让事后追责链漂移），
原始组名同时保留，供审计与「我所在组」查询。引擎不内置组织架构存储 ——
这与 Flowable 把 group 交给 `ACT_ID_` 表 + `IdentityService` 是同一条边界。

展开失败（未配 resolver / 组内无人 / 解析器抛异常）会抛 `GroupResolutionException`，
**不会**留下"流程在跑但谁都办不了"的静默死锁；紧急情况下用 `adminTransferTask` 强行改派。
详见 CHANGELOG v3.17.0。

### 27.3 不静默降级

导入器只做「能映射的映射」，无法映射的属性与元素**不会无声消失**：

```java
BpmnImportDiagnostics diag = new BpmnImportDiagnostics();
ProcessDefinition def = BpmnImporter.importFrom(bpmnXml, diag);

if (diag.hasWarnings()) {
    // 例如：flowable:formKey 不受支持 / candidateGroups 未展开 / 顺序多实例语义未保留
    diag.getWarnings().forEach(System.err::println);
}
```

单参 `BpmnImporter.importFrom(xml)` 保持兼容，诊断只写日志（`WARN` 级）。

设计动机：导入器最危险的失败不是抛异常，而是**静默降级** ——
定义导进来了、流程也能跑，但某个节点少了会签、丢了候选池，
直到生产上有人绕过审批才发现。抛异常至少是响的。

### 27.4 往返对称

`BpmnExporter` 支持 `MULTI_INSTANCE` 导出（`flowable:collection` + 完成条件），与导入侧构成闭环。
判别依据：本引擎导出 `USER_TASK + candidate` 时只写 `wf:cardinality`、从不写 `flowable:collection`，
因此「真 Flowable 多实例」与「自有格式往返」不会互相误判。

### 27.5 不支持的

- `sendTask` / `receiveTask` / `scriptTask` / `manualTask` —— 引擎无对应节点类型，导入时记入诊断
- 若被忽略的节点参与了连线，`build()` 校验会失败，此时错误信息会点出被忽略的节点 id
- `flowable:assignee` 与 `candidateUsers/candidateGroups` 并存时按 Flowable 语义 **assignee 优先**，候选池忽略并告警

### 27.6 测试

`FlowableImportCompatibilityTest`（14 用例）+ `FlowableSampleImportTest`（真实样本 `customer_order_flow.bpmn` 零降级断言）。

---

## 28. 审批意见（v3.20）

### 28.1 场景

驳回理由、加签说明、财务附言 —— 这些「话」往往比审批结果更需要长期留存。
此前它们只能挂在 `wf_audit_log.detail`，而**审计日志会被历史保留策略清理**：
等要做归档导出时，最该留的那部分反而第一批消失。v3.20 把意见提升为一等数据，
语义对齐 Flowable 的 `ACT_HI_COMMENT`。

### 28.2 设计：意见不归历史保留策略管

这是本能力唯一一条不能被「统一化」重构动摇的约束：

```
HistoryRetention.purgeBefore()   →  清历史活动 / 历史任务
CommentRepository.deleteBefore() →  清意见（独立开关，必须显式调用）
```

把 `deleteBefore` 接进 `purgeBefore` 能让代码少一个入口，代价是归档需求**静默**失效
—— 而那个静默失效，正是本能力要修的东西。

**定序用 `createTime + seq` 双键**：同一毫秒写入的多条意见必须按写入顺序返回。
顺序交给随机 UUID 决定的话，并发审批下串起来的话就是错的。

**可选注入**：`commentRepo` 未注入时引擎行为与 v3.19.0 完全一致（零破坏）；
但显式查询 / 写意见时**快速失败**，不返回空列表 —— 空列表会被读成「这个流程真的没有意见」。

### 28.3 API

```java
engine.supportsComments();                 // 部署是否启用了意见能力

// 任务级 / 流程级（taskId 传 null 即流程级；nodeId 由引擎从任务推导）
engine.addComment(instanceId, taskId, "u1", "出差三天，请批准");
engine.addComment(instanceId, null, "u1", CommentType.COMMENT, "补充：往返高铁");

// 带意见完成审批：意见与审批动作同事务，要么都成、要么都不留
engine.completeTask(taskId, "u1", true, "同意");

engine.getTaskComments(taskId);
engine.getInstanceComments(instanceId);    // 流程级 + 各任务，时间升序
```

`rejectTask(taskId, userId, reason)` 会自动落一条 `REJECT` 意见，调用方无需额外操作。

### 28.4 意见类型

| 类别 | 取值 |
|---|---|
| 纯评论 | `COMMENT`（默认） |
| 随审批动作产生 | `APPROVE` / `REJECT` / `TRANSFER` / `DELEGATE` / `WITHDRAW` / `TIMEOUT` / `SYSTEM` |

区分这两类是为了让导出能分开处理：「某人的备注」和「系统因超时自动通过」不是一回事。

### 28.5 REST

| 方法 路径 | 作用 | 成功码 |
|---|---|---|
| `GET /api/instances/{id}/comments` | 实例全部意见 | 200 |
| `POST /api/instances/{id}/comments` | 加流程级意见 | 201 |
| `GET /api/tasks/{id}/comments` | 某任务的意见 | 200 |
| `POST /api/tasks/{id}/comments` | 给任务加意见 | 201 |

请求体 `{"userId": "...", "message": "...", "type": "APPROVE?"}`，`type` 缺省为 `COMMENT`。
任务级 `POST` 只要 `taskId`，实例与节点由服务端推导。

**未启用意见仓储的部署返回 501**，而不是靠引擎 `IllegalStateException` 兜底出的 409 ——
「这个部署没开这个功能」和「当前状态办不到、刷新重试」对客户端是两种处置方式。

### 28.6 持久化

- Flyway `V10__comment.sql`（纯增量新表 `wf_comment`，不动任何既有表）
- JPA：`WfCommentEntity` + `JpaCommentRepository`
- MyBatis：`WfCommentEntity` + `WfCommentMapper` + `MybatisCommentRepository`

三套语义一致，同一套用例逐条对应。

### 28.7 测试

`CommentTest`(11) / `JpaCommentTest`(8) / `MybatisCommentTest`(8) / `RestCommentApiTest`(8)。
关键用例「保留策略清空历史后，意见一条不少」：历史被清空，意见仍完整可读。

---

_本文档随项目演进持续更新。_
