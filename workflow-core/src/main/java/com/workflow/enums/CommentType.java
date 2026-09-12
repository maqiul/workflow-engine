package com.workflow.enums;

/**
 * 审批意见类型 —— 区分「纯评论」与「随审批动作产生的意见」。
 *
 * <p>分开的价值在于检索：追溯驳回理由时只看 {@link #REJECT}，
 * 而「这个流程上人说过什么」取全部。若只存一段文本、不存类型，
 * 事后无法把系统生成的说明与人的表达分开。
 */
public enum CommentType {
    /** 普通评论（不改变流程状态） */
    COMMENT,
    /** 同意（随 completeTask 产生） */
    APPROVE,
    /** 驳回（随 rejectTask 产生） */
    REJECT,
    /** 转办 */
    TRANSFER,
    /** 加签 / 减签 */
    SIGN,
    /** 跳转 / 回退到指定节点 */
    JUMP,
    /** 撤回 */
    WITHDRAW,
    /** 终止 */
    TERMINATE
}
