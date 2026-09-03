package com.workflow.persistence.mybatis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.workflow.persistence.mybatis.entity.WfInstanceEntity;

/**
 * 流程实例 Mapper - 单主键,走 BaseMapper 通用 CRUD
 */
public interface WfInstanceMapper extends BaseMapper<WfInstanceEntity> {
}
