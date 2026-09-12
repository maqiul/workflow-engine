package com.workflow.persistence.mybatis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.workflow.persistence.mybatis.entity.WfCommentEntity;

/**
 * 审批意见 Mapper —— 单主键，查询全部走 {@code BaseMapper} + {@code QueryWrapper}，
 * 无手写 SQL，因此不存在「SELECT 漏列、读回时字段静默丢失」的风险。
 */
public interface WfCommentMapper extends BaseMapper<WfCommentEntity> {
}
