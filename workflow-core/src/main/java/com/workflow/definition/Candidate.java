package com.workflow.definition;

import com.workflow.enums.CandidateStrategy;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 候选人配置 - 不可变
 *
 * <p>候选人由两部分组成：{@link #getUserIds() 具体用户} 与 {@link #getGroupIds() 候选组}，
 * 两者不可同时为空。
 *
 * <p><b>组的语义（重要）</b>：组<b>不携带</b>独立策略 —— {@link #getStrategy() strategy}
 * 是节点级的，作用于组展开后的<b>实际候选人集合</b>。于是：
 * <ul>
 *   <li>「组 ANY」= 展开成组内成员 + ANY → 组内任一成员可办</li>
 *   <li>「组 ALL」= 展开成组内成员 + ALL → 组内全员会签</li>
 * </ul>
 * 因此不存在"组策略与节点策略打架"的问题，也不需要为组引入第二套策略。
 *
 * <p><b>引擎不解析组织架构</b>：组名由调用方注入的
 * {@code com.workflow.engine.GroupResolver} 在任务创建时展开为具体用户，
 * 展开结果<b>快照</b>进任务候选（对齐 {@code candidateUsers} 的既有教训：转办会改写
 * 当前候选人，实时解析会让事后反查的责任链漂移）。{@code groupIds} 保留原始组名，
 * 用于审计与「我所在组的待办」查询。
 *
 * <p><b>未展开时</b>：{@code userIds} 为空，该任务对任何人都不可办理，但流程不会因此卡死
 * （仍可由 {@code transferTask} 兜底）；办理时给出的是"候选人是组 X、未展开"这类
 * 明确提示，而不是含糊的"不是候选人"。
 *
 * @see com.workflow.enums.CandidateStrategy
 */
public final class Candidate {
    private final Set<String> userIds;
    private final Set<String> groupIds;
    private final CandidateStrategy strategy;

    /**
     * 完整构造：用户 + 组。
     *
     * @param userIds  具体用户，null 视作空集
     * @param groupIds 候选组，null 视作空集
     * @param strategy 节点级策略，作用于组展开后的实际候选人集合
     */
    public Candidate(Set<String> userIds, Set<String> groupIds, CandidateStrategy strategy) {
        Objects.requireNonNull(strategy, "strategy 不能为空");
        Set<String> users = normalize(userIds);
        Set<String> groups = normalize(groupIds);
        if (users.isEmpty() && groups.isEmpty()) {
            throw new IllegalArgumentException("候选人与候选组不能同时为空");
        }
        this.userIds = Collections.unmodifiableSet(users);
        this.groupIds = Collections.unmodifiableSet(groups);
        this.strategy = strategy;
    }

    /** 兼容构造：仅有具体用户（无候选组）。 */
    public Candidate(Set<String> userIds, CandidateStrategy strategy) {
        this(userIds, null, strategy);
    }

    public static Candidate ofAny(String... userIds) {
        return new Candidate(toSet(userIds), null, CandidateStrategy.ANY);
    }

    public static Candidate ofAll(String... userIds) {
        return new Candidate(toSet(userIds), null, CandidateStrategy.ALL);
    }

    /** 仅由候选组构成（组内展开前 userIds 为空）。 */
    public static Candidate ofGroups(Set<String> groupIds, CandidateStrategy strategy) {
        return new Candidate(null, groupIds, strategy);
    }

    /**
     * 展开结果合并 —— 返回把解析出的用户并进候选人的<b>新</b>实例。
     *
     * <p>{@code groupIds} 原样保留：展开是快照，但"这个任务当初来自哪个组"必须留痕，
     * 否则「我所在组的待办」查询与事后审计都无从谈起。
     */
    public Candidate withExpandedUsers(Set<String> resolvedUsers) {
        Set<String> merged = new LinkedHashSet<>(getUserIds());
        if (resolvedUsers != null) {
            merged.addAll(resolvedUsers);
        }
        if (merged.isEmpty()) {
            // 展开无果：保持原样（含 groupIds），由调用方告警，不抛异常阻断流程
            return this;
        }
        return new Candidate(merged, getGroupIds(), strategy);
    }

    /** 具体用户（永不为 null）。 */
    public Set<String> getUserIds() { return userIds == null ? Set.of() : userIds; }

    /** 候选组名（永不为 null）。 */
    public Set<String> getGroupIds() { return groupIds == null ? Set.of() : groupIds; }

    public CandidateStrategy getStrategy() { return strategy; }

    /** 是否含候选组。 */
    public boolean hasGroups() { return !getGroupIds().isEmpty(); }

    /** 是否有可直接办理的具体用户。 */
    public boolean hasUsers() { return !getUserIds().isEmpty(); }

    /**
     * 是否为"有组但尚未展开"的状态 —— 此时无人可办理。
     *
     * <p>用于把错误提示从"某某不是候选人"升级为"候选人是组 X，尚未展开"。
     */
    public boolean isUnresolved() { return getUserIds().isEmpty() && !getGroupIds().isEmpty(); }

    /**
     * 全部候选标识（用户 ∪ 组）。
     *
     * <p>用于判断"某个标识是否出现在本任务候选中"—— 查询与展示层关心这个；
     * 而"能否办理"必须只看 {@link #getUserIds()}，否则组名会被误认为可办理人。
     */
    public Set<String> getAllIds() {
        Set<String> all = new LinkedHashSet<>(getUserIds());
        all.addAll(getGroupIds());
        return Collections.unmodifiableSet(all);
    }

    /**
     * 解释"这份候选人配置为何拒绝了某个用户"。
     *
     * <p>把含糊的"你不是候选人"升级成可定位的提示 —— 最常见的成因是<b>候选组尚未展开</b>
     * （调用方没注入 {@code GroupResolver}），此时候选用户集合为空，
     * 原样抛出"用户 X 不是候选人"会把排查方向完全带偏。
     *
     * <p>引擎与 {@code TaskInstance} 共用此方法，保证各处口径一致。
     */
    public String explainRejection(String userId) {
        if (isUnresolved()) {
            return "用户 " + userId + " 无法办理任务：候选组 " + groupIds
                    + " 未展开成具体用户。引擎建任务时就会拒绝这种状态，"
                    + "走到这里说明是绕过引擎写入的存量数据 —— 请用 adminTransferTask 指定办理人";
        }
        if (hasGroups()) {
            return "用户 " + userId + " 不是本任务候选人（候选用户=" + userIds
                    + "，候选组=" + groupIds + " 已展开）";
        }
        return "用户 " + userId + " 不是本任务候选人";
    }

    private static Set<String> normalize(Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return new LinkedHashSet<>();
        }
        return toSet(ids.toArray(new String[0]));
    }

    /**
     * 收集标识 —— 自动去重、忽略 null 与空白项。
     *
     * <p>此前用 {@code Set.of(...)} 直接装箱：重复元素抛 {@code IllegalArgumentException}，
     * null 抛 {@code NullPointerException}。而组展开后很可能与显式列出的用户重叠，
     * 撞上就是引擎崩。这里放宽为"能救则救"，把去重交给集合本身。
     */
    private static Set<String> toSet(String... ids) {
        Set<String> set = new LinkedHashSet<>();
        if (ids == null) {
            return set;
        }
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                set.add(id);
            }
        }
        return set;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Candidate other)) {
            return false;
        }
        return strategy == other.strategy
                && getUserIds().equals(other.getUserIds())
                && getGroupIds().equals(other.getGroupIds());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getUserIds(), getGroupIds(), strategy);
    }

    @Override
    public String toString() {
        if (!hasGroups()) {
            return strategy + "(" + getUserIds() + ")";
        }
        if (!hasUsers()) {
            return strategy + "(组" + getGroupIds() + " 未展开)";
        }
        return strategy + "(用户" + getUserIds() + " + 组" + getGroupIds() + ")";
    }
}
