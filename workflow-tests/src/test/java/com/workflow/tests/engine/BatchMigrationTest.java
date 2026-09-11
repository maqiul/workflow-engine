package com.workflow.tests.engine;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.BatchResult;
import com.workflow.engine.WorkflowEngine;
import com.workflow.engine.WorkflowEngineBuilder;
import com.workflow.repository.InMemoryInstanceRepository;
import com.workflow.repository.InMemoryProcessRepository;
import com.workflow.repository.InMemoryTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批量实例迁移测试。
 *
 * <p>本组用例守的是「逐实例独立事务」这一条：某个实例失败，绝不能把已经迁成功的那些
 * 一起拖下水。这与 {@code batchTerminateInstances} 的全或无语义<b>正好相反</b> ——
 * 那边失败会抛 BatchPartialFailureException 让整个事务回滚，是既有约定；
 * 而批量迁移的诉求是「尽量多迁成功」，故有意做成独立事务。
 *
 * <p>两个用例从两侧夹住这条语义：
 * <ul>
 *   <li>「失败实例之前的」成果必须保住 —— 防连坐回滚；</li>
 *   <li>「失败实例之后的」照常处理 —— 防一处失败就中断整批。</li>
 * </ul>
 */
@DisplayName("批量实例迁移")
class BatchMigrationTest {

    private InMemoryProcessRepository procRepo;
    private InMemoryInstanceRepository instRepo;
    private InMemoryTaskRepository taskRepo;
    private WorkflowEngine engine;

    @BeforeEach
    void setUp() {
        procRepo = new InMemoryProcessRepository();
        instRepo = new InMemoryInstanceRepository();
        taskRepo = new InMemoryTaskRepository();
        engine = WorkflowEngineBuilder.builder(procRepo, instRepo, taskRepo).build();
    }

    /** v1：apply → manager → end */
    private ProcessDefinition v1() {
        return ProcessBuilder.create("leave-flow", "请假审批")
                .version(1)
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("user1"))
                .userTask("manager", "经理审批", Candidate.ofAny("manager1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "manager")
                .connect("manager", "end")
                .build();
    }

    /** v2：manager 之后多一个 director */
    private ProcessDefinition v2() {
        return ProcessBuilder.create("leave-flow", "请假审批")
                .version(2)
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("user1"))
                .userTask("manager", "经理审批", Candidate.ofAny("manager1"))
                .userTask("director", "总监审批", Candidate.ofAny("director1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "manager")
                .connect("manager", "director")
                .connect("director", "end")
                .build();
    }

    private void saveBothVersions() {
        procRepo.save(v1());
        procRepo.save(v2());
    }

    @Test
    @DisplayName("全部可迁移时，三个实例都成功")
    void allSucceed() {
        saveBothVersions();
        List<String> ids = List.of(
                engine.start("leave-flow", 1, Map.of()),
                engine.start("leave-flow", 1, Map.of()),
                engine.start("leave-flow", 1, Map.of()));

        BatchResult result = engine.migrateInstances(ids, "leave-flow", 2, Map.of(), "admin");

        assertThat(result.getTotal()).isEqualTo(3);
        assertThat(result.getSuccessCount()).isEqualTo(3);
        assertThat(result.getFailureCount()).isZero();
        assertThat(result.isAllSuccess()).isTrue();

        for (String id : ids) {
            assertThat(instRepo.findById(id).getProcessVersion())
                    .as("实例 %s 应已迁到 v2", id)
                    .isEqualTo(2);
        }
    }

    @Test
    @DisplayName("中间那个失败，前后两个照常 —— 不中断、也不连坐回滚")
    void oneFailureDoesNotStopOrRollBackTheRest() {
        saveBothVersions();

        String before = engine.start("leave-flow", 1, Map.of());
        String doomed = engine.start("leave-flow", 1, Map.of());
        String after = engine.start("leave-flow", 1, Map.of());

        // 终结中间那个：非 RUNNING 实例不可迁移，它必然失败
        engine.terminate(doomed);

        BatchResult result = engine.migrateInstances(
                List.of(before, doomed, after), "leave-flow", 2, Map.of(), "admin");

        assertThat(result.getTotal()).isEqualTo(3);
        assertThat(result.getSuccessCount()).isEqualTo(2);
        assertThat(result.getFailureCount()).isEqualTo(1);
        assertThat(result.isAllSuccess()).isFalse();

        // 失败者必须被点名，且带上可定位的原因
        assertThat(result.getFailures()).hasSize(1);
        BatchResult.FailureDetail failure = result.getFailures().get(0);
        assertThat(failure.getId()).isEqualTo(doomed);
        assertThat(failure.getErrorMessage()).contains("仅 RUNNING 实例可迁移");
        assertThat(failure.getExceptionType()).isEqualTo("IllegalStateException");

        // 排在其后的实例照迁不误 —— 证明单点失败没有中断整批
        assertThat(instRepo.findById(after).getProcessVersion())
                .as("失败实例之后的实例必须照常迁移")
                .isEqualTo(2);

        // 排在其前的实例成果保住 —— 证明没有被连坐回滚
        assertThat(instRepo.findById(before).getProcessVersion())
                .as("失败实例之前的实例成果必须保留，不被连坐回滚")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("混入不存在的实例 id：只记它一个失败，其余不受影响")
    void unknownInstanceIdIsRecordedAsFailure() {
        saveBothVersions();
        String good = engine.start("leave-flow", 1, Map.of());

        BatchResult result = engine.migrateInstances(
                List.of(good, "no-such-instance"), "leave-flow", 2, Map.of(), "admin");

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailureCount()).isEqualTo(1);
        assertThat(result.getFailures().get(0).getId()).isEqualTo("no-such-instance");
        assertThat(instRepo.findById(good).getProcessVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("空列表：0 个也算全部成功，不该当成错误")
    void emptyListIsAllSuccess() {
        saveBothVersions();

        BatchResult result = engine.migrateInstances(List.of(), "leave-flow", 2, Map.of(), "admin");

        assertThat(result.getTotal()).isZero();
        assertThat(result.getSuccessCount()).isZero();
        assertThat(result.getFailureCount()).isZero();
        assertThat(result.isAllSuccess()).isTrue();
        assertThat(result.getFailures()).isEmpty();
    }
}
