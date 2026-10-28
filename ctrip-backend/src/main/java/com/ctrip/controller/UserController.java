package com.ctrip.controller;

import com.ctrip.api.ApiResponse;
import com.ctrip.config.AuthInterceptor;
import com.ctrip.service.QrLoginService;
import com.ctrip.service.SmsCodeService;
import com.ctrip.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 账号接口：/api/auth/*
 *
 * <p>三种登录方式：账号密码（/login）、手机验证码（/sms/send + /sms/login）、
 * 扫码（/qrcode 创建 + /qrcode/{scene} 轮询，手机侧 /qrcode/{scene}/scan|confirm|reject）。
 *
 * <p>令牌读取顺序为 Authorization: Bearer > X-Token > 查询参数 token，
 * 与 {@link AuthInterceptor#resolveToken(HttpServletRequest)} 保持一致。
 * 查询参数方式仅为兼容旧版前端保留，建议前端统一改用 Header（令牌会留在访问日志里）。
 *
 * <p>/api/auth/* 不在拦截器覆盖范围内（登录注册必须匿名可访问），
 * 因此这里每个需要登录态的方法都自行校验：拿不到有效令牌时返回 code=401，
 * 前端会据此清掉过期登录态，而不是当成普通业务错误。
 * 扫码的 scan/confirm/reject 就是这个套路——它们由手机确认页调用。
 */
@RestController
@RequestMapping("/api/auth")
public class UserController {

    private final UserService userService;
    private final SmsCodeService smsCodeService;
    private final QrLoginService qrLoginService;

    public UserController(UserService userService,
                          SmsCodeService smsCodeService,
                          QrLoginService qrLoginService) {
        this.userService = userService;
        this.smsCodeService = smsCodeService;
        this.qrLoginService = qrLoginService;
    }

    /**
     * 注册。两种方式：
     * <ul>
     *   <li>账号密码注册：username + password（+ 选填 nickname/phone），原有逻辑；</li>
     *   <li>手机号注册：phone + code（注册码）+ 选填 nickname/password，注册成功即登录。</li>
     * </ul>
     * 分支依据是请求里是否带了 code——带 code 走 {@link UserService#registerBySms}。
     */
    @PostMapping("/register")
    public ApiResponse<?> register(@RequestBody Map<String, Object> req, HttpServletRequest request) {
        try {
            if (req != null && req.get("code") != null) {
                return ApiResponse.ok(userService.registerBySms(req, clientIp(request)));
            }
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

    // ==================== 手机验证码登录 ====================

    /** 发码。演示站没有短信网关：ctrip.auth.expose-sms-code 为真时把明文码一并返回给页面 */
    @PostMapping("/sms/send")
    public ApiResponse<?> sendSmsCode(@RequestBody Map<String, Object> req, HttpServletRequest request) {
        try {
            return ApiResponse.ok(smsCodeService.send(str(req.get("phone")), clientIp(request)));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 验证码登录：只接受已注册且唯一归属的手机号，成功后签发的会话与账号密码登录完全同构 */
    @PostMapping("/sms/login")
    public ApiResponse<?> loginBySms(@RequestBody Map<String, Object> req) {
        try {
            return ApiResponse.ok(userService.loginBySms(str(req.get("phone")), str(req.get("code"))));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 发注册码：只给未注册的号码。校验、限流、一次性规则与登录码同源 */
    @PostMapping("/sms/register-code")
    public ApiResponse<?> sendRegisterCode(@RequestBody Map<String, Object> req, HttpServletRequest request) {
        try {
            return ApiResponse.ok(smsCodeService.sendRegisterCode(str(req.get("phone")), clientIp(request)));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    // ==================== 扫码登录 ====================

    /** 电脑端创建扫码场景，返回二维码内容（手机确认页地址）；受单 IP 每分钟创建次数限制 */
    @PostMapping("/qrcode")
    public ApiResponse<?> createQrcode(HttpServletRequest request) {
        try {
            return ApiResponse.ok(qrLoginService.create(
                    request.getScheme(), request.getHeader("Host"), request.getServerPort(), clientIp(request)));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 电脑端轮询状态；状态变成 CONFIRMED 时这一次返回会带上会话令牌，且只返回一次 */
    @GetMapping("/qrcode/{scene}")
    public ApiResponse<?> queryQrcode(@PathVariable String scene) {
        return ApiResponse.ok(qrLoginService.poll(scene));
    }

    /** 以下三个都由手机确认页调用，必须是已登录设备，所以自行校验会话 */
    @PostMapping("/qrcode/{scene}/scan")
    public ApiResponse<?> scanQrcode(@PathVariable String scene, HttpServletRequest request) {
        UserService.AuthUser user = currentUser(request);
        if (user == null) return ApiResponse.unauthorized("请先在手机上登录，再确认扫码");
        try {
            return ApiResponse.ok(qrLoginService.scan(scene, user.id()));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/qrcode/{scene}/confirm")
    public ApiResponse<?> confirmQrcode(@PathVariable String scene, HttpServletRequest request) {
        UserService.AuthUser user = currentUser(request);
        if (user == null) return ApiResponse.unauthorized("请先在手机上登录，再确认扫码");
        try {
            return ApiResponse.ok(qrLoginService.confirm(scene, user.id()));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/qrcode/{scene}/reject")
    public ApiResponse<?> rejectQrcode(@PathVariable String scene, HttpServletRequest request) {
        UserService.AuthUser user = currentUser(request);
        if (user == null) return ApiResponse.unauthorized("请先在手机上登录，再取消扫码");
        try {
            return ApiResponse.ok(qrLoginService.reject(scene, user.id()));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 自校验会话：无效令牌返回 null，由调用方给 401，而不是抛异常变成 500 */
    private UserService.AuthUser currentUser(HttpServletRequest request) {
        return userService.findAuthUser(AuthInterceptor.resolveToken(request));
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
