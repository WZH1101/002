package com.ctrip.controller;

import com.ctrip.api.ApiResponse;
import com.ctrip.service.ProductService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 产品搜索接口（五模块）
 */
@RestController
@RequestMapping("/api")
public class ProductController {

    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @GetMapping("/hotels")
    public ApiResponse<List<Map<String, Object>>> hotels(@RequestParam String city,
                                                         @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.searchHotels(city, keyword));
    }

    @GetMapping("/attractions")
    public ApiResponse<List<Map<String, Object>>> attractions(@RequestParam String city,
                                                              @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.searchAttractions(city, keyword));
    }

    @GetMapping("/trains")
    public ApiResponse<List<Map<String, Object>>> trains(@RequestParam String from, @RequestParam String to) {
        return ApiResponse.ok(service.searchTrains(from, to));
    }

    @GetMapping("/flights")
    public ApiResponse<List<Map<String, Object>>> flights(@RequestParam String from, @RequestParam String to) {
        return ApiResponse.ok(service.searchFlights(from, to));
    }

    @GetMapping("/buses")
    public ApiResponse<List<Map<String, Object>>> buses(@RequestParam String from, @RequestParam String to) {
        return ApiResponse.ok(service.searchBuses(from, to));
    }

    @GetMapping("/ships")
    public ApiResponse<List<Map<String, Object>>> ships(@RequestParam String from, @RequestParam String to) {
        return ApiResponse.ok(service.searchShips(from, to));
    }

    // ==================== 新模块（旅游/用车/礼品卡/全球购） ====================

    @GetMapping("/vacations")
    public ApiResponse<List<Map<String, Object>>> vacations(@RequestParam String city,
            @RequestParam(required = false) String style) {
        return ApiResponse.ok(service.searchVacations(city, style));
    }

    @GetMapping("/cars")
    public ApiResponse<List<Map<String, Object>>> cars(@RequestParam String city) {
        return ApiResponse.ok(service.searchCars(city));
    }

    @GetMapping("/giftcards")
    public ApiResponse<List<Map<String, Object>>> giftCards() {
        return ApiResponse.ok(service.listGiftCards());
    }

    @GetMapping("/shop")
    public ApiResponse<List<Map<String, Object>>> shop(@RequestParam(required = false) String category) {
        return ApiResponse.ok(service.listShopProducts(category));
    }
}
