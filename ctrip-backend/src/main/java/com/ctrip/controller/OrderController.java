package com.ctrip.controller;

import com.ctrip.api.ApiResponse;
import com.ctrip.config.AuthInterceptor;
import com.ctrip.service.OrderService;
import com.ctrip.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 订单接口：创建（服务端计价+扣库存）/ 列表 / 清空。
 *
 * <p>三个方法都需要登录，由 {@link com.ctrip.config.AuthInterceptor} 拦下 /api/orders/**。
 * 改动前它们是匿名的：任何人都能读到全部订单的联系人姓名与手机号，
 * 任何人都能发一个 DELETE 清空整张表。
 *
 * <p>登录之后还要按归属隔离：travel_order.user_id 记录下单人，
 * 列表只返回本人订单（外加加列之前 user_id 为 NULL 的历史订单，避免升级后「丢单」），
 * 清空只删本人订单。替家人朋友订票时填的是乘机人手机号，所以归属只看 user_id，不用手机号筛。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    /** 拦截器已校验令牌，此处为 null 只可能是令牌在请求处理中途过期 */
    private static final String EXPIRED = "登录已过期，请重新登录";

    private final OrderService service;
    private final UserService userService;

    public OrderController(OrderService service, UserService userService) {
        this.service = service;
        this.userService = userService;
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> req, HttpServletRequest request) {
        UserService.AuthUser user = currentUser(request);
        if (user == null) return ApiResponse.unauthorized(EXPIRED);
        try {
            return ApiResponse.ok(service.createOrder(req, user.id()));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(HttpServletRequest request) {
        UserService.AuthUser user = currentUser(request);
        if (user == null) return ApiResponse.unauthorized(EXPIRED);
        return ApiResponse.ok(service.listOrders(user.id()));
    }

    /**
     * 清空当前账号的订单。该操作不可逆，因此额外要求显式确认参数，
     * 避免前端误发一个 DELETE 就把数据删光。
     */
    @DeleteMapping
    public ApiResponse<String> clear(@RequestParam(value = "confirm", required = false) String confirm,
                                     HttpServletRequest request) {
        UserService.AuthUser user = currentUser(request);
        if (user == null) return ApiResponse.unauthorized(EXPIRED);
        if (!"YES".equals(confirm)) {
            return ApiResponse.fail("清空订单需要显式确认：请求需带 ?confirm=YES");
        }
        int deleted = service.clearOrders(user.id());
        return ApiResponse.ok("已清空本人订单 " + deleted + " 条");
    }

    /**
     * 取当前会话用户。正常情况拿不到这里为 null——拦截器已经校验过令牌；
     * 只有令牌恰好在拦截器放行后过期才会命中，此时返回 401 而不是 500。
     */
    private UserService.AuthUser currentUser(HttpServletRequest request) {
        return userService.findAuthUser(AuthInterceptor.resolveToken(request));
    }
}
