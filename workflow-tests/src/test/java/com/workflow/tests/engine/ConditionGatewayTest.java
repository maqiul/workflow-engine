package com.workflow.tests.engine;

import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import com.workflow.tests.EngineTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 条件网关(EXCLUSIVE_GATEWAY + condition)测试
 *
 * 覆盖:
 *  - 数值比较分支选择
 *  - 字符串比较分支选择
 *  - 逻辑表达式(&& / || / !)
 *  - 默认出口兜底
 *  - 无匹配且无默认出口 -> 终止(不卡死)
 *  - 表达式语法错误 -> 抛异常
 */
class ConditionGatewayTest extends EngineTestBase {

    @BeforeEach
    void init() { setUp(); }

    @Test
    void should_route_by_numeric_condition() {
        ProcessDefinition def = simple("gw-num")
                .start("start")
                .userTask("apply", "申请", any("u1"))
                .exclusiveGateway("gw")
                .userTask("small", "小额审批", any("u2"))
                .userTask("large", "大额审批", any("u3"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "gw")
                .connect("gw", "small", "amount < 1000")
                .connect("gw", "large", "amount >= 1000")
                .connect("small", "end")
                .connect("large", "end")
                .build();
        register(def);

        // 小额 -> 走 small
        String id1 = engine.start("gw-num", Map.of("amount", 500));
        engine.completeTask(firstPendingTask(engine, id1), "u1", true);
        ProcessInstance inst1 = engine.getInstance(id1);
        assertThat(inst1.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(inst1.getTasks()).anyMatch(t -> t.getNodeId().equals("small")
                && t.getStatus() == TaskStatus.PENDING);

        // 大额 -> 走 large
        String id2 = engine.start("gw-num", Map.of("amount", 5000));
        engine.completeTask(firstPendingTask(engine, id2), "u1", true);
        ProcessInstance inst2 = engine.getInstance(id2);
        assertThat(inst2.getTasks()).anyMatch(t -> t.getNodeId().equals("large")
                && t.getStatus() == TaskStatus.PENDING);
    }

    @Test
    void should_route_by_string_and_logic_condition() {
        ProcessDefinition def = simple("gw-str")
                .start("start")
                .userTask("apply", "申请", any("u1"))
                .exclusiveGateway("gw")
                .userTask("special", "特殊审批", any("u2"))
                .userTask("normal", "普通审批", any("u3"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "gw")
                .connect("gw", "special", "type == 'special' && days >= 5")
                .connect("gw", "normal", "type != 'special' || days < 5")
                .connect("special", "end")
                .connect("normal", "end")
                .build();
        register(def);

        // 特殊类型 + 天数够 -> special
        String id1 = engine.start("gw-str", Map.of("type", "special", "days", 7));
        engine.completeTask(firstPendingTask(engine, id1), "u1", true);
        ProcessInstance inst1 = engine.getInstance(id1);
        assertThat(inst1.getTasks()).anyMatch(t -> t.getNodeId().equals("special"));

        // 普通类型 -> normal
        String id2 = engine.start("gw-str", Map.of("type", "normal", "days", 3));
        engine.completeTask(firstPendingTask(engine, id2), "u1", true);
        ProcessInstance inst2 = engine.getInstance(id2);
        assertThat(inst2.getTasks()).anyMatch(t -> t.getNodeId().equals("normal"));
    }

    @Test
    void should_use_default_outgoing_when_no_condition_matches() {
        ProcessDefinition def = simple("gw-default")
                .start("start")
                .userTask("apply", "申请", any("u1"))
                .exclusiveGateway("gw")
                .userTask("big", "大额", any("u2"))
                .userTask("other", "其他", any("u3"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "gw")
                .connect("gw", "big", "amount > 1000")
                .connect("gw", "other")          // 无条件 = 默认出口
                .connect("big", "end")
                .connect("other", "end")
                .build();
        register(def);

        // amount=100 不满足 >1000 -> 走默认 other
        String id = engine.start("gw-default", Map.of("amount", 100));
        engine.completeTask(firstPendingTask(engine, id), "u1", true);
        ProcessInstance inst = engine.getInstance(id);
        assertThat(inst.getTasks()).anyMatch(t -> t.getNodeId().equals("other")
                && t.getStatus() == TaskStatus.PENDING);
    }

    @Test
    void should_consume_token_when_no_matching_outgoing() {
        ProcessDefinition def = simple("gw-none")
                .start("start")
                .userTask("apply", "申请", any("u1"))
                .exclusiveGateway("gw")
                .userTask("big", "大额", any("u2"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "gw")
                .connect("gw", "big", "amount > 1000")
                .connect("big", "end")
                .build();
        register(def);

        // 没有匹配条件 -> 不卡死,Token 被消耗,实例结束(无 PENDING 任务)
        String id = engine.start("gw-none", Map.of("amount", 100));
        engine.completeTask(firstPendingTask(engine, id), "u1", true);
        ProcessInstance inst = engine.getInstance(id);
        assertThat(inst.getActiveTokens()).isEmpty();
        assertThat(inst.getTasks()).noneMatch(t -> t.getStatus() == TaskStatus.PENDING);
    }

    @Test
    void should_throw_on_invalid_condition_syntax() {
        ProcessDefinition def = simple("gw-bad")
                .start("start")
                .userTask("apply", "申请", any("u1"))
                .exclusiveGateway("gw")
                .userTask("big", "大额", any("u2"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "gw")
                .connect("gw", "big", "amount >>> 1000")   // 非法运算符
                .connect("big", "end")
                .build();
        register(def);

        String id = engine.start("gw-bad", Map.of("amount", 5000));
        assertThatThrownBy(() -> engine.completeTask(firstPendingTask(engine, id), "u1", true))
                .isInstanceOf(RuntimeException.class);
    }

    // ========== 辅助 ==========

    private static String firstPendingTask(com.workflow.engine.WorkflowEngine engine, String instanceId) {
        ProcessInstance inst = engine.getInstance(instanceId);
        for (TaskInstance t : inst.getTasks()) {
            if (t.getStatus() == TaskStatus.PENDING) {
                return t.getId();
            }
        }
        throw new IllegalStateException("实例 " + instanceId + " 无 PENDING 任务");
    }
}
