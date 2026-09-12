# CHANGELOG

自研工作流引擎（workflow-engine）变更日志。格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

项目状态：**v3.19.0**
- v3.15.0 — 进生产底盘加固（① 超时调度重启恢复 ✅ / ② REST 鉴权 ✅ / ③ 集群乐观锁 ✅ / ④ 批量迁移 ✅）
- v3.16.0 — Flowable BPMN 导入兼容性加固（修硬失败 / 消除会签静默降级 / 未知属性不再静默丢弃）
- v3.17.0 — 候选组组织架构支持（模型层 `groupIds` / 展开失败即抛出 / 导出往返对称 / 管理员改派通道）
- v3.18.0 — 发布最后一公里（版本号唯一来源 / 真实可解析的发布坐标 / 异常基类 / nightly 真库验证）
- v3.19.0 — 嵌入式集成（接入宿主 DataSource 与事务 / 独立 Flyway 历史表 / 连接池不再传递给消费方）

---

## [3.19.0] - 2026-09-12

定位：**把引擎嵌进宿主**。前三版解决的是「引擎自己跑得对不对」，这一版解决的是
「引擎在别人的进程、别人的连接池、别人的事务里跑得对不对」。

> ⚠️ **修的是一个数据事故级的缺口**：引擎此前**接不进宿主的连接池** ——
> `MybatisPersistence` 私有构造 + 自建 Hikari，宿主开 `@Transactional` 调引擎时，
> 引擎从自己的池里另取一条连接，两边是**两笔独立事务** → 宿主回滚不会回滚引擎写入，
> 留下「宿主业务失败但流程已推进」的脏数据，且无法自愈。

### 新增

- **`com.workflow.tx.ExternalTransactionProvider`**：宿主事务资源 SPI（引擎保持零 Spring 依赖）。
  实现 `currentConnection()`，返回宿主当前事务的连接、无事务时返回 `null` 即可；
  Spring 侧适配器约 10 行（`TransactionSynchronizationManager` + `DataSourceUtils`）。
- **`MybatisPersistence.withDataSource(DataSource)` / `withDataSource(ds, migrate)`**：
  嵌入式集成入口 —— 用宿主的连接池，引擎不再自建；`close()` 也不会关掉宿主的池。
  第二个重载传 `false` 表示「DDL 由宿主管，不要迁移」。
- **`FlywayMigrator.migrate(DataSource, String historyTable)`**：可指定 schema history 表名。

### 修复

- **引擎写入不并入宿主事务**：`inSession` 改为三分支 —— ① 宿主事务连接
  （引擎**不提交、不回滚、不关闭**它）→ ② 引擎自身事务 → ③ 独立短事务。
- **同库集成时与宿主 Flyway 抢历史表**：`withDataSource()` 默认用引擎专属的
  `wf_schema_history`。此前两者共用 `flyway_schema_history` —— 宿主已有的 `V1__xxx`
  会让引擎的 `V1__init` 被判为「已执行」而**静默跳过建表**，或直接抛
  「Found more than one migration with version」。
- **`close()` 会关掉宿主的连接池**：改为只关引擎自建的池（按 `AutoCloseable` 语义，不绑 Hikari 类型）。

### 变更

- **HikariCP / H2 由 `implementation` 降为 `compileOnly`**：不再作为运行时依赖传给消费方。
  宿主用 Spring Boot 3.5 时它管的是 HikariCP **6.3**，而引擎带的是 **5.1.0** ——
  Maven 的 nearest-wins 会让宿主用上低版本（`NoSuchMethodError` 风险）。
  走 `withDataSource()` 的集成方式本就不需要引擎的池；独立/演示用法请自行引入连接池（见 README §5.6）。

### 测试

- 新增 `MybatisExternalTransactionTest` 4 用例：宿主事务内引擎写入对外不可见 / 宿主回滚写入一并消失 /
  宿主提交写入保留且流程可在同一事务内推进 / **对照组复现旧缺口**（未注入 provider 时宿主回滚管不到引擎写入）。
- 本轮 `--rerun-tasks` 实测：**PASSED 434 / FAILED 0 / ERRORS 0 / SKIPPED 35**（总 469，跨库需 Docker）。
- **口径更正**：`[3.18.0]` 那条记的「PASSED 355 / 总 390」是**漏统计**（少了 75 个用例），
  当时据此覆盖了 v3.17.0 的旧数字 —— 方向反了。正确基线是 v3.17.0 的 **430 / 35（总 465）**：
  本轮 `434 - 4(新增用例) = 430` 逐项吻合，可复核。

