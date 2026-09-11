package com.workflow.definition;

import com.workflow.enums.CandidateStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code candidate_json} 编解码 —— 重点是<b>老数据兼容</b>。
 *
 * <p>生产库里存量的 {@code candidate_json} 没有 {@code groupIds} 键。
 * 早先靠 {@code JSON.parseObject(json, Candidate.class)} 直接反射灌 final 字段，
 * 加字段就意味着老数据读回来是 null 并一路 NPE；现在改为显式映射，
 * 这里把两条路径都钉死。
 */
@DisplayName("候选人 JSON 编解码")
class CandidateCodecTest {

    @Test
    @DisplayName("仅用户：往返一致")
    void roundTripUsersOnly() {
        Candidate original = Candidate.ofAll("u1", "u2");
        Candidate back = CandidateCodec.fromJson(CandidateCodec.toJson(original));

        assertThat(back).isEqualTo(original);
        assertThat(back.getGroupIds()).isEmpty();
        assertThat(back.hasGroups()).isFalse();
    }

    @Test
    @DisplayName("用户 + 组：往返一致，组不会被写进用户")
    void roundTripUsersAndGroups() {
        Candidate original = new Candidate(Set.of("u1"), Set.of("managers", "hr"), CandidateStrategy.ANY);
        String json = CandidateCodec.toJson(original);
        Candidate back = CandidateCodec.fromJson(json);

        assertThat(back).isEqualTo(original);
        assertThat(back.getUserIds()).containsExactly("u1");
        assertThat(back.getGroupIds()).containsExactlyInAnyOrder("managers", "hr");
    }

    @Test
    @DisplayName("仅组：往返一致，且被识别为「尚未展开」")
    void roundTripGroupsOnly() {
        Candidate original = Candidate.ofGroups(Set.of("managers"), CandidateStrategy.ALL);
        Candidate back = CandidateCodec.fromJson(CandidateCodec.toJson(original));

        assertThat(back).isEqualTo(original);
        assertThat(back.getUserIds()).isEmpty();
        assertThat(back.isUnresolved()).isTrue();
    }

    @Test
    @DisplayName("老数据（没有 groupIds 键）读回来是空集，不 NPE")
    void readsLegacyJsonWithoutGroupIds() {
        String legacy = "{\"strategy\":\"ANY\",\"userIds\":[\"alice\",\"bob\"]}";

        Candidate back = CandidateCodec.fromJson(legacy);

        assertThat(back.getUserIds()).containsExactlyInAnyOrder("alice", "bob");
        assertThat(back.getGroupIds()).isEmpty();
        assertThat(back.getStrategy()).isEqualTo(CandidateStrategy.ANY);
        assertThat(back.hasGroups()).isFalse();
    }

    @Test
    @DisplayName("无组时不写 groupIds 键 —— 老版本读取端也能照常读")
    void omitsGroupKeyWhenNoGroups() {
        String json = CandidateCodec.toJson(Candidate.ofAny("u1"));

        assertThat(json).doesNotContain("groupIds");
    }

    @Test
    @DisplayName("空白 json → null；损坏 json → 抛异常而不是悄悄返回半个人")
    void rejectsCorruptedJson() {
        assertThat(CandidateCodec.fromJson(null)).isNull();
        assertThat(CandidateCodec.fromJson("  ")).isNull();

        assertThatThrownBy(() -> CandidateCodec.fromJson("{\"strategy\":\"ANY\",\"userIds\":[]}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("数据损坏");

        assertThatThrownBy(() -> CandidateCodec.fromJson("{\"strategy\":\"NOT_A_STRATEGY\",\"userIds\":[\"u1\"]}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOT_A_STRATEGY");
    }
}
