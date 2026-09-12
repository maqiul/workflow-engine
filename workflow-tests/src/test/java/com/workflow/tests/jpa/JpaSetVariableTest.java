package com.workflow.tests.jpa;

import com.workflow.definition.VariableDefinition;
import com.workflow.definition.VariableType;
import com.workflow.enums.AuditEventType;
import com.workflow.enums.InstanceStatus;
import com.workflow.runtime.AuditLog;
import com.workflow.tests.JpaEngineTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 运行期变量写入（JPA 仓储）。
 *
 * <p>与 InMemory 版共用同一组语义断言，重点在<b>跨库一致</b>：
 * 仓储实现不同不该让同一个 API 表现不同。其中 {@code null = 清除} 一条最容易分叉 ——
 * 若把 null 直接写进 variables map，JSON 序列化会把它丢掉（key 消失），
 * 而 InMemory 会保留 key，于是同一个调用在两套仓储下读出两种结果。
 */
@DisplayName("运行期变量写入（JPA）")
class JpaSetVariableTest extends JpaEngineTestBase {

    private String startTyped() {
        register(simple("typed-flow")
                .start("start")
                .userTask("apply", "申请", any("u1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .variable(VariableDefinition.builder("amount", VariableType.INTEGER)
                        .required(true).build())
                .variable(VariableDefinition.builder("reason", VariableType.STRING).build())
                .build());
        return engine.start("typed-flow", Map.of("amount", 1000));
    }

    /** 直查原生列 —— 绕过 ORM 的一级缓存，证明写的是库而不是内存里的实体。 */
    @SuppressWarnings("unchecked")
    private String rawVariablesJson(String instanceId) {
        return jpa.inTransaction(em -> {
            List<Object> rows = em.createNativeQuery(
                            "SELECT variables_json FROM wf_instance WHERE id = ?1")
                    .setParameter(1, instanceId)
                    .getResultList();
            return rows.isEmpty() ? null : String.valueOf(rows.get(0));
        });
    }

    @Test
    @DisplayName("写单个变量：重读可见，且原生列里确实有它")
    void singleVariableIsPersisted() {
        String instanceId = startTyped();

        engine.setVariable(instanceId, "reason", "预算调整", "u1");

        assertThat(engine.getInstance(instanceId).getVariable("reason"))
                .isEqualTo("预算调整");
        assertThat(rawVariablesJson(instanceId))
                .as("变量必须落到 wf_instance.variables_json 列里").contains("预算调整");
    }

    @Test
    @DisplayName("批量写：一次全部生效")
    void batchVariablesArePersisted() {
        String instanceId = startTyped();
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("amount", 2000);
        vars.put("reason", "追加预算");

        engine.setVariables(instanceId, vars, "u1");

        assertThat(engine.getInstance(instanceId).getVariable("amount")).isEqualTo(2000);
        assertThat(engine.getInstance(instanceId).getVariable("reason")).isEqualTo("追加预算");
    }

    @Test
    @DisplayName("值传 null = 清除该变量（key 真消失，不是留个 null 占位）")
    void nullValueClearsVariable() {
        String instanceId = startTyped();
        engine.setVariable(instanceId, "reason", "先设上", "u1");
        assertThat(engine.getInstance(instanceId).getVariables()).containsKey("reason");

        engine.setVariable(instanceId, "reason", null, "u1");

        assertThat(engine.getInstance(instanceId).getVariables()).doesNotContainKey("reason");
        assertThat(rawVariablesJson(instanceId)).doesNotContain("先设上");
    }

    @Test
    @DisplayName("引擎保留前缀一律拒写")
    void reservedPrefixIsRejected() {
        String instanceId = startTyped();

        assertThatThrownBy(() -> engine.setVariable(instanceId, "__initiator", "hacked", "u2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("保留前缀");
        assertThatThrownBy(() -> engine.setVariable(instanceId, "__mi_t1", "expanded", "u2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("保留前缀");
    }

    @Test
    @DisplayName("类型不符定义 → 拒绝")
    void typeMismatchIsRejected() {
        String instanceId = startTyped();

        assertThatThrownBy(() -> engine.setVariable(instanceId, "amount", "一千", "u1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("类型不匹配");
    }

    @Test
    @DisplayName("已结束实例 → 拒绝")
    void endedInstanceIsRejected() {
        String instanceId = startTyped();
        engine.terminate(instanceId);

        assertThatThrownBy(() -> engine.setVariable(instanceId, "reason", "补一刀", "u1"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(engine.getInstance(instanceId).getStatus())
                .isEqualTo(InstanceStatus.TERMINATED);
    }

    @Test
    @DisplayName("审计落库：记下新旧值与操作人")
    void auditRecordsOldValue() {
        String instanceId = startTyped();

        engine.setVariable(instanceId, "amount", 2000, "u1");

        List<AuditLog> logs = auditLogRepo.findByInstanceId(instanceId);
        AuditLog last = logs.get(logs.size() - 1);
        assertThat(last.getEventType()).isEqualTo(AuditEventType.VARIABLE_UPDATED);
        assertThat(last.getOperator()).isEqualTo("u1");
        assertThat(last.getDetail()).contains("amount=2000").contains("原 1000");
    }
}
