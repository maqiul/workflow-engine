# 设计方案：运行中实例版本迁移

> 状态：**已交付** · v3.13.0（单实例 `migrateInstance`）+ v3.15.0（批量 `migrateInstances`）· 关联：OA 能力对照表 #8、Flowable ProcessInstanceMigration API

---

## 1. 问题

流程定义发布新版本后，已在运行的旧版本实例怎么办？

**典型场景**：
- 审批流程 v1 有 3 个节点（申请→经理审批→结束），v2 新增"总监审批"节点
- 已有实例停在"经理审批"节点，升级后应该继续走 v2 的"经理审批→总监审批→结束"
- 节点可能改名（"经理审批"→"部门经理审批"）、拆分、合并

**Flowable 做法**：
- 默认不迁移：新实例用新版本，旧实例继续用旧版本跑完
- 提供 `ProcessInstanceMigrationBuilder` 主动迁移：指定节点映射 + 变量映射

## 2. 方案：`migrateInstance` API

### 2.1 核心 API

```java
/**
 * 迁移运行中实例到新版本流程定义
 * 
 * @param instanceId 要迁移的实例 ID
 * @param targetProcessKey 目标流程定义 key（通常与原实例相同）
 * @param targetVersion 目标版本号（-1 表示最新版）
 * @param nodeMapping 节点映射：旧节点 ID → 新节点 ID
 *                    未映射的节点必须在新版中仍存在且 ID 相同
 * @param operator 操作人（写入 INSTANCE_MIGRATED 审计日志）
 */
void migrateInstance(String instanceId, String targetProcessKey, 
                     int targetVersion, Map<String, String> nodeMapping, String operator);
```

### 2.2 迁移步骤

1. **校验目标版本存在**：`processRepo.findByKeyAndVersion(targetProcessKey, targetVersion)` 必须存在
2. **校验实例状态**：仅 `RUNNING` 实例可迁移（COMPLETED/TERMINATED/SUSPENDED 不可）
3. **校验节点映射**：
   - 所有活跃 Token 所在节点必须在映射中，或在新版中 ID 相同且类型兼容
   - 映射目标节点必须在新版中存在
   - 节点类型必须兼容（USER_TASK→USER_TASK、GATEWAY→GATEWAY 等）
4. **执行迁移**：
   - 逐个 Token：更新 `currentNodeId` → 新节点 ID
   - 更新 `instance.processKey` / `instance.processVersion`
   - 保存 instance + tokens
5. **记录审计**：`AuditEventType.INSTANCE_MIGRATED`

### 2.3 节点类型兼容性

| 旧类型 | 允许迁移到 | 不允许 |
|---|---|---|
| USER_TASK | USER_TASK | GATEWAY/END/SERVICE_TASK |
| EXCLUSIVE_GATEWAY | EXCLUSIVE_GATEWAY | USER_TASK/END |
| PARALLEL_GATEWAY | PARALLEL_GATEWAY | USER_TASK/END |
| SERVICE_TASK | SERVICE_TASK | USER_TASK/END |
| START/END | — | （不应有 Token 停在 START/END） |

### 2.4 变量处理

- **不自动映射变量**：变量名变更由业务层在迁移前自行处理（`instance.setVariable`）
- **新增变量**：迁移前由业务层设置默认值
- **删除变量**：迁移后旧变量仍保留在 instance.variables 中（不影响运行）

### 2.5 在途任务处理

- **PENDING 任务**：Token 移动后，原节点的 PENDING 任务状态不变（仍 PENDING），但不再被引擎处理（因为 Token 已离开）
- **建议**：迁移前业务层先处理/终止在途任务，或迁移后手动清理

## 3. 改动清单

### 3.1 引擎核心（workflow-core）
- `IWorkflowEngine` 加 `migrateInstance` 方法
- `WorkflowEngine` 实现 `migrateInstance`
- `AuditEventType` 加 `INSTANCE_MIGRATED`

### 3.2 测试
- `InstanceMigrationTest`（InMemory）：
  - 基本迁移（节点改名）
  - 迁移到最新版（version=-1）
  - 节点类型不兼容抛异常
  - 目标版本不存在抛异常
  - 非 RUNNING 实例不可迁移
