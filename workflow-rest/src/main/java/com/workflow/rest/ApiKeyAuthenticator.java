package com.workflow.rest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 最小可用的 API Key 鉴权：比对请求头里的密钥是否在白名单内。
 *
 * <p>只做「凭证校验」这一件事 —— 不做授权、不做限流、不解析 JWT。那些交给部署方自己的
 * {@link RequestAuthenticator} 实现，或者更常见的：交给上游网关。
 *
 * <p><b>为什么用静态工厂而不是重载构造器</b>：{@code (String...)} 与
 * {@code (String, String...)} 两个构造器对最常见的 {@code new ApiKeyAuthenticator("k")}
 * 是<b>歧义的</b>，编译器直接报错。工厂方法（{@link #of} / {@link #ofHeader} /
 * {@link #bearer}）名字不同，调用处一眼看得出用的哪种，也不再有歧义。
 *
 * <pre>{@code
 * ApiKeyAuthenticator.of("key-1", "key-2")        // X-API-Key: <key>
 * ApiKeyAuthenticator.ofHeader("X-Token", "key")  // X-Token: <key>
 * ApiKeyAuthenticator.bearer("token")             // Authorization: Bearer <token>
 * }</pre>
 *
 * <p>两点刻意为之：
 * <ul>
 *   <li>用 {@link MessageDigest#isEqual} 做<b>常量时间</b>比较。普通的 {@code String.equals}
 *       在首个不同字节处就返回，攻击者可以据此逐字节试探出密钥；</li>
 *   <li>多密钥时不短路，<b>全部比完</b>再下结论，避免用响应时间泄漏命中的是哪一个。</li>
 * </ul>
 */
public class ApiKeyAuthenticator implements RequestAuthenticator {

    /** 默认请求头名。 */
    public static final String DEFAULT_HEADER = "X-API-Key";

    private final String headerName;
    private final String prefix;      // 可为 null：不做前缀剥离
    private final List<byte[]> keys;

    /** 用默认请求头 {@link #DEFAULT_HEADER}，密钥白名单可传多个。 */
    public static ApiKeyAuthenticator of(String... validKeys) {
        return new ApiKeyAuthenticator(DEFAULT_HEADER, null, validKeys);
    }

    /** 用自定义请求头名，例如 {@code X-Token}。 */
    public static ApiKeyAuthenticator ofHeader(String headerName, String... validKeys) {
        return new ApiKeyAuthenticator(headerName, null, validKeys);
    }

    /**
     * 走标准的 {@code Authorization: Bearer <token>} 头。
     *
     * <p>网关、SDK、curl 用户默认都会这么发；自定义头虽然更隐蔽，但需要每个客户端都改。
     */
    public static ApiKeyAuthenticator bearer(String... validTokens) {
        return new ApiKeyAuthenticator("Authorization", "Bearer ", validTokens);
    }

    private ApiKeyAuthenticator(String headerName, String prefix, String... validKeys) {
        this.headerName = Objects.requireNonNull(headerName, "headerName");
        this.prefix = prefix;
        if (validKeys == null || validKeys.length == 0) {
            throw new IllegalArgumentException("至少需要一个有效密钥");
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String key : validKeys) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("密钥不能为空");
            }
            distinct.add(key);
        }
        this.keys = distinct.stream()
                .map(key -> key.getBytes(StandardCharsets.UTF_8))
                .toList();
    }

    @Override
    public AuthResult authenticate(RestRequest request) {
        String raw = request.header(headerName);
        if (raw == null || raw.isEmpty()) {
            return AuthResult.unauthorized("缺少凭证");
        }
        String presented = raw;
        if (prefix != null) {
            // 前缀按 HTTP 惯例大小写不敏感："bearer xxx" 与 "Bearer xxx" 都合法
            if (raw.length() <= prefix.length()
                    || !raw.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return AuthResult.unauthorized("凭证格式不正确，应为 " + prefix.trim() + " <token>");
            }
            presented = raw.substring(prefix.length());
        }
        byte[] candidate = presented.getBytes(StandardCharsets.UTF_8);
        boolean matched = false;
        for (byte[] key : keys) {
            matched |= MessageDigest.isEqual(key, candidate);
        }
        return matched ? AuthResult.allowed() : AuthResult.unauthorized("凭证无效");
    }
}
