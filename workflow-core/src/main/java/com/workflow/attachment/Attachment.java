package com.workflow.attachment;

import java.time.LocalDateTime;

/**
 * 附件定义
 * 
 * <p>描述附件的元数据信息。
 */
public class Attachment {
    
    /** 附件 ID */
    private final String id;
    
    /** 文件名 */
    private final String fileName;
    
    /** 文件类型（MIME 类型） */
    private final String fileType;
    
    /** 文件大小（字节） */
    private final long fileSize;
    
    /** 存储路径或 URL */
    private final String storagePath;
    
    /** 关联的业务类型（TASK/INSTANCE/PROCESS） */
    private final BusinessType businessType;
    
    /** 关联的业务 ID（任务 ID/实例 ID/流程定义 ID） */
    private final String businessId;
    
    /** 上传者 */
    private final String uploadedBy;
    
    /** 上传时间 */
    private final LocalDateTime uploadedAt;
    
    /** 备注 */
    private final String remark;
    
    public Attachment(String id, String fileName, String fileType, long fileSize,
                     String storagePath, BusinessType businessType, String businessId,
                     String uploadedBy, LocalDateTime uploadedAt, String remark) {
        this.id = id;
        this.fileName = fileName;
        this.fileType = fileType;
        this.fileSize = fileSize;
        this.storagePath = storagePath;
        this.businessType = businessType;
        this.businessId = businessId;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = uploadedAt;
        this.remark = remark;
    }
    
    public String getId() {
        return id;
    }
    
    public String getFileName() {
        return fileName;
    }
    
    public String getFileType() {
        return fileType;
    }
    
    public long getFileSize() {
        return fileSize;
    }
    
    public String getStoragePath() {
        return storagePath;
    }
    
    public BusinessType getBusinessType() {
        return businessType;
    }
    
    public String getBusinessId() {
        return businessId;
    }
    
    public String getUploadedBy() {
        return uploadedBy;
    }
    
    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }
    
    public String getRemark() {
        return remark;
    }
    
    /**
     * 业务类型枚举
     */
    public enum BusinessType {
        /** 任务附件 */
        TASK,
        /** 流程实例附件 */
        INSTANCE,
        /** 流程定义附件 */
        PROCESS
    }
}
