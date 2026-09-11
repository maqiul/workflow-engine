package com.workflow.rest;

/**
 * 鉴权钩子 —— 零依赖的可插拔入口。
 *
 * <p>本模块刻意不引入任何安全框架（Spring Security / Shiro 等）：引擎定位是嵌入式 jar，
 * 把一整套安全栈塞进依赖会逼所有调用方接受它。这里只留一个函数式接口，怎么鉴权由部署方
 * 决定；{@link ApiKeyAuthenticator} 是随包提供的最小可用实现。
 *
 * <p><b>默认放行</b>（{@link #NONE}）—— 也就是说「是否需要鉴权」由部署方显式选择，
 * 本模块不替它猜。但要清楚：一旦 REST 端口对外可达，不配置鉴权就等于把
 * 「启动/终止/改派任意流程实例」的能力敞开。
 */
@FunctionalInterface
public interface RequestAuthenticator {

    /** 判断请求是否放行。实现不应有副作用。 */
    AuthResult authenticate(RestRequest request);

    /** 放行一切 —— 默认值，保持「库内调用、不监听端口」场景的零配置体验。 */
    RequestAuthenticator NONE = request -> AuthResult.allowed();
}
