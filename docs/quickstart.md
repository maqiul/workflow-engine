# 最短上手卡片（Quickstart · 1 页）

> 目标：clone 到本地后，照抄这一页就能跑起来、接进去。详细用法见 [../OPERATIONS.md](../OPERATIONS.md)，原理见 [../README.md](../README.md)。

## 0. 环境
- **JDK 17**（硬性）。仓库自带 Gradle 8.5，无需另装。
- 仓库是 **本地位于** `D:/project/workflow-engine` 的 Gradle 多模块工程（当前**无远程**，接入用源码级 composite/复制模块；若已 `publish` 到私服再按坐标引）。

## 1. 先跑通 Demo（证明环境 OK）
```bat
cd /d D:\project\workflow-engine
gradlew.bat :workflow-sample:run --no-daemon
```
看到 `✅ 流程正常结束` 即成。流程：`start → apply → manager(或签) → hr → end`。

## 2. 五步接一个流程
```java
// ① 定义
ProcessDefinition def = ProcessBuilder.create("leave","请假")
    .start("s")
    .userTask("apply","申请", Candidate.ofAny("emp"))
    .userTask("m","审批",   Candidate.ofAny("mgr"))
    .end("e")
    .connect("s","apply").connect("apply","m").connect("m","e")
    .build();

// ② 装配引擎（内存版最快；生产换 JPA/MyBatis 见 OPERATIONS §11）
var procRepo = new InMemoryProcessRepository();
var instRepo = new InMemoryInstanceRepository();
var taskRepo = new InMemoryTaskRepository();
procRepo.save(def);
WorkflowEngine engine = WorkflowEngineBuilder
    .builder(procRepo, instRepo, taskRepo).build();

// ③ 发起
String id = engine.start("leave", "emp", Map.of("days", 3));

// ④ 取待办 → 审批
String taskId = engine.getInstance(id).getTasks().stream()
    .filter(t -> t.getStatus() == TaskStatus.PENDING)
    .findFirst().orElseThrow().getId();
engine.completeTask(taskId, "mgr", true);   // approved=false 即驳回

// ⑤ 查状态
engine.getInstance(id).getStatus();          // RUNNING / COMPLETED ...
```

## 3. 高频操作一行流
```java
engine.rejectTask(taskId, "mgr", "资料不全");                 // 驳回
engine.transferTask(taskId, "mgr", "deputy");                 // 转办
engine.suspend(id); engine.resume(id); engine.terminate(id);  // 挂起/恢复/终止(仅RUNNING)
engine.withdraw(id, "emp");                                   // 发起人撤回(未审批前)
engine.jumpToNode(id, "apply", "admin", "重填");              // 退回任意节点
engine.batchStart("leave", vars);                             // 批量发起
engine.batchCompleteTasks(List.of(t1,t2), "mgr", true);       // 批量完成(原子)
DashboardMetrics m = engine.dashboard(10);                    // 监控快照
```

## 4. 常用命令
```bat
gradlew.bat assemble --no-daemon                 :: 只编译打包
gradlew.bat :workflow-tests:test --no-daemon     :: 全量测试(约270,跨库需Docker否则skip)
gradlew.bat :workflow-tests:test --no-daemon --tests "com.workflow.tests.engine.*"   :: 仅InMemory
gradlew.bat :workflow-tests:test --no-daemon --tests "com.workflow.tests.perf.*" -Dperf=true  :: 性能基准
```

## 5. 接真实数据库（3 行切换，用例不动）
```java
JpaPersistence jpa = JpaPersistence.getDefault(); jpa.init();   // Flyway 自动建表 V1..V7
WorkflowEngine engine = WorkflowEngineBuilder
    .builder(jpa.processRepo(), jpa.instanceRepo(), jpa.taskRepo())
    .auditLogRepository(jpa.auditLogRepo()).build();
```

## 6. 三个必记的坑
- **加字段要三处同步**：domain → 三套实体读写 → `snapshot/copy/reconstruct` → 迁移脚本，并加一条跨仓储往返测试；少一处就"内存绿、真库炸"。
- **并发冲突返回 409 不是 bug**：`任务非 PENDING`（REST 409）= 该待办已被他人处理，前端刷新即可。
- **多租户用完清 ThreadLocal**：`TenantContext.setTenantId(...)` 后在池化线程里记得 `clear()`。

---
*对应 v3.8.0；与源码不一致时以源码为准。*
