# 自研工作流引擎 — 架构图

> 6 张图覆盖完整链路：模块依赖 / DSL 流程 / Domain 模型 / 引擎调度 / JPA 持久化 / 请求时序。
> 全部使用 Mermaid 语法（GitHub / VS Code / Typora / Obsidian 原生支持）。

---

## 图 1：模块依赖

展示 4 个 Gradle 模块的依赖关系 + 各模块的内部包结构。

```mermaid
flowchart TB
    subgraph Sample["workflow-sample (Demo)"]
        Demo["LeaveDemo.java"]
    end

    subgraph Tests["workflow-tests (单元测试)"]
        InMemTest["engine/SerialFlowTest<br/>ParallelGatewayTest<br/>SignStrategyTest<br/>RejectTransferTerminateTest"]
        JpaTest["jpa/JpaSerialFlowTest<br/>JpaParallelGatewayTest<br/>JpaSignStrategyTest<br/>JpaRejectTransferTerminateTest"]
        Base["EngineTestBase<br/>JpaEngineTestBase"]
    end

    subgraph Jpa["workflow-persistence-jpa (JPA 实现)"]
        JpaEntry["JpaPersistence.java<br/>(入口/工厂)"]
        JpaRepo["JpaProcessRepository<br/>JpaInstanceRepository<br/>JpaTaskRepository"]
        JpaEntity["WfProcessDefEntity<br/>WfInstanceEntity<br/>WfTokenEntity<br/>WfTaskEntity"]
        PXml["META-INF/persistence.xml<br/>(H2 + Hibernate)"]
    end

    subgraph Core["workflow-core (核心引擎 + 仓储接口)"]
        Builder["builder/ProcessBuilder<br/>(链式 DSL)"]
        Engine["engine/IWorkflowEngine<br/>engine/WorkflowEngine<br/>engine/GatewayKind<br/>engine/PathNavigator"]
        Definition["definition/ProcessDefinition<br/>definition/NodeDefinition<br/>definition/Transition<br/>definition/Candidate"]
        Runtime["runtime/ProcessInstance<br/>runtime/Token<br/>runtime/TaskInstance"]
        Enums["enums/NodeType<br/>enums/TaskStatus<br/>enums/InstanceStatus<br/>enums/TokenStatus<br/>enums/CandidateStrategy"]
        RepoIface["repository/ProcessRepository<br/>repository/InstanceRepository<br/>repository/TaskRepository"]
        RepoImpl["repository/InMemoryProcessRepository<br/>repository/InMemoryInstanceRepository<br/>repository/InMemoryTaskRepository"]
    end

    Demo --> Builder
    Demo --> Engine
    Demo --> RepoImpl

    InMemTest --> Base
    JpaTest --> Base
    Base --> Engine
    Base --> RepoIface
    JpaTest --> Jpa

    JpaEntry --> JpaRepo
    JpaRepo --> JpaEntity
    JpaRepo --> PXml

    Builder --> Definition
    Engine --> Definition
    Engine --> Runtime
    Engine --> RepoIface
    RepoImpl -.implements.-> RepoIface
    Definition --> Enums
    Runtime --> Enums
```

**关键点**：
- `workflow-core` 是叶子节点（不依赖任何其他模块）
- `workflow-sample` 与 `workflow-tests` 都通过 `repository/` 接口注入实现（依赖倒置）
- `workflow-persistence-jpa` 实现 core 的仓储接口,通过 `api(project(":workflow-core"))` 暴露

---

## 图 2：DSL 流程示例

请假审批 Demo 的 DSL 定义 + 运行时 Token 流转。

