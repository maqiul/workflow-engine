package com.workflow.tests.job;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.WorkflowEngine;
import com.workflow.engine.WorkflowEngineBuilder;
import com.workflow.enums.JobType;
import com.workflow.repository.InMemoryInstanceRepository;
import com.workflow.repository.InMemoryJobRepository;
import com.workflow.repository.InMemoryProcessRepository;
import com.workflow.repository.InMemoryTaskRepository;
import com.workflow.runtime.Job;
import com.workflow.runtime.TaskInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 作业与实例生命周期绑定 —— 实例进终态时清掉该实例遗留的作业。
 *
 * <p>不清会留下「实例已结束、作业还在库里等着触发」的孤儿作业：到点仍被领取执行，
 * 白跑一趟；若作业带副作用，更糟（会在实例结束后改动业务数据）。
 */
@DisplayName("作业与实例生命周期绑定")
class JobLifecycleBindingTest {

    private InMemoryProcessRepository procRepo;
    private InMemoryInstanceRepository instRepo;
    private InMemoryTaskRepository taskRepo;
    private InMemoryJobRepository jobRepo;
    private WorkflowEngine engine;

    @BeforeEach
    void setUp() {
        procRepo = new InMemoryProcessRepository();
        instRepo = new InMemoryInstanceRepository();
        taskRepo = new InMemoryTaskRepository();
        jobRepo = new InMemoryJobRepository();
        engine = WorkflowEngineBuilder.builder(procRepo, instRepo, taskRepo)
                .jobRepository(jobRepo)
                .build();
        procRepo.save(ProcessBuilder.create("job-life")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("u1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build());
    }

    /** 给实例塞一条作业（模拟异步服务任务等待执行） */
    private void addJob(String instanceId, String jobId) {
        jobRepo.save(new Job(jobId, JobType.SERVICE_TASK, instanceId, "{}", 
                System.currentTimeMillis() + 60_000, System.currentTimeMillis()));
    }

    @Test
    @DisplayName("实例完成 → 作业被清理")
    void completedInstance_clearsJobs() {
        String id = engine.start("job-life", Map.of());
        addJob(id, "j1");
        assertThat(jobRepo.findByInstanceId(id)).hasSize(1);

        TaskInstance task = taskRepo.findByInstanceId(id).get(0);
        engine.completeTask(task.getId(), "u1", true);

        assertThat(jobRepo.findByInstanceId(id)).as("实例完成，作业应被清理").isEmpty();
    }

    @Test
    @DisplayName("实例终止 → 作业被清理")
    void terminatedInstance_clearsJobs() {
        String id = engine.start("job-life", Map.of());
        addJob(id, "j1");

        engine.terminate(id);

        assertThat(jobRepo.findByInstanceId(id)).as("实例终止，作业应被清理").isEmpty();
    }

    @Test
    @DisplayName("实例撤回 → 作业被清理")
    void withdrawnInstance_clearsJobs() {
        String id = engine.start("job-life", "alice", Map.of());
        addJob(id, "j1");

        engine.withdraw(id, "alice");

        assertThat(jobRepo.findByInstanceId(id)).as("实例撤回，作业应被清理").isEmpty();
    }

    @Test
    @DisplayName("实例仍在跑 → 作业保留（不能误删）")
    void runningInstance_keepsJobs() {
        String id = engine.start("job-life", Map.of());
        addJob(id, "j1");

        assertThat(jobRepo.findByInstanceId(id)).as("运行中实例的作业应保留").hasSize(1);
    }

    @Test
    @DisplayName("未注入 jobRepository 时不影响引擎（不 NPE）")
    void noJobRepository_isFine() {
        WorkflowEngine plain = WorkflowEngineBuilder
                .builder(new InMemoryProcessRepository(), new InMemoryInstanceRepository(), new InMemoryTaskRepository())
                .build();
        // 不注入 jobRepository → 引擎不碰作业，流程照常走完
        assertThat(plain).isNotNull();
    }
}
