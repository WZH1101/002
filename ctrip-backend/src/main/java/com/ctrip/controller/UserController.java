package com.ctrip.controller;

import com.ctrip.api.ApiResponse;
import com.ctrip.config.AuthInterceptor;
import com.ctrip.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 账号接口：/api/auth/*
 *
 * <p>令牌读取顺序为 Authorization: Bearer > X-Token > 查询参数 token，
 * 与 {@link AuthInterceptor#resolveToken(HttpServletRequest)} 保持一致。
 * 查询参数方式仅为兼容旧版前端保留，建议前端统一改用 Header（令牌会留在访问日志里）。
 *
 * <p>/api/auth/* 不在拦截器覆盖范围内（登录注册必须匿名可访问），
 * 因此 /me 与 /password 自行校验会话：改密接口拿不到有效令牌时返回 code=401，
 * 前端会据此清掉过期登录态，而不是当成普通业务错误。
 */
@RestController
@RequestMapping("/api/auth")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public ApiResponse<?> register(@RequestBody Map<String, Object> req, HttpServletRequest request) {
        try {
            return ApiResponse.ok(userService.register(req, clientIp(request)));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/login")
    public ApiResponse<?> login(@RequestBody Map<String, Object> req, HttpServletRequest request) {
        try {
            return ApiResponse.ok(userService.login(req, clientIp(request)));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @GetMapping("/me")
    public ApiResponse<?> me(HttpServletRequest request) {
        try {
            return ApiResponse.ok(userService.me(AuthInterceptor.resolveToken(request)));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 退出登录：优先取 Header 里的令牌，兼容旧前端从请求体传 token */
    @PostMapping("/logout")
    public ApiResponse<?> logout(HttpServletRequest request,
                                 @RequestBody(required = false) Map<String, Object> req) {
        String token = AuthInterceptor.resolveToken(request);
        if (token == null && req != null && req.get("token") != null) {
            token = String.valueOf(req.get("token"));
        }
        userService.logout(token);
        return ApiResponse.ok("ok");
    }

    /**
     * 修改密码。需要有效登录态；成功后该账号的所有会话都被吊销，
     * 包括当前这一个——所以这里明确告诉前端要重新登录。
     */
    @PostMapping("/password")
    public ApiResponse<?> changePassword(HttpServletRequest request,
                                         @RequestBody Map<String, Object> req) {
        String token = AuthInterceptor.resolveToken(request);
        if (userService.findAuthUser(token) == null) {
            return ApiResponse.unauthorized("登录已过期，请重新登录");
        }
        try {
            userService.changePassword(token,
                    req == null ? null : str(req.get("oldPassword")),
                    req == null ? null : str(req.get("newPassword")));
            return ApiResponse.ok("密码已修改，所有设备需重新登录");
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * 限速维度用的客户端地址。直接放在本机/内网演示是准确的；
     * 真正上线挂在 Nginx 后面时 getRemoteAddr 拿到的会是代理地址，
     * 需要开 Spring Boot 的 server.forward-headers-strategy 或接入网关后再取 X-Forwarded-For
     * （这里不无条件信任该头——它由客户端任意伪造，反而会让锁定形同虚设）。
     */
    private static String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
