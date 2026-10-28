package com.ctrip.api;

/**
 * 统一响应体：{code:0, msg:"ok", data:...}
 */
public record ApiResponse<T>(int code, String msg, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    public static <T> ApiResponse<T> fail(String msg) {
        return new ApiResponse<>(1, msg, null);
    }

    /**
     * 会话失效：code 与 {@link com.ctrip.config.AuthInterceptor} 返回的 401 保持一致，
     * 前端 request() 据此清掉过期登录态，而不是当成普通业务错误。
     */
    public static <T> ApiResponse<T> unauthorized(String msg) {
        return new ApiResponse<>(401, msg, null);
    }
}
