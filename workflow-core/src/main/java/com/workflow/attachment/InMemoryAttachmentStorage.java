package com.workflow.attachment;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版附件存储服务实现
 * 
 * <p>使用 ConcurrentHashMap 存储文件内容，适用于测试和演示场景。
 */
public class InMemoryAttachmentStorage implements AttachmentStorage {
    
    private final Map<String, byte[]> store = new ConcurrentHashMap<>();
    
    @Override
    public String store(String fileName, InputStream inputStream) {
        try {
            String storagePath = "memory://" + UUID.randomUUID().toString() + "/" + fileName;
            byte[] data = inputStream.readAllBytes();
            store.put(storagePath, data);
            return storagePath;
        } catch (Exception e) {
            throw new RuntimeException("Failed to store attachment: " + fileName, e);
        }
    }
    
    @Override
    public InputStream read(String storagePath) {
        byte[] data = store.get(storagePath);
        if (data == null) {
            throw new IllegalArgumentException("Attachment not found: " + storagePath);
        }
        return new ByteArrayInputStream(data);
    }
    
    @Override
    public boolean delete(String storagePath) {
        return store.remove(storagePath) != null;
    }
    
    @Override
    public boolean exists(String storagePath) {
        return store.containsKey(storagePath);
    }
}
