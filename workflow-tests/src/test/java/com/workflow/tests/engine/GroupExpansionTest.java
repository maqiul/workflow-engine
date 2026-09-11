package com.workflow.tests.engine;

import com.workflow.definition.Candidate;
import com.workflow.engine.GroupResolutionException;
import com.workflow.enums.CandidateStrategy;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
import com.workflow.query.TaskQuery;
import com.workflow.runtime.TaskInstance;
import com.workflow.tests.EngineTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 候选组展开 —— 引擎与组织架构接缝的完整行为。
 *
 * <p>约定：引擎不解析组织架构，只经 {@code GroupResolver} 取成员，且展开发生在
 * <b>任务创建时</b>（快照），原始组名留在候选信息里供查询与审计。
 */
@DisplayName("候选组展开")
class GroupExpansionTest extends EngineTestBase {

    @BeforeEach
    void init() {
        setUp();
    }

    /** 固定组织架构：managers = {m1, m2}，hr = {h1}，其余组无人。 */
    private void withOrg() {
        engine.setGroupResolver(groupId -> switch (groupId) {
            case "managers" -> Set.of("m1", "m2");
            case "hr" -> Set.of("h1");
            default -> Set.of();
        });
    }

    private TaskInstance firstTask(String instanceId) {
        return engine.getInstance(instanceId).getTasks().get(0);
    }

    // ---------- 展开本身 ----------

    @Test
    @DisplayName("展开后组内成员可办理，原始组名保留在候选信息里")
    void groupIsExpandedAtTaskCreation() {
        withOrg();
        register(simple("group-any")
                .start("start")
                .userTask("apply", "组审批", Candidate.ofGroups(Set.of("managers"), CandidateStrategy.ANY))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build());

        String instanceId = engine.start("group-any", Map.of());
        TaskInstance task = firstTask(instanceId);

        assertThat(task.getCandidate().getUserIds()).containsExactlyInAnyOrder("m1", "m2");
        // 组名不是被"用掉就丢"的中间产物：查询与审计都要靠它
        assertThat(task.getCandidate().getGroupIds()).containsExactly("managers");
        assertThat(task.getCandidate().isUnresolved()).isFalse();

        engine.completeTask(task.getId(), "m1", true);
        assertThat(engine.getInstance(instanceId).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    @DisplayName("组与显式用户混合，重叠成员自动去重（曾因 Set.of 撞重复直接崩）")
    void mixedUsersAndGroupsDeduplicate() {
        withOrg();
        Candidate candidate = new Candidate(Set.of("m1", "boss"),
                Set.of("managers", "hr"), CandidateStrategy.ANY);
        register(simple("group-mixed")
                .start("start")
                .userTask("apply", "混合", candidate)
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build());

        TaskInstance task = firstTask(engine.start("group-mixed", Map.of()));

        // m1 既在显式用户里也在 managers 里，只能出现一次
        assertThat(task.getCandidate().getUserIds()).containsExactlyInAnyOrder("m1", "m2", "boss", "h1");
    }

    @Test
    @DisplayName("解析器把组名原样返回时按失败处理，绝不把组名当人")
    void groupNameIsNeverMistakenForUser() {
        // 一个实现有误的解析器：把组名自己当成成员返回
        engine.setGroupResolver(Set::of);
        register(simple("group-selftrap")
                .start("start")
                .userTask("apply", "自陷", Candidate.ofGroups(Set.of("managers"), CandidateStrategy.ANY))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build());

        assertThatThrownBy(() -> engine.start("group-selftrap", Map.of()))
                .isInstanceOf(GroupResolutionException.class)
                .hasMessageContaining("managers");
    }

    // ---------- 失败即暴露：不制造「谁都办不了」的静默死锁 ----------

    @Test
    @DisplayName("未注入解析器：启动期直接拒绝，不留「实例存在但没人能办」的脏数据")
    void missingResolverRejectsAtStart() {
        register(simple("group-noresolver")
                .start("start")
                .userTask("apply", "无解析器", Candidate.ofGroups(Set.of("managers"), CandidateStrategy.ANY))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build());

        assertThatThrownBy(() -> engine.start("group-noresolver", Map.of()))
                .isInstanceOf(GroupResolutionException.class)
                .hasMessageContaining("managers")
                .hasMessageContaining("GroupResolver");

        // 预检发生在实例落库之前 —— 不该有任何任务被建出来
        assertThat(TaskQuery.create().list(engine)).isEmpty();
    }

    @Test
    @DisplayName("组内无人：建任务即失败，而不是静默留下一张谁都办不了的待办")
    void emptyGroupFailsTaskCreation() {
        withOrg();
        register(simple("group-empty")
                .start("start")
                .userTask("apply", "空组", Candidate.ofGroups(Set.of("nobody"), CandidateStrategy.ANY))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build());

        assertThatThrownBy(() -> engine.start("group-empty", Map.of()))
                .isInstanceOf(GroupResolutionException.class)
                .hasMessageContaining("nobody")
                .hasMessageContaining("组内无成员");
    }

    @Test
    @DisplayName("某组解析抛异常：整体失败并点出是哪个组，不做「悄悄丢掉一个组」的处理")
    void failingGroupFailsTaskCreation() {
        engine.setGroupResolver(groupId -> {
            if ("broken".equals(groupId)) {
                throw new IllegalStateException("组织架构服务不可用");
            }
            return "managers".equals(groupId) ? Set.of("m1", "m2") : Set.of();
        });
        register(simple("group-broken")
                .start("start")
                .userTask("apply", "混合故障", Candidate.ofGroups(Set.of("broken", "managers"), CandidateStrategy.ANY))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build());

        // managers 明明有人可办，但 broken 没解析出来。部分静默失败最难排查，
        // 所以这里选择整体失败，把问题钉在事发点。
        assertThatThrownBy(() -> engine.start("group-broken", Map.of()))
                .isInstanceOf(GroupResolutionException.class)
                .hasMessageContaining("broken")
                .hasMessageContaining("组织架构服务不可用");
    }

    // ---------- 运维兜底：管理员强制改派 ----------

    @Test
    @DisplayName("候选人离职场景由 adminTransferTask 兜底：绕过候选人校验强行改派")
    void adminTransferRescuesOrphanTask() {
        withOrg();
        register(simple("group-rescue")
                .start("start")
                .userTask("apply", "组审批", Candidate.ofGroups(Set.of("managers"), CandidateStrategy.ANY))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build());

        String instanceId = engine.start("group-rescue", Map.of());
        String taskId = firstTask(instanceId).getId();

        // 常规转办要求发起人本身是候选人 —— 外部人接不了这张单
        assertThatThrownBy(() -> engine.transferTask(taskId, "outsider", "m1"))
                .isInstanceOf(IllegalArgumentException.class);

        // 管理员通道不校验候选人，但必须留操作人（审计要求）
        assertThatThrownBy(() -> engine.adminTransferTask(taskId, "outsider", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("操作人");

        engine.adminTransferTask(taskId, "outsider", "admin");

        assertThat(engine.getTask(taskId).getStatus()).isEqualTo(TaskStatus.TRANSFERRED);
        List<TaskInstance> reassigned = TaskQuery.create().candidate("outsider").list(engine);
        assertThat(reassigned).hasSize(1);

        engine.completeTask(reassigned.get(0).getId(), "outsider", true);
        assertThat(engine.getInstance(instanceId).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    // ---------- 策略语义 ----------

    @Test
    @DisplayName("组 + ALL：展开后组内全员会签，一人通过不推进")
    void allSignOverGroup() {
        withOrg();
        register(simple("group-all")
                .start("start")
                .userTask("apply", "会签", Candidate.ofGroups(Set.of("managers"), CandidateStrategy.ALL))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build());

        String instanceId = engine.start("group-all", Map.of());
        String taskId = firstTask(instanceId).getId();

        engine.completeTask(taskId, "m1", true);
        assertThat(engine.getTask(taskId).getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(engine.getInstance(instanceId).getStatus()).isEqualTo(InstanceStatus.RUNNING);

        engine.completeTask(taskId, "m2", true);
        assertThat(engine.getTask(taskId).getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(engine.getInstance(instanceId).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    @DisplayName("组 + ANY：组内任一成员完成即推进")
    void anySignOverGroup() {
        withOrg();
        register(simple("group-any-one")
                .start("start")
                .userTask("apply", "或签", Candidate.ofGroups(Set.of("managers"), CandidateStrategy.ANY))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build());

        String instanceId = engine.start("group-any-one", Map.of());
        String taskId = firstTask(instanceId).getId();

        engine.completeTask(taskId, "m2", true);
        assertThat(engine.getInstance(instanceId).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    // ---------- 查询 ----------

    @Test
    @DisplayName("按候选组查待办：调用方拿自己的组名就能查到该办的活")
    void queryByCandidateGroup() {
        withOrg();
        register(simple("group-query")
                .start("start")
                .userTask("apply", "组审批", Candidate.ofGroups(Set.of("managers"), CandidateStrategy.ANY))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build());

        engine.start("group-query", Map.of());

        List<TaskInstance> byGroup = TaskQuery.create().candidateGroup("managers").list(engine);
        assertThat(byGroup).hasSize(1);
        assertThat(byGroup.get(0).getNodeId()).isEqualTo("apply");

        // 不相干的组查不到
        assertThat(TaskQuery.create().candidateGroup("hr").list(engine)).isEmpty();
        // 按候选人查依然只认具体用户
        assertThat(TaskQuery.create().candidate("m1").list(engine)).hasSize(1);
    }
}