- `JpaInstanceMigrationTest`（JPA）：基本迁移
- `MybatisInstanceMigrationTest`（MyBatis）：基本迁移

### 3.3 BPMN 适配
- 无改动（迁移是运行时 API，与 BPMN 无关）

## 4. 分步实施
1. **步骤1 核心逻辑**：`IWorkflowEngine.migrateInstance` + `WorkflowEngine` 实现 + `AuditEventType.INSTANCE_MIGRATED` + InMemory 测试。验收：InMemory 全量绿。
2. **步骤2 持久化验证**：JPA/MyBatis 跨仓储一致性测试。验收：三套全量绿。
3. **步骤3 收尾**：CI 绿 + 文档（README/OPERATIONS/CHANGELOG v3.13.0）。

## 5. 风险与回退
- **风险**：迁移后 Token 停在新版节点，但新版节点的条件/候选人与旧版不同 → 运行时行为变化。**缓解**：文档明确"迁移前需验证节点语义兼容"。
- **风险**：在途任务未处理 → 迁移后出现"幽灵任务"。**缓解**：API 文档建议在途任务处理策略；未来可加"自动终止在途任务"选项。
- 每步独立 commit，任一步回归即回退该步。

## 6. 不做什么
- 不做自动节点映射（需 AI/规则引擎，过度设计）
- 不做变量自动映射（业务层自行处理）
- ~~不做批量迁移（单实例迁移足够，批量可循环调用）~~ → **v3.15 已推翻此判断**：
  "批量可循环调用"这句话忽略了一件事 —— 循环里的每一步，要么自己开事务、要么被并进一个大事务，
  这个决定不该外包给每个调用方。引擎侧提供 `migrateInstances`，才能把
  "**逐实例独立事务、部分成功保留**"这一语义固化下来（见 §7）。
  一个容易做错的判断，放在库里比放在二十个调用点上演更靠谱。
- 不做迁移回滚（迁移是破坏性操作，回滚需业务层自行备份）

---

## 7. 批量迁移（v3.15 补充）

### 7.1 API

```java
BatchResult migrateInstances(List<String> instanceIds, String targetProcessKey,
                             int targetVersion, Map<String, String> nodeMapping, String operator);
```

结果复用既有的 `BatchResult` / `BatchResult.FailureDetail`（与 `batchTerminateInstances` 同一套类型）。

### 7.2 关键决策：逐实例独立事务

批量迁移**刻意不做成"全或无"**，与既有的 `batchTerminateInstances` 正好相反：

| API | 语义 | 失败时 |
|---|---|---|
| `batchTerminateInstances` | 全或无 | 抛 `BatchPartialFailureException`，整批回滚 |
| `migrateInstances` | **逐个提交、部分成功保留** | 只记进 `BatchResult.failures`，不抛异常 |

理由：批量迁移的诉求是"尽量多迁成功"。迁 100 个实例时第 37 个失败，
把前 36 个一起回滚纯属倒退 —— 运维要的是"哪几个没成、各自为什么"。

实现上只是循环调用 `migrateInstance` —— 后者内部是 `exclusive → tx.execute`，
每次调用天然构成一个独立事务；**批方法自身刻意不开事务**，
否则 N 个实例会被合并进同一个事务，独立事务就白设计了。

### 7.3 失败不中断

`catch (RuntimeException)` 记入 `failures` 后继续处理下一个实例。
覆盖的失败类型：非 `RUNNING`、目标版本不存在、目标节点不存在、节点类型不兼容、乐观锁冲突重试耗尽。

### 7.4 测试

`BatchMigrationTest` 从**两侧夹住**独立事务语义（顺序 `[成功, 失败, 成功]`，终结中间那个实例使它必然迁移失败）：

- 失败实例**之前**的成果必须保住 → 防连坐回滚
- 失败实例**之后**的照常处理 → 防一处失败就中断整批

另覆盖：全部成功、未知实例 id 单独记失败、空列表。
