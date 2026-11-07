package com.ctrip.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 根路径状态页：访问 http://localhost:8080/ 时展示服务状态与接口清单，
 * 替代 Spring Boot 默认的 Whitelabel 404 兜底页。
 */
@RestController
public class IndexController {

    private static final Logger log = LoggerFactory.getLogger(IndexController.class);

    /** 状态页允许统计的表，写死白名单；即使将来有人往 count() 传外部参数也不会拼进 SQL */
    private static final Set<String> STAT_TABLES = Set.of(
            "hotel", "attraction", "train_ticket", "flight", "bus_ticket", "travel_order",
            "app_user", "user_session");

    private final JdbcTemplate jdbc;

    /** 支付窗口，状态页要说清楚超时多久自动取消，和 OrderService 读同一个配置项 */
    @Value("${ctrip.order.pay-deadline-minutes:15}")
    private int payDeadlineMinutes;

    public IndexController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping(value = {"/", "/index.html"}, produces = "text/html;charset=UTF-8")
    public String index() {
        String stats;
        try {
            long hotels = count("hotel");
            long spots = count("attraction");
            long trains = count("train_ticket");
            long flights = count("flight");
            long buses = count("bus_ticket");
            long orders = count("travel_order");
            long users = count("app_user");
            long sessions = count("user_session");
            stats = "<table>"
                    + row("酒店", hotels) + row("景点门票", spots) + row("火车票", trains)
                    + row("航班", flights) + row("汽车票班次", buses) + row("订单", orders)
                    + row("账号", users) + row("会话记录", sessions)
                    + "</table>";
        } catch (Exception e) {
            // 原始异常只记日志，不把驱动报错/库名/连接串回显到公开页面
            log.warn("状态页统计失败", e);
            stats = "<p class='bad'>⚠ 数据库未就绪，请先启动 MySQL 并导入 sql/init.sql</p>";
        }
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                <meta charset="UTF-8">
                <title>悦途预订平台 · 后端服务</title>
                <style>
                  body{font-family:"Microsoft YaHei",sans-serif;background:#f5f7fa;margin:0;padding:40px;color:#333}
                  .card{max-width:720px;margin:0 auto;background:#fff;border-radius:12px;padding:32px 40px;box-shadow:0 4px 16px rgba(0,0,0,.08)}
                  h1{margin:0 0 4px;font-size:22px}
                  h1 .ok{color:#1fa971;font-size:14px;font-weight:normal}
                  .sub{color:#888;font-size:13px;margin-bottom:20px}
                  table{border-collapse:collapse;width:100%;margin-bottom:20px}
                  td{border-bottom:1px solid #eee;padding:8px 4px;font-size:14px}
                  td:last-child{text-align:right;font-weight:bold;color:#2577e3}
                  code{background:#eef3fb;color:#2577e3;padding:2px 8px;border-radius:4px;font-size:13px}
                  li{margin:8px 0;font-size:14px}
                  .bad{color:#e04c4c}
                  a.btn{display:inline-block;margin-top:16px;background:#2577e3;color:#fff;text-decoration:none;padding:10px 22px;border-radius:6px;font-size:14px}
                </style>
                </head>
                <body>
                  <div class="card">
                    <h1>悦途预订平台 · Java 后端服务 <span class="ok">● 运行中</span></h1>
                    <div class="sub">Spring Boot 3.2.5 + MySQL 8 · 端口 8080</div>
                    <h3 style="font-size:15px;margin:0 0 8px">📊 数据库实时统计</h3>
                    __STATS__
                    <h3 style="font-size:15px;margin:16px 0 8px">🔌 可用接口</h3>
                    <ul>
                      <li><code>GET /api/hotels?city=北京</code> 酒店搜索</li>
                      <li><code>GET /api/attractions?city=杭州</code> 景点门票搜索</li>
                      <li><code>GET /api/trains?from=北京&amp;to=上海</code> 火车票搜索</li>
                      <li><code>GET /api/flights?from=北京&amp;to=上海</code> 机票搜索</li>
                      <li><code>GET /api/buses?from=北京&amp;to=天津</code> 汽车票搜索</li>
                      <li><code>POST /api/auth/register|login|logout</code>、<code>GET /api/auth/me</code> 账号与登录态</li>
                      <li>登录五种方式（前端默认展第一种）：账号密码（用户名或<b>注册邮箱</b>均可）<code>POST /api/auth/login</code> · 手机验证码
                          <code>/api/auth/sms/send</code> + <code>/api/auth/sms/login</code> · 邮箱验证码
                          <code>/api/auth/email/send</code> + <code>/api/auth/email/login</code> ·
                          扫码 <code>POST /api/auth/qrcode</code> 出二维码，手机打开 <code>GET /scan?scene=…</code> 确认后由本端轮询领取 ·
                          第三方授权（与注册同一条路，见下）</li>
                      <li>注册三种方式，<b>用户名一律由服务端自动生成</b>（用户名+密码注册已下线：它不校验任何东西）：
                          手机号+验证码 <code>/api/auth/sms/register-code</code> · 邮箱+验证码 <code>/api/auth/email/register-code</code>
                          （都再提交 <code>POST /api/auth/register</code>，建号当场签发会话）·
                          第三方授权 <code>POST /api/auth/oauth</code> 拿 state，打开
                          <code>GET /oauth/authorize?state=…</code> 这张<b>本站模拟的授权页</b>同意后由发起页轮询领取；
                          <b>首次授权即注册</b>，同一 openId 再授权就是登录，所以登录面板底部那一排渠道
                          （目前五个：微信/QQ/微博/支付宝/百度，清单以后端 <code>OAuthService</code> 的注册表为准）也能直接登进来</li>
                      <li><code>POST /api/support/chat</code> 7×24 客服问答 —— <b>规则演示，未接大模型</b>；无需登录，单条 500 字上限、单 IP 每分钟 30 条</li>
                      <li><code>POST /api/auth/password</code> 修改密码 · <b>改密后全端令牌失效</b></li>
                      <li><code>POST /api/orders</code> 下单（生成<b>待支付</b>订单并锁定库存）· <code>GET /api/orders</code> 本人订单列表</li>
                      <li><code>POST /api/orders/{orderNo}/pay|cancel|refund</code> 支付 / 取消 / 退款 · <code>DELETE /api/orders/{orderNo}</code> 删除已取消或已退款订单</li>
                      <li><code>DELETE /api/orders?confirm=YES</code> 批量取消本人全部待支付订单</li>
                      <li>待支付订单超过 <code>__PAY_WINDOW__</code> 分钟未支付，由定时任务自动取消并回补库存</li>
                    </ul>
                    <div class="sub">提示：前端页面请直接打开本地文件「悦途旅行预订.html」，页面会自动调用以上接口。</div>
                    <a class="btn" href="/api/hotels?city=%E5%8C%97%E4%BA%AC" target="_blank">测试酒店接口 →</a>
                  </div>
                </body>
                </html>
                """.replace("__STATS__", stats)
                .replace("__PAY_WINDOW__", String.valueOf(payDeadlineMinutes));
    }

    private long count(String table) {
        if (!STAT_TABLES.contains(table)) {
            throw new IllegalArgumentException("未授权统计的表: " + table);
        }
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return n == null ? 0 : n;
    }

    private String row(String name, long n) {
        return "<tr><td>" + name + "</td><td>" + n + " 条</td></tr>";
    }
}
