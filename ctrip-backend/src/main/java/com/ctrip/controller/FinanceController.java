package com.ctrip.controller;

import com.ctrip.api.ApiResponse;
import com.ctrip.config.AuthInterceptor;
import com.ctrip.service.FinanceService;
import com.ctrip.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 悦途金融接口：授信 / 借款 / 还款 / 充值 / 投保 / 理赔 / 退保。
 * 全部要求登录（WebConfig 注册 /api/finance/**），只操作本人账户。
 */
@RestController
@RequestMapping("/api/finance")
public class FinanceController {

    private static final String EXPIRED = "登录已过期，请重新登录";

    private final FinanceService service;
    private final UserService userService;

    public FinanceController(FinanceService service, UserService userService) {
        this.service = service;
        this.userService = userService;
    }

    @GetMapping
    public ApiResponse<?> overview(HttpServletRequest request) {
        UserService.AuthUser user = currentUser(request);
        if (user == null) return ApiResponse.unauthorized(EXPIRED);
        return ApiResponse.ok(service.overview(user.id()));
    }

    @PostMapping("/credit")
    public ApiResponse<?> credit(@RequestBody Map<String, Object> req, HttpServletRequest request) {
        UserService.AuthUser user = currentUser(request);
        if (user == null) return ApiResponse.unauthorized(EXPIRED);
        try {
            return ApiResponse.ok(service.applyCredit(user.id(), str(req.get("realName")), str(req.get("idCard")),
                    intOf(req.get("income")), str(req.get("job"))));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/loan")
    public ApiResponse<?> loan(@RequestBody Map<String, Object> req, HttpServletRequest request) {
        UserService.AuthUser user = currentUser(request);
        if (user == null) return ApiResponse.unauthorized(EXPIRED);
        try {
            return ApiResponse.ok(service.borrow(user.id(), str(req.get("kind")), dbl(req.get("amount")),
                    intOf(req.get("months")), str(req.get("orderNo")), str(req.get("title"))));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/loan/{loanNo}/repay")
    public ApiResponse<?> repay(@PathVariable String loanNo, @RequestBody Map<String, Object> req, HttpServletRequest request) {
        UserService.AuthUser user = currentUser(request);
        if (user == null) return ApiResponse.unauthorized(EXPIRED);
        try {
            return ApiResponse.ok(service.repay(user.id(), loanNo, str(req.get("mode"))));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/wallet/topup")
    public ApiResponse<?> topup(@RequestBody Map<String, Object> req, HttpServletRequest request) {
        UserService.AuthUser user = currentUser(request);
        if (user == null) return ApiResponse.unauthorized(EXPIRED);
        try {
            return ApiResponse.ok(service.topup(user.id(), dbl(req.get("amount")), str(req.get("channel"))));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/policy")
    public ApiResponse<?> policy(@RequestBody Map<String, Object> req, HttpServletRequest request) {
        UserService.AuthUser user = currentUser(request);
        if (user == null) return ApiResponse.unauthorized(EXPIRED);
        try {
            return ApiResponse.ok(service.buyPolicy(user.id(), str(req.get("plan")), str(req.get("insured")),
                    str(req.get("destination")), str(req.get("startDate")), intOf(req.get("days"))));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/policy/{policyNo}/claim")
    public ApiResponse<?> claim(@PathVariable String policyNo, @RequestBody Map<String, Object> req, HttpServletRequest request) {
        UserService.AuthUser user = currentUser(request);
        if (user == null) return ApiResponse.unauthorized(EXPIRED);
        try {
            return ApiResponse.ok(service.claim(user.id(), policyNo, str(req.get("type")),
                    dbl(req.get("amount")), str(req.get("reason"))));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/policy/{policyNo}/surrender")
    public ApiResponse<?> surrender(@PathVariable String policyNo, HttpServletRequest request) {
        UserService.AuthUser user = currentUser(request);
        if (user == null) return ApiResponse.unauthorized(EXPIRED);
        try {
            return ApiResponse.ok(service.surrender(user.id(), policyNo));
        } catch (IllegalStateException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    private UserService.AuthUser currentUser(HttpServletRequest request) {
        return userService.findAuthUser(AuthInterceptor.resolveToken(request));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    private static int intOf(Object o) {
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static double dbl(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(o).trim());
        } catch (Exception e) {
            return 0;
        }
    }
}