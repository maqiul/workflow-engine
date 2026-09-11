package com.workflow.bpmn;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.enums.CandidateStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 候选组的 BPMN 往返 —— 组必须在导出与导入之间完整活下来。
 *
 * <p>回归点：早先 {@code wf:candidate} 只写用户文本，组名被并进用户列表，
 * 于是"导出再导入"一次，组就永久变成了几个不存在的人，且无人察觉。
 */
@DisplayName("BPMN 候选组往返")
class BpmnGroupRoundTripTest {

    @Test
    @DisplayName("仅候选组：组名与策略完整往返，不被当成人")
    void groupsOnlyRoundTrip() {
        ProcessDefinition original = ProcessBuilder.create("grp", "组审批")
                .start("start")
                .userTask("apply", "组审批", Candidate.ofGroups(Set.of("managers", "hr"), CandidateStrategy.ANY))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();

        String xml = BpmnExporter.export(original);
        ProcessDefinition imported = BpmnImporter.importFrom(xml);

        Candidate c = imported.getNode("apply").getCandidate();
        assertThat(c.getGroupIds()).containsExactlyInAnyOrder("managers", "hr");
        assertThat(c.getUserIds()).isEmpty();
        assertThat(c.getStrategy()).isEqualTo(CandidateStrategy.ANY);
    }

    @Test
    @DisplayName("用户 + 组混合：两条线各自往返，不互相污染")
    void mixedRoundTrip() {
        ProcessDefinition original = ProcessBuilder.create("mix", "混合审批")
                .start("start")
                .userTask("apply", "混合审批",
                        new Candidate(Set.of("u1", "u2"), Set.of("managers"), CandidateStrategy.ALL))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();

        ProcessDefinition imported = BpmnImporter.importFrom(BpmnExporter.export(original));

        Candidate c = imported.getNode("apply").getCandidate();
        assertThat(c.getUserIds()).containsExactlyInAnyOrder("u1", "u2");
        assertThat(c.getGroupIds()).containsExactly("managers");
        assertThat(c.getStrategy()).isEqualTo(CandidateStrategy.ALL);
    }

    @Test
    @DisplayName("会签（ALL）的组节点：多实例标记不因缺人数而丢失")
    void allSignGroupKeepsMultiInstanceMarker() {
        ProcessDefinition original = ProcessBuilder.create("grp-all", "会签组")
                .start("start")
                .userTask("apply", "会签", Candidate.ofGroups(Set.of("managers"), CandidateStrategy.ALL))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();

        String xml = BpmnExporter.export(original);

        // 组在导出期未展开，人数无从得知 —— 不编造 cardinality，但组名要标注出来
        assertThat(xml).contains("wf:candidateGroups");
        assertThat(xml).doesNotContain("wf:cardinality");

        Candidate c = BpmnImporter.importFrom(xml).getNode("apply").getCandidate();
        assertThat(c.getGroupIds()).containsExactly("managers");
        assertThat(c.getStrategy()).isEqualTo(CandidateStrategy.ALL);
    }
}
