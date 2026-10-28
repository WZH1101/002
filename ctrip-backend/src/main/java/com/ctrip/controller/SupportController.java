package com.ctrip.controller;

import com.ctrip.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 7×24 在线客服：规则问答版 AI 回复。
 *
 * <p>这里没有接大模型——回复是关键词命中的 FAQ 模板，命中即答，未命中给转人工话术。
 * 接口本身 7×24 可用（不依赖营业时间，也不用登录），前端客服面板标了
 * 「演示 · 规则问答」。接真实模型时把 {@link #answer} 换成对模型服务的调用即可，
 * 出入参结构（message → {reply}）不用动。
 *
 * <p>安全口径与其余接口一致：消息长度截断到 500 字（防拿来当存储/重放载体），
 * 单 IP 每分钟最多 30 条（防刷），不登录也可用——客服本来就该服务未登录访客。
 */
@RestController
@RequestMapping("/api/support")
public class SupportController {

    private static final int MAX_MSG_LEN = 500;
    private static final int MAX_PER_MINUTE = 30;
    private static final int COUNTER_CAP = 10_000;

    /** IP -> {条数, 分钟序号}，单机内存（演示规模够用；多实例应换 Redis，见 UserService 同类注释） */
    private final Map<String, long[]> window = new ConcurrentHashMap<>();

    /**
     * 规则条目：构造参数依次是 回复文案 + 关键词（命中任一即返回该回复）。
     * 顺序即优先级——「退款」要在「订单」前面，因为一句话可能同时提到两者，先答更具体的。
     */
    private static final Rule[] RULES = {
            new Rule("订单已付款后可以申请退款：打开「订单中心」，点击订单号进入详情页，点「申请退款」即可，"
                    + "款项原路退回、金额全额退还。待支付订单不需要退款，直接不支付会超时自动取消，"
                    + "也可以手动取消。取消/退款后库存会立即回补。",
                    "退款"),
            new Rule("在「订单中心」找到对应订单，点击订单号进入详情页，待支付订单可以点「取消订单」，"
                    + "已支付订单可以点「申请退款」。取消和退款都不收手续费，出行前 2 小时以上可随时办理。",
                    "取消"),
            new Rule("下单后订单处于「待支付」状态，系统会为您锁定库存并预留位置。请在规定时间内完成支付，"
                    + "超时未支付订单会自动取消并释放库存。支付方式在下单时自主选择，没有默认项。",
                    "支付"),
            new Rule("待支付订单设有支付倒计时，超过时限未支付会自动取消，被锁定的库存（座位/房量）会立即释放。"
                    + "您可以随时重新下单。",
                    "超时", "倒计时"),
            new Rule("火车票、机票的改签规则以出票时的退改说明为准。当前演示平台暂未开放线上改签，"
                    + "如行程有变，建议先取消原订单（未出行可全额退）再重新预订。",
                    "改签", "改期"),
            new Rule("演示平台暂未接入发票系统。需要报销凭证时，请在订单详情页保存订单信息，"
                    + "并联系人工客服 400-168-6688 协助开具。",
                    "发票", "报销"),
            new Rule("新人礼包、满减券会在活动期间自动发放到您的账户。演示版本未开放领券中心，"
                    + "优惠以各产品页面显示价格为准。",
                    "优惠券", "满减", "新人礼"),
            new Rule("用手机号登录或注册时需要短信验证码。验证码 5 分钟内有效、仅限使用一次，"
                    + "60 秒后可重新获取，同一号码每小时获取次数有限。",
                    "验证码"),
            new Rule("电脑上打开「登录 → 扫码登录」，用已登录悦途的手机扫码进入确认页，"
                    + "在手机上点击「确认登录」，电脑端会自动登录。二维码 2 分钟有效，过期点击刷新即可。",
                    "扫码", "二维码"),
            new Rule("点击右上角「注册」即可创建账号：支持用户名+密码注册，也支持手机号+验证码注册。"
                    + "密码需至少 8 位并同时包含字母和数字。",
                    "注册"),
            new Rule("登录后点击右上角头像区进入「改密码」，输入原密码并设置新密码。"
                    + "改密成功后，该账号在其他所有设备上的登录会立即失效，需用新密码重新登录。",
                    "改密码"),
            new Rule("连续输错 5 次密码，账号会临时锁定 15 分钟，这是防止暴力破解的保护措施。"
                    + "到时间后自动解除；若忘记密码请拨打 400-168-6688 联系人工客服核实身份后处理。",
                    "锁定", "忘记密码", "输错"),
            new Rule("平台支持三种登录方式：账号密码、手机验证码、扫码登录，三种方式登录后的功能完全一致。"
                    + "提示「该手机号未注册」时请先注册，或改用账号密码登录。",
                    "登录"),
            new Rule("订单在「订单中心」查看：待支付订单可支付或取消，已支付订单可申请退款，"
                    + "已取消/已退款的订单可以删除。点击订单号可查看完整详情和状态时间线。",
                    "订单"),
            new Rule("各交通方式的行李额度以承运方规定为准，一般火车票包含随身行李，机票含手提行李，"
                    + "托运行李额请在出票页面查看。",
                    "行李"),
            new Rule("携宠出行请提前咨询承运方：多数火车允许导盲犬上车，普通宠物需办理托运；"
                    + "具体以承运方最新规定为准。",
                    "宠物", "导盲犬"),
            new Rule("企业客户可使用「企业商旅」模块提交认证申请，认证通过后支持月结报销流程。"
                    + "提交认证需要先登录企业账号。",
                    "商旅", "企业", "报销流程"),
            new Rule("景点门票在「景点门票」频道搜索购买，部分场馆支持电子票直接扫码入园，"
                    + "出行前请留意各景点的开放时间与预约要求。",
                    "景点", "门票"),
            new Rule("酒店订单可在线取消（入住前），具体可取消时限以预订页面显示为准。"
                    + "对房间有特殊需求（楼层、大床等）可在预订备注中写明。",
                    "酒店"),
            new Rule("客服热线 400-168-6688（7×24 小时）。转人工时请准备好订单号，可加快处理速度。",
                    "电话"),
            new Rule("已为您转接人工客服排队：请拨打 400-168-6688（7×24 小时）并准备好订单号。"
                    + "本智能助手是规则问答演示，复杂问题请以人工客服答复为准。",
                    "人工", "转人工"),
            new Rule("非常抱歉给您带来不好的体验。请拨打 400-168-6688 或在此描述具体问题，"
                    + "我们会记录并优先处理。",
                    "投诉", "差评", "生气"),
            new Rule("您好，我是悦途旅行智能客服小悦，7×24 小时在线。您可以问我订单、退款、支付、登录等问题。",
                    "你好", "您好"),
            new Rule("在的，7×24 小时都在。您可以问我订单、退款、支付、登录等问题。",
                    "在吗", "在不在"),
            new Rule("不客气，祝您旅途愉快！",
                    "谢谢", "感谢"),
            new Rule("客服 7×24 小时在线，随时可以咨询；智能助手秒回，人工客服高峰期可能需要排队。",
                    "工作时间", "几点", "营业时间"),
    };

    private static final String FALLBACK =
            "这个问题我暂时没有标准答案，已为您转人工：请拨打客服热线 400-168-6688（7×24 小时），"
                    + "或换一种问法，例如「怎么退款」「订单在哪里看」「怎么用验证码登录」。";

    @PostMapping("/chat")
    public ApiResponse<?> chat(@RequestBody Map<String, Object> req, HttpServletRequest request) {
        // 注意别用 String.valueOf(getOrDefault(...))：请求体里显式写 "message": null 时
        // getOrDefault 仍返回 null→"null" 这个四字符串会被当成一句提问回答出去
        Object raw = req == null ? null : req.get("message");
        String message = raw == null ? "" : String.valueOf(raw);
        if (message.isBlank()) {
            return ApiResponse.fail("请输入您想咨询的问题");
        }
        if (!allow(request.getRemoteAddr())) {
            return ApiResponse.fail("提问过于频繁，请稍后再试（每分钟最多 " + MAX_PER_MINUTE + " 条）");
        }
        if (message.length() > MAX_MSG_LEN) {
            message = message.substring(0, MAX_MSG_LEN);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reply", answer(message));
        out.put("source", "rule-demo");
        out.put("servicePhone", "400-168-6688");
        out.put("hour24", true);
        return ApiResponse.ok(out);
    }

    /** 关键词命中：优先返回第一个命中的规则 */
    private static String answer(String message) {
        for (Rule rule : RULES) {
            for (String key : rule.keys) {
                if (message.contains(key)) {
                    return rule.reply;
                }
            }
        }
        return FALLBACK;
    }

    /** 单 IP 每分钟上限；计数器单机内存，与登录限速同口径（见类注释） */
    private boolean allow(String ip) {
        String key = ip == null ? "-" : ip;
        long nowMin = System.currentTimeMillis() / 60_000;
        // 与 UserService/SmsCodeService 同一条纪律：内存计数器到阈值就淘汰上一分钟的条目
        if (window.size() > COUNTER_CAP) window.values().removeIf(v -> v[1] != nowMin);
        long[] w = window.compute(key, (k, v) -> {
            if (v == null || v[1] != nowMin) return new long[]{1, nowMin};
            v[0]++;
            return v;
        });
        return w == null || w[0] <= MAX_PER_MINUTE;
    }

    private record Rule(String reply, String... keys) {}
}