```mermaid
flowchart LR
    start([start]) --> apply[apply<br/>userTask<br/>ANY employee]
    apply --> manager[manager<br/>userTask<br/>ANY managerA, managerB]
    manager --> hr[hr<br/>userTask<br/>ANY hr]
    hr --> end([end])

    %% Token 流转示意
    T0[/"Token#0<br/>@start"/] -.->|advanceToken| T1[/"Token#1<br/>@apply"/]
    T1 -.->|completeTask<br/>employee| T2[/"Token#2<br/>@manager"/]
    T2 -.->|completeTask<br/>managerA| T3[/"Token#3<br/>@hr"/]
    T3 -.->|completeTask<br/>hr| T4[/"Token#4<br/>@end<br/>consumed"/]

    style T0 fill:#e1f5e1
    style T1 fill:#fff4e1
    style T2 fill:#fff4e1
    style T3 fill:#fff4e1
    style T4 fill:#f5e1e1
```

**并行网关版本（fork/join）**：

```mermaid
flowchart LR
    start([start]) --> apply[apply]
    apply --> fork{{"fork<br/>PARALLEL_GATEWAY"}}
    fork --> branchA[branchA<br/>userTask]
    fork --> branchB[branchB<br/>userTask]
    branchA --> join{{"join<br/>PARALLEL_GATEWAY"}}
    branchB --> join
    join --> end([end])

    T0[/"Token#0"/] -.->|fork| TA[/"Token#A<br/>@branchA"/]
    T0 -.->|fork| TB[/"Token#B<br/>@branchB"/]
    TA -.->|branchA 完成| TCA[/"Token#A<br/>consumed"/]
    TB -.->|branchB 完成| TCB[/"Token#B<br/>consumed"/]
    TCA -.->|join 汇聚| TNext[/"Token#Next<br/>@end"/]
    TCB -.->|join 汇聚| TNext

    style fork fill:#ffe1e1
    style join fill:#ffe1e1
```

---

## 图 3：Domain 模型（类图）

```mermaid
classDiagram
    class ProcessDefinition {
        +String key
        +String name
        +int version
        +Map~String,NodeDefinition~ nodes
        +Map~String,List~Transition~~ outgoing
        +NodeDefinition getNode(String id)
        +List~Transition~ getOutgoing(String fromId)
    }

    class NodeDefinition {
        +String id
        +String name
        +NodeType type
        +Candidate candidate
    }

    class Transition {
        +String from
        +String to
        +String condition
    }

    class Candidate {
        +CandidateStrategy strategy
        +List~String~ userIds
    }

    class ProcessInstance {
        +String id
        +String processKey
        +InstanceStatus status
        +long createTime
        +long endTime
        +Map~String,Token~ activeTokens
        +List~TaskInstance~ tasks
        +Map~String,Object~ variables
    }

    class Token {
        +String id
        +String instanceId
        +String currentNodeId
        +TokenStatus status
    }

    class TaskInstance {
        +String id
        +String instanceId
        +String tokenId
        +String nodeId
        +Candidate candidate
        +Set~String~ completedApprovers
        +TaskStatus status
        +boolean recordCompletion(String userId)
    }

    class NodeType {
        <<enum>>
        START
        END
        USER_TASK
        EXCLUSIVE_GATEWAY
        PARALLEL_GATEWAY
    }

    class InstanceStatus {
        <<enum>>
        RUNNING
        SUSPENDED
        COMPLETED
        TERMINATED
    }

    class TaskStatus {
        <<enum>>
        PENDING
        COMPLETED
        REJECTED
        TRANSFERRED
        TERMINATED
    }

    class TokenStatus {
        <<enum>>
        ACTIVE
        CONSUMED
    }

    class CandidateStrategy {
        <<enum>>
        ANY
        ALL
    }

    ProcessDefinition "1" --> "*" NodeDefinition : nodes
    ProcessDefinition "1" --> "*" Transition : outgoing
    NodeDefinition "1" --> "0..1" Candidate : candidate
    NodeDefinition --> NodeType

    ProcessInstance "1" --> "*" Token : activeTokens
    ProcessInstance "1" --> "*" TaskInstance : tasks
    ProcessInstance --> InstanceStatus
    Token --> TokenStatus
    TaskInstance "1" --> "1" Candidate
    TaskInstance --> TaskStatus
    Candidate --> CandidateStrategy

    note for ProcessDefinition "definition/ 包\n流程静态定义"
    note for ProcessInstance "runtime/ 包\n流程运行态"
```

