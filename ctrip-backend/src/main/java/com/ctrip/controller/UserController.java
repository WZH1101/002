package com.ctrip.controller;

import com.ctrip.api.ApiResponse;
import com.ctrip.service.UserService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 登录注册接口：/api/auth/*
 * token 通过查询参数/请求体传递（避免自定义 Header 的 CORS 预检复杂度）。
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
    public ApiResponse<?> me(@RequestParam(required = false) String token) {
        try {
            return ApiResponse.ok(userService.me(token));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/logout")
    public ApiResponse<?> logout(@RequestBody Map<String, Object> req) {
        userService.logout(req.get("token") == null ? null : String.valueOf(req.get("token")));
        return ApiResponse.ok("ok");
    }
}
