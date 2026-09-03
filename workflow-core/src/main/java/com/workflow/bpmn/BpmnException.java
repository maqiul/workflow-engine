package com.workflow.bpmn;

/**
 * BPMN 解析/生成过程中的统一异常。
 *
 * <p>区分两类错误，便于调用方决定是重试还是改输入：
 * <ul>
 *   <li><b>格式错误</b>（XML 非法、缺必需属性）—— 客户端输入问题，重试无意义</li>
 *   <li><b>语义错误</b>（节点 id 重复、网关无出口、条件网关缺条件）——
 *       由 {@link com.workflow.builder.ProcessBuilder#build()} 的校验抛出，
 *       同样重试无意义，但错误信息更具体</li>
 * </ul>
 */
public class BpmnException extends RuntimeException {

    public BpmnException(String message) {
        super(message);
    }

    public BpmnException(String message, Throwable cause) {
        super(message, cause);
    }
}