### 设计要点

- **复用宿主的连接，但不共享宿主的 `SqlSessionFactory`**：宿主的插件链里没有
  `OptimisticLockerInnerInterceptor`，共享工厂会让引擎 `@Version` 乐观锁**静默失效**
  —— 并发写冲突无人拦截，比事务问题更难发现。引擎自建工厂、只借连接。
- **引擎不碰宿主连接的 commit / rollback / close**：事务边界归宿主，引擎只把 SQL 下发到那条连接上。
- **旧入口零变化**：`init()` / `init(url, user, pass)` 与未注入 provider 时的行为都和 v3.18 完全一致。

---

## [3.18.0] - 2026-09-12

定位：**「给别人用」的最后一公里**。功能面早已完整（49 项能力 / 三套仓储 / BPMN 双向 / REST），
这一版补的是别人接手时会立刻绊倒的地方 —— 版本号、发布产物、异常契约、真库的持续验证。

> ⚠️ **行为变更（REST 状态码）**：引擎抛出的可预期失败不再一律返回 500。
> `BpmnException`（流程定义非法）→ **400**；其余 `WorkflowException` 子类
> （候选组展不出人、拿不到实例锁、批量部分失败）→ **409**。
> 此前它们全都落到兜底的 `catch (RuntimeException)` → 500 + 一条 error 日志，
> 调用方看到"服务内部错误"只能去翻服务器日志，而问题其实就摆在自己提交的输入里。
> **升级要求**：靠 500 判断"服务器故障"来触发告警的调用方需同步调整 ——
> 正确做法是判断 4xx/5xx 区间，而不是钉住 500。

### 新增

- **`com.workflow.WorkflowException`**：引擎「可预期失败」的共同基类。调用方终于能写
  `catch (WorkflowException e)` 作统一边界 —— 在此之前只能 `catch (RuntimeException)`，
  连引擎自己的空指针也一并被当成业务失败咽下去。已继承者：
  `BpmnException` / `WorkflowConflictException` / `BatchPartialFailureException` /
  `GroupResolutionException` / `LocalInstanceLocks.LockAcquisitionException`
- **`maven-publish` 发布能力**：5 个库模块产出可用坐标
  （`workflow-core` / `workflow-persistence-flyway` / `workflow-persistence-jpa` /
  `workflow-persistence-mybatis` / `workflow-rest`）。
  `publishToMavenLocal` 或 `publish`（→ `build/local-repo`）；sample 与 tests 不发布
- **`gradle.properties` 的 `projectVersion`**：版本号唯一来源

### 修复

- **文档里的坐标解析不了**：README / FEATURES 写着 `implementation("com.workflow:workflow-core:3.16.0")`，
  但构建里根本没有 `maven-publish` —— 照抄一行就卡在依赖解析
- **版本号漂移 9 个版本**：构建脚本硬编码 `3.8.0`，而项目已到 3.17.0。
  漂移能持续这么久，是因为没有任何机制把它和 CHANGELOG / 发布坐标绑在一起
- **POM scope 泄漏（消费方编译期必踩）**：`JpaPersistence` 的公开签名暴露 `EntityManager`
  （`bindCurrentEm`、`currentEm`、`inTransaction(em -> ...)`），`MybatisPersistence` 暴露
  `SqlSession` / `SqlSessionFactory`，而对应依赖声明为 `implementation` → POM 里落到 `runtime` scope。
  消费方编译自己那行 lambda 就会报 `cannot access EntityManager`。改用 `api`
- **REST 层 4 类失败被误报成 500**：见上方行为变更

### 变更

- **移除 hutool**：全 7 模块主代码与测试对 `cn.hutool` 的引用数为 **0**，
  但根构建一直把它注入每个模块、并作为 `runtime` scope 传给消费方（`hutool-all` 约 2.4 MB）——
  消费方为一份从未被调用的库白背了一个包。README 里「工具用 hutool」的宣称同步更正，
  国产化口径改为 fastjson2 + MyBatis-Plus（这两个都在真用）