**关键点**：
- `definition/` 是**静态结构**（不可变,编译期固定）
- `runtime/` 是**动态状态**（随任务流转变化）
- `TaskInstance.candidate` 与 `NodeDefinition.candidate` 是**同一对象引用**——避免冗余

---

## 图 4：引擎调度核心（`advanceToken` 状态机）

```mermaid
stateDiagram-v2
    [*] --> 推进Token: engine.start()<br/>新 Token @ start 节点

    state "取 currentNode = def.getNode(token.currentNodeId)" as 取节点

    推进Token --> 取节点

    state "判断 currentNode.type" as 判断类型

    取节点 --> 判断类型

    判断类型 --> END分支: NodeType.END
    判断类型 --> USER_TASK分支: NodeType.USER_TASK
    判断类型 --> EXCLUSIVE_GATEWAY分支: NodeType.EXCLUSIVE_GATEWAY
    判断类型 --> PARALLEL_GATEWAY分支: NodeType.PARALLEL_GATEWAY

    state END分支 {
        [*] --> consumeToken: instance.consumeToken(tokenId)<br/>状态变 COMPLETED
    }

    state USER_TASK分支 {
        [*] --> 查existing: existing = tasks<br/>.filter(tokenId, nodeId)<br/>.findFirst()

        查existing --> 创建新任务: existing == null
        查existing --> 推进Token: existing.status == COMPLETED
        查existing --> 跳过忽略: existing.status == PENDING

        state 创建新任务 {
            [*] --> 建TaskInstance
            建TaskInstance --> save: taskRepo.save(task)<br/>instanceRepo.save(instance)
            save --> [*]: 等用户操作
        }

        state 推进Token {
            [*] --> 取出口
            取出口 --> 单出口: outs.size == 1
            取出口 --> 多出口: outs.size > 1<br/>(异常:应走排他网关)

            单出口 --> 更新currentNode: token.currentNodeId = outs[0].to
            更新currentNode --> saveInstance: instanceRepo.save(instance)
            saveInstance --> 递归调用: advanceToken(instance, def, tokenId)
            递归调用 --> [*]
        }
    }

    state EXCLUSIVE_GATEWAY分支 {
        [*] --> 取首出口: 简化实现: outs[0]
        取首出口 --> 更新currentNode: token.currentNodeId = outs[0].to
        更新currentNode --> 递归调用: advanceToken
        递归调用 --> [*]
    }

    state PARALLEL_GATEWAY分支 {
        [*] --> 是否JOIN: GatewayKind.isJoin(def, id)
        是否JOIN --> JOIN汇聚: 是 (入度 > 1)
        是否JOIN --> FORK分裂: 否 (出度 > 1)

        state JOIN汇聚 {
            [*] --> consumeToken: instance.consumeToken(tokenId)
            consumeToken --> 检查兄弟: allJoinArrived()
            检查兄弟 --> 创建后续: 都到了<br/>新建 Token 走 outs[0]
            检查兄弟 --> 等待: 还没到<br/>instanceRepo.save(instance)
        }

        state FORK分裂 {
            [*] --> cloneToken: 克隆当前 Token
            cloneToken --> 创建N个新Token: 每个出口一个
            创建N个新Token --> 递归每个: advanceToken(instance, def, t.id)
        }
    }

    END分支 --> [*]: Token consumed
    跳过忽略 --> [*]
```

**关键路径**：
1. **start()** → 创建 Token @ START 节点 → advanceToken
2. **USER_TASK existing==null** → 创建 PENDING 任务 → 等用户操作
3. **completeTask** → task.recordCompletion + save + **同步 instance 视图** + advanceToken
4. **USER_TASK existing.COMPLETED** → 更新 token.currentNodeId → 递归 advanceToken

