package com.ctrip.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * 加 user_id 之前那批无主历史订单，在列出时就地认领给当前账号（见 {@link #listOrders}），
 * 认领之后归属与可操作性都和普通订单一致。
 */
@Service
public class OrderService {

    /** 状态常量，写接口和出参都用它们，避免中文串在多处拼写漂移 */
    public static final String PENDING = "待支付";
    public static final String PAID = "已支付";
    public static final String CANCELLED = "已取消";
    public static final String REFUNDED = "已退款";

    /** 每笔订单最多改签次数（火车票第 2 次起收 5% 改签费，机票 24 小时内收 10%） */
    private static final int MAX_CHANGES = 2;

    /** 出行时间（yyyy-MM-dd HH:mm）在 date_text 里的位置：机票还带机场括号，这里只取日期与时刻 */
    private static final Pattern DEP_TIME = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})\\s+(\\d{2}):(\\d{2})");

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
                    + "UNIX_TIMESTAMP(pay_deadline) * 1000 AS pay_expire_ms,"
                    // 改签次数决定「还能不能改、这一次收不收费」，出参里一并带上，前端不必再单独请求
                    + "(SELECT COUNT(*) FROM order_change oc WHERE oc.order_no = travel_order.order_no) AS change_count"
                    + " FROM travel_order";

    /** 改签历史字段：order_no 索引命中，seq 从 1 递增 */
    private static final String SELECT_CHANGE_COLUMNS =
            "SELECT seq,old_title,new_title,old_date_text,new_date_text,old_choice,new_choice,"
                    + "old_unit_price,old_total_price,new_unit_price,new_total_price,fee,diff,"
                    + "DATE_FORMAT(create_time,'%Y/%m/%d %H:%i:%s') AS change_time FROM order_change";

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

        // 服务端计价 + 扣减库存（库存不足抛异常，事务回滚）；机票经济舱要按出行日期算淡旺季
        String dateText = str(req.getOrDefault("dateText", ""));
        Map<String, Object> price = productService.priceAndDeduct(type, itemId, choice, qty, days, departDate(dateText));

        String orderNo = "C" + System.currentTimeMillis();
        String title = str(req.getOrDefault("title", choice));
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
     * <p>替家人朋友订票时 contact phone 是乘机人的，所以归属只看 user_id，绝不能用 phone 去筛。
     *
     * <p>无主历史单（{@code user_id IS NULL}）在列出时就地认领给当前账号。
     * 以前只是把它们一并返回、四个可操作性标志恒为 {@code false}，于是订单中心里会出现
     * 两笔同样「已支付」的单子——自己下的能点「申请退款」，无主的那笔按钮直接消失，
     * 可见却按不动，用户只会当成 bug。这批单本来就没有归属信息可判，演示站里也就是
     * 这台浏览器自己产下的，所以采用 README 原先写下的那条断开方式（一条 UPDATE 认领），
     * 并把动作放在列表接口里自动做掉：认领后归属、按钮、写操作校验全部与普通单一致。
     */
    @Transactional
    public List<Map<String, Object>> listOrders(long userId) {
        jdbc.update("UPDATE travel_order SET user_id = ? WHERE user_id IS NULL", userId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                SELECT_ORDER_COLUMNS + " WHERE user_id = ? ORDER BY id DESC LIMIT 200", userId);
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
     * 改签：已支付的机票/火车票订单换成同航线的新日期、新班次、新舱位。
     *
     * <p>请求：{itemId, choice, date}。前端传的 title/dateText/金额一律丢弃：
     * 新班次的展示文案与票价都从库表重新取、重新算，与下单走同一个
     * {@link ProductService#priceAndDeduct}，不会出现「改签后价格和官网不一致」。
     *
     * <p>手续费规则（服务端唯一口径，前端只做展示）：
     * <ul>
     *   <li>火车票：首次改签免费，第 2 次起收新票款的 5%，最低 ¥2；</li>
     *   <li>机票：距原航班起飞超过 24 小时免费，24 小时内收原票款的 10%，最低 ¥30；</li>
     *   <li>每笔订单最多改签 {@value #MAX_CHANGES} 次，差价多退少补。</li>
     * </ul>
     *
     * <p>库存：先回补旧班次占用的席位，再扣新班次的——同一班次换日期/舱位时就落在同一个 SKU 上，
     * 顺序反了会把自己挡在「余票不足」外面。新旧库存在同一事务里，失败一起回滚。
     *
     * <p>区间一致性由前端候选保证（只列原航线的班次）：后端拿不到下单时的出发/到达城市，
     * 不在这里假装校验，而真实价格与班次信息仍以服务端为准。
     */
    @Transactional
    public Map<String, Object> changeOrder(String orderNo, long userId, Map<String, Object> req) {
        if (req == null) throw new IllegalStateException("改签参数缺失");
        Map<String, Object> row = ownOrder(orderNo, userId);
        String type = (String) row.get("type");
        if (!"flight".equals(type) && !"train".equals(type)) {
            throw new IllegalStateException("只有机票 / 火车票订单支持改签");
        }
        if (!PAID.equals(row.get("status"))) throw new IllegalStateException("只有已支付订单可以改签");

        Long done = jdbc.queryForObject("SELECT COUNT(*) FROM order_change WHERE order_no = ?", Long.class, orderNo);
        int seq = (done == null ? 0 : done.intValue()) + 1;
        if (seq > MAX_CHANGES) throw new IllegalStateException("每笔订单最多改签 " + MAX_CHANGES + " 次");

        long newItemId;
        try {
            newItemId = Long.parseLong(String.valueOf(req == null ? null : req.get("itemId")));
        } catch (NumberFormatException e) {
            // 本地演示数据用随机浮点 id，发到这里就是前端口径错了，不能当真实班次处理
            throw new IllegalStateException("改签班次无效，请重新选择");
        }
        String choice = str(req.get("choice"));
        String date = str(req.get("date"));
        if (choice == null || choice.isBlank()) throw new IllegalStateException("请选择新的座位 / 舱位");
        if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalStateException("请选择改签日期");

        int qty = ((Number) row.get("quantity")).intValue();
        long oldItemId = ((Number) row.get("item_id")).longValue();
        String oldChoice = (String) row.get("choice_text");
        double oldTotal = row.get("total_price") == null ? 0 : ((Number) row.get("total_price")).doubleValue();

        Map<String, Object> product = productSummary(type, newItemId, date);
        String newTitle = (String) product.get("title");
        String newDateText = (String) product.get("dateText");

        productService.restoreStock(type, oldItemId, oldChoice, qty);
        // 新日期可能落在旺季/淡季，机票经济舱按同一个公式重算
        Map<String, Object> price = productService.priceAndDeduct(type, newItemId, choice, qty, 1, date);
        double newUnit = ((Number) price.get("unitPrice")).doubleValue();
        double newTotal = ((Number) price.get("totalPrice")).doubleValue();
        double fee = changeFee(type, stringOf(row.get("date_text")), seq, oldTotal);
        double diff = round2(newTotal + fee - oldTotal);

        // 累计实付 = 新票款 + 改签费：差价在这次改签时由前端完成收银（多退少补）
        int changed = jdbc.update("UPDATE travel_order SET item_id = ?, title = ?, date_text = ?, choice_text = ?,"
                        + " unit_price = ?, total_price = ? WHERE order_no = ? AND user_id = ? AND status = '已支付'",
                newItemId, newTitle, newDateText, choice,
                money(newUnit), money(newTotal + fee), orderNo, userId);
        if (changed == 0) throw new IllegalStateException("订单状态已变化，请刷新后重试");

        try {
            jdbc.update("INSERT INTO order_change(order_no,seq,old_item_id,new_item_id,old_title,new_title,"
                            + "old_date_text,new_date_text,old_choice,new_choice,old_unit_price,old_total_price,"
                            + "new_unit_price,new_total_price,fee,diff) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    orderNo, seq, oldItemId, newItemId, row.get("title"), newTitle,
                    row.get("date_text"), newDateText, oldChoice, choice,
                    row.get("unit_price"), row.get("total_price"), money(newUnit), money(newTotal),
                    money(fee), money(diff));
        } catch (DuplicateKeyException e) {
            // 并发改签：两边都算成同一 seq，唯一索引兜底后整笔回滚（库存、金额都跟着退）
            throw new IllegalStateException("改签正在处理中，请刷新后重试");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("order", orderViewByQuery(userId, "WHERE order_no = ?", orderNo));
        out.put("change", changeView(jdbc.queryForMap(SELECT_CHANGE_COLUMNS
                + " WHERE order_no = ? AND seq = ?", orderNo, seq)));
        return out;
    }

    /** 改签历史（最新一轮在前）：先在 ownOrder 里校验归属，避免拿别人的订单号查记录 */
    public List<Map<String, Object>> listChanges(String orderNo, long userId) {
        ownOrder(orderNo, userId);
        return jdbc.queryForList(SELECT_CHANGE_COLUMNS + " WHERE order_no = ? ORDER BY seq", orderNo)
                .stream().map(OrderService::changeView).toList();
    }

    /** 删订单一行时连带清掉它的改签记录（订单都删了，改签流水留着没有归属可挂） */
    private int deleteChanges(String orderNo) {
        return jdbc.update("DELETE FROM order_change WHERE order_no = ?", orderNo);
    }

    /**
     * 从库表取新班次并组装展示文案（不接受前端传标题）。
     * dateText 格式与下单时 confirmBooking 生成的完全一致，订单详情/列表不用改渲染。
     */
    private Map<String, Object> productSummary(String type, long itemId, String date) {
        Map<String, Object> out = new LinkedHashMap<>();
        if ("train".equals(type)) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT train_no,from_station,to_station,dep_time FROM train_ticket WHERE id = ?", itemId);
            if (rows.isEmpty()) throw new IllegalStateException("改签车次不存在");
            Map<String, Object> r = rows.get(0);
            out.put("title", stringOf(r.get("train_no")) + " " + stringOf(r.get("from_station"))
                    + "→" + stringOf(r.get("to_station")));
            out.put("dateText", date + " " + stringOf(r.get("dep_time")));
            return out;
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT flight_no,airline,from_airport,to_airport,dep_time FROM flight WHERE id = ?", itemId);
        if (rows.isEmpty()) throw new IllegalStateException("改签航班不存在");
        Map<String, Object> r = rows.get(0);
        out.put("title", stringOf(r.get("airline")) + " " + stringOf(r.get("flight_no")));
        out.put("dateText", date + " " + stringOf(r.get("dep_time"))
                + "（" + stringOf(r.get("from_airport")) + "→" + stringOf(r.get("to_airport")) + "）");
        return out;
    }

    /**
     * 改签手续费。seq 从 1 开始（首次改签）。
     * 机票以「原订单的出行时刻」为基准：老订单的 date_text 解析不出时间时按免费处理，
     * 不能因为字段缺失就默默多收一笔钱。
     */
    private static double changeFee(String type, String oldDateText, int seq, double oldTotal) {
        if ("train".equals(type)) {
            return seq == 1 ? 0 : Math.max(2, round2(oldTotal * 0.05));
        }
        Long hours = hoursToDeparture(oldDateText);
        if (hours == null) return 0;
        return hours > 24 ? 0 : Math.max(30, round2(oldTotal * 0.10));
    }

    /** 从 date_text（如「2026-11-22 08:30（北京首都→上海虹桥）」）里取出行日期；取不到返回 null */
    private static String departDate(String dateText) {
        if (dateText == null) return null;
        Matcher m = DEP_TIME.matcher(dateText);
        if (!m.find()) return null;
        return m.group(1) + "-" + m.group(2) + "-" + m.group(3);
    }

    /** 距 date_text 里的出行时刻还有多少小时；解析不出返回 null */
    private static Long hoursToDeparture(String dateText) {
        if (dateText == null) return null;
        Matcher m = DEP_TIME.matcher(dateText);
        if (!m.find()) return null;
        LocalDateTime dep = LocalDateTime.of(
                Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)));
        return Duration.between(LocalDateTime.now(), dep).toHours();
    }

    private static Map<String, Object> changeView(Map<String, Object> r) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("seq", r.get("seq"));
        o.put("time", r.get("change_time"));
        o.put("oldTitle", r.get("old_title"));
        o.put("newTitle", r.get("new_title"));
        o.put("oldDateText", r.get("old_date_text"));
        o.put("newDateText", r.get("new_date_text"));
        o.put("oldChoice", r.get("old_choice"));
        o.put("newChoice", r.get("new_choice"));
        o.put("oldTotal", toNum(r.get("old_total_price")));
        o.put("newTotal", toNum(r.get("new_total_price")));
        o.put("fee", toNum(r.get("fee")));
        o.put("diff", toNum(r.get("diff")));
        return o;
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
        int changed = jdbc.update("DELETE FROM travel_order WHERE order_no = ? AND user_id = ? AND status IN ('已取消','已退款')",
                orderNo, userId);
        if (changed > 0) deleteChanges(orderNo);
        return changed;
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
                "SELECT order_no,type,item_id,title,date_text,choice_text,quantity,unit_price,total_price,status"
                        + " FROM travel_order WHERE order_no = ? AND user_id = ?", orderNo, userId);
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
        Object count = r.get("change_count");
        long changeCount = count instanceof Number n ? n.longValue() : 0;
        boolean transport = "flight".equals(r.get("type")) || "train".equals(r.get("type"));
        o.put("changeCount", changeCount);
        // 只有本人的已支付机票/火车票、且改签次数未用完才允许改签；前端按这个标志显隐按钮
        o.put("changeable", mine && PAID.equals(status) && transport && changeCount < MAX_CHANGES);
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

    /** 列值转字符串，null 当空串（拼展示文案时用，避免冒出 "null"） */
    private static String stringOf(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    /** 金额入库：整数去掉小数位，与 ProductService.tidy 的口径一致 */
    private static Object money(double v) {
        double r = round2(v);
        return r == Math.floor(r) ? (Object) (long) r : (Object) r;
    }

    private static Object toNum(Object o) {
        if (o instanceof java.math.BigDecimal bd) {
            return bd.stripTrailingZeros().scale() <= 0 ? bd.longValue() : bd.doubleValue();
        }
        return o;
    }
}
