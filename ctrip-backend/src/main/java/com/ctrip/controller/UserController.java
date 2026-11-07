package com.ctrip.controller;

import com.ctrip.api.ApiResponse;
import com.ctrip.config.AuthInterceptor;
import com.ctrip.service.EmailCodeService;
import com.ctrip.service.OAuthService;
import com.ctrip.service.QrLoginService;
import com.ctrip.service.SmsCodeService;
import com.ctrip.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 账号接口：/api/auth/*
 *
 * <p>五种登录方式：账号密码（/login，用户名或邮箱）、手机验证码（/sms/send + /sms/login）、
 * 邮箱验证码（/email/send + /email/login）、扫码（/qrcode 创建 + /qrcode/{scene} 轮询，
 * 手机侧 /qrcode/{scene}/scan|confirm|reject）、第三方授权（/oauth，见下）。
 * 前四种各自一条独立通道，第五种与注册复用同一条：首次授权建号并直接签会话，同一 openId 再授权就是登录，
 * 所以前端登录面板底部那一排渠道按钮走的就是 /oauth，不另开接口。
 *
 * <p>三种注册方式：手机号（/sms/register-code + /register）、邮箱（/email/register-code + /register）、
 * 第三方账号（/oauth 创建 + /oauth/{state} 轮询，授权页侧 /oauth/{state}/approve|reject）。
 * <b>「用户名 + 密码」注册已下线</b>：它只证明用户名没人占，不证明申请人持有任何东西。
 * 账号密码登录因此只对设过口令的账号有效（验证码/第三方建的号若未设口令，需先走 /auth/password 或忘记密码）。
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
    private final EmailCodeService emailCodeService;
    private final QrLoginService qrLoginService;
    private final OAuthService oauthService;

    public UserController(UserService userService,
                          SmsCodeService smsCodeService,
                          EmailCodeService emailCodeService,
                          QrLoginService qrLoginService,
                          OAuthService oauthService) {
        this.userService = userService;
        this.smsCodeService = smsCodeService;
        this.emailCodeService = emailCodeService;
        this.qrLoginService = qrLoginService;
        this.oauthService = oauthService;
    }

    /**
     * 注册。按请求里带了什么分流：
     * <ul>
     *   <li>带 email → {@link UserService#registerByEmail}（邮箱 + 邮箱验证码）；</li>
     *   <li>带 code（无 email）→ {@link UserService#registerBySms}（手机号 + 短信验证码）；</li>
     *   <li>两者都没有 → 明确拒绝，不再走「用户名 + 密码」。</li>
     * </ul>
     * 第三方账号不在这里：那条路没有表单可提交，见 /oauth 一组接口。
     */
    @PostMapping("/register")
    public ApiResponse<?> register(@RequestBody Map<String, Object> req, HttpServletRequest request) {
        try {
            if (req != null && req.get("email") != null) {
                return ApiResponse.ok(userService.registerByEmail(req, clientIp(request)));
            }
            if (req != null && req.get("code") != null) {
                return ApiResponse.ok(userService.registerBySms(req, clientIp(request)));
            }
            return ApiResponse.fail("用户名+密码注册已下线，请改用手机号、邮箱或第三方账号注册");
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

    /**
     * 验证码登录：成功后签发的会话与账号登录完全同构。
     * 一个手机号挂着多个账号时，第一次返回 needChoose + 候选列表，用户挑定后带 chooseToken + username 再请求一次。
     */
    @PostMapping("/sms/login")
    public ApiResponse<?> loginBySms(@RequestBody Map<String, Object> req) {
        try {
            return ApiResponse.ok(userService.loginBySms(str(req.get("phone")), str(req.get("code")),
                    str(req.get("chooseToken")), str(req.get("username"))));
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

    // ==================== 邮箱验证码 ====================

    /** 发登录码。演示站没有 SMTP 通道：ctrip.auth.expose-email-code 为真时把明文码一并返回给页面 */
    @PostMapping("/email/send")
    public ApiResponse<?> sendEmailCode(@RequestBody Map<String, Object> req, HttpServletRequest request) {
        try {
            return ApiResponse.ok(emailCodeService.sendLoginCode(str(req.get("email")), clientIp(request)));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 发注册码：只给未注册的邮箱。限流与一次性规则与登录码同源 */
    @PostMapping("/email/register-code")
    public ApiResponse<?> sendEmailRegisterCode(@RequestBody Map<String, Object> req, HttpServletRequest request) {
        try {
            return ApiResponse.ok(emailCodeService.sendRegisterCode(str(req.get("email")), clientIp(request)));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 邮箱验证码登录：成功后签发的会话与账号密码登录完全同构 */
    @PostMapping("/email/login")
    public ApiResponse<?> loginByEmail(@RequestBody Map<String, Object> req) {
        try {
            return ApiResponse.ok(userService.loginByEmailCode(str(req.get("email")), str(req.get("code"))));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    // ==================== 第三方账号授权（演示 · 模拟授权页） ====================

    /**
     * 发起一次第三方授权，返回授权页地址（前端把它开在新窗口/标签里）。
     *
     * <p>与扫码登录同一套状态机，但角色相反：扫码是「已登录的手机替新设备点确认」，
     * 这里是「渠道把身份交给本站」。授权页由本站直出（GET /oauth/authorize?state=），
     * 因为演示环境没有真的渠道可跳转——接真渠道时这一对接口换成 /oauth/{provider}/callback。
     */
    @PostMapping("/oauth")
    public ApiResponse<?> startOauth(@RequestBody(required = false) Map<String, Object> req,
                                     HttpServletRequest request) {
        try {
            String provider = req == null ? null : str(req.get("provider"));
            return ApiResponse.ok(oauthService.start(provider, request.getScheme(),
                    request.getHeader("Host"), request.getServerPort(), clientIp(request)));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 发起方轮询；状态变成 APPROVED 时这一次返回会带上会话令牌，且只返回一次 */
    @GetMapping("/oauth/{state}")
    public ApiResponse<?> pollOauth(@PathVariable String state) {
        return ApiResponse.ok(oauthService.poll(state));
    }

    /** 以下两个都由模拟授权页调用：同意就是选一个预置演示身份，拒绝就是把场景钉成 REJECTED */
    @PostMapping("/oauth/{state}/approve")
    public ApiResponse<?> approveOauth(@PathVariable String state,
                                       @RequestBody(required = false) Map<String, Object> req) {
        try {
            return ApiResponse.ok(oauthService.approve(state, req == null ? null : str(req.get("identity"))));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/oauth/{state}/reject")
    public ApiResponse<?> rejectOauth(@PathVariable String state) {
        try {
            return ApiResponse.ok(oauthService.reject(state));
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

    /** 个人中心：账号信息 + 已绑定的第三方应用。令牌无效给 401，让前端按「登录已过期」清掉本地登录态 */
    @GetMapping("/me")
    public ApiResponse<?> me(HttpServletRequest request) {
        String token = AuthInterceptor.resolveToken(request);
        if (userService.findAuthUser(token) == null) {
            return ApiResponse.unauthorized("登录已过期，请重新登录");
        }
        try {
            return ApiResponse.ok(userService.me(token));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /**
     * 解绑手机号（个人中心）。解完必须还剩至少一种登录方式，否则账号会登不进去，
     * 这条门槛在后端 {@link UserService#unbindPhone} 里把，不依赖前端置灰。
     */
    @PostMapping("/phone/unbind")
    public ApiResponse<?> unbindPhone(HttpServletRequest request) {
        String token = AuthInterceptor.resolveToken(request);
        if (userService.findAuthUser(token) == null) {
            return ApiResponse.unauthorized("登录已过期，请重新登录");
        }
        try {
            return ApiResponse.ok(userService.unbindPhone(token));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 解绑第三方应用（微信 / QQ / 微博）：只删本站与它的绑定关系，不涉及渠道侧 */
    @PostMapping("/oauth/unbind")
    public ApiResponse<?> unbindOAuth(HttpServletRequest request,
                                      @RequestBody(required = false) Map<String, Object> req) {
        String token = AuthInterceptor.resolveToken(request);
        if (userService.findAuthUser(token) == null) {
            return ApiResponse.unauthorized("登录已过期，请重新登录");
        }
        try {
            return ApiResponse.ok(userService.unbindOAuth(token, req == null ? null : str(req.get("provider"))));
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

    // ==================== 找回密码（忘记密码） ====================
    //
    // 与改密不同：改密需登录态且校验旧密码；找回密码面向「登不进去」的场景，
    // 不要求登录也不校验旧密码，账号归属完全由重置验证码证明（scene=reset，与登录/注册码互不通用）。

    /** 手机号找回密码：发重置码（号码需已注册且唯一归属） */
    @PostMapping("/sms/reset-code")
    public ApiResponse<?> sendSmsResetCode(@RequestBody Map<String, Object> req, HttpServletRequest request) {
        try {
            return ApiResponse.ok(smsCodeService.sendResetCode(str(req.get("phone")), clientIp(request)));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 手机号 + 重置码 + 新密码：成功即重置，不返回会话；多账号时先返回 needChoose 让用户选一个 */
    @PostMapping("/sms/reset")
    public ApiResponse<?> resetBySms(@RequestBody Map<String, Object> req) {
        try {
            Map<String, Object> r = userService.resetPasswordByPhone(str(req.get("phone")), str(req.get("code")),
                    str(req.get("newPassword")), str(req.get("chooseToken")), str(req.get("username")));
            return r != null ? ApiResponse.ok(r) : ApiResponse.ok("密码已重置，请用新密码登录");
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 邮箱找回密码：发重置码（邮箱需已注册且唯一归属） */
    @PostMapping("/email/reset-code")
    public ApiResponse<?> sendEmailResetCode(@RequestBody Map<String, Object> req, HttpServletRequest request) {
        try {
            return ApiResponse.ok(emailCodeService.sendResetCode(str(req.get("email")), clientIp(request)));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 邮箱 + 重置码 + 新密码：成功即重置，不返回会话 */
    @PostMapping("/email/reset")
    public ApiResponse<?> resetByEmail(@RequestBody Map<String, Object> req) {
        try {
            userService.resetPasswordByEmail(str(req.get("email")), str(req.get("code")), str(req.get("newPassword")));
            return ApiResponse.ok("密码已重置，请用新密码登录");
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
