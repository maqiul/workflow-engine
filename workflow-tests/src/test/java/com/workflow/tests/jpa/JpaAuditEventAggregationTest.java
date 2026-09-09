package com.workflow.tests.jpa;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.enums.AuditEventType;
import com.workflow.repository.EventTypeCount;
import com.workflow.tests.JpaEngineTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JPA 版审计事件类型分组计数聚合与 dashboard 一致性。
 * 守住 JPQL `WHERE eventType LIKE 'TIMEOUT_%' GROUP BY eventType` 不跑偏。
 */
@DisplayName("JPA 审计事件聚合一致性")
class JpaAuditEventAggregationTest extends JpaEngineTestBase {

    private ProcessDefinition def() {
        return ProcessBuilder.create("jpa-audit")
                .start("start").userTask("apply", "申请", Candidate.ofAny("u1")).end("end")
                .connect("start", "apply").connect("apply", "end").build();
    }

    @Test
    @DisplayName("审计事件分组计数与 dashboard 一致")
    void auditEventAggregationConsistent() {
        procRepo.save(def());
        String instanceId = engine.start("jpa-audit", Map.of());
        engine.completeTask(engine.getInstance(instanceId).getTasks().get(0).getId(), "u1", true);

        // 触发超时事件（手动写审计日志模拟）
        auditLogRepo.save(new com.workflow.runtime.AuditLog(
                instanceId, null, AuditEventType.TIMEOUT_AUTO_APPROVED, "system", "超时自动通过"));
        auditLogRepo.save(new com.workflow.runtime.AuditLog(
                instanceId, null, AuditEventType.TIMEOUT_AUTO_APPROVED, "system", "超时自动通过"));

        List<EventTypeCount> counts = auditLogRepo.countGroupByEventTypePrefix("TIMEOUT_");
        assertThat(counts).hasSize(1);
        assertThat(counts.get(0).eventType()).isEqualTo(AuditEventType.TIMEOUT_AUTO_APPROVED);
        assertThat(counts.get(0).count()).isEqualTo(2);

        var m = engine.dashboard(10);
        assertThat(m.timeoutEvents()).containsEntry("TIMEOUT_AUTO_APPROVED", 2L);
    }
}
