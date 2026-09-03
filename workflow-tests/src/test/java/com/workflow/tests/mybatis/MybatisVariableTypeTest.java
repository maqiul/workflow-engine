package com.workflow.tests.mybatis;

import com.workflow.definition.ProcessDefinition;
import com.workflow.definition.VariableDefinition;
import com.workflow.definition.VariableType;
import com.workflow.enums.InstanceStatus;
import com.workflow.runtime.ProcessInstance;
import com.workflow.tests.MybatisEngineTestBase;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MyBatis 仓储下的变量强类型测试
 */
class MybatisVariableTypeTest extends MybatisEngineTestBase {

    @Test
    void should_pass_validation_when_variables_match_schema() {
        ProcessDefinition def = simple("var-test")
                .start("start")
                .userTask("apply", "申请", any("user1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .variable("amount", VariableType.INTEGER)
                .variable("reason", VariableType.STRING)
                .build();
        register(def);

        Map<String, Object> vars = new HashMap<>();
        vars.put("amount", 1000);
        vars.put("reason", "出差");

        String instanceId = engine.start("var-test", vars);
        ProcessInstance instance = engine.getInstance(instanceId);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(instance.getVariable("amount")).isEqualTo(1000);
    }

    @Test
    void should_fail_validation_when_required_variable_missing() {
        ProcessDefinition def = simple("var-required")
                .start("start")
                .userTask("apply", "申请", any("user1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .variable(VariableDefinition.builder("amount", VariableType.INTEGER)
                        .required(true)
                        .build())
                .build();
        register(def);

        Map<String, Object> vars = new HashMap<>();

        assertThatThrownBy(() -> engine.start("var-required", vars))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
    }

    @Test
    void should_use_default_value_when_variable_not_provided() {
        ProcessDefinition def = simple("var-default")
                .start("start")
                .userTask("apply", "申请", any("user1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .variable(VariableDefinition.builder("priority", VariableType.STRING)
                        .defaultValue("normal")
                        .build())
                .build();
        register(def);

        Map<String, Object> vars = new HashMap<>();
        String instanceId = engine.start("var-default", vars);
        ProcessInstance instance = engine.getInstance(instanceId);
        assertThat(instance.getVariable("priority")).isEqualTo("normal");
    }

    @Test
    void should_fail_validation_when_type_mismatch() {
        ProcessDefinition def = simple("var-type")
                .start("start")
                .userTask("apply", "申请", any("user1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .variable("amount", VariableType.INTEGER)
                .build();
        register(def);

        Map<String, Object> vars = new HashMap<>();
        vars.put("amount", "not-a-number");

        assertThatThrownBy(() -> engine.start("var-type", vars))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("类型不匹配");
    }
}
