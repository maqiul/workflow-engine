package com.workflow.attachment;

import java.util.List;

/**
 * 附件仓储接口
 * 
 * <p>管理附件元数据的存储和检索。
 */
public interface AttachmentRepository {
    
    /**
     * 保存附件元数据
     */
    void save(Attachment attachment);
    
    /**
     * 根据 ID 查找附件
     */
    Attachment findById(String id);
    
    /**
     * 根据业务类型和业务 ID 查找附件列表
     */
    List<Attachment> findByBusiness(Attachment.BusinessType businessType, String businessId);
    
    /**
     * 根据任务 ID 查找附件
     */
    List<Attachment> findByTaskId(String taskId);
    
    /**
     * 根据实例 ID 查找附件
     */
    List<Attachment> findByInstanceId(String instanceId);
    
    /**
     * 根据上传者查找附件
     */
    List<Attachment> findByUploader(String uploadedBy);
    
    /**
     * 删除附件
     */
    void delete(String id);
    
    /**
     * 删除业务关联的所有附件
     */
    void deleteByBusiness(Attachment.BusinessType businessType, String businessId);
}
