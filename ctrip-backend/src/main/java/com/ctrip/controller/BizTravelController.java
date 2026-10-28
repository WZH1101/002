package com.ctrip.controller;

import com.ctrip.api.ApiResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 企业商旅认证：提交认证申请 + 按企业名称查询认证状态（演示用，存 MySQL）
 */
@RestController
@RequestMapping("/api/biztravel")
public class BizTravelController {

    private final JdbcTemplate jdbc;

    public BizTravelController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping
    public ApiResponse<?> apply(@RequestBody Map<String, Object> req) {
        String company = str(req.get("company"));
        String creditCode = str(req.get("creditCode"));
        String contact = str(req.get("contact"));
        String phone = str(req.get("phone"));

        if (company == null || company.length() < 4) return ApiResponse.fail("请输入完整的企业名称");
        if (creditCode == null || !creditCode.matches("[0-9A-Z]{18}")) return ApiResponse.fail("统一社会信用代码需 18 位数字/大写字母");
        if (contact == null || contact.isEmpty()) return ApiResponse.fail("请输入经办人姓名");
        if (phone == null || !phone.matches("1[3-9]\\d{9}")) return ApiResponse.fail("请输入11位有效手机号码");

        List<Map<String, Object>> exists = jdbc.queryForList(
                "SELECT status FROM biz_travel_apply WHERE company = ? ORDER BY id DESC LIMIT 1", company);
        if (!exists.isEmpty()) {
            return ApiResponse.ok(Map.of("company", company, "status", exists.get(0).get("status"), "msg", "该企业已有认证记录"));
        }
        jdbc.update("INSERT INTO biz_travel_apply(company, credit_code, contact, phone, status) VALUES(?,?,?,?,?)",
                company, creditCode, contact, phone, "审核中");
        return ApiResponse.ok(Map.of("company", company, "status", "审核中", "msg", "认证申请已提交，1-3 个工作日内完成审核"));
    }

    @GetMapping
    public ApiResponse<?> query(@RequestParam String company) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT company, status, DATE_FORMAT(create_time,'%Y-%m-%d %H:%i') ct FROM biz_travel_apply WHERE company = ? ORDER BY id DESC LIMIT 1",
                company);
        if (rows.isEmpty()) return ApiResponse.fail("未找到该企业的认证记录");
        Map<String, Object> out = new LinkedHashMap<>(rows.get(0));
        return ApiResponse.ok(out);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }
}
