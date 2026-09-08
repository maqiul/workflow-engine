package com.workflow.attachment;

import java.io.InputStream;

/**
 * 附件存储服务接口
 * 
 * <p>负责实际的文件存储和读取。
 */
public interface AttachmentStorage {
    
    /**
     * 存储文件
     * 
     * @param fileName 文件名
     * @param inputStream 文件输入流
     * @return 存储路径或 URL
     */
    String store(String fileName, InputStream inputStream);
    
    /**
     * 读取文件
     * 
     * @param storagePath 存储路径
     * @return 文件输入流
     */
    InputStream read(String storagePath);
    
    /**
     * 删除文件
     * 
     * @param storagePath 存储路径
     * @return 是否删除成功
     */
    boolean delete(String storagePath);
    
    /**
     * 检查文件是否存在
     * 
     * @param storagePath 存储路径
     * @return 是否存在
     */
    boolean exists(String storagePath);
}
