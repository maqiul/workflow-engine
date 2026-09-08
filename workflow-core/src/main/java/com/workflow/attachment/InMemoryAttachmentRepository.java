package com.workflow.attachment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存版附件仓储实现
 * 
 * <p>使用 ConcurrentHashMap 存储附件元数据，适用于测试和演示场景。
 */
public class InMemoryAttachmentRepository implements AttachmentRepository {
    
    private final Map<String, Attachment> store = new ConcurrentHashMap<>();
    
    @Override
    public void save(Attachment attachment) {
        if (attachment == null || attachment.getId() == null) {
            throw new IllegalArgumentException("Attachment and its ID cannot be null");
        }
        store.put(attachment.getId(), attachment);
    }
    
    @Override
    public Attachment findById(String id) {
        if (id == null) {
            return null;
        }
        return store.get(id);
    }
    
    @Override
    public List<Attachment> findByBusiness(Attachment.BusinessType businessType, String businessId) {
        if (businessType == null || businessId == null) {
            return new ArrayList<>();
        }
        return store.values().stream()
                .filter(a -> businessType.equals(a.getBusinessType()) && businessId.equals(a.getBusinessId()))
                .collect(Collectors.toList());
    }
    
    @Override
    public List<Attachment> findByTaskId(String taskId) {
        return findByBusiness(Attachment.BusinessType.TASK, taskId);
    }
    
    @Override
    public List<Attachment> findByInstanceId(String instanceId) {
        return findByBusiness(Attachment.BusinessType.INSTANCE, instanceId);
    }
    
    @Override
    public List<Attachment> findByUploader(String uploadedBy) {
        if (uploadedBy == null) {
            return new ArrayList<>();
        }
        return store.values().stream()
                .filter(a -> uploadedBy.equals(a.getUploadedBy()))
                .collect(Collectors.toList());
    }
    
    @Override
    public void delete(String id) {
        if (id != null) {
            store.remove(id);
        }
    }
    
    @Override
    public void deleteByBusiness(Attachment.BusinessType businessType, String businessId) {
        if (businessType == null || businessId == null) {
            return;
        }
        store.values().removeIf(a -> businessType.equals(a.getBusinessType()) && businessId.equals(a.getBusinessId()));
    }
}
