package com.ctrip.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单中心：创建订单（服务端计价 + 扣库存，事务保证一致性）、查询、清空
 */
@Service
public class OrderService {

    private static final Map<String, String> TYPE_NAMES = Map.ofEntries(
            Map.entry("hotel", "酒店"), Map.entry("attraction", "门票"), Map.entry("ticket", "门票"),
            Map.entry("train", "车票"), Map.entry("flight", "机票"), Map.entry("bus", "汽车票"),
            Map.entry("ship", "船票"), Map.entry("vacation", "旅游"), Map.entry("car", "用车"),
            Map.entry("shop", "全球购"), Map.entry("gift", "礼品卡"));

    private final JdbcTemplate jdbc;
    private final ProductService productService;

    public OrderService(JdbcTemplate jdbc, ProductService productService) {
        this.jdbc = jdbc;
        this.productService = productService;
    }

    /**
     * 创建订单。请求：{type, itemId, choice, quantity, days, name, phone, title, dateText}
     * 单价/总价以服务端计算为准
     */
    @Transactional
    public Map<String, Object> createOrder(Map<String, Object> req) {
        String type = str(req.get("type"));
        String choice = str(req.get("choice"));
        String name = str(req.get("name"));
        String phone = str(req.get("phone"));

        if (type == null || !TYPE_NAMES.containsKey(type)) throw new IllegalStateException("未知的预订类型");
        if (choice == null || choice.isEmpty()) throw new IllegalStateException("请选择预订项目");
        if (name == null || name.trim().isEmpty()) throw new IllegalStateException("请输入联系人姓名");
        if (!phone.matches("1[3-9]\\d{9}")) throw new IllegalStateException("请输入11位有效手机号码");

        long itemId;
        int qty;
        int days;
        try {
            itemId = Long.parseLong(String.valueOf(req.get("itemId")));
            qty = Integer.parseInt(String.valueOf(req.getOrDefault("quantity", 1)));
            days = Integer.parseInt(String.valueOf(req.getOrDefault("days", 1)));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("订单参数格式错误");
        }
        if (qty < 1 || qty > 99) throw new IllegalStateException("数量需在 1-99 之间");

        // 服务端计价 + 扣减库存（库存不足抛异常，事务回滚）
        Map<String, Object> price = productService.priceAndDeduct(type, itemId, choice, qty, days);

        String orderNo = "C" + System.currentTimeMillis();
        String title = str(req.getOrDefault("title", choice));
        String dateText = str(req.getOrDefault("dateText", ""));

        jdbc.update("INSERT INTO travel_order(order_no,type,item_id,title,date_text,choice_text,quantity,unit_price,total_price,contact_name,phone,status,pay_time) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,NOW())",
                orderNo, type, itemId, title, dateText, choice, qty,
                price.get("unitPrice"), price.get("totalPrice"), name.trim(), phone, "已支付");

        Map<String, Object> order = new LinkedHashMap<>();
        order.put("orderNo", orderNo);
        order.put("type", type);
        order.put("typeName", TYPE_NAMES.get(type));
        order.put("itemId", itemId);
        order.put("title", title);
        order.put("dateText", dateText);
        order.put("choiceText", choice);
        order.put("quantity", qty);
        order.put("unitPrice", price.get("unitPrice"));
        order.put("totalPrice", price.get("totalPrice"));
        order.put("passengerName", name.trim());
        order.put("phone", phone);
        order.put("status", "已支付");
        order.put("payTime", jdbc.queryForObject("SELECT DATE_FORMAT(pay_time,'%Y/%m/%d %H:%i:%s') FROM travel_order WHERE order_no = ?", String.class, orderNo));
        return order;
    }

    /** 订单列表（最新在前） */
    public List<Map<String, Object>> listOrders() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT order_no,type,item_id,title,date_text,choice_text,quantity,unit_price,total_price,contact_name,phone,status,"
                        + "DATE_FORMAT(pay_time,'%Y/%m/%d %H:%i:%s') AS pay_time FROM travel_order ORDER BY id DESC LIMIT 200");
        return rows.stream().map(r -> {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("orderNo", r.get("order_no"));
            o.put("type", r.get("type"));
            o.put("typeName", TYPE_NAMES.getOrDefault(r.get("type"), "订单"));
            o.put("itemId", r.get("item_id"));
            o.put("title", r.get("title"));
            o.put("dateText", r.get("date_text"));
            o.put("choiceText", r.get("choice_text"));
            o.put("quantity", r.get("quantity"));
            o.put("unitPrice", toNum(r.get("unit_price")));
            o.put("totalPrice", toNum(r.get("total_price")));
            o.put("passengerName", r.get("contact_name"));
            o.put("phone", r.get("phone"));
            o.put("status", r.get("status"));
            o.put("payTime", r.get("pay_time"));
            return o;
        }).toList();
    }

    public void clearOrders() {
        jdbc.update("DELETE FROM travel_order");
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Object toNum(Object o) {
        if (o instanceof java.math.BigDecimal bd) {
            return bd.stripTrailingZeros().scale() <= 0 ? bd.longValue() : bd.doubleValue();
        }
        return o;
    }
}
