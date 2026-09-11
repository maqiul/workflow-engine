package com.workflow.rest;

/**
 * 鉴权结果。
 *
 * <p>区分 401 与 403 不是洁癖：401 表示「没带凭证或凭证无效」，调用方应当去取/换凭证；
 * 403 表示「知道你是谁，但你没这个权限」，重试多少次都一样。把两者压成 400 或 500，
 * 调用方就无从判断该刷新凭证还是该找管理员 —— 与 409 之所以重要的理由相同。
 */
public record AuthResult(boolean granted, int status, String message) {

    public static AuthResult allowed() {
        return new AuthResult(true, 200, null);
    }

    public static AuthResult unauthorized(String message) {
        return new AuthResult(false, 401, message);
    }

    public static AuthResult forbidden(String message) {
        return new AuthResult(false, 403, message);
    }
}
