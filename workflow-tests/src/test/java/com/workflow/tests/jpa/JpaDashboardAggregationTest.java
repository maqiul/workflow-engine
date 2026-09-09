package com.workflow.tests.jpa;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.enums.InstanceStatus;
import com.workflow.repository.ProcessStatusCount;
import com.workflow.tests.JpaEngineTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JPA 版监控聚合(countGroupByProcessAndStatus / countPending)与引擎快照的一致性。
 * 守住 JPQL GROUP BY + 枚举映射不跑偏。
 */
@DisplayName("JPA 监控聚合一致性")
class JpaDashboardAggregationTest extends JpaEngineTestBase {

    private ProcessDefinition def() {
        return ProcessBuilder.create("jpa-dash")
                .start("start").userTask("apply", "申请", Candidate.ofAny("u1")).end("end")
                .connect("start", "apply").connect("apply", "end").build();
    }

    @Test
    @DisplayName("分组计数与待办 COUNT 与 dashboard 一致")
    void aggregationConsistent() {
        procRepo.save(def());
        engine.start("jpa-dash", Map.of());
        engine.start("jpa-dash", Map.of());

        List<ProcessStatusCount> groups = instRepo.countGroupByProcessAndStatus();
        long running = groups.stream()
                .filter(g -> g.status() == InstanceStatus.RUNNING)
                .mapToLong(ProcessStatusCount::count).sum();
        assertThat(running).isEqualTo(2);
        assertThat(groups.stream().mapToLong(ProcessStatusCount::count).sum()).isEqualTo(2);
        assertThat(taskRepo.countPending()).isEqualTo(2); // 两个实例各一张 apply 待办

        var m = engine.dashboard(10);
        assertThat(m.totalInstances()).isEqualTo(2);
        assertThat(m.pendingTasks()).isEqualTo(2);
        assertThat(m.instancesByStatus()).containsEntry("RUNNING", 2L);
    }
}
