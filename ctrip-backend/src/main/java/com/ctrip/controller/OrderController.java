package com.ctrip.controller;

import com.ctrip.api.ApiResponse;
import com.ctrip.service.OrderService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 订单接口：创建（服务端计价+扣库存）/ 列表 / 清空
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

    @DeleteMapping
    public ApiResponse<String> clear() {
        service.clearOrders();
        return ApiResponse.ok("cleared");
    }
}
