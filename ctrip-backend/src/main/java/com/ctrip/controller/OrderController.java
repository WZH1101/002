package com.ctrip.controller;

import com.ctrip.api.ApiResponse;
import com.ctrip.service.OrderService;
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
 * <p>已知限制：列表返回的是全量订单，因为 travel_order 没有归属人字段（无 user_id，
 * 注册时手机号也非必填）。按账号手机号过滤能让数据更隔离，但替家人订票时填的是乘机人手机号，
 * 过滤会造成用户「丢单」，所以这里宁可保持登录可见全量。
 * 彻底的按用户隔离需要一次库表迁移（加 user_id 并在下单时写入会话用户）。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> req) {
        try {
            return ApiResponse.ok(service.createOrder(req));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(service.listOrders());
    }

    /**
     * 清空订单。该操作不可逆且影响全表，因此额外要求显式确认参数，
     * 避免前端误发一个 DELETE 就把数据删光。
     */
    @DeleteMapping
    public ApiResponse<String> clear(@RequestParam(value = "confirm", required = false) String confirm) {
        if (!"YES".equals(confirm)) {
            return ApiResponse.fail("清空全部订单需要显式确认：请求需带 ?confirm=YES");
        }
        service.clearOrders();
        return ApiResponse.ok("cleared");
    }
}
