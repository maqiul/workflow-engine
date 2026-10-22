package com.workflow.engine;

import com.workflow.enums.JobState;
import com.workflow.enums.JobType;
import com.workflow.repository.JobRepository;
import com.workflow.runtime.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 持久化异步作业执行器。
 *
 * <p>与内存调度器（{@link ScheduledTimeoutScheduler}）的根本差别：
 * <ul>
 *   <li><b>作业落库</b> → 进程重启后作业还在，不用"扫描任务再重算到期时刻"这种派生式恢复；</li>
 *   <li><b>失败可重试</b> → 内存调度器里回调抛异常就永久丢失（只打一行 error log）；</li>
 *   <li><b>死信可见</b> → 重试耗尽进 {@link JobState#FAILED}，行保留、可查、可人工重放；</li>
 *   <li><b>多节点安全</b> → 原子领取（{@link JobRepository#claim}）+ 租约，多个节点不会重复执行同一作业。</li>
 * </ul>
 *
 * <p>执行模型：单条作业在<b>独立事务</b>里执行（不在领取事务里）——作业可能要写多张表、
 * 耗时不定，不能把数据库锁撑在领取事务上。领取靠租约，节点崩溃后租约到期，作业可被别人接管。
 *
 * <p>用法：
 * <pre>{@code
 *   JobExecutor executor = new JobExecutor(jobRepo);
 *   executor.register(JobType.TIMEOUT, job -> { ... });
 *   executor.start();
 *   ...
 *   executor.close();   // 优雅停机
 * }</pre>
 */
public class JobExecutor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JobExecutor.class);

    /** 作业处理器 */
    public interface JobHandler {
        /**
         * 执行作业。抛异常 = 执行失败 → 触发重试/死信。
         * 处理器<b>必须幂等</b>：崩溃/租约过期会导致同一作业被重投。
         */
        void execute(Job job) throws Exception;
    }

    private final JobRepository jobRepo;
    private final Map<JobType, JobHandler> handlers = new EnumMap<>(JobType.class);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong executedCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();

    private String nodeId;
    private int pollIntervalMillis = 1000;
    private int batchSize = 20;
    private long leaseMillis = 60_000L;
    private long retryBackoffBaseMillis = 1000L;
    private long retryBackoffMaxMillis = 60_000L;
    private int workerThreads = 1;

    private ScheduledExecutorService poller;

    public JobExecutor(JobRepository jobRepo) {
        this.jobRepo = jobRepo;
        this.nodeId = defaultNodeId();
    }

    /** 注册处理器（同类型重复注册以最后一次为准） */
    public JobExecutor register(JobType type, JobHandler handler) {
        handlers.put(type, handler);
        return this;
    }

    public JobExecutor nodeId(String nodeId) {
        this.nodeId = nodeId;
        return this;
    }

    public JobExecutor pollIntervalMillis(int v) {
        this.pollIntervalMillis = Math.max(50, v);
        return this;
    }

    public JobExecutor batchSize(int v) {
        this.batchSize = Math.max(1, v);
        return this;
    }

    /** 领取租约时长：节点崩溃后，作业最多这么久可被别人接管 */
    public JobExecutor leaseMillis(long v) {
        this.leaseMillis = Math.max(1000L, v);
        return this;
    }

    public JobExecutor workerThreads(int v) {
        this.workerThreads = Math.max(1, v);
        return this;
    }

    /** 退避基数：第 n 次重试的等待 = base * 2^n（封顶） */
    public JobExecutor retryBackoffBaseMillis(long v) {
        this.retryBackoffBaseMillis = Math.max(0L, v);
        return this;
    }

    public JobExecutor retryBackoffMaxMillis(long v) {
        this.retryBackoffMaxMillis = Math.max(this.retryBackoffBaseMillis, v);
        return this;
    }

    public String nodeId() {
        return nodeId;
    }

    public long executedCount() {
        return executedCount.get();
    }

    public long failedCount() {
        return failedCount.get();
    }

    /**
     * 提交作业（等价于「到点执行」）。
     *
     * @param jobId      作业 id（幂等键；同 id 重复提交 = 覆盖）
     * @param type       作业类型
     * @param instanceId 关联实例（可空）
     * @param payload    作业参数（可空）
     * @param dueAt      到期时刻（epoch millis）
     */
    public void submit(String jobId, JobType type, String instanceId, String payload, long dueAt) {
        submit(jobId, type, instanceId, payload, dueAt, Job.DEFAULT_MAX_RETRIES);
    }

    /** 提交作业（指定最大重试次数） */
    public void submit(String jobId, JobType type, String instanceId, String payload,
                       long dueAt, int maxRetries) {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("作业 id 不能为空");
        }
        long now = System.currentTimeMillis();
        Job job = new Job(jobId, type, instanceId, payload, dueAt, now);
        job.setMaxRetries(maxRetries);
        jobRepo.save(job);
        log.debug("[作业] 提交 id={} type={} dueAt={}", jobId, type, dueAt);
    }

    /** 取消作业（按 id 删除；不存在也不报错） */
    public void cancel(String jobId) {
        jobRepo.delete(jobId);
    }

    /** 启动轮询线程 */
    public synchronized void start() {
        if (closed.get()) {
            throw new IllegalStateException("执行器已关闭");
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "workflow-job-" + nodeId);
            t.setDaemon(true);
            return t;
        };
        poller = Executors.newScheduledThreadPool(workerThreads, tf);
        for (int i = 0; i < workerThreads; i++) {
            poller.scheduleWithFixedDelay(this::pollOnceSafely,
                    pollIntervalMillis, pollIntervalMillis, TimeUnit.MILLISECONDS);
        }
        log.info("[作业] 执行器已启动 nodeId={} 轮询间隔={}ms 线程数={}",
                nodeId, pollIntervalMillis, workerThreads);
    }

    /** 轮询一轮（单线程安全）—— 也供测试直接调用，避免等定时器 */
    public void pollOnce() {
        long now = System.currentTimeMillis();
        java.util.List<Job> due = jobRepo.findDue(now, batchSize);
        if (due.isEmpty()) {
            return;
        }
        for (Job candidate : due) {
            executeIfClaimed(candidate.getId());
        }
    }

    private void pollOnceSafely() {
        try {
            pollOnce();
        } catch (Throwable t) {
            // 轮询线程绝不能因为一次异常而死掉 —— 否则所有作业静默停摆
            log.error("[作业] 轮询异常: {}", t.getMessage(), t);
        }
    }

    /**
     * 领取并执行单个作业。
     *
     * @return true 表示本次真的执行了（而不是被别人抢走或已不存在）
     */
    public boolean executeIfClaimed(String jobId) {
        long now = System.currentTimeMillis();
        if (!jobRepo.claim(jobId, nodeId, now, now + leaseMillis)) {
            return false;   // 已被别人领取 / 尚未到期 / 不存在
        }
        Job job = jobRepo.findById(jobId);
        if (job == null) {
            return false;
        }
        JobHandler handler = handlers.get(job.getType());
        if (handler == null) {
            // 没有处理器 = 配置错误，重试也没用 → 直接死信，别无限重试
            String err = "没有为作业类型注册处理器: " + job.getType();
            log.error("[作业] {}", err);
            jobRepo.markFailed(jobId, err);
            failedCount.incrementAndGet();
            return false;
        }
        try {
            handler.execute(job);
            jobRepo.delete(jobId);
            executedCount.incrementAndGet();
            log.info("[作业] 执行成功 id={} type={}", jobId, job.getType());
            return true;
        } catch (Exception e) {
            failedCount.incrementAndGet();
            handleFailure(job, e);
            return false;
        }
    }

    private void handleFailure(Job job, Exception e) {
        String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        int retries = job.getRetries();
        if (retries < job.getMaxRetries()) {
            long backoff = backoffFor(retries);
            long nextDue = System.currentTimeMillis() + backoff;
            jobRepo.markRetry(job.getId(), err, nextDue);
            log.warn("[作业] 执行失败，退避重试 id={} 第{}次 退避={}ms 原因={}",
                    job.getId(), retries + 1, backoff, err, e);
        } else {
            jobRepo.markFailed(job.getId(), err);
            log.error("[作业] 重试耗尽，转死信 id={} type={} 重试={}次 原因={}",
                    job.getId(), job.getType(), retries, err, e);
        }
    }

    /** 退避：base * 2^n，封顶 max */
    private long backoffFor(int retryIndex) {
        long delay = retryBackoffBaseMillis;
        for (int i = 0; i < retryIndex && delay < retryBackoffMaxMillis; i++) {
            delay <<= 1;
        }
        return Math.min(delay, retryBackoffMaxMillis);
    }

    /** 优雅停机：停止轮询、等待在途作业收尾（最多等 lease） */
    @Override
    public synchronized void close() {
        if (closed.getAndSet(true)) {
            return;
        }
        if (poller != null) {
            poller.shutdown();
            try {
                if (!poller.awaitTermination(5, TimeUnit.SECONDS)) {
                    poller.shutdownNow();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                poller.shutdownNow();
            }
        }
        log.info("[作业] 执行器已停止 nodeId={} 已执行={} 失败={}",
                nodeId, executedCount.get(), failedCount.get());
    }

    /** 死信列表（观测） */
    public java.util.List<Job> deadLetters(int limit) {
        return jobRepo.findByState(JobState.FAILED, limit);
    }

    /** 节点标识：主机名 + pid，够用且可读 */
    private static String defaultNodeId() {
        try {
            String host = java.net.InetAddress.getLocalHost().getHostName();
            return host + "-" + ProcessHandle.current().pid();
        } catch (Exception e) {
            return "node-" + ProcessHandle.current().pid();
        }
    }
}
