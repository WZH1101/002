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
 * 订单接口：下单（待支付）/ 支付 / 取消 / 退款 / 删除 / 批量取消。
 *
 * <p>写接口都需要登录，由 {@link com.ctrip.config.AuthInterceptor} 拦下 /api/orders/**，
 * 归属再按会话用户判定。改动前它们是匿名的：任何人都能读到全部订单的联系人姓名与手机号，
 * 任何人都能发一个 DELETE 清空整张表。
 *
 * <p>状态流转在服务端收敛（待支付 → 已支付 → 已退款；待支付 → 已取消），
 * 前端只发「做什么」，不报「变成什么」——请求体里的 status、金额一律丢弃。
 * 出参手机号已掩码（见 {@link com.ctrip.service.OrderService}），本层不再返回明文。
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

    /** 下单：生成「待支付」订单并锁定库存，超过支付窗口未付由定时任务自动取消 */
    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> req, HttpServletRequest request) {
        UserService.AuthUser user = currentUser(request);
        if (user == null) return ApiResponse.unauthorized(EXPIRED);
        try {
            Object key = req == null ? null : req.get("idemKey");
            return ApiResponse.ok(service.createOrder(req, user.id(), key == null ? null : String.valueOf(key)));
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

    /** 支付本人的待支付订单；超时订单会被就地取消并提示重新下单 */
    @PostMapping("/{orderNo}/pay")
    public ApiResponse<Map<String, Object>> pay(@PathVariable String orderNo, HttpServletRequest request) {
        UserService.AuthUser user = currentUser(request);
        if (user == null) return ApiResponse.unauthorized(EXPIRED);
        try {
            return ApiResponse.ok(service.payOrder(orderNo, user.id()));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 取消待支付订单：状态置为已取消并回补库存，订单保留（要留痕、要对账） */
    @PostMapping("/{orderNo}/cancel")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable String orderNo, HttpServletRequest request) {
        UserService.AuthUser user = currentUser(request);
        if (user == null) return ApiResponse.unauthorized(EXPIRED);
        try {
            return ApiResponse.ok(service.cancelOrder(orderNo, user.id()));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 退款：已支付订单转为已退款并回补库存。演示项目没有真实资金通道，只走状态与库存 */
    @PostMapping("/{orderNo}/refund")
    public ApiResponse<Map<String, Object>> refund(@PathVariable String orderNo, HttpServletRequest request) {
        UserService.AuthUser user = currentUser(request);
        if (user == null) return ApiResponse.unauthorized(EXPIRED);
        try {
            return ApiResponse.ok(service.refundOrder(orderNo, user.id()));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 删除已取消/已退款的订单记录（物理删除）；待支付、已支付不允许删 */
    @DeleteMapping("/{orderNo}")
    public ApiResponse<String> delete(@PathVariable String orderNo, HttpServletRequest request) {
        UserService.AuthUser user = currentUser(request);
        if (user == null) return ApiResponse.unauthorized(EXPIRED);
        try {
            int n = service.deleteOrder(orderNo, user.id());
            return ApiResponse.ok(n > 0 ? "已删除订单 " + orderNo : "订单未删除");
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /**
     * 批量取消当前账号全部「待支付」订单并回补库存。
     * 该操作不可逆（库存回补后要重新下单），因此额外要求显式确认参数。
     * 已支付订单不在这里处理——那是要退款，不是取消。
     */
    @DeleteMapping
    public ApiResponse<String> clear(@RequestParam(value = "confirm", required = false) String confirm,
                                     HttpServletRequest request) {
        UserService.AuthUser user = currentUser(request);
        if (user == null) return ApiResponse.unauthorized(EXPIRED);
        if (!"YES".equals(confirm)) {
            return ApiResponse.fail("批量取消待支付订单需要显式确认：请求需带 ?confirm=YES");
        }
        int cancelled = service.cancelAllPending(user.id());
        return ApiResponse.ok("已取消本人待支付订单 " + cancelled + " 条（库存已回补）");
    }

    /**
     * 取当前会话用户。正常情况拿不到这里为 null——拦截器已经校验过令牌；
     * 只有令牌恰好在拦截器放行后过期才会命中，此时返回 401 而不是 500。
     */
    private UserService.AuthUser currentUser(HttpServletRequest request) {
        return userService.findAuthUser(AuthInterceptor.resolveToken(request));
    }
}
