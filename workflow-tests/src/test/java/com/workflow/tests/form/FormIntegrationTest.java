package com.workflow.tests.form;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.FormSubmitResult;
import com.workflow.engine.WorkflowEngine;
import com.workflow.engine.WorkflowEngineBuilder;
import com.workflow.form.*;
import com.workflow.repository.InMemoryInstanceRepository;
import com.workflow.repository.InMemoryProcessRepository;
import com.workflow.repository.InMemoryTaskRepository;
import com.workflow.runtime.TaskInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 表单集成测试
 */
@DisplayName("表单集成")
class FormIntegrationTest {

    private InMemoryProcessRepository procRepo;
    private InMemoryInstanceRepository instRepo;
    private InMemoryTaskRepository taskRepo;
    private InMemoryFormRepository formRepo;
    private InMemoryFormBindingRepository formBindingRepo;
    private WorkflowEngine engine;

    @BeforeEach
    void setUp() {
        procRepo = new InMemoryProcessRepository();
        instRepo = new InMemoryInstanceRepository();
        taskRepo = new InMemoryTaskRepository();
        formRepo = new InMemoryFormRepository();
        formBindingRepo = new InMemoryFormBindingRepository();
        
        engine = WorkflowEngineBuilder.builder(procRepo, instRepo, taskRepo)
                .formRepository(formRepo)
                .formBindingRepository(formBindingRepo)
                .build();
    }

    @Test
    @DisplayName("获取任务表单 - 成功获取")
    void getFormForTask_success() {
        // 创建流程定义
        ProcessDefinition def = ProcessBuilder.create("form-flow-1")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("user1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        procRepo.save(def);

        // 创建表单定义
        List<FormField> fields = Arrays.asList(
                new FormField("name", "姓名", FormField.FieldType.TEXT, true,
                        null, "请输入姓名", null, null, null, null, "applicantName"),
                new FormField("age", "年龄", FormField.FieldType.NUMBER, false,
                        null, "请输入年龄", null, null, null, null, "applicantAge")
        );
        FormDefinition form = new FormDefinition("form1", "申请表单", "用于申请", 1,
                fields, null, null);
        formRepo.save(form);

        // 创建表单绑定
        FormBinding binding = new FormBinding("binding1", "form-flow-1", "apply", "form1",
                Map.of("name", "applicantName", "age", "applicantAge"),
                Map.of("applicantName", "name", "applicantAge", "age"),
                FormBinding.SubmitAction.COMPLETE_TASK);
        formBindingRepo.save(binding);

        // 启动流程
        String instanceId = engine.start("form-flow-1", Map.of());
        TaskInstance task = taskRepo.findByInstanceId(instanceId).get(0);

        // 获取表单
        FormDefinition retrievedForm = engine.getFormForTask(task.getId());

        // 验证
        assertThat(retrievedForm).isNotNull();
        assertThat(retrievedForm.getId()).isEqualTo("form1");
        assertThat(retrievedForm.getFields()).hasSize(2);
        assertThat(retrievedForm.getFields().get(0).getId()).isEqualTo("name");
    }

    @Test
    @DisplayName("获取任务表单 - 未绑定表单")
    void getFormForTask_noBinding() {
        ProcessDefinition def = ProcessBuilder.create("form-flow-2")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("user1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        procRepo.save(def);

        String instanceId = engine.start("form-flow-2", Map.of());
        TaskInstance task = taskRepo.findByInstanceId(instanceId).get(0);

        FormDefinition form = engine.getFormForTask(task.getId());
        assertThat(form).isNull();
    }

    @Test
    @DisplayName("提交表单 - 成功提交")
    void submitForm_success() {
        // 创建流程定义
        ProcessDefinition def = ProcessBuilder.create("form-flow-3")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("user1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        procRepo.save(def);

        // 创建表单定义
        List<FormField> fields = Arrays.asList(
                new FormField("name", "姓名", FormField.FieldType.TEXT, true,
                        null, null, null, null, null, null, "applicantName")
        );
        FormDefinition form = new FormDefinition("form2", "申请表单", null, 1,
                fields, null, null);
        formRepo.save(form);

        // 创建表单绑定
        FormBinding binding = new FormBinding("binding2", "form-flow-3", "apply", "form2",
                Map.of("name", "applicantName"),
                Map.of("applicantName", "name"),
                FormBinding.SubmitAction.COMPLETE_TASK);
        formBindingRepo.save(binding);

        // 启动流程
        String instanceId = engine.start("form-flow-3", Map.of());
        TaskInstance task = taskRepo.findByInstanceId(instanceId).get(0);

        // 提交表单
        Map<String, Object> formData = Map.of("name", "张三");
        FormSubmitResult result = engine.submitForm(task.getId(), formData, "user1", true);

        // 验证
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMappedVariables()).containsEntry("applicantName", "张三");
        
        // 验证流程变量已更新
        assertThat(instRepo.findById(instanceId).getVariable("applicantName")).isEqualTo("张三");
        
        // 验证任务已完成
        assertThat(taskRepo.findById(task.getId()).getStatus())
                .isEqualTo(com.workflow.enums.TaskStatus.COMPLETED);
    }

    @Test
    @DisplayName("提交表单 - 验证失败")
    void submitForm_validationFailed() {
        ProcessDefinition def = ProcessBuilder.create("form-flow-4")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("user1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        procRepo.save(def);

        // 创建带验证规则的表单
        List<ValidationRule> validations = Arrays.asList(
                new ValidationRule(ValidationRule.ValidationType.MIN_LENGTH, 2, "姓名至少2个字符")
        );
        List<FormField> fields = Arrays.asList(
                new FormField("name", "姓名", FormField.FieldType.TEXT, true,
                        null, null, null, validations, null, null, "applicantName")
        );
        FormDefinition form = new FormDefinition("form3", "申请表单", null, 1,
                fields, null, null);
        formRepo.save(form);

        FormBinding binding = new FormBinding("binding3", "form-flow-4", "apply", "form3",
                Map.of("name", "applicantName"),
                Map.of("applicantName", "name"),
                FormBinding.SubmitAction.COMPLETE_TASK);
        formBindingRepo.save(binding);

        String instanceId = engine.start("form-flow-4", Map.of());
        TaskInstance task = taskRepo.findByInstanceId(instanceId).get(0);

        // 提交不符合验证规则的数据
        Map<String, Object> formData = Map.of("name", "张"); // 只有1个字符
        FormSubmitResult result = engine.submitForm(task.getId(), formData, "user1", true);

        // 验证失败
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getValidationErrors()).isNotEmpty();
        assertThat(result.getValidationErrors().get(0).getMessage()).isEqualTo("姓名至少2个字符");
    }
}