---

## 图 5：JPA 持久化（Domain ↔ Entity ↔ Table）

```mermaid
flowchart LR
    subgraph Domain["Domain 对象 (workflow-core)"]
        PD["ProcessDefinition"]
        PI["ProcessInstance"]
        T["Token"]
        TI["TaskInstance"]
    end

    subgraph Entity["JPA Entity (workflow-persistence-jpa)"]
        PDE["WfProcessDefEntity<br/>key_, name,<br/>start_node_id,<br/>nodes_json,<br/>outgoing_json,<br/>version"]
        PIE["WfInstanceEntity<br/>id, process_key,<br/>status, create_time,<br/>end_time,<br/>variables_json"]
        TE["WfTokenEntity<br/>id, instance_id,<br/>current_node_id,<br/>status"]
        TIE["WfTaskEntity<br/>id, instance_id,<br/>token_id, node_id,<br/>candidate_json,<br/>completed_approvers_json,<br/>status, create_time"]
    end

    subgraph Table["H2 表"]
        TPD[("wf_process_def")]
        TPI[("wf_instance")]
        TT[("wf_token")]
        TTI[("wf_task")]
    end

    PD -.->|JSON 序列化<br/>nodes / outgoing| PDE
    PI -.->|直接映射 +<br/>variables JSON| PIE
    T -.->|直接字段映射| TE
    TI -.->|candidate/<br/>completedApprovers<br/>JSON 化| TIE

    PDE --> TPD
    PIE --> TPI
    TE --> TT
    TIE --> TTI

    TPD -.->|save| PD
    TPI -.->|findById<br/>反射重建| PI
    TT -.->|附属于 PI| PI
    TTI -.->|附属于 PI| TI

    note1["反射写 final 字段<br/>(JDK 17 setAccessible)"]
    Domain -.- note1

    style Domain fill:#e1f0ff
    style Entity fill:#fff4e1
    style Table fill:#e1f5e1
```

**映射策略**：

| Domain | Entity | 映射方式 |
|---|---|---|
| `ProcessDefinition.nodes` | `WfProcessDefEntity.nodes_json` | `JSON.toJSONString` 序列化到 TEXT |
| `ProcessInstance.variables` | `WfInstanceEntity.variables_json` | fastjson2 序列化 |
| `TaskInstance.candidate` | `WfTaskEntity.candidate_json` | fastjson2 序列化 |
| `TaskInstance.completedApprovers` | `WfTaskEntity.completed_approvers_json` | fastjson2 序列化 |
| `TaskInstance.status` | `WfTaskEntity.status` | `@Enumerated(STRING)` 直接映射 |

**关键点**：
- Domain 对象**不可变 + 无 setter**——JPA 仓储用反射写 final 字段
- Token **不单独 save**——`instanceRepo.save` 内部全量同步（DELETE ALL + persist）
- Task **单独 save**（频繁 update 状态）——`taskRepo.save` upsert

---

## 图 6：一次 `completeTask` 的时序

展示 InMemory 与 JPA 的差异点（重点是 **JPA 下 instance 视图同步**）。