- **CI 增加 nightly 跨库 job**：`cross-db` 在每日 UTC 02:00 与手动触发时跑真库套件
  （JPA×MySQL / JPA×PostgreSQL / MyBatis×MySQL / MyBatis×PostgreSQL），
  并把实际用例数写进 Job Summary —— 只报"绿"不报跑了几条是危险的，
  "跨库全绿"完全可能是 0 条用例在跑。push/PR 门禁仍用 `-PskipCrossDb=true` 保持快

### 测试

- 本轮 `--rerun-tasks` 实测：**PASSED 355 / FAILED 0 / ERRORS 0 / SKIPPED 35**（跨库，本机无 Docker）
- 真库的持续保障改由 nightly 承担；跨库用例的绿不再依赖"某人某天手动跑过一次"

### 设计要点

- **异常分界划在"谁能处理"上**：调用方可以理解、也应当处理的失败 → `WorkflowException`；
  用错 API、或引擎自己坏了 → 保持 JDK 原生异常类型不动。
  把编程错误伪装成业务失败，只会诱使调用方加个 catch 把 bug 吞掉 —— 那比不分类更糟
- **REST 里 3 个内部异常刻意不继承**：`ResourceNotFound` / `BadRequest` / `UnsupportedOperation`
  是 HTTP 状态映射的私有控制流，不属于引擎失败。把它们混进业务异常体系，
  "可预期失败"这个边界就失去意义了
- **不做全模块 `api` 化**：`fastjson2` 的 `@JSONCreator`/`@JSONField` 确实出现在 `NodeDefinition`
  等公开类型上，但注解不参与消费方编译；强行 `api` 会把 `slf4j`、`logback` 一类基础库一并推成编译期依赖，
  给调用方增加无谓的类路径负担。只在**签名真的暴露了类型**时（JPA / MyBatis 那两处）才用 `api`
- **版本号只留一个入口**：`gradle.properties`。多一个入口就多一次漂移的机会，
  而漂移是**不会被测试发现**的那类错误

---

## [3.17.0] - 2026-09-11

定位：**候选组（`candidateGroups`）组织架构支持** —— 从模型层到三套仓储完整打通，并修掉这条链上被将就过的历史缺陷。

> ⚠️ **行为变更（组展开失败）**：候选组无法展开成具体用户时，引擎现在**抛出 `GroupResolutionException`**，
> 不再"保留未展开任务 + 告警"。旧策略换来的是**静默死锁** —— `start` 返回成功、界面显示"进行中"，
> 实际谁都办不了；而它标榜的兜底路径（`transferTask`）在未展开时恰恰**走不通**（转办同样要求发起人是候选人），
> 等于承诺了一条不存在的退路。
> **升级要求**：定义里含候选组的流程**必须注入 `GroupResolver`**，否则启动即失败（这是有意的 ——
> 部署期没接组织架构属配置错误，要在实例落库之前暴露）。存量"未展开任务"请用 `adminTransferTask` 处理。

### 修复

- **ALL 会签永久死锁**：`completedApprovers.containsAll(candidate.getUserIds())` —— 组名混进候选用户后该条件**永远无法满足**，任务卡死。现按展开后的用户集合判定
- **`Candidate.ofAny/ofAll` 重复元素即崩**：内部 `Set.of(...)` 遇重复直接抛 `IllegalArgumentException`。组展开结果与显式用户极易重叠，撞上引擎就崩。改用 `LinkedHashSet` 收集
- **`candidateGroups` 组名被当成用户**：导入器把组名塞进 `userIds`，任务对任何人都不可办。现映射进独立的 `groupIds`
- **导出往返丢组**：`BpmnExporter` 只写 `userIds`，`candidateGroups` 往返后消失。现用户与组分别写回，往返对称
- **转办路径的隐性死锁**：`transferTask` 要求发起人本身是候选人，而**未展开的任务谁都转办不了**
- **`Candidate` 缺 `equals`/`hashCode`**：对象比较永远不等

### 新增

- **`Candidate.groupIds`**：候选人区分「用户」与「组」两个维度；`isUnresolved()` / `explainRejection()` 给出可定位的拒因
- **`GroupResolver`**：组织架构解析钩子 —— 引擎**不内置**组织架构存储，只留接缝
- **`CandidateExpander`**：任务创建时把组展开成**候选快照**；原始组名同时保留，供审计与「我所在组」查询
- **`GroupResolutionException`**：携带 `nodeId` + 未展开的组名，直接定位到节点，不用翻日志
- **`WorkflowEngine.adminTransferTask(taskId, toUserId, operator)`**：绕过候选人校验的管理员兜底通道；必须记录 `operator` 进审计
- **`CandidateCodec`**：`candidate_json` 统一编解码，兼容无 `groupIds` 的历史数据
- **三套仓储 + `TaskQuery` 支持按组查询**：「我所在组的待办」此前查不出来（只看 `getUserIds()`）

