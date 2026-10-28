package com.ctrip.config;

import com.ctrip.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录态拦截器：保护有副作用或含个人信息的接口。
 *
 * <p>受保护范围（在 {@link WebConfig} 中注册）：
 * <ul>
 *   <li>/api/orders 的 POST/GET/DELETE —— 下单写入、订单列表含联系人姓名与手机号、DELETE 会清空全表</li>
 *   <li>/api/biztravel 的 POST —— 企业认证写入</li>
 * </ul>
 * 产品搜索类只读接口（/api/hotels、/api/trains 等）保持公开，前端游客可直接浏览。
 *
 * <p>token 读取优先级：Authorization: Bearer &gt; X-Token &gt; 查询参数 token。
 * 查询参数仅为兼容旧版前端保留，它会出现在访问日志与 Referer 中，前端应改用 Header。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 校验通过的 token 存入 request attribute，控制器可据此取回会话 */
    public static final String ATTR_TOKEN = "ctripToken";

    private final UserService userService;

    public AuthInterceptor(UserService userService) {
        this.userService = userService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // CORS 预检不带自定义 Header，直接放行给 CORS 处理链
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // 企业商旅的状态查询（GET /api/biztravel?company=xxx）不含个人信息，保持公开；
        // 只有 POST 写入要求登录。拦截器注册不能按 HTTP 方法区分，故在此判断。
        if ("GET".equalsIgnoreCase(request.getMethod())
                && request.getRequestURI().startsWith("/api/biztravel")) {
            return true;
        }
        String token = resolveToken(request);
        if (userService.isValidToken(token)) {
            request.setAttribute(ATTR_TOKEN, token);
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":401,\"msg\":\"请先登录后再操作\",\"data\":null}");
        return false;
    }

    /** 解析请求携带的令牌；控制器（如 /api/auth/me）复用同一套规则，避免两处实现漂移 */
    public static String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String value = header.substring(7).trim();
            if (!value.isEmpty()) return value;
        }
        String custom = request.getHeader("X-Token");
        if (custom != null && !custom.isBlank()) return custom.trim();
        String query = request.getParameter("token");
        return query == null || query.isBlank() ? null : query.trim();
    }
}
