package com.workflow.tests.crossdb;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.WorkflowEngine;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
import com.workflow.repository.InstanceRepository;
import com.workflow.repository.ProcessRepository;
import com.workflow.repository.TaskRepository;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 跨数据库核心用例 - 同一份用例在 MySQL / PostgreSQL 下验证
 *
 * 子类负责:
 *  - 静态初始化持久化(JPA 或 MyBatis-Plus + 目标数据库容器)
 *  - 提供 procRepo / instRepo / taskRepo
 *  - clearTables() 清理表数据
 *  - {@code @BeforeAll} 首行调用 {@link #requireDocker()}
 *
 * 覆盖功能域:串行 / 并行网关 / 会签 / 驳回 / 转办 / 终止 / 挂起 /
 * 条件网关 / 版本管理 / 子流程 / 非候选拦截
 */
public abstract class AbstractCrossDbTest {

    protected static ProcessRepository procRepo;
    protected static InstanceRepository instRepo;
    protected static TaskRepository taskRepo;

    protected WorkflowEngine engine;

    /**
     * Docker 不可用时把整类标记为 <b>skipped</b> 而非 <b>failed</b>。
     *
     * <p>本机没装 / 没起 Docker 属于环境缺失，不代表引擎有回归。若不显式拦截，
     * {@code MYSQL.start()} 抛出的 IllegalStateException 会被 JUnit 记作
     * {@code initializationError} + FAILED，让 CI 在无法运行它的机器上假红。
     *
     * <p><b>探测本身也会炸</b>，所以这里连探测异常一起兜：Testcontainers 的
     * {@code DockerMachineClient} 解析 PATH 时不认条目尾部的空格
     * （GitHub Desktop 会把 {@code C:\...\GitHubDesktop\bin } 写进 PATH），
     * 于是 {@code isDockerAvailable()} 直接抛 {@code InvalidPathException}，
     * 照样变成假红。「探测不出来」和「探测出来没有」对用例而言是同一件事 ——
     * 都是这台机器上跑不了，一律按 skip 处理。
     *
     * <p>必须在子类的 {@code @BeforeAll} <b>第一行</b>调用，早于任何容器启动。
     */
    protected static void requireDocker() {
        boolean available;
        try {
            available = DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException | LinkageError ex) {
            available = false;
        }
        Assumptions.assumeTrue(available, "本机无可用 Docker，跳过 Testcontainers 跨库用例");
    }

    @BeforeEach
    void setUpEngine() {
        clearTables();
        engine = new WorkflowEngine(procRepo, instRepo, taskRepo);
    }

    /** 由子类实现:清空 4 张表 */
    protected abstract void clearTables();

    // ---------- 工具 ----------

    protected ProcessDefinition register(ProcessDefinition def) {
        procRepo.save(def);
        return def;
    }

    protected ProcessBuilder simple(String key) {
        return ProcessBuilder.create(key);
    }

    protected Candidate any(String... users) {
        return Candidate.ofAny(users);
    }

    protected Candidate all(String... users) {
        return Candidate.ofAll(users);
    }

    private TaskInstance firstPending(WorkflowEngine engine, String instanceId) {
        ProcessInstance inst = engine.getInstance(instanceId);
        return inst.getTasks().stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING)
                .findFirst()
                .orElseThrow(() -> new AssertionError("无待办任务, tasks=" + inst.getTasks()));
    }

    private ProcessInstance findChild(ProcessInstance parent) {
        for (Map.Entry<String, Object> e : parent.getVariables().entrySet()) {
            if (e.getKey().startsWith("__sub_") && !e.getKey().equals("__sub_depth")) {
                return engine.getInstance(e.getValue().toString());
            }
        }
        throw new AssertionError("未找到子流程实例, variables=" + parent.getVariables());
    }

    // ---------- 1. 串行流程 ----------

    @Test
    void serial_flow_advances() {
        register(simple("serial")
                .start("start")
                .userTask("apply", "申请", any("u1"))
                .userTask("manager", "经理审批", any("u2"))
                .userTask("hr", "人事确认", any("u3"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "manager")
                .connect("manager", "hr")
                .connect("hr", "end")
                .build());

        String id = engine.start("serial", Map.of("reason", "年假"));
        assertThat(engine.getInstance(id).getTasks()).hasSize(1);

        engine.completeTask(firstPending(engine, id).getId(), "u1", true);
        engine.completeTask(firstPending(engine, id).getId(), "u2", true);
        engine.completeTask(firstPending(engine, id).getId(), "u3", true);

        assertThat(engine.getInstance(id).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    // ---------- 2. 并行网关 ----------

    @Test
    void parallel_fork_join() {
        register(simple("para")
                .start("start")
                .userTask("apply", "申请", any("u1"))
                .parallelGateway("fork")
                .userTask("review1", "评审1", any("u2"))
                .userTask("review2", "评审2", any("u3"))
                .parallelGateway("join")
                .userTask("final", "终审", any("u4"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "fork")
                .connect("fork", "review1")
                .connect("fork", "review2")
                .connect("review1", "join")
                .connect("review2", "join")
                .connect("join", "final")
                .connect("final", "end")
                .build());

        String id = engine.start("para", Map.of());
        engine.completeTask(firstPending(engine, id).getId(), "u1", true);

        ProcessInstance inst = engine.getInstance(id);
        assertThat(inst.getTasks().stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING)).hasSize(2);
        assertThat(inst.getActiveTokens()).hasSize(2);

        engine.completeTask(firstPending(engine, id).getId(), "u2", true);
        // 分支1完成,join 等待分支2
        assertThat(engine.getInstance(id).getStatus()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(engine.getInstance(id).getTasks().stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING)).hasSize(1);
        engine.completeTask(firstPending(engine, id).getId(), "u3", true);
        // 两条分支汇合 -> 终审
        engine.completeTask(firstPending(engine, id).getId(), "u4", true);
        assertThat(engine.getInstance(id).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(engine.getInstance(id).getActiveTokens()).isEmpty();
    }

    // ---------- 3. 会签(ANY / ALL) ----------

    @Test
    void sign_strategies() {
        register(simple("sign-any")
                .start("start")
                .userTask("any1", "或签", any("u1", "u2"))
                .end("end")
                .connect("start", "any1")
                .connect("any1", "end")
                .build());
        register(simple("sign-all")
                .start("start")
                .userTask("all1", "会签", all("u1", "u2"))
                .end("end")
                .connect("start", "all1")
                .connect("all1", "end")
                .build());

        // ANY:一人通过即过
        String id1 = engine.start("sign-any", Map.of());
        engine.completeTask(firstPending(engine, id1).getId(), "u1", true);
        assertThat(engine.getInstance(id1).getStatus()).isEqualTo(InstanceStatus.COMPLETED);

        // ALL:两人都通过才过
        String id2 = engine.start("sign-all", Map.of());
        engine.completeTask(firstPending(engine, id2).getId(), "u1", true);
        assertThat(engine.getInstance(id2).getStatus()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(engine.getInstance(id2).getTasks()).hasSize(1);
        engine.completeTask(firstPending(engine, id2).getId(), "u2", true);
        assertThat(engine.getInstance(id2).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    // ---------- 4. 驳回 / 转办 / 终止 / 挂起 ----------

    @Test
    void reject_transfer_terminate() {
        register(simple("ops")
                .start("start")
                .userTask("apply", "申请", any("u1"))
                .userTask("review", "审批", any("u2"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "review")
                .connect("review", "end")
                .build());

        // 驳回:回到上一节点重新生成待办
        String id1 = engine.start("ops", Map.of());
        engine.completeTask(firstPending(engine, id1).getId(), "u1", true);
        String reviewTask = firstPending(engine, id1).getId();
        engine.rejectTask(reviewTask, "u2", "材料不全");
        ProcessInstance inst1 = engine.getInstance(id1);
        assertThat(inst1.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(inst1.getTasks().stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING)
                .map(TaskInstance::getNodeId)).containsExactly("apply");

        // 转办:待办转给新用户
        String id2 = engine.start("ops", Map.of());
        engine.completeTask(firstPending(engine, id2).getId(), "u1", true);
        String review2 = firstPending(engine, id2).getId();
        engine.transferTask(review2, "u2", "u3");
        engine.completeTask(firstPending(engine, id2).getId(), "u3", true);
        assertThat(engine.getInstance(id2).getStatus()).isEqualTo(InstanceStatus.COMPLETED);

        // 终止:实例关闭,待办取消(已完成任务保持 COMPLETED)
        String id3 = engine.start("ops", Map.of());
        engine.terminate(id3);
        ProcessInstance inst3 = engine.getInstance(id3);
        assertThat(inst3.getStatus()).isEqualTo(InstanceStatus.TERMINATED);
        assertThat(inst3.getTasks().stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING)).isEmpty();
        assertThat(inst3.getTasks()).allMatch(t -> t.getStatus() == TaskStatus.TERMINATED);

        // 挂起/恢复
        String id4 = engine.start("ops", Map.of());
        engine.suspend(id4);
        assertThat(engine.getInstance(id4).getStatus()).isEqualTo(InstanceStatus.SUSPENDED);
        engine.resume(id4);
        assertThat(engine.getInstance(id4).getStatus()).isEqualTo(InstanceStatus.RUNNING);
    }

    @Test
    void non_candidate_rejected() {
        register(simple("guard")
                .start("start")
                .userTask("t1", "任务", any("u1"))
                .end("end")
                .connect("start", "t1")
                .connect("t1", "end")
                .build());
        String id = engine.start("guard", Map.of());
        String taskId = firstPending(engine, id).getId();
        assertThatThrownBy(() -> engine.completeTask(taskId, "u99", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("u99");
    }

    // ---------- 5. 条件网关 ----------

    @Test
    void condition_gateway_routes() {
        register(simple("cond")
                .start("start")
                .userTask("apply", "申请", any("u1"))
                .exclusiveGateway("gw")
                .userTask("small", "小额", any("u2"))
                .userTask("large", "大额", any("u3"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "gw")
                .connect("gw", "small", "amount < 1000")
                .connect("gw", "large", "amount >= 1000")
                .connect("small", "end")
                .connect("large", "end")
                .build());

        String id1 = engine.start("cond", Map.of("amount", 500));
        engine.completeTask(firstPending(engine, id1).getId(), "u1", true);
        assertThat(engine.getInstance(id1).getTasks().stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING)
                .map(TaskInstance::getNodeId)).containsExactly("small");

        String id2 = engine.start("cond", Map.of("amount", 5000));
        engine.completeTask(firstPending(engine, id2).getId(), "u1", true);
        assertThat(engine.getInstance(id2).getTasks().stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING)
                .map(TaskInstance::getNodeId)).containsExactly("large");
    }

    // ---------- 6. 版本管理 ----------

    @Test
    void version_isolation() {
        register(simple("v")
                .version(1)
                .start("start")
                .userTask("v1t", "V1任务", any("u1"))
                .end("end")
                .connect("start", "v1t")
                .connect("v1t", "end")
                .build());
        register(simple("v")
                .version(2)
                .start("start")
                .userTask("v2t", "V2任务", any("u2"))
                .end("end")
                .connect("start", "v2t")
                .connect("v2t", "end")
                .build());

        assertThat(procRepo.findByKey("v").getVersion()).isEqualTo(2);

        String id1 = engine.start("v", 1, Map.of());
        assertThat(engine.getInstance(id1).getTasks().get(0).getNodeId()).isEqualTo("v1t");
        String id2 = engine.start("v", 2, Map.of());
        assertThat(engine.getInstance(id2).getTasks().get(0).getNodeId()).isEqualTo("v2t");

        // 两实例互不影响
        engine.completeTask(firstPending(engine, id1).getId(), "u1", true);
        assertThat(engine.getInstance(id1).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(engine.getInstance(id2).getStatus()).isEqualTo(InstanceStatus.RUNNING);
    }

    // ---------- 7. 子流程嵌入 ----------

    @Test
    void sub_process_embedded() {
        register(simple("sub")
                .start("start")
                .userTask("review", "子流程审批", any("u2"))
                .end("end")
                .connect("start", "review")
                .connect("review", "end")
                .build());
        register(simple("main")
                .start("start")
                .userTask("apply", "提交", any("u1"))
                .subProcess("sub1", "子流程", "sub")
                .end("end")
                .connect("start", "apply")
                .connect("apply", "sub1")
                .connect("sub1", "end")
                .build());

        String mainId = engine.start("main", Map.of());
        engine.completeTask(firstPending(engine, mainId).getId(), "u1", true);

        ProcessInstance child = findChild(engine.getInstance(mainId));
        assertThat(child.isSubProcess()).isTrue();
        assertThat(child.getTasks()).hasSize(1);
        assertThat(child.getTasks().get(0).getNodeId()).isEqualTo("review");

        engine.completeTask(child.getTasks().get(0).getId(), "u2", true);
        assertThat(engine.getInstance(child.getId()).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(engine.getInstance(mainId).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }
}
