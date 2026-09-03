package com.workflow.enums;

/**
 * 多人审批策略
 */
public enum CandidateStrategy {
    /** 或签 - 任一候选人完成即通过 */
    ANY,
    /** 会签 - 全部候选人都需完成 */
    ALL
}