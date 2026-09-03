package com.workflow.rest;

import com.alibaba.fastjson2.JSON;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 与传输层无关的响应模型。
 *
 * <p>状态码语义固定如下，客户端可据此决定重试还是提示用户：
 * <ul>
 *   <li><b>400</b> 请求本身有问题（参数缺失、非法值）——重试无意义</li>
 *   <li><b>404</b> 引用的资源不存在</li>
 *   <li><b>409</b> 资源当前状态使本请求无效：待办已被他人处理、或跨实例并发冲突
 *       —— <b>应重新拉取后再决定</b>，不要盲目重放</li>
 *   <li><b>501</b> 该部署未启用对应能力（如没接历史仓储）</li>
 *   <li><b>500</b> 未预期错误，细节不外泄给客户端，只留服务端日志</li>
 * </ul>
 */
public record RestResponse(int status, String jsonBody) {

    public static RestResponse ok(Object payload) {
        return new RestResponse(200, JSON.toJSONString(payload));
    }

    public static RestResponse created(Object payload) {
        return new RestResponse(201, JSON.toJSONString(payload));
    }

    public static RestResponse noContent() {
        return new RestResponse(204, null);
    }

    /** 错误响应统一只带一个 message 字段，避免客户端依赖易变结构。 */
    public static RestResponse error(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", status);
        body.put("message", message == null ? "" : message);
        return new RestResponse(status, JSON.toJSONString(body));
    }

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }
}