### 设计要点

- **失败即抛出，而不是降级告警**：无论是导入还是运行期，最危险的失败都是静默降级 —— 抛异常至少是响的。两条落地细节：① 未配 `GroupResolver` 且定义含组 → 在**实例落库之前**预检拦下（零成本、无脏数据）；② 引擎 `exclusive` 本身是事务边界，其余展开失败**整体回滚**，调用方可安全重试
- **部分静默失败最难查**：某节点有"好组 + 坏组"时不做"只丢坏组"的处理（那样 `managers` 有人可办、`broken` 无声消失），整体失败并点名是哪个组
- **组不携带策略**：策略是节点级的。"组 ALL" = 展开全员 + 节点 ALL（全员会签），"组 ANY" = 展开 + 组内任一可办。不为组引入第二套策略，也就不存在两套策略打架
- **展开结果是快照**：只在任务创建时解析一次，此后组成员变动不影响在途任务 —— 实时解析会让事后追责链漂移
- **管理员通道不做权限判断**：引擎不知道调用方的权限模型，这与不替调用方决定组织架构**是同一条边界**，鉴权属调用方责任
- **`Set.of` 的教训**：凡"把外部数据收进集合"的地方都该用 `LinkedHashSet` —— `Set.of` 撞重复即抛，而外部数据天然可能重复

### 测试

- 新增 `GroupExpansionTest`（展开 / 空组 / 无 resolver / 部分故障 / 组名自陷 / 老数据 / 管理员改派）、`CandidateCodecTest`、`BpmnGroupRoundTripTest`
- 修正 `FlowableImportCompatibilityTest` 中被固化的旧断言（把"组名当用户"当成了正确行为）
- **全量回归 0 失败**：PASSED 430 / FAILED 0 / SKIPPED 35（总 465，`--rerun-tasks` 实测）

---

## [3.16.0] - 2026-09-11

定位：**Flowable BPMN 导入兼容性加固** —— 修掉三类缺陷：导入硬失败、静默降级、静默丢弃。

> ⚠️ **行为变更（导入语义）**：带 `flowable:collection` 的 `userTask` 现在映射为 `MULTI_INSTANCE` 节点。
> 早先这类节点被当成「单人动态指派」导入 —— 会签静默退化成单人任务，流程照跑但语义错。
> 若已依赖旧行为，升级后需重新核对此类节点。

### 修复

- **`flowable:candidateGroups` / `flowable:candidateUsers` 硬失败**：导入器只认自有 `wf:candidate` 扩展，遇到 Flowable 标准属性直接抛 `BpmnException`，整份定义导不进来。现两者都映射为候选池（ANY 策略）
- **静态 `flowable:assignee="zhangsan"` 硬失败**：早先只处理 `${var}` 动态形式，静态值掉进 candidate 分支后抛错
- **多实例会签被静默降级**：`multiInstanceLoopCharacteristics` + `flowable:collection="${var}"` 此前被完全忽略，只取了同节点的 `flowable:assignee` —— 于是一个「按集合展开的会签」被导入成「单人动态指派」：**导入成功、无任何报错、流程照跑，但会签没了**
- **未知属性 / 元素静默丢弃**：`flowable:formKey` / `taskListener` / `priority` 以及 `scriptTask` 等不支持的节点，此前无声无息地消失

### 新增

- **`BpmnImportDiagnostics`**（导入诊断）：约定是「能映射的映射，不能映射的一律记下来」。`importFrom(xml, diag)` 把语义降级交给调用方决定接受 / 告警 / 拒绝；单参 `importFrom(xml)` 保持兼容，只写日志。动机：导入器最危险的失败不是抛异常而是静默降级 —— 抛异常至少是响的
- **导入侧 `multiInstanceLoopCharacteristics` 支持**：读 `flowable:collection` → `MULTI_INSTANCE`；`completionCondition` 为 `nrOfCompletedInstances >= 1` 判或签（ANY），其余（含缺省）判会签（ALL）
- **导出侧 `MULTI_INSTANCE` 支持**：`BpmnExporter` 此前对该类型抛 `UnsupportedOperationException`，现导出为 `multiInstanceLoopCharacteristics flowable:collection="${var}"` + 完成条件，导入导出**往返对称**

