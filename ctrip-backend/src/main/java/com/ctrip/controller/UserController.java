package com.ctrip.controller;

import com.ctrip.api.ApiResponse;
import com.ctrip.config.AuthInterceptor;
import com.ctrip.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 登录注册接口：/api/auth/*
 *
 * <p>令牌读取顺序为 Authorization: Bearer > X-Token > 查询参数 token，
 * 与 {@link AuthInterceptor#resolveToken(HttpServletRequest)} 保持一致。
 * 查询参数方式仅为兼容旧版前端保留，建议前端统一改用 Header（令牌会留在访问日志里）。
 */
@RestController
@RequestMapping("/api/auth")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public ApiResponse<?> register(@RequestBody Map<String, Object> req) {
        try {
            return ApiResponse.ok(userService.register(req));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/login")
    public ApiResponse<?> login(@RequestBody Map<String, Object> req) {
        try {
            return ApiResponse.ok(userService.login(req));
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
}