```mermaid
sequenceDiagram
    autonumber
    participant Client as 客户端代码
    participant Engine as WorkflowEngine
    participant TaskRepo as TaskRepository
    participant InstRepo as InstanceRepository
    participant DefRepo as ProcessRepository
    participant DB as 持久层<br/>(InMemory Map / JPA DB)

    Client->>Engine: completeTask(taskId, userId, true)

    rect rgba(255, 244, 225, 0.3)
        note over Engine, DB: 第 1 步:加载 task + 校验
        Engine->>TaskRepo: findById(taskId)
        TaskRepo->>DB: SELECT/GET
        DB-->>TaskRepo: task(PENDING)
        TaskRepo-->>Engine: task
        Engine->>Engine: ensureRunning(task)<br/>校验 candidate / status
    end

    rect rgba(225, 245, 225, 0.3)
        note over Engine, DB: 第 2 步:加载 instance 视图
        Engine->>InstRepo: findById(task.instanceId)
        InstRepo->>DB: SELECT/GET
        DB-->>InstRepo: instance + tasks[apply=PENDING]
        InstRepo-->>Engine: instance<br/>(apply 是 PENDING 对象)
    end

    Engine->>DefRepo: findByKey(processKey)
    DefRepo-->>Engine: ProcessDefinition

    rect rgba(255, 225, 225, 0.5)
        note over Engine, DB: 第 3 步:关键 — 同步 instance 视图<br/>(v2 子任务 4 修复)
        Engine->>Engine: 1. task.recordCompletion(userId)<br/>   → task.status = COMPLETED
        Engine->>Engine: 2. 同步 instance.tasks[apply]<br/>   → instanceTask.status = COMPLETED<br/>   → completedApprovers = {userId}
    end

    rect rgba(225, 240, 255, 0.3)
        note over Engine, DB: 第 4 步:持久化
        Engine->>TaskRepo: save(task)
        TaskRepo->>DB: UPDATE / persist (status=COMPLETED)
        Engine->>InstRepo: save(instance)
        InstRepo->>DB: 同步 tokens + (实例属性)
    end

    rect rgba(240, 225, 255, 0.3)
        note over Engine: 第 5 步:推进 Token
        Engine->>Engine: advanceToken(instance, def, applyTokenId)

        alt existing == null (首次到 apply 节点)
            Engine->>Engine: 创建 apply PENDING 任务
        else existing.status == COMPLETED (完成推进)
            Engine->>Engine: token.currentNodeId = outs[0].to
            Engine->>InstRepo: save(instance)
            Engine->>Engine: advanceToken 递归调用
        else existing.status == PENDING (异常路径)
            Engine->>Engine: 跳过 (debug 日志)
        end
    end
```

**InMemory vs JPA 关键差异**：

| 步骤 | InMemory | JPA |
|---|---|---|
| `taskRepo.findById` | 返回内存 Map 引用 | 返回 WfTaskEntity → 反射重建的 TaskInstance |
| `instanceRepo.findById` | 返回内存引用（同一 task 对象） | 返回 WfInstanceEntity + 重建的 TaskInstance（**新对象**） |
| `task.recordCompletion` | 修改内存对象 → instance.tasks[0] **自动跟随** | 修改 line 91 task → instance.tasks[0] **不跟随**（不同引用） |
| **必须同步 instance 视图** | 否 | **是**（v2 子任务 4 修复） |

---

## 📎 附录：图与代码的映射表

| 图 | 主要对应源码 |
|---|---|
| 图 1 模块依赖 | `settings.gradle.kts`, `build.gradle.kts` |
| 图 2 DSL 流程 | `builder/ProcessBuilder.java`, `sample/LeaveDemo.java` |
| 图 3 Domain 模型 | `definition/*`, `runtime/*`, `enums/*` |
| 图 4 引擎调度 | `engine/WorkflowEngine.java#advanceToken`, `engine/GatewayKind.java`, `engine/PathNavigator.java` |
| 图 5 JPA 映射 | `persistence-jpa/entity/*`, `persistence-jpa/repository/*` |
| 图 6 时序 | `engine/WorkflowEngine.java#completeAndAdvance` |

---

## 🔧 在各平台查看

| 平台 | 查看方式 |
|---|---|
| **GitHub** | 直接渲染 Mermaid,无需插件 |
| **VS Code** | 安装 `Markdown Preview Mermaid Support` 扩展 |
| **Typora** | 原生支持,无需设置 |
| **Obsidian** | 原生支持 |
| **IntelliJ IDEA** | 安装 `Markdown Navigator` 插件 |
| **在线预览** | [mermaid.live](https://mermaid.live/) 粘贴代码预览 |

---

_本文档随代码演进同步更新。_
