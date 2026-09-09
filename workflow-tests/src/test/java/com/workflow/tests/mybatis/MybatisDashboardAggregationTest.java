package com.workflow.tests.mybatis;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.enums.InstanceStatus;
import com.workflow.repository.ProcessStatusCount;
import com.workflow.tests.MybatisEngineTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MyBatis 版监控聚合一致性。
 * 守住 QueryWrapper.selectMaps + GROUP BY + selectCount(列标签大小写/H2 转大写)不跑偏。
 */
@DisplayName("MyBatis 监控聚合一致性")
class MybatisDashboardAggregationTest extends MybatisEngineTestBase {

    private ProcessDefinition def() {
        return ProcessBuilder.create("mb-dash")
                .start("start").userTask("apply", "申请", Candidate.ofAny("u1")).end("end")
                .connect("start", "apply").connect("apply", "end").build();
    }

    @Test
    @DisplayName("分组计数与待办 COUNT 与 dashboard 一致")
    void aggregationConsistent() {
        procRepo.save(def());
        engine.start("mb-dash", Map.of());
        engine.start("mb-dash", Map.of());

        List<ProcessStatusCount> groups = instRepo.countGroupByProcessAndStatus();
        long running = groups.stream()
                .filter(g -> g.status() == InstanceStatus.RUNNING)
                .mapToLong(ProcessStatusCount::count).sum();
        assertThat(running).isEqualTo(2);
        assertThat(taskRepo.countPending()).isEqualTo(2);

        var m = engine.dashboard(10);
        assertThat(m.totalInstances()).isEqualTo(2);
        assertThat(m.pendingTasks()).isEqualTo(2);
        assertThat(m.instancesByStatus()).containsEntry("RUNNING", 2L);
    }
}
