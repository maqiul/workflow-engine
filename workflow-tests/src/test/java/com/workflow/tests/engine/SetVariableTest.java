package com.workflow.tests.engine;

import com.workflow.definition.ProcessDefinition;
import com.workflow.definition.VariableDefinition;
import com.workflow.definition.VariableType;
import com.workflow.enums.AuditEventType;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
import com.workflow.runtime.AuditLog;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import com.workflow.tests.EngineTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 运行期变量写入（OA 集成缺口 #4）。
 *
 * <p><b>断言重心是「真的落库了」</b>：仓储是拷贝语义，
 * {@code engine.getInstance(id)} 拿到的是副本，改它等于没改。
 * 所以每个写入用例都必须<b>重新读一次</b>才能证明生效 ——
 * 直接断言手上那个对象，恰好是通不过的那种「假绿」。
 */
@DisplayName("运行期变量写入")
class SetVariableTest extends EngineTestBase {

    @BeforeEach
    void init() { setUp(); }

    /** 带变量 schema 的流程：amount 必填且为整数，reason 可选。 */
    private void registerTypedFlow() {
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
    }

    private String startTyped() {
        registerTypedFlow();
        return engine.start("typed-flow", Map.of("amount", 1000));
    }

    private TaskInstance pending(String instanceId, String nodeId) {
        return taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> nodeId.equals(t.getNodeId()))
                .filter(t -> t.getStatus() == TaskStatus.PENDING)
                .findFirst()
                .orElseThrow(() -> new AssertionError("节点无待办: " + nodeId));
    }

    // ---------- 基本写入 ----------

    @Test
    @DisplayName("写单个变量：重读实例可见（不是改了个副本）")
    void singleVariableIsPersisted() {
        String instanceId = startTyped();

        engine.setVariable(instanceId, "reason", "预算调整", "u1");

        assertThat(engine.getInstance(instanceId).getVariable("reason"))
                .isEqualTo("预算调整");
    }

    @Test
    @DisplayName("批量写：一次全部生效")
    void batchVariablesArePersisted() {
        String instanceId = startTyped();
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("amount", 2000);
        vars.put("reason", "追加预算");
        vars.put("note", "临时变量");

        engine.setVariables(instanceId, vars, "u1");

        ProcessInstance reloaded = engine.getInstance(instanceId);
        assertThat(reloaded.getVariable("amount")).isEqualTo(2000);
        assertThat(reloaded.getVariable("reason")).isEqualTo("追加预算");
        assertThat(reloaded.getVariable("note")).isEqualTo("临时变量");
    }

    @Test
    @DisplayName("值传 null = 清除该变量")
    void nullValueClearsVariable() {
        String instanceId = startTyped();
        engine.setVariable(instanceId, "reason", "先设上", "u1");
        assertThat(engine.getInstance(instanceId).getVariables()).containsKey("reason");

        engine.setVariable(instanceId, "reason", null, "u1");

        assertThat(engine.getInstance(instanceId).getVariables()).doesNotContainKey("reason");
    }

    @Test
    @DisplayName("改完的变量真的参与路由：下一次推进按新值走分支")
    void changedVariableDrivesRouting() {
        register(simple("route-flow")
                .start("start")
                .userTask("apply", "申请", any("u1"))
                .exclusiveGateway("gw")
                .userTask("fast", "快速通道", any("u2"))
                .userTask("slow", "慢通道", any("u2"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "gw")
                .connect("gw", "fast", "${urgent == true}")
                .connect("gw", "slow", "${urgent == false}")
                .connect("fast", "end")
                .connect("slow", "end")
                .build());
        String instanceId = engine.start("route-flow", Map.of("urgent", false));

        // 推进之前把闸门变量掰到另一支
        engine.setVariable(instanceId, "urgent", true, "u1");
        engine.completeTask(pending(instanceId, "apply").getId(), "u1", true);

        assertThat(taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING)
                .map(TaskInstance::getNodeId))
                .containsExactly("fast");
    }

    // ---------- 保留前缀（安全边界） ----------

    @Test
    @DisplayName("引擎内部变量一律拒写：`__` 前缀是安全边界，不是命名风格")
    void reservedPrefixIsRejected() {
        String instanceId = startTyped();

        // __initiator 决定"谁能撤回"，__mi_/__dynamic_ 决定节点幂等，__sub_ 决定子流程不重发
        for (String reserved : List.of("__initiator", "__sub_depth", "__sub_t1",
                "__mi_t1", "__dynamic_t1")) {
            assertThatThrownBy(() -> engine.setVariable(instanceId, reserved, "hacked", "u2"))
                    .as("保留变量 %s 必须拒写", reserved)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("保留前缀");
        }
    }

    @Test
    @DisplayName("伪造 __initiator 被拦在入口，发起人仍是原发起人")
    void initiatorCannotBeForged() {
        register(simple("withdraw-flow")
                .start("start")
                .userTask("apply", "申请", any("u1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build());
        String instanceId = engine.start("withdraw-flow", "emp1", Map.of());

        assertThatThrownBy(() -> engine.setVariable(instanceId, "__initiator", "emp2", "emp2"))
                .isInstanceOf(IllegalArgumentException.class);

        // 抢不了别人的流程：撤回仍只认原发起人
        assertThatThrownBy(() -> engine.withdraw(instanceId, "emp2"))
                .hasMessageContaining("发起人");
        engine.withdraw(instanceId, "emp1");
        assertThat(engine.getInstance(instanceId).getStatus())
                .isEqualTo(InstanceStatus.TERMINATED);
    }

    // ---------- 校验 ----------

    @Test
    @DisplayName("类型不符定义 → 拒绝")
    void typeMismatchIsRejected() {
        String instanceId = startTyped();

        assertThatThrownBy(() -> engine.setVariable(instanceId, "amount", "一千", "u1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("类型不匹配");
    }

    @Test
    @DisplayName("未在定义里声明的变量放行（运行时变量本就允许临时出现）")
    void undeclaredVariableIsAllowed() {
        String instanceId = startTyped();

        engine.setVariable(instanceId, "adHocFlag", true, "u1");

        assertThat(engine.getInstance(instanceId).getVariable("adHocFlag")).isEqualTo(true);
    }

    @Test
    @DisplayName("不复查必填：只改 reason 时，不该因 amount 不在本次入参里而失败")
    void requiredVariablesAreNotRechecked() {
        String instanceId = startTyped();

        // amount 是必填，但它在实例里、不在本次入参里。
        // 若实现拿整体 schema 再校一遍，这里会因为"amount 是必填项但未提供"而炸。
        engine.setVariable(instanceId, "reason", "只改这一个", "u1");

        assertThat(engine.getInstance(instanceId).getVariable("reason"))
                .isEqualTo("只改这一个");
    }

    @Test
    @DisplayName("空批量 → 快速失败，不做静默 no-op")
    void emptyBatchIsRejected() {
        String instanceId = startTyped();

        assertThatThrownBy(() -> engine.setVariables(instanceId, Map.of(), "u1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }

    // ---------- 状态与存在性 ----------

    @Test
    @DisplayName("实例不存在 → 拒绝")
    void unknownInstanceIsRejected() {
        registerTypedFlow();

        assertThatThrownBy(() -> engine.setVariable("no-such-instance", "reason", "x", "u1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("已结束实例 → 拒绝（事后改数据会污染审计与报表）")
    void endedInstanceIsRejected() {
        String instanceId = startTyped();
        engine.terminate(instanceId);

        assertThatThrownBy(() -> engine.setVariable(instanceId, "reason", "补一刀", "u1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RUNNING");
    }

    @Test
    @DisplayName("挂起中可改：挂起暂停的是推进，不是数据")
    void suspendedInstanceIsWritable() {
        String instanceId = startTyped();
        engine.suspend(instanceId);

        engine.setVariable(instanceId, "reason", "挂起期间补正", "u1");

        assertThat(engine.getInstance(instanceId).getVariable("reason"))
                .isEqualTo("挂起期间补正");
        assertThat(engine.getInstance(instanceId).getStatus())
                .isEqualTo(InstanceStatus.SUSPENDED);
    }

    // ---------- 审计 ----------

    @Test
    @DisplayName("审计记下新旧值；operator 缺省记系统")
    void auditRecordsOldValue() {
        String instanceId = startTyped();

        engine.setVariable(instanceId, "amount", 2000, "u1");
        engine.setVariable(instanceId, "reason", "无签字", null);

        List<AuditLog> logs = auditLogRepo.findByInstanceId(instanceId);
        AuditLog first = logs.get(logs.size() - 2);
        assertThat(first.getEventType()).isEqualTo(AuditEventType.VARIABLE_UPDATED);
        assertThat(first.getOperator()).isEqualTo("u1");
        assertThat(first.getDetail()).contains("amount=2000").contains("原 1000");

        AuditLog second = logs.get(logs.size() - 1);
        assertThat(second.getOperator()).as("operator 缺省不该在审计里留空").isEqualTo("__system__");
    }
}
