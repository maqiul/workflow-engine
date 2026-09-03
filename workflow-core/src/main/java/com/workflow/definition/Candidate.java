package com.workflow.definition;

import com.workflow.enums.CandidateStrategy;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 候选人配置 - 不可变
 * 描述一个 UserTask 的审批人及策略
 */
public final class Candidate {
    private final Set<String> userIds;
    private final CandidateStrategy strategy;

    public Candidate(Set<String> userIds, CandidateStrategy strategy) {
        Objects.requireNonNull(userIds, "userIds 不能为空");
        Objects.requireNonNull(strategy, "strategy 不能为空");
        if (userIds.isEmpty()) {
            throw new IllegalArgumentException("userIds 不能为空集合");
        }
        this.userIds = Collections.unmodifiableSet(new LinkedHashSet<>(userIds));
        this.strategy = strategy;
    }

    public static Candidate ofAny(String... userIds) {
        return new Candidate(Set.of(userIds), CandidateStrategy.ANY);
    }

    public static Candidate ofAll(String... userIds) {
        return new Candidate(Set.of(userIds), CandidateStrategy.ALL);
    }

    public Set<String> getUserIds() { return userIds; }
    public CandidateStrategy getStrategy() { return strategy; }

    @Override
    public String toString() {
        return strategy + "(" + userIds + ")";
    }
}