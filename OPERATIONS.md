# 操作手册 (Operations Guide)

> 本手册面向**接入方 / 运维**，讲"怎么用"。想了解设计原理与内部机制请看 [README.md](README.md)，版本变更见 [CHANGELOG.md](CHANGELOG.md)。
>
> 文中所有类名、方法签名、命令、REST 路径、异常文案均对照当前源码（v3.11.0）核验。

---

## 目录
- [0. 前置条件](#0-前置条件)
- [1. 三分钟跑通 Demo](#1-三分钟跑通-demo)
- [2. 最小接入：五步走](#2-最小接入五步走)
- [3. 流程定义 DSL 速查](#3-流程定义-dsl-速查)
- [4. 引擎装配（WorkflowEngineBuilder）](#4-引擎装配workflowenginebuilder)
- [5. 日常操作手册](#5-日常操作手册)
- [6. 事件网关操作](#6-事件网关操作)
- [7. DMN 决策表操作](#7-dmn-决策表操作)
- [8. 查询：TaskQuery 与实例查询](#8-查询taskquery-与实例查询)
- [9. 多租户操作](#9-多租户操作)
- [10. 监控操作](#10-监控操作)
- [11. 接入数据库（JPA / MyBatis-Plus / Flyway）](#11-接入数据库jpa--mybatis-plus--flyway)
- [12. REST API 手册](#12-rest-api-手册)
- [13. 构建与测试命令](#13-构建与测试命令)
- [14. 常见报错与排查](#14-常见报错与排查)
- [15. 最佳实践](#15-最佳实践)
- [16. 循环回边操作](#16-循环回边操作)
- [17. 动态 assignee 操作](#17-动态-assignee-操作)
- [18. serviceTask 自动节点操作](#18-servicetask-自动节点操作)
- [19. 拓扑自省操作](#19-拓扑自省操作)

---

## 0. 前置条件

| 项 | 要求 |
|---|---|
| JDK | **17**（源码/目标兼容版本均为 17） |
| 构建 | 无需安装 —— 仓库自带 Gradle 8.5（`gradle-8.5/`），用 `gradlew.bat` 即可 |
| 数据库 | 可选。默认 H2 内存库演示；生产可换 MySQL / PostgreSQL |
| Docker | 仅跨库测试（`*CrossDbTest`）需要，平时不装也能全绿（相关用例自动 skip） |

Windows 命令行（cmd）通用：
```bat
cd /d D:\project\workflow-engine
```
> 注：cmd 无 `tail`，看长输出用 `> build.log` 重定向后 `type build.log`。

---

## 1. 三分钟跑通 Demo

请假审批流程：`start → apply(员工) → manager(或签 managerA/managerB) → hr → end`。

```bat
cd /d D:\project\workflow-engine
gradlew.bat :workflow-sample:run --no-daemon
```

预期输出（节选）：
```
===== 自研工作流引擎 - 请假审批 Demo =====
流程发起成功 instanceId=<uuid>
  实例状态: RUNNING
  ...
HR 审批完成
===== Demo 完成 =====
最终状态: COMPLETED
✅ 流程正常结束
```

入口类：`workflow-sample/.../LeaveDemo.java`（`gradle` application 插件，`mainClass=com.workflow.sample.LeaveDemo`）。

---

## 2. 最小接入：五步走

```java
import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.WorkflowEngine;
import com.workflow.engine.WorkflowEngineBuilder;
import com.workflow.repository.*;
import java.util.Map;

// ① 定义流程
ProcessDefinition leave = ProcessBuilder.create("leave", "请假审批")
        .start("start")
        .userTask("apply",   "提交申请",   Candidate.ofAny("employee"))
        .userTask("manager", "经理审批",   Candidate.ofAny("managerA", "managerB"))
        .userTask("hr",      "HR 审批",    Candidate.ofAny("hr"))
        .end("end")
        .connect("start", "apply")
        .connect("apply", "manager")
        .connect("manager", "hr")
        .connect("hr", "end")
        .build();

// ② 装配引擎（内存版最简；生产换仓储见 §11）
InMemoryProcessRepository procRepo = new InMemoryProcessRepository();
InMemoryInstanceRepository instRepo = new InMemoryInstanceRepository();
InMemoryTaskRepository  taskRepo = new InMemoryTaskRepository();
procRepo.save(leave);                                  // 注册定义
WorkflowEngine engine = WorkflowEngineBuilder
        .builder(procRepo, instRepo, taskRepo)
        .build();

// ③ 发起流程 → 返回 instanceId
String instanceId = engine.start("leave", "employee", Map.of("days", 3));

// ④ 取当前待办并审批（taskId 来自实例的任务列表）
String taskId = engine.getInstance(instanceId).getTasks().stream()
        .filter(t -> t.getStatus() == com.workflow.enums.TaskStatus.PENDING)
        .findFirst().orElseThrow().getId();
engine.completeTask(taskId, "employee", true);          // approved=true 通过

// ⑤ 查状态
System.out.println(engine.getInstance(instanceId).getStatus());  // RUNNING/COMPLETED/...
```

> `completeTask(taskId, userId, approved)`：`approved=false` 等同驳回（内部走 reject）。

---

## 3. 流程定义 DSL 速查

`ProcessBuilder.create(key[, name])` 起步，链式注册节点 + `connect` 连线，`build()` 时一次性校验。

| DSL 方法 | 节点类型 | 说明 |
|---|---|---|
| `.start(id)` | START | 必须且唯一 |
| `.end(id)` | END | 结束 |
| `.userTask(id, name, candidate)` | USER_TASK | 人工审批；`Candidate.ofAny(...)` 或签 / `ofAll(...)` 会签 |
| `.exclusiveGateway(id)` | EXCLUSIVE | 排他网关（按条件选一条出口） |
| `.parallelGateway(id)` | PARALLEL | 并行网关（按入度自动判 fork/join） |
| `.subProcess(id, name, childKey)` | SUB_PROCESS | 子流程，引用另一 key |
| `.dynamicParallel(id, name, var, strategy)` | DYNAMIC_PARALLEL | 运行时按 `var`(List) 生成会签 |
| `.messageEvent(id, name, messageName, correlationExpr)` | MESSAGE_EVENT | 等待外部消息 |
| `.signalEvent(id, name, signalName)` | SIGNAL_EVENT | 等待广播信号 |
| `.timerBoundary(id, name, attachedTo, durationMillis, interrupting)` | TIMER_BOUNDARY | 附加在某任务上的定时器 |
| `.decision(id, name, decisionTableId)` | DECISION | DMN 决策表（见 §7） |
| `.timeout(taskId, millis, policy[, targetUser])` | — | 给已注册的 USER_TASK 配超时 |
| `.variable(name, type)` / `.variable(varDef)` | — | 变量 schema，发起时自动校验 |
| `.version(v)` | — | 流程版本（默认 1） |
| `.connect(from, to)` / `.connect(from, to, condition)` / `.connectAll(from, to...)` | 转移 | 带条件用于排他网关 |

### 排他网关 + 条件
```java
ProcessBuilder.create("exp")
    .start("s").userTask("apply","申请", Candidate.ofAny("u1"))
    .exclusiveGateway("gw")
    .userTask("small","小额审批", Candidate.ofAny("m1"))
    .userTask("large","大额审批", Candidate.ofAny("boss"))
    .end("e")
    .connect("s","apply").connect("apply","gw")
    .connect("gw","small","${amount <= 1000}")      // 条件命中即走；否则看下一条
    .connect("gw","large","${amount > 1000}")
    .connect("small","e").connect("large","e")
    .build();
```
> 条件求值失败/无匹配 → 引擎**吞掉该 Token**并记 WARN（不抛异常）。所以每个排他网关要保证条件覆盖或留一条兜底分支。

### 并行网关（fork/join）
```java
    .parallelGateway("fork")
    .connect("fork","taskA").connect("fork","taskB")  // 入度1出度多 = fork
    ...
    .parallelGateway("join")
    .connect("taskA","join").connect("taskB","join")  // 入度多出度1 = join，等两分支都到齐
    .connect("join","next")
```

### 超时
```java
    .userTask("manager","经理审批", Candidate.ofAny("m1"))
    .timeout("manager", 60_000, TimeoutPolicy.AUTO_APPROVE)                       // 1分钟无操作自动通过
    .timeout("manager", 60_000, TimeoutPolicy.AUTO_TRANSFER, "m2")                // 自动转办给 m2（AUTO_TRANSFER 必须给 targetUser）
```
`TimeoutPolicy`：`AUTO_APPROVE` / `AUTO_REJECT` / `AUTO_TERMINATE` / `AUTO_TRANSFER`。

---

## 4. 引擎装配（WorkflowEngineBuilder）

必填只有 3 个仓储；其余全部可选，不设即关闭对应能力：

```java
WorkflowEngine engine = WorkflowEngineBuilder
        .builder(procRepo, instRepo, taskRepo)     // 必填
        .auditLogRepository(auditRepo)             // 审计日志（不设=不记）
        .historyRepository(histRepo)               // 历史活动/任务（不设=不记）
        .historyKinds(EnumSet.allOf(HistoryKind.class)) // 只留 TASK 或只留 ACTIVITY 可裁剪
        .eventRepository(eventRepo)                // 事件网关（不设=消息/信号/定时器不可用）
        .decisionRepository(decisionRepo)          // DMN（不设=决策节点不可用）
        .decisionHistoryRepository(decisionHistRepo)
        .delegationRepository(delegationRepo)      // 委托
        .carbonCopyRepository(ccRepo)              // 抄送
        .notificationService(new LoggingNotificationService()) // 通知（见 §5.3/§...）
        .timeoutScheduler(customScheduler)         // 不设=默认 ScheduledTimeoutScheduler
        .conflictRetries(3)                        // 乐观锁冲突重试次数（0=不重试直接抛）
        .retryBackoffMillis(20)                    // 重试前等待
        .build();

engine.shutdown();   // 关闭默认调度器线程池（用外部 scheduler 时不关）
```

> 需要"关掉并发/事务、退回裸执行"用于压测：`engine.withoutConcurrencyControl()`；只记某类历史：`engine.withHistoryKinds(...)`。

---

## 5. 日常操作手册

以下均为 `IWorkflowEngine` 方法。`taskId`/`instanceId` 由业务侧从返回或查询里拿。

### 5.1 任务级
| 操作 | 调用 | 说明 |
|---|---|---|
| 通过/完成 | `completeTask(taskId, userId, true)` | 会签下所有(ALL)或任一(ANY)通过后推进 |
| 驳回 | `rejectTask(taskId, userId, "资料不全")` | 退回上一个 USER_TASK，重建待办 |
| 转办 | `transferTask(taskId, fromUserId, toUserId)` | 原任务置 TRANSFERRED，为目标人建新待办 |
| 催办 | `urge(taskId, operator, "尽快处理")` | 需配 `notificationService`；向候选人发通知 |

### 5.2 实例级
| 操作 | 调用 | 前置状态 |
|---|---|---|
| 挂起 | `suspend(instanceId)` | RUNNING |
| 恢复 | `resume(instanceId)` | SUSPENDED |
| 终止 | `terminate(instanceId)` | **仅 RUNNING**（终态再调会抛 `IllegalStateException`） |
| 撤回 | `withdraw(instanceId, initiator)` | 发起人本人 + 尚未有人审批 |
| 退回任意节点 | `jumpToNode(instanceId, targetNodeId, operator, reason)` | RUNNING；target 必须存在于定义中 |

`jumpToNode` 行为：消耗当前所有 Token → 终止所有 PENDING 任务 → 在目标节点重建 Token 并推进（目标是 USER_TASK 会新建待办）。对并行网关也能整树回退。

### 5.3 委托与抄送
```java
engine.delegate("alice", "bob");                        // alice 的待办自动转给 bob（全局）
engine.delegate("alice", "bob", "manager", "leave");   // 仅 leave 流程 manager 节点
engine.revokeDelegate("alice", "bob");

engine.carbonCopy(instanceId, taskId, nodeId,
        List.of("boss","hr-cc"), "operator", "知会一下");
List<CarbonCopy> unread = engine.getUnreadCarbonCopies("hr-cc");
engine.markCarbonCopyRead(ccId);
```

### 5.4 批处理
```java
BatchResult r = engine.batchCompleteTasks(List.of(t1,t2,t3), "m1", true);
// 原子：任一失败整批回滚，抛 BatchPartialFailureException（含 BatchResult：成功/失败计数 + 逐条详情）
if (!r.isAllSuccess()) System.out.println(r.getFailures());

engine.batchTerminateInstances(List.of(i1,i2), "admin", "清理测试数据");
```

### 5.5 批量启动
```java
List<Map<String,Object>> vars = List.of(Map.of("days",1), Map.of("days",2), Map.of("days",5));
List<String> ids = engine.batchStart("leave", vars);          // 单事务 saveBatch 后逐个推进
List<String> ids2 = engine.batchStart("leave", 2, vars);      // 指定版本
```
> 提醒：InMemory 下批量启动未必比逐个快（内存写本就便宜）；批量的价值在**真实数据库**（省往返/事务），见 §15。

---

## 6. 事件网关操作

装配时给 `.eventRepository(new InMemoryEventRepository())`（或 DB 版）。

| 事件 | 定义侧 | 触发侧 |
|---|---|---|
| 消息（点对点） | `.messageEvent("wait","等待回执","paymentDone","${orderId}")` | `engine.sendMessage("paymentDone", "订单实际值")` |
| 信号（广播） | `.signalEvent("gate","审批门","releaseAll")` | `engine.sendSignal("releaseAll")` → 唤醒**所有**等待该信号的实例 |
| 定时器边界 | `.timerBoundary("t","超时","manager", 30_000, true)` | 到点由调度器触发；`checkAndTriggerTimers()` 可手动扫描 |

- 消息事件：进入节点时按 `messageName:correlationKey` 登记，Token 停在节点；`sendMessage` 用相同 key 命中后推进该实例。
- 信号事件：可多实例同时等待，一次 `sendSignal` 全部唤醒。
- 定时器 `interrupting=true` 取消原任务走超时分支；`false` 触发分支但原任务继续。

> ⚠️ `sendMessage` / `sendSignal` / `checkAndTriggerTimers` 定义在 `WorkflowEngine` 实现类上，**未进 `IWorkflowEngine` 接口**——用 `WorkflowEngine` 类型持有引擎实例才能调用（本手册 §2 的 `engine` 即 `WorkflowEngine`）。

---

## 7. DMN 决策表操作

装配给 `.decisionRepository(new InMemoryDecisionRepository())`。

```java
DecisionTable table = new DecisionTable(
    "approval-table", "审批级别决策",
    List.of(new DecisionTable.InputClause("amount", "amount")),
    List.of(new DecisionTable.OutputClause("approvalLevel", "string")),
    List.of(
        new DecisionTable.DecisionRule("r1", List.of("..1000"),    List.of("\"manager\""),   1),
        new DecisionTable.DecisionRule("r2", List.of("1000..5000"),List.of("\"director\""),  2),
        new DecisionTable.DecisionRule("r3", List.of("5000.."),    List.of("\"ceo\""),      3)
    ),
    DecisionTable.HitPolicy.FIRST);
decisionRepo.save(table);

ProcessDefinition def = ProcessBuilder.create("flow")
    .start("s")
    .decision("d", "定级", "approval-table")          // 决策节点
    .userTask("m","经理", Candidate.ofAny("m1"))
    .userTask("c","CEO",  Candidate.ofAny("ceo1"))
    .end("e")
    .connect("s","d")
    .connect("d","m","${approvalLevel == 'manager'}") // 决策结果写入流程变量，驱动后续分支
    .connect("d","c","${approvalLevel == 'ceo'}")
    .connect("m","e").connect("c","e")
    .build();
```

- 输入条目支持：范围 `"1000..5000"`、单边 `"..1000"` / `"5000.."`、通配 `"-"`、字面量。
- 输出字面量：`"\"manager\""`（带引号=字符串）、`0.2`（数字）、`true`。
- 决策结果自动写回流程变量；`DecisionHistoryRepository` 记录每次命中（`matchedRuleId`/inputs/outputs）。
- 命中策略：`UNIQUE` / `FIRST` / `PRIORITY` / `ALL` / `COLLECT`。

---

## 8. 查询：TaskQuery 与实例查询

```java
// 待办分页（真实总数走 count()，不受分页影响）
List<TaskInstance> mine = TaskQuery.create()
        .candidate("alice")                 // 候选人/处理人
        .status(TaskStatus.PENDING)
        .processDefinitionKey("leave")
        .orderByCreateTime()
        .offset(0).limit(20)
        .list(engine);
long total = TaskQuery.create().candidate("alice").status(TaskStatus.PENDING).count(engine);

// 其它过滤：.nodeId(..) .processDefinitionVersion(v) .processInstanceId(id)
// 唯一结果：.singleResult()  —— 命中多条会抛异常（用于"就该一条"的断言场景）

// 实例：
engine.getInstance(id);              // 含 activeTokens / tasks / status / variables
engine.allInstances();               // 全量（内存/小表用；大表按需自封装仓储查询）
engine.allTasks();
```
> 查询能力（`allTasks`/`findAll`/`findByStatus` 等）三套仓储都实现，跨库行为一致，由一致性测试守。

---

## 9. 多租户操作

```java
import com.workflow.engine.TenantContext;

TenantContext.setTenantId("tenant-A");       // 之后 start/batchStart 的实例都归属 tenant-A
String id = engine.start("leave", Map.of());
TenantContext.clear();                        // 务必清理（尤其线程池复用）

// 临时切租户并自动还原：
List<String> ids = TenantContext.withTenant("tenant-B",
        () -> engine.batchStart("leave", vars));
```
- 租户 ID 优先级：流程定义的 `tenantId` > 当前 `TenantContext`。
- 查询隔离：`dashboard(topN, "tenant-A")`、`countPending(tenantId)`、`countGroupByProcessAndStatus(tenantId)`；传 `null` = 全局视图（兼容老数据）。
- 存储：`wf_instance.tenant_id` / `wf_task.tenant_id`（Flyway V7）。

---

## 10. 监控操作

```java
DashboardMetrics m = engine.dashboard(10);          // 全局
DashboardMetrics mx = engine.dashboard(10, "tenant-A");  // 按租户

m.totalInstances(); m.instancesByStatus(); m.instancesByProcess();
m.pendingTasks();   m.pendingByNode();     // 待办按(流程,节点)分布
m.slowestNodes();   // 瓶颈节点 TopN（平均耗时降序，来自历史活动聚合）
m.timeoutEvents();  // 超时自动处理事件计数
```
- REST：`GET /api/metrics/dashboard?topN=10`（见 §12）。
- 示例看板：浏览器打开仓库根 `docs/dashboard.html`，填后端 API 地址点"刷新"。（`file://` 直开受同源限制，需 REST 服务允许跨域或同源部署。）
- 未启用历史/审计仓储时，耗时/超时类指标为空，其余照常。

---

## 11. 接入数据库（JPA / MyBatis-Plus / Flyway）

两套实现均实现同一批仓储接口，引擎无感知。切库只换装配，测试用例不动。

### JPA（Hibernate + H2 演示）
```java
JpaPersistence jpa = JpaPersistence.getDefault();
jpa.init();                       // 建 EntityManagerFactory；Flyway 自动建表
WorkflowEngine engine = WorkflowEngineBuilder
        .builder(jpa.processRepo(), jpa.instanceRepo(), jpa.taskRepo())
        .auditLogRepository(jpa.auditLogRepo())
        .build();
// 用完： jpa.close();
```
- 单元名 `workflow-pu`，配置在 `workflow-persistence-jpa/src/main/resources/META-INF/persistence.xml`。
- 换 MySQL/PG：传覆盖属性 `init(url, driver, user, pwd)` 或用 `-Dworkflow.jdbc.*` 系统属性。

### MyBatis-Plus
```java
MybatisPersistence mb = MybatisPersistence.getDefault();
mb.init();                        // 建 SqlSessionFactory + Flyway 迁移
WorkflowEngine engine = WorkflowEngineBuilder
        .builder(mb.processRepo(), mb.instanceRepo(), mb.taskRepo())
        .build();
mb.close();
```
- 默认连独立 H2 库 `jdbc:h2:mem:workflow_mybatis`；`init(url,user,pwd)` 换库。

### Flyway（DDL 单一真相）
迁移脚本在 `workflow-persistence-flyway/src/main/resources/db/migration/`：`V1__init` → `V7__multi_tenant`。启动时 `FlywayMigrator.migrate(url,...)` 自动执行；三库同一份脚本。

> 加新列的规矩：改 domain → 改三套实体/仓储读写 → 加一条 `V{n}__*.sql` → **务必真实核验脚本已生成并执行**（历史上出现过实体引用新列但迁移未落地，导致 `Column not found` 大面积红）。

---

## 12. REST API 手册

模块 `workflow-rest`，零第三方依赖（JDK `HttpServer` + fastjson2）。启动示例：

```java
WorkflowRestApi api = new WorkflowRestApi(engine /*, historyRepo, procRepo*/);
// 由 RestServer 绑定端口运行；端点如下（请求/响应均 JSON，UTF-8）
```

> 流程定义在应用侧 `procRepo.save(def)` 注册后，REST 负责运行期操作。

### 端点总表

| 方法 路径 | 作用 | 请求体 / 查询 | 成功码 |
|---|---|---|---|
| `GET /api/health` | 健康检查 | — | 200 |
| `POST /api/processes/{key}/start` | 发起流程 | `{"initiator?","version?","variables":{...}}` | 201 + `instanceId`/`tasks` |
| `GET /api/instances/{id}` | 实例详情 | — | 200 |
| `POST /api/instances/{id}/suspend` | 挂起 | — | 204 |
| `POST /api/instances/{id}/resume` | 恢复 | — | 204 |
| `POST /api/instances/{id}/terminate` | 终止 | — | 204 |
| `POST /api/instances/{id}/withdraw` | 撤回 | `{"initiator"}` | 204 |
| `POST /api/instances/{id}/jump` | 退回任意节点 | `{"targetNodeId","operator","reason?"}` | 204 |
| `GET /api/instances/{id}/history` | 实例历史 | — | 200 |
| `GET /api/tasks` | 待办列表/分页 | `?assignee=&status=&nodeId=&processKey=&instanceId=&processVersion=&page=&size=` | 200 |
| `POST /api/tasks/{id}/complete` | 完成/通过 | `{"userId","approved?=true"}` | 204 |
| `POST /api/tasks/{id}/reject` | 驳回 | `{"userId","reason?"}` | 204 |
| `POST /api/tasks/{id}/transfer` | 转办 | `{"fromUserId","toUserId"}` | 204 |
| `GET /api/metrics/dashboard` | 监控快照 | `?topN=10` | 200 |

### curl 示例
```bash
# 发起
curl -XPOST localhost:8080/api/processes/leave/start \
     -H 'Content-Type: application/json' \
     -d '{"initiator":"employee","variables":{"days":3}}'

# 某人待办（分页）
curl 'localhost:8080/api/tasks?assignee=managerA&status=PENDING&page=0&size=20'

# 通过某任务
curl -XPOST localhost:8080/api/tasks/<taskId>/complete \
     -H 'Content-Type: application/json' -d '{"userId":"managerA","approved":true}'

# 退回到 apply 节点
curl -XPOST localhost:8080/api/instances/<id>/jump \
     -H 'Content-Type: application/json' \
     -d '{"targetNodeId":"apply","operator":"admin","reason":"需重新填写"}'
```

### 状态码语义
| 码 | 含义 | 典型场景 |
|---|---|---|
| 400 | 参数错误 | 缺字段、`topN` 非整数、`page/size` 越界、body 非法 JSON |
| 404 | 资源不存在 | 实例/任务/流程定义/端点不存在 |
| **409** | 状态冲突（**并发**） | 任务非 PENDING（"这条待办已被他人处理"）——审批最高频交互，务必与 400 区分 |
| 501 | 不支持的操作 | 如对子实例调 `withdraw` |

---

## 13. 构建与测试命令

```bat
cd /d D:\project\workflow-engine

:: 跑 Demo
gradlew.bat :workflow-sample:run --no-daemon

:: 编译（不测试）
gradlew.bat assemble --no-daemon

:: 全量测试（约 270 用例；跨库需 Docker，否则 skip）
gradlew.bat :workflow-tests:test --no-daemon

:: 分套件
gradlew.bat :workflow-tests:test --no-daemon --tests "com.workflow.tests.engine.*"   :: 仅 InMemory
gradlew.bat :workflow-tests:test --no-daemon --tests "com.workflow.tests.jpa.*"      :: 仅 JPA
gradlew.bat :workflow-tests:test --no-daemon --tests "com.workflow.tests.mybatis.*"  :: 仅 MyBatis

:: 性能基准（默认跳过，不拖慢常规构建）
gradlew.bat :workflow-tests:test --no-daemon --tests "com.workflow.tests.perf.*" -Dperf=true
```
测试报告：`workflow-tests/build/reports/tests/test/index.html`。

> 测试隔离说明：`workflow-tests` 已配 `forkEvery=1`（每个测试类独立 JVM），避免 JPA/MyBatis 事务原子性用例与静态单例/H2 命名库/ThreadLocal 事务上下文跨类污染。

---

## 14. 常见报错与排查

| 报错 | 原因 | 处理 |
|---|---|---|
| `任务不存在: <id>` | taskId 错/未刷新 | 先 `getInstance(id).getTasks()` 拿真实待办 id |
| `任务非 PENDING 状态: X`（REST 409） | 该待办已被他人处理/迟到者 | **正常并发语义**，前端提示"已处理，请刷新"，非崩溃 |
| `流程定义不存在: <key>` | 未 `procRepo.save(def)` 或 key 拼错 | 先注册定义 |
| `USER_TASK 节点 X 没有任何出口,会卡死` | build 期校验 | 给该节点补 `connect` |
| `只有 USER_TASK 节点支持超时配置` | `.timeout` 用在了非 USER_TASK | 只对 userTask 配超时 |
| `AUTO_TRANSFER 超时策略必须指定目标用户` | 转办没给 targetUser | `.timeout(id,ms,AUTO_TRANSFER,"user")` |
| `排他网关 ... 无匹配出口` | 条件未覆盖 | 加兜底分支；否则 Token 被吞（仅 WARN） |
| `目标节点 X 不存在于流程定义中` | jumpToNode target 非法 | 传定义里真实存在的 nodeId |
| `仅 RUNNING 状态的流程可终止/跳转` | 实例已终态 | 先判 `getInstance(id).getStatus()` |
| `批量启动/完成失败` `BatchPartialFailureException` | 批内某条非法 | 读 `e.getResult().getFailures()` 定位；整批已回滚 |
| `Column "TENANT_ID" not found`（DB） | 实体引用了新列但迁移没跑 | 确认对应 `V*.sql` 已生成并执行（`flyway_schema_history` 有记录） |
| `UnsupportedOperationException: ... not implemented` | 某仓储缺实现 | 用抽象方法暴露缺口是刻意设计；补齐该仓储实现 |

---

## 15. 最佳实践

- **发起带 initiator**：`start(key, initiator, vars)`，否则无法 `withdraw`。
- **审批前先按候选人查待办**：`TaskQuery.candidate(user).status(PENDING)`，别猜 taskId。
- **区分 409 与 400**：审批页对 409 刷新、对 400 报参数错。
- **线程池环境用完 `TenantContext.clear()`**，否则脏租户串味。
- **通知渠道失败不要冒泡**：`WebhookNotificationService` 已内置吞异常；自实现同理——通知不是流程正确性的一部分。
- **调度副作用等提交后**：超时/取消走 `afterCommit`（引擎内部已处理），别在事务内直接 `scheduler.cancel`。
- **加字段走全流程**：domain → 三套实体读写 → snapshot/copy/reconstruct → 迁移脚本 → 一条跨仓储往返测试。少一处就会出现"内存绿、真库炸"或"回滚丢数据"。
- **InMemory 适合测试/演示，不适合大数据**：内存版批量启动甚至更慢；生产用 JPA/MyBatis。
- **`gradle build` 跑全部测试；跨库那批需本机 Docker**，没装会 skip 不算失败。

---

## 16. 循环回边操作

### 16.1 场景

排他网关回边式循环：`tpl → gw →(条件)→ tpl`。典型用例：
- 驳回后重新提交（申请人修改后重新走审批）
- 审批流中的循环网关（条件不满足时回到前序节点补充材料）

### 16.2 DSL 定义

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
```

### 16.3 启动与推进

```java
// 启动时设置循环条件
String instanceId = engine.start("loop-flow", Map.of("loopContinue", true));

// 第一次 tpl 待办
List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
TaskInstance first = tasks.stream()
    .filter(t -> "tpl".equals(t.getNodeId()) && t.getStatus() == TaskStatus.PENDING)
    .findFirst().orElseThrow();

// 完成第一次 tpl → 到 gw → 条件为真 → 回边到 tpl → 第二次 tpl 待办（新任务）
engine.completeTask(first.getId(), "u1", true);

// 第二次 tpl 待办（arrival 不同，是新任务）
List<TaskInstance> tasks2 = taskRepo.findByInstanceId(instanceId);
TaskInstance second = tasks2.stream()
    .filter(t -> "tpl".equals(t.getNodeId()) && t.getStatus() == TaskStatus.PENDING)
    .findFirst().orElseThrow();

// 设置退出条件 → 完成 → 流程结束
engine.setVariable(instanceId, "loopContinue", false);
engine.completeTask(second.getId(), "u1", true);
```

### 16.4 注意事项

- **Token 到达代次（arrival）自动管理**：引擎内部通过 `Token.moveTo()` 自增 arrival，无需业务代码干预。
- **回边重入会新建任务**：同一节点多轮到达时，每轮的任务 arrival 不同，互不干扰。
- **与 reject/transfer 兼容**：reject 用新 token（arrival=0），transfer 不移动 token（arrival 不变），均不会误判。
- **V8 迁移必须执行**：`wf_token`/`wf_task` 加 `arrival INT DEFAULT 0`，老数据向后兼容。

---

## 17. 动态 assignee 操作

### 17.1 场景

运行时从变量取办理人：`flowable:assignee="${xxxApprover}"`。典型用例：
- 请假审批：申请人 → 直属经理（`managerId` 变量）→ HR（`hrId` 变量）
- 采购审批：部门经理（`deptManagerId`）→ 财务总监（`cfoId`）
- 驳回重提：回到原节点，办理人可能变了（`originalAssignee` 变量）

### 17.2 DSL 定义

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
```

### 17.3 启动与推进

```java
// 启动时设变量
String instanceId = engine.start("leave-flow", Map.of(
    "applyApprover", "user1",
    "reviewApprover", "manager1"
));

// 第一次 apply 待办（候选人 = user1）
List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
TaskInstance applyTask = tasks.stream()
    .filter(t -> "apply".equals(t.getNodeId()) && t.getStatus() == TaskStatus.PENDING)
    .findFirst().orElseThrow();

// 完成 apply → 到 review（候选人 = manager1）
engine.completeTask(applyTask.getId(), "user1", true);

// 驳回回退到 apply（变量可以变了）
ProcessInstance instance = instRepo.findById(instanceId);
instance.setVariable("applyApprover", "user2");  // 换人
instRepo.save(instance);
engine.rejectTask(reviewTask.getId(), "manager1", "需要修改");

// 新 apply 待办（候选人 = user2）
```

### 17.4 注意事项

- **变量必须设**：启动时或运行中必须设变量，否则 `handleUserTask` 抛 `IllegalStateException("动态 assignee 变量 'xxx' 未设置")`。
- **变量必须是 String**：非 String 类型抛 `IllegalStateException("动态 assignee 变量 'xxx' 必须是 String 类型")`。
- **与静态 candidate 互斥**：`ProcessBuilder.build()` 时校验，不能同时指定 `candidate` 和 `assigneeVariable`。
- **驳回/循环回边后变量可变**：每次重入节点都会重新读变量，办理人可以不同。
- **BPMN 兼容**：导出为 `flowable:assignee="${varName}"`，导入时自动识别。

---

## 18. serviceTask 自动节点操作

### 18.1 场景

自动执行逻辑，不创建 TaskInstance：
- 自动抄送：审批通过后自动发通知给相关人员
- 数据转换：节点间自动处理数据格式
- 外部系统回调：流程结束后自动调用外部系统接口

### 18.2 DSL 定义

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

### 18.3 执行语义

- Token 到达 serviceTask → 执行 delegate → 自动推进到下一节点
- 不创建 TaskInstance（无需人工干预）
- delegate 可以访问流程变量（只读）

### 18.4 异常处理

- **delegate 未注册**：抛 `IllegalStateException("delegate 'xxx' 未注册")`，流程挂起
- **delegate 抛异常**：抛 `RuntimeException("SERVICE_TASK delegate 执行失败")`，流程挂起
- **挂起后恢复**：调用 `engine.resumeInstance(instanceId)` 继续执行

### 18.5 注意事项

- **delegate 应异步化**：如果 delegate 执行时间长（如调用外部系统），应在 delegate 内部用线程池异步化，避免阻塞 Token 推进
- **与 USER_TASK 互斥**：serviceTask 不需要候选人，不能指定 `candidate` 或 `assigneeVariable`
- **不支持加签/减签/驳回/转办**：serviceTask 是自动节点，无人工干预
- **BPMN 兼容**：导出为 `<serviceTask><extensionElements><wf:delegate key="..."/></extensionElements></serviceTask>`，导入时自动识别

---

## 19. 拓扑自省操作

### 19.1 场景

给前端/上层提供流程图渲染与高亮数据：

- 流程图：节点、连线（含条件表达式）
- 高亮：运行实例当前 Token 所在节点
- 路径：已完成节点（审批历史）

### 19.2 API 速查

```java
TopologyView getTopology(String processKey, int version);   // version=-1 取最新版
InstanceTopologyView getInstanceTopology(String instanceId);
```

### 19.3 定义拓扑

```java
TopologyView topo = engine.getTopology("leave-flow", -1);
topo.getProcessKey();     // "leave-flow"
topo.getVersion();        // 版本号
topo.getNodes();          // List<NodeView>
topo.getTransitions();    // List<TransitionView>
```

`NodeView` 字段：`id` / `name` / `type` / `userIds` / `assigneeVariable` / `delegateKey`。
`TransitionView` 字段：`from` / `to` / `condition`（条件表达式，可为 null）。

### 19.4 实例高亮

```java
InstanceTopologyView view = engine.getInstanceTopology(instanceId);
view.getActiveNodeIds();     // 当前 Token 所在节点 ID（高亮用）
view.getCompletedNodeIds();  // 已完成节点 ID（历史路径）
view.getStatus();            // 实例状态
```

### 19.5 注意事项

- **只读**：视图不可修改，改流程请走 `ProcessBuilder` / 迁移 API
- **version=-1** 表示取最新版；指定版本不存在或流程不存在抛 `IllegalArgumentException`
- **前端友好**：所有视图可 JSON 序列化（字段均有 getter，集合不可变）

---

*本手册对应 v3.14.0。API 若与源码不一致，以源码为准，并烦请反馈更新。*