### 设计要点

- **`candidateGroups` 的组名原样保留，不展开**：引擎不做组织架构解析（Flowable 同样把 group 交给 `ACT_ID_` 表 + `IdentityService`）。静默丢弃是数据损失，比语义不精确更糟 —— 组名照存 + 诊断点名，需调用方展开
- **区分「真 Flowable 多实例」与「自有格式往返」靠 `flowable:collection` 的有无**：本引擎导出 `USER_TASK + candidate` 时只写 `wf:cardinality`、从不写 `flowable:collection`，两种格式不会互相误判。这条判别线是让改动安全的关键
- **`flowable:elementVariable` 不需映射**：引擎按人为单位建任务，天然具备该语义
- **顺序多实例（`isSequential="true"`）产生诊断而非报错**：引擎的 `MULTI_INSTANCE` 一次展开全部任务（并行），顺序性未保留 —— 能跑但不精确，属于该让人知道的事
- **被忽略的节点 id 参与报错**：不支持的节点跳过后，指向它的连线会让 `build()` 报「转移终点不存在」——一句让人摸不着头脑的话。现在把被忽略的节点 id 一并点出来

### 测试

- **`FlowableImportCompatibilityTest`**（14 用例）：静态 / 动态 assignee、`candidateUsers`、`candidateGroups`、assignee 与候选池并存时的优先级、`flowable:collection` 映射、或签 / 会签判定、往返对称、顺序多实例诊断、未知属性诊断、未知元素诊断、被忽略节点导致校验失败时的报错内容、无审批人来源时的错误可操作性
- **修正 `FlowableSampleImportTest` 中被固化的错误断言**：真实样本 `customer_order_flow.bpmn` 的 `edraft_submit` 曾被测成「动态 assignee 单人任务」——把静默降级当成了正确行为。现断言其为 `MULTI_INSTANCE` + `edraft_submitApprovers` + ANY，并额外断言真实样本导入**零语义降级警告**
- **全量回归 411 用例 0 失败**：PASSED 411 / FAILED 0 / SKIPPED 35（`--rerun-tasks` 实测）

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

### ④ 批量迁移

#### 新增
- **`IWorkflowEngine#migrateInstances(instanceIds, targetProcessKey, targetVersion, nodeMapping, operator)`**
  —— **逐实例独立事务**：每个实例各自提交，某个失败不回滚已成功的那些，失败仅记入返回结果的 `failures`
- 复用既有 `BatchResult` / `BatchResult.FailureDetail`（与 `batchTerminateInstances` 同一套结果类型）

#### 语义差异（有意为之）
- `batchTerminateInstances`：**全或无** —— 有失败即抛 `BatchPartialFailureException` 让整个事务回滚
- `migrateInstances`：**逐个提交、部分成功保留** —— 批量迁移的诉求是"尽量多迁成功"，
  运维要的是"哪几个没成、各自为什么"，而非拿到第一个异常、剩下的动没动全靠猜

#### 测试
- **`BatchMigrationTest`**（4 用例）从两侧夹住"独立事务"这条语义：失败实例**之前**的成果必须保住（防连坐回滚）、
  失败实例**之后**的照常处理（防一处失败就中断整批）—— 顺序 `[成功, 失败, 成功]`，
  终结中间那个实例使其非 RUNNING，从而必然迁移失败
- 另覆盖：全部成功、未知实例 id 单独记失败、空列表
- **全量回归（`gradle build --rerun-tasks`）：397 PASSED / 0 FAILED / 35 SKIPPED**

### 迁移脚本归一

- `V8__add_arrival_for_loop_support.sql` 原先错放在 `workflow-persistence-mybatis` 模块自己的 `resources` 下，
  已**原样**挪回 `workflow-persistence-flyway`（mybatis 的 `src/main/resources/db` 整个删除，build 残留同步清理）
- **一个字节都没改**：Flyway 的 checksum 只认内容，改注释会让存量库校验失败；而 `script` 路径
  （`db/migration/xxx.sql`）不含模块名，故移动本身不影响已应用的记录
- 至此迁移脚本只有一个家 —— 版本号才靠得住，不会再出现两处各一份 V8 的撞号

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