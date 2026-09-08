package com.workflow.tests.attachment;

import com.workflow.attachment.*;
import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.WorkflowEngine;
import com.workflow.engine.WorkflowEngineBuilder;
import com.workflow.repository.InMemoryInstanceRepository;
import com.workflow.repository.InMemoryProcessRepository;
import com.workflow.repository.InMemoryTaskRepository;
import com.workflow.runtime.TaskInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 附件管理测试
 */
@DisplayName("附件管理")
class AttachmentManagementTest {

    private InMemoryProcessRepository procRepo;
    private InMemoryInstanceRepository instRepo;
    private InMemoryTaskRepository taskRepo;
    private InMemoryAttachmentRepository attachmentRepo;
    private InMemoryAttachmentStorage attachmentStorage;
    private WorkflowEngine engine;

    @BeforeEach
    void setUp() {
        procRepo = new InMemoryProcessRepository();
        instRepo = new InMemoryInstanceRepository();
        taskRepo = new InMemoryTaskRepository();
        attachmentRepo = new InMemoryAttachmentRepository();
        attachmentStorage = new InMemoryAttachmentStorage();
        
        engine = WorkflowEngineBuilder.builder(procRepo, instRepo, taskRepo)
                .attachmentRepository(attachmentRepo)
                .attachmentStorage(attachmentStorage)
                .build();
    }

    @Test
    @DisplayName("上传附件 - 成功上传")
    void uploadAttachment_success() {
        // 创建流程定义
        ProcessDefinition def = ProcessBuilder.create("attachment-flow-1")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("user1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        procRepo.save(def);

        // 启动流程
        String instanceId = engine.start("attachment-flow-1", java.util.Map.of());
        TaskInstance task = taskRepo.findByInstanceId(instanceId).get(0);

        // 上传附件
        byte[] fileContent = "测试文件内容".getBytes();
        InputStream inputStream = new ByteArrayInputStream(fileContent);
        
        String attachmentId = engine.uploadAttachment(
                task.getId(),
                "test.txt",
                "text/plain",
                fileContent.length,
                inputStream,
                "user1",
                "测试附件"
        );

        // 验证
        assertThat(attachmentId).isNotNull();
        
        Attachment attachment = attachmentRepo.findById(attachmentId);
        assertThat(attachment).isNotNull();
        assertThat(attachment.getFileName()).isEqualTo("test.txt");
        assertThat(attachment.getFileType()).isEqualTo("text/plain");
        assertThat(attachment.getFileSize()).isEqualTo(fileContent.length);
        assertThat(attachment.getBusinessType()).isEqualTo(Attachment.BusinessType.TASK);
        assertThat(attachment.getBusinessId()).isEqualTo(task.getId());
        assertThat(attachment.getUploadedBy()).isEqualTo("user1");
        assertThat(attachment.getRemark()).isEqualTo("测试附件");
    }

    @Test
    @DisplayName("获取任务附件列表")
    void getTaskAttachments() {
        ProcessDefinition def = ProcessBuilder.create("attachment-flow-2")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("user1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        procRepo.save(def);

        String instanceId = engine.start("attachment-flow-2", java.util.Map.of());
        TaskInstance task = taskRepo.findByInstanceId(instanceId).get(0);

        // 上传多个附件
        engine.uploadAttachment(task.getId(), "file1.txt", "text/plain", 10,
                new ByteArrayInputStream("content1".getBytes()), "user1", null);
        engine.uploadAttachment(task.getId(), "file2.pdf", "application/pdf", 20,
                new ByteArrayInputStream("content2".getBytes()), "user1", null);

        // 获取附件列表
        List<Attachment> attachments = engine.getTaskAttachments(task.getId());

        // 验证
        assertThat(attachments).hasSize(2);
        assertThat(attachments)
                .extracting(Attachment::getFileName)
                .containsExactlyInAnyOrder("file1.txt", "file2.pdf");
    }

    @Test
    @DisplayName("获取附件内容")
    void getAttachmentContent() throws Exception {
        ProcessDefinition def = ProcessBuilder.create("attachment-flow-3")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("user1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        procRepo.save(def);

        String instanceId = engine.start("attachment-flow-3", java.util.Map.of());
        TaskInstance task = taskRepo.findByInstanceId(instanceId).get(0);

        // 上传附件
        byte[] fileContent = "测试文件内容ABC".getBytes();
        String attachmentId = engine.uploadAttachment(
                task.getId(),
                "test.txt",
                "text/plain",
                fileContent.length,
                new ByteArrayInputStream(fileContent),
                "user1",
                null
        );

        // 获取附件内容
        InputStream contentStream = engine.getAttachmentContent(attachmentId);
        byte[] retrievedContent = contentStream.readAllBytes();

        // 验证
        assertThat(retrievedContent).isEqualTo(fileContent);
    }

    @Test
    @DisplayName("删除附件")
    void deleteAttachment() {
        ProcessDefinition def = ProcessBuilder.create("attachment-flow-4")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("user1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        procRepo.save(def);

        String instanceId = engine.start("attachment-flow-4", java.util.Map.of());
        TaskInstance task = taskRepo.findByInstanceId(instanceId).get(0);

        // 上传附件
        String attachmentId = engine.uploadAttachment(
                task.getId(),
                "test.txt",
                "text/plain",
                10,
                new ByteArrayInputStream("content".getBytes()),
                "user1",
                null
        );

        // 验证附件存在
        assertThat(attachmentRepo.findById(attachmentId)).isNotNull();

        // 删除附件
        engine.deleteAttachment(attachmentId);

        // 验证附件已删除
        assertThat(attachmentRepo.findById(attachmentId)).isNull();
    }

    @Test
    @DisplayName("附件功能未启用时抛出异常")
    void attachmentFeatureDisabled() {
        // 创建不启用附件功能的引擎
        WorkflowEngine engineWithoutAttachment = WorkflowEngineBuilder
                .builder(procRepo, instRepo, taskRepo)
                .build();

        ProcessDefinition def = ProcessBuilder.create("attachment-flow-5")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("user1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        procRepo.save(def);

        String instanceId = engineWithoutAttachment.start("attachment-flow-5", java.util.Map.of());
        TaskInstance task = taskRepo.findByInstanceId(instanceId).get(0);

        // 尝试上传附件应该抛出异常
        assertThatThrownBy(() -> engineWithoutAttachment.uploadAttachment(
                task.getId(),
                "test.txt",
                "text/plain",
                10,
                new ByteArrayInputStream("content".getBytes()),
                "user1",
                null
        )).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("附件功能未启用");
    }
}
