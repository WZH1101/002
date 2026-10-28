package com.ctrip.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单中心：创建（服务端计价 + 锁库存）、支付、取消、退款、删除、超时自动取消。
 *
 * <p>状态机（travel_order.status，四态）：
 * <pre>
 *   待支付 ──支付──▶ 已支付 ──退款──▶ 已退款
 *      │                                
 *      ├─用户取消─▶ 已取消 ◀─超时自动取消─┘（同一出口，都回补库存）
 * </pre>
 * 改动前是「下单即已支付」：提交订单就等于付过钱了，于是关掉页面的人把库存占住没人释放，
 * 已支付的单子也只能说「取消」而不说退款，和真实业务对不上。现在下单只是锁库存，
 * 超过 {@code ctrip.order.pay-deadline-minutes} 未支付由 {@link OrderExpiryTask} 自动取消并回补。
 *
 * <p>三条一直生效的约束：
 * <ol>
 *   <li>单价/总价只认服务端计算结果，前端传来的金额一律丢弃；</li>
 *   <li>库存扣减与状态变更在同一个事务里，配套 {@link ProductService#restoreStock}
 *       在取消/退款时原样回补——此前只有扣没有加，删单后库存被永久磨掉；</li>
 *   <li>出参里的手机号做掩码（{@code 138****8000}）。库里仍是明文（改签、退票、客服核对都要用），
 *       但接口不再把整表联系人手机号原样吐给调用方。</li>
 * </ol>
 *
 * <p>归属：只有订单的 user_id 等于当前会话用户才允许支付/取消/退款/删除；
 * 加 user_id 之前那批无主历史订单在列表里可见（避免升级后「丢单」），但任何写操作都不放行。
 */
@Service
public class OrderService {

    /** 状态常量，写接口和出参都用它们，避免中文串在多处拼写漂移 */
    public static final String PENDING = "待支付";
    public static final String PAID = "已支付";
    public static final String CANCELLED = "已取消";
    public static final String REFUNDED = "已退款";

    private static final Map<String, String> TYPE_NAMES = Map.ofEntries(
            Map.entry("hotel", "酒店"), Map.entry("attraction", "门票"), Map.entry("ticket", "门票"),
            Map.entry("train", "车票"), Map.entry("flight", "机票"), Map.entry("bus", "汽车票"),
            Map.entry("ship", "船票"), Map.entry("vacation", "旅游"), Map.entry("car", "用车"),
            Map.entry("shop", "全球购"), Map.entry("gift", "礼品卡"));

    /** 订单查询字段（不含 phone 明文，掩码在出参做；user_id 只用于算各类可操作性标志） */
    private static final String SELECT_ORDER_COLUMNS =
            "SELECT order_no,type,item_id,user_id,title,date_text,choice_text,quantity,unit_price,total_price,"
                    + "contact_name,phone,status,"
                    + "DATE_FORMAT(pay_time,'%Y/%m/%d %H:%i:%s') AS pay_time,"
                    + "DATE_FORMAT(cancel_time,'%Y/%m/%d %H:%i:%s') AS cancel_time,"
                    + "DATE_FORMAT(refund_time,'%Y/%m/%d %H:%i:%s') AS refund_time,"
                    + "DATE_FORMAT(pay_deadline,'%Y/%m/%d %H:%i:%s') AS pay_deadline,"
                    + "UNIX_TIMESTAMP(pay_deadline) * 1000 AS pay_expire_ms FROM travel_order";

    private final JdbcTemplate jdbc;
    private final ProductService productService;
    /** 支付窗口（分钟）：超时未支付的订单由定时任务自动取消 */
    private final int payDeadlineMinutes;

    public OrderService(JdbcTemplate jdbc, ProductService productService,
                        @Value("${ctrip.order.pay-deadline-minutes:15}") int payDeadlineMinutes) {
        this.jdbc = jdbc;
        this.productService = productService;
        this.payDeadlineMinutes = Math.max(1, payDeadlineMinutes);
    }

    /** 供定时任务与状态页读取 */
    public int getPayDeadlineMinutes() {
        return payDeadlineMinutes;
    }

    /**
     * 创建订单（状态为「待支付」，同时锁定库存）。
     * 请求：{type, itemId, choice, quantity, days, name, phone, title, dateText, idemKey}
     *
     * <p>idemKey 是客户端生成的幂等键（前端沿用它预先生成的订单号）。重复提交同一个键不会二次扣库存，
     * 而是把已存在的那单返回——没有它时，双击「提交订单」或网络重试都会落下两笔真单。
     */
    @Transactional
    public Map<String, Object> createOrder(Map<String, Object> req, long userId, String idemKey) {
        if (idemKey != null && !idemKey.isBlank()) {
            Map<String, Object> existing = orderViewByQuery(userId, "WHERE idem_key = ? AND user_id = ?", idemKey, userId);
            if (existing != null) return existing;
        }

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
        String key = idemKey == null || idemKey.isBlank() ? null : idemKey.trim();
        Timestamp deadline = Timestamp.from(Instant.now().plusSeconds(payDeadlineMinutes * 60L));

        try {
            jdbc.update("INSERT INTO travel_order(order_no,idem_key,type,item_id,user_id,title,date_text,choice_text,"
                            + "quantity,unit_price,total_price,contact_name,phone,status,pay_deadline) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'待支付',?)",
                    orderNo, key, type, itemId, userId, title, dateText, choice, qty,
                    price.get("unitPrice"), price.get("totalPrice"), name.trim(), phone, deadline);
        } catch (DuplicateKeyException e) {
            // 并发下两个请求同时通过上面的存在性检查：唯一索引兜底，返回对手刚写入的那单
            if (key != null) {
                Map<String, Object> raced = orderViewByQuery(userId, "WHERE idem_key = ? AND user_id = ?", key, userId);
                if (raced != null) return raced;
            }
            throw new IllegalStateException("订单提交失败，请重试");
        }

        Map<String, Object> order = orderViewByQuery(userId, "WHERE order_no = ?", orderNo);
        return order == null ? new LinkedHashMap<>() : order;
    }

    /**
     * 订单列表（最新在前）：本人下的单 + 加 user_id 列之前的历史订单。
     * 四种状态都返回——订单中心要能看到「已取消/已退款」，用户才敢放心取消。
     *
     * <p>把 NULL 一并返回是有意的：老订单无从判断归属，若按归属严格过滤，
     * 用户会在升级后突然「丢单」。替家人朋友订票时 contact phone 是乘机人的，
     * 所以归属只看 user_id，绝不能用 phone 去筛。
     */
    public List<Map<String, Object>> listOrders(long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                SELECT_ORDER_COLUMNS + " WHERE user_id = ? OR user_id IS NULL ORDER BY id DESC LIMIT 200", userId);
        return rows.stream().map(r -> toOrderView(r, userId)).toList();
    }

    /**
     * 支付订单：只有本人的「待支付」且尚未超时才放行。
     *
     * <p>超时但定时任务还没来得及扫到的那一小段窗口，这里顺手把它取消掉再报错——
     * 否则用户可以点「立即支付」无限续期，倒计时就成了摆设。
     *
     * <p>{@code noRollbackFor} 是为这一步加的：IllegalStateException 是本方法唯一的业务失败出口，
     * 而超时分支在抛错之前已经写好了「已取消 + 回补库存」。用默认的 rollback-for-RuntimeException，
     * 那次取消会连库存一起被回滚，订单停在「待支付」等定时任务下一轮扫描——
     * 用户看到「已取消」的提示，余房却迟迟不回来（冒烟脚本第 9 节实测到过）。
     * 其余抛出点都在写操作之前或影响 0 行的 UPDATE 之后，没有需要回滚的中间状态。
     */
    @Transactional(noRollbackFor = IllegalStateException.class)
    public Map<String, Object> payOrder(String orderNo, long userId) {
        Map<String, Object> row = ownOrder(orderNo, userId);
        String status = (String) row.get("status");
        if (PAID.equals(status)) throw new IllegalStateException("该订单已支付，无需重复支付");
        if (CANCELLED.equals(status)) throw new IllegalStateException("该订单已取消，请重新下单");
        if (REFUNDED.equals(status)) throw new IllegalStateException("该订单已退款，请重新下单");

        Long expired = jdbc.queryForObject("SELECT COUNT(*) FROM travel_order"
                + " WHERE order_no = ? AND user_id = ? AND status = '待支付' AND pay_deadline <= NOW()",
                Long.class, orderNo, userId);
        if (expired != null && expired > 0) {
            cancelPending(row, userId);
            throw new IllegalStateException("支付超时，订单已自动取消，请重新下单");
        }

        int changed = jdbc.update("UPDATE travel_order SET status = '已支付', pay_time = NOW() "
                + "WHERE order_no = ? AND user_id = ? AND status = '待支付'", orderNo, userId);
        if (changed == 0) throw new IllegalStateException("订单状态已变化，请刷新后重试");
        return toOrderView(jdbc.queryForMap(SELECT_ORDER_COLUMNS + " WHERE order_no = ?", orderNo), userId);
    }

    /**
     * 取消订单：只有「待支付」可以直接取消（钱没付，不涉及资金退回）。
     * 已支付的单请走 {@link #refundOrder}——以前这里把已支付订单一并置为「已取消」，
     * 相当于把一笔退款悄悄做成了取消，账目对不上。
     */
    @Transactional
    public Map<String, Object> cancelOrder(String orderNo, long userId) {
        Map<String, Object> row = ownOrder(orderNo, userId);
        String status = (String) row.get("status");
        if (CANCELLED.equals(status)) throw new IllegalStateException("该订单已取消");
        if (REFUNDED.equals(status)) throw new IllegalStateException("该订单已退款");
        if (PAID.equals(status)) throw new IllegalStateException("订单已支付，请走退款而不是取消");
        cancelPending(row, userId);
        return toOrderView(jdbc.queryForMap(SELECT_ORDER_COLUMNS + " WHERE order_no = ?", orderNo), userId);
    }

    /** 退款：本人的「已支付」订单 → 已退款，回补库存。演示项目没有真实资金通道，见 README 的限制说明 */
    @Transactional
    public Map<String, Object> refundOrder(String orderNo, long userId) {
        Map<String, Object> row = ownOrder(orderNo, userId);
        String status = (String) row.get("status");
        if (REFUNDED.equals(status)) throw new IllegalStateException("该订单已退款");
        if (CANCELLED.equals(status)) throw new IllegalStateException("该订单未支付即已取消，无需退款");
        if (!PAID.equals(status)) throw new IllegalStateException("只有已支付订单可以退款");

        int changed = jdbc.update("UPDATE travel_order SET status = '已退款', refund_time = NOW() "
                + "WHERE order_no = ? AND user_id = ? AND status = '已支付'", orderNo, userId);
        if (changed == 0) throw new IllegalStateException("订单状态已变化，请刷新后重试");
        restore(row);
        return toOrderView(jdbc.queryForMap(SELECT_ORDER_COLUMNS + " WHERE order_no = ?", orderNo), userId);
    }

    /**
     * 删除订单一行：仅限本人「已取消 / 已退款」的订单。
     * 这两类钱已经结清（没付出去或已退回），留在列表里只是噪音；
     * 待支付、已支付的订单删除会连带丢掉支付凭据，所以不放行。
     */
    @Transactional
    public int deleteOrder(String orderNo, long userId) {
        Map<String, Object> row = ownOrder(orderNo, userId);
        String status = (String) row.get("status");
        if (!CANCELLED.equals(status) && !REFUNDED.equals(status)) {
            throw new IllegalStateException("只有已取消或已退款的订单可以删除");
        }
        return jdbc.update("DELETE FROM travel_order WHERE order_no = ? AND user_id = ? AND status IN ('已取消','已退款')",
                orderNo, userId);
    }

    /**
     * 取消当前用户全部「待支付」订单并回补库存，返回取消条数。
     *
     * <p>此前这条路径是 {@code DELETE FROM travel_order WHERE user_id = ?}：
     * 行被物理删掉，扣掉的库存不会回来，也留不下任何痕迹。
     * 历史遗留的 user_id 为 NULL 的订单不归任何人，这里不动，避免误删他人的历史数据。
     */
    @Transactional
    public int cancelAllPending(long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT order_no,type,item_id,choice_text,quantity FROM travel_order"
                        + " WHERE user_id = ? AND status = '待支付'", userId);
        for (Map<String, Object> row : rows) {
            jdbc.update("UPDATE travel_order SET status = '已取消', cancel_time = NOW() WHERE order_no = ?",
                    row.get("order_no"));
            productService.restoreStock((String) row.get("type"), ((Number) row.get("item_id")).longValue(),
                    (String) row.get("choice_text"), ((Number) row.get("quantity")).intValue());
        }
        return rows.size();
    }

    /**
     * 超时未支付自动取消（供 {@link OrderExpiryTask} 每分钟调用）。
     * 返回本次取消条数；库存逐单回补，动作和「用户主动取消」完全一致。
     */
    @Transactional
    public int cancelExpiredOrders() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT order_no,user_id,type,item_id,choice_text,quantity FROM travel_order"
                        + " WHERE status = '待支付' AND pay_deadline IS NOT NULL AND pay_deadline <= NOW()"
                        + " ORDER BY id LIMIT 200");
        for (Map<String, Object> row : rows) {
            int changed = jdbc.update("UPDATE travel_order SET status = '已取消', cancel_time = NOW()"
                    + " WHERE order_no = ? AND status = '待支付'", row.get("order_no"));
            if (changed > 0) {
                productService.restoreStock((String) row.get("type"), ((Number) row.get("item_id")).longValue(),
                        (String) row.get("choice_text"), ((Number) row.get("quantity")).intValue());
            }
        }
        return rows.size();
    }

    /** 待支付 → 已取消 + 回补库存；调用方已确认状态为待支付 */
    private void cancelPending(Map<String, Object> row, long userId) {
        String orderNo = (String) row.get("order_no");
        int changed = jdbc.update("UPDATE travel_order SET status = '已取消', cancel_time = NOW() "
                + "WHERE order_no = ? AND user_id = ? AND status = '待支付'", orderNo, userId);
        if (changed == 0) return;
        restore(row);
    }

    private void restore(Map<String, Object> row) {
        productService.restoreStock((String) row.get("type"), ((Number) row.get("item_id")).longValue(),
                (String) row.get("choice_text"), ((Number) row.get("quantity")).intValue());
    }

    /** 取本人订单一行；不存在或不属于本人一律拒绝（不区分「不存在」和「没权限」，避免订单号探测） */
    private Map<String, Object> ownOrder(String orderNo, long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT order_no,type,item_id,choice_text,quantity,status FROM travel_order"
                        + " WHERE order_no = ? AND user_id = ?", orderNo, userId);
        if (rows.isEmpty()) throw new IllegalStateException("订单不存在或不属于当前账号");
        return rows.get(0);
    }

    /** 按条件取一单并转成出参结构；不存在返回 null */
    private Map<String, Object> orderViewByQuery(long viewerId, String where, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(SELECT_ORDER_COLUMNS + " " + where, args);
        return rows.isEmpty() ? null : toOrderView(rows.get(0), viewerId);
    }

    /**
     * 行转接口出参。viewerId 用来算四个可操作性标志：
     * 前端按标志决定按钮显示与否，避免点了才知道没权限或状态不对。
     */
    private static Map<String, Object> toOrderView(Map<String, Object> r, long viewerId) {
        Map<String, Object> o = new LinkedHashMap<>();
        Object owner = r.get("user_id");
        boolean mine = owner instanceof Number n && n.longValue() == viewerId;
        String status = r.get("status") == null ? PENDING : (String) r.get("status");
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
        o.put("phone", maskPhone((String) r.get("phone")));
        o.put("status", status);
        o.put("payTime", r.get("pay_time"));
        o.put("cancelTime", r.get("cancel_time"));
        o.put("refundTime", r.get("refund_time"));
        o.put("payDeadline", r.get("pay_deadline"));
        o.put("payExpireMs", r.get("pay_expire_ms") == null ? null : toNum(r.get("pay_expire_ms")));
        o.put("payable", mine && PENDING.equals(status));
        o.put("cancellable", mine && PENDING.equals(status));
        o.put("refundable", mine && PAID.equals(status));
        o.put("deletable", mine && (CANCELLED.equals(status) || REFUNDED.equals(status)));
        return o;
    }

    /** 13812345678 -> 138****5678；非 11 位（脏数据）原样返回，避免把信息变成空串 */
    private static String maskPhone(String phone) {
        if (phone == null || phone.length() != 11) return phone;
        return phone.substring(0, 3) + "****" + phone.substring(7);
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
