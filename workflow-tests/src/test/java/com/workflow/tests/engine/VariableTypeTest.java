package com.workflow.tests.engine;

import com.workflow.definition.ProcessDefinition;
import com.workflow.definition.VariableDefinition;
import com.workflow.definition.VariableType;
import com.workflow.engine.WorkflowEngine;
import com.workflow.enums.InstanceStatus;
import com.workflow.runtime.ProcessInstance;
import com.workflow.tests.EngineTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 流程变量强类型测试
 */
class VariableTypeTest extends EngineTestBase {

    @BeforeEach
    void init() { setUp(); }

    @Test
    void should_pass_validation_when_variables_match_schema() {
        // 定义流程，包含变量 schema
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

        // 传入符合 schema 的变量
        Map<String, Object> vars = new HashMap<>();
        vars.put("amount", 1000);
        vars.put("reason", "出差");

        String instanceId = engine.start("var-test", vars);

        ProcessInstance instance = engine.getInstance(instanceId);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(instance.getVariable("amount")).isEqualTo(1000);
        assertThat(instance.getVariable("reason")).isEqualTo("出差");
    }

    @Test
    void should_fail_validation_when_required_variable_missing() {
        // 定义必填变量
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

        // 未传入必填变量
        Map<String, Object> vars = new HashMap<>();

        assertThatThrownBy(() -> engine.start("var-required", vars))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount")
                .hasMessageContaining("必填");
    }

    @Test
    void should_use_default_value_when_variable_not_provided() {
        // 定义带默认值的变量
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

        // 未传入变量，应使用默认值
        Map<String, Object> vars = new HashMap<>();
        String instanceId = engine.start("var-default", vars);

        ProcessInstance instance = engine.getInstance(instanceId);
        assertThat(instance.getVariable("priority")).isEqualTo("normal");
    }

    @Test
    void should_fail_validation_when_type_mismatch() {
        // 定义 INTEGER 类型变量
        ProcessDefinition def = simple("var-type")
                .start("start")
                .userTask("apply", "申请", any("user1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .variable("amount", VariableType.INTEGER)
                .build();
        register(def);

        // 传入错误类型
        Map<String, Object> vars = new HashMap<>();
        vars.put("amount", "not-a-number");

        assertThatThrownBy(() -> engine.start("var-type", vars))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount")
                .hasMessageContaining("类型不匹配");
    }

    @Test
    void should_pass_validation_when_no_schema_defined() {
        // 不定义变量 schema
        ProcessDefinition def = simple("no-schema")
                .start("start")
                .userTask("apply", "申请", any("user1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        register(def);

        // 传入任意变量，应通过
        Map<String, Object> vars = new HashMap<>();
        vars.put("anything", "goes");
        vars.put("number", 123);

        String instanceId = engine.start("no-schema", vars);
        ProcessInstance instance = engine.getInstance(instanceId);
        assertThat(instance.getVariable("anything")).isEqualTo("goes");
        assertThat(instance.getVariable("number")).isEqualTo(123);
    }

    @Test
    void should_support_all_variable_types() {
        // 定义各种类型的变量
        ProcessDefinition def = simple("all-types")
                .start("start")
                .userTask("apply", "申请", any("user1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .variable("str", VariableType.STRING)
                .variable("int", VariableType.INTEGER)
                .variable("long", VariableType.LONG)
                .variable("double", VariableType.DOUBLE)
                .variable("bool", VariableType.BOOLEAN)
                .variable("obj", VariableType.OBJECT)
                .build();
        register(def);

        Map<String, Object> vars = new HashMap<>();
        vars.put("str", "hello");
        vars.put("int", 42);
        vars.put("long", 123456789L);
        vars.put("double", 3.14);
        vars.put("bool", true);
        vars.put("obj", new Object());  // 任意对象

        String instanceId = engine.start("all-types", vars);
        ProcessInstance instance = engine.getInstance(instanceId);
        assertThat(instance.getVariables()).hasSize(6);
    }

    @Test
    void should_allow_integer_for_long_type() {
        // LONG 类型应接受 Integer
        ProcessDefinition def = simple("long-accept-int")
                .start("start")
                .userTask("apply", "申请", any("user1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .variable("bigNumber", VariableType.LONG)
                .build();
        register(def);

        Map<String, Object> vars = new HashMap<>();
        vars.put("bigNumber", 42);  // Integer，不是 Long

        String instanceId = engine.start("long-accept-int", vars);
        ProcessInstance instance = engine.getInstance(instanceId);
        assertThat(instance.getVariable("bigNumber")).isEqualTo(42);
    }

    @Test
    void should_allow_numeric_for_double_type() {
        // DOUBLE 类型应接受 Integer/Long
        ProcessDefinition def = simple("double-accept-numeric")
                .start("start")
                .userTask("apply", "申请", any("user1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .variable("price", VariableType.DOUBLE)
                .build();
        register(def);

        Map<String, Object> vars = new HashMap<>();
        vars.put("price", 100);  // Integer

        String instanceId = engine.start("double-accept-numeric", vars);
        ProcessInstance instance = engine.getInstance(instanceId);
        assertThat(instance.getVariable("price")).isEqualTo(100);
    }
}
