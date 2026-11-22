package com.ctrip.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 悦途金融：拿去花授信分期 / 信用微贷 / 出行保障 / 钱包账单。
 *
 * <p>全部业务落 MySQL（fin_account / fin_loan / fin_repay / fin_policy / fin_claim / fin_tx），
 * 与前端本地回退模式共用同一套规则：等额本息还款计划、按收入职业评估授信、
 * 保障按方案限额理赔。金额一律两位小数（r2），避免浮点尾差进账。
 */
@Service
public class FinanceService {

    private final JdbcTemplate jdbc;

    public FinanceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 方案：[保费, 延误限额, 取消限额, 医疗限额] */
    private static final Map<String, double[]> PLAN_CAPS = Map.of(
            "basic", new double[]{15, 200, 500, 10000},
            "plus", new double[]{39, 500, 2000, 50000},
            "family", new double[]{69, 800, 5000, 100000});
    private static final Map<String, String> PLAN_NAMES = Map.of(
            "basic", "基础保障", "plus", "尊享保障", "family", "全家保障");
    private static final Map<String, String> CLAIM_NAMES = Map.of(
            "delay", "航班延误", "cancel", "行程取消", "medical", "意外医疗");
    private static final Map<String, String> CHANNEL_NAMES = Map.of(
            "wechat", "微信支付", "alipay", "支付宝", "visa", "Visa", "mastercard", "万事达", "unionpay", "银联");

    private static double r2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static String no(String prefix) {
        return prefix + System.currentTimeMillis() + String.format("%03d", ThreadLocalRandom.current().nextInt(1000));
    }

    // ==================== 总览 ====================

    public Map<String, Object> overview(long userId) {
        // 先把到期状态刷一遍：逾期账单 / 过期保单，前端拿到的永远是当前真实状态
        jdbc.update("UPDATE fin_repay r JOIN fin_loan l ON r.loan_id = l.id SET r.status = '逾期' " +
                "WHERE l.user_id = ? AND r.status = '待还' AND r.due_date < CURDATE()", userId);
        jdbc.update("UPDATE fin_policy SET status = '已过期' WHERE user_id = ? AND status = '保障中' AND end_date < CURDATE()", userId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("account", account(userId));

        List<Map<String, Object>> loans = jdbc.queryForList(
                "SELECT id, loan_no loanNo, kind, order_no orderNo, title, principal, months, rate, monthly, " +
                        "paid_months paidMonths, status, DATE_FORMAT(create_time,'%Y-%m-%d %H:%i') createTime " +
                        "FROM fin_loan WHERE user_id = ? ORDER BY id DESC", userId);
        for (Map<String, Object> loan : loans) {
            loan.put("schedule", jdbc.queryForList(
                    "SELECT seq, DATE_FORMAT(due_date,'%Y-%m-%d') due, amount, principal, interest, status, " +
                            "DATE_FORMAT(paid_time,'%Y-%m-%d %H:%i') paidTime FROM fin_repay WHERE loan_id = ? ORDER BY seq",
                    ((Number) loan.get("id")).longValue()));
        }
        out.put("loans", loans);
        out.put("policies", jdbc.queryForList(
                "SELECT policy_no policyNo, plan, plan_name planName, price, insured, destination, " +
                        "DATE_FORMAT(start_date,'%Y-%m-%d') startDate, DATE_FORMAT(end_date,'%Y-%m-%d') endDate, " +
                        "status, DATE_FORMAT(create_time,'%Y-%m-%d %H:%i') createTime FROM fin_policy WHERE user_id = ? ORDER BY id DESC", userId));
        out.put("claims", jdbc.queryForList(
                "SELECT c.claim_no claimNo, p.policy_no policyNo, c.type, c.amount, c.payout, c.reason, c.status, " +
                        "DATE_FORMAT(c.create_time,'%Y-%m-%d %H:%i') createTime FROM fin_claim c " +
                        "JOIN fin_policy p ON c.policy_id = p.id WHERE c.user_id = ? ORDER BY c.id DESC", userId));
        out.put("txs", jdbc.queryForList(
                "SELECT DATE_FORMAT(create_time,'%Y-%m-%d %H:%i') time, kind, amount, balance_after balance, memo, ref_no refNo " +
                        "FROM fin_tx WHERE user_id = ? ORDER BY id DESC LIMIT 60", userId));
        return out;
    }

    /** 账户行：没有就建一个空户（钱包 0 / 未授信），所有读写都以此为准 */
    private Map<String, Object> account(long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT real_name realName, id_card idCard, income, job, credit_status creditStatus, " +
                        "credit_limit creditLimit, credit_used creditUsed, credit_score creditScore, wallet " +
                        "FROM fin_account WHERE user_id = ?", userId);
        if (rows.isEmpty()) {
            jdbc.update("INSERT INTO fin_account(user_id) VALUES(?)", userId);
            return account(userId);
        }
        return rows.get(0);
    }

    /** 钱包变动后记一笔流水（余额以变动后的账户行为准） */
    private void tx(long userId, String kind, double amount, String memo, String refNo) {
        double balance = ((Number) jdbc.queryForList("SELECT wallet FROM fin_account WHERE user_id = ?", userId)
                .get(0).get("wallet")).doubleValue();
        jdbc.update("INSERT INTO fin_tx(user_id, kind, amount, balance_after, memo, ref_no) VALUES(?,?,?,?,?,?)",
                userId, kind, r2(amount), r2(balance), memo, refNo);
    }

    private void walletAdd(long userId, double delta) {
        jdbc.update("UPDATE fin_account SET wallet = ROUND(wallet + ?, 2) WHERE user_id = ?", r2(delta), userId);
    }

    // ==================== 拿去花授信 ====================

    public Map<String, Object> applyCredit(long userId, String realName, String idCard, int income, String job) {
        if (realName == null || realName.trim().length() < 2) throw new IllegalStateException("请输入真实姓名（至少 2 个字）");
        if (idCard == null || !idCard.matches("\\d{17}[\\dXx]")) throw new IllegalStateException("身份证号需 18 位（末位可为 X）");
        if (income < 0) throw new IllegalStateException("月收入不能为负");
        if (job == null || job.isEmpty()) throw new IllegalStateException("请选择职业类型");

        LocalDate birth;
        try {
            birth = LocalDate.parse(idCard.substring(6, 14), DateTimeFormatter.BASIC_ISO_DATE);
        } catch (Exception e) {
            throw new IllegalStateException("身份证出生日期无效");
        }
        int age = Period.between(birth, LocalDate.now()).getYears();
        if (age < 18 || age > 65) throw new IllegalStateException("授信申请年龄需在 18-65 周岁之间");

        // 与前端本地模式同一套评分：基础分 + 收入 + 职业 + 实名 + 年龄
        int score = 350;
        if (income < 5000) score += 50;
        else if (income < 10000) score += 120;
        else if (income < 20000) score += 200;
        else score += 280;
        switch (job) {
            case "公务员/事业单位": score += 120; break;
            case "国企/外企": score += 100; break;
            case "民营/互联网": score += 80; break;
            case "自由职业": score += 40; break;
            case "学生": score += 0; break;
            default: score += 60;
        }
        score += 50; // 身份证实名校验通过
        score += (age >= 22 && age <= 55) ? 60 : 20;
        score = Math.min(score, 950);

        boolean approved = score >= 550;
        double limit = approved ? Math.round(score / 950.0 * 50000 / 100) * 100 : 0;
        jdbc.update("INSERT INTO fin_account(user_id, real_name, id_card, income, job, credit_status, credit_limit, credit_score) " +
                        "VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE real_name=VALUES(real_name), id_card=VALUES(id_card), " +
                        "income=VALUES(income), job=VALUES(job), credit_status=VALUES(credit_status), " +
                        "credit_limit=VALUES(credit_limit), credit_score=VALUES(credit_score)",
                userId, realName.trim(), idCard.toUpperCase(), income, job,
                approved ? "approved" : "rejected", limit, score);
        Map<String, Object> acc = account(userId);
        acc.put("msg", approved ? "授信通过，额度 ¥" + (long) limit : "综合评分不足，暂无法开通拿去花");
        return acc;
    }

    // ==================== 借款（微贷 / 拿去花分期） ====================

    public Map<String, Object> borrow(long userId, String kind, double amount, int months, String orderNo, String title) {
        Map<String, Object> acc = account(userId);
        boolean installment = "installment".equals(kind);
        if (!installment && !"cash".equals(kind)) throw new IllegalStateException("未知的借款类型");
        amount = r2(amount);
        if (installment) {
            if (months != 3 && months != 6 && months != 12) throw new IllegalStateException("拿去花支持 3/6/12 期");
            if (!"approved".equals(acc.get("creditStatus"))) throw new IllegalStateException("请先开通拿去花授信");
            double avail = r2(num(acc.get("creditLimit")) - num(acc.get("creditUsed")));
            if (amount <= 0 || amount > avail) throw new IllegalStateException("拿去花可用额度不足（当前可用 ¥" + avail + "）");
        } else {
            if (amount < 1000 || amount > 50000) throw new IllegalStateException("微贷金额需在 ¥1,000 - ¥50,000 之间");
            if (months != 3 && months != 6 && months != 9 && months != 12 && months != 24 && months != 36)
                throw new IllegalStateException("微贷支持 3/6/9/12/24/36 期");
        }

        double rate = rateFor(acc, kind, months);
        double monthly = monthlyOf(amount, months, rate);
        String loanNo = no("LN");
        jdbc.update("INSERT INTO fin_loan(user_id, loan_no, kind, order_no, title, principal, months, rate, monthly) " +
                        "VALUES(?,?,?,?,?,?,?,?,?)",
                userId, loanNo, kind, orderNo, title, amount, months, rate, monthly);
        long loanId = jdbc.queryForObject("SELECT id FROM fin_loan WHERE loan_no = ?", Long.class, loanNo);
        insertSchedule(loanId, amount, months, rate, LocalDate.now());

        if (installment) {
            jdbc.update("UPDATE fin_account SET credit_used = ROUND(credit_used + ?, 2) WHERE user_id = ?", amount, userId);
        } else {
            walletAdd(userId, amount);
            tx(userId, "借款放款", amount, "信用·微贷放款", loanNo);
        }
        return loanView(userId, loanNo);
    }

    /** 年化利率：授信用户 7.5% 起，未授信 12%；长期 +1.5，低分 +2，封顶 18 */
    private double rateFor(Map<String, Object> acc, String kind, int months) {
        if ("installment".equals(kind)) return 7.5;
        boolean approved = "approved".equals(acc.get("creditStatus"));
        double rate = approved ? 7.5 : 12.0;
        if (months > 12) rate += 1.5;
        if (approved && num(acc.get("creditScore")) < 650) rate += 2;
        return Math.min(rate, 18);
    }

    private static double num(Object o) {
        return o == null ? 0 : ((Number) o).doubleValue();
    }

    /** 等额本息月供（与前端计算器同一公式） */
    public static double monthlyOf(double p, int n, double annualRate) {
        double i = annualRate / 100 / 12;
        if (i == 0) return r2(p / n);
        double pow = Math.pow(1 + i, n);
        return r2(p * i * pow / (pow - 1));
    }

    /** 生成还款计划：每期利息 = 剩余本金 × 月利率，末期本金兜底找平 */
    private void insertSchedule(long loanId, double principal, int months, double rate, LocalDate start) {
        double i = rate / 100 / 12;
        double monthly = monthlyOf(principal, months, rate);
        double remain = principal;
        for (int seq = 1; seq <= months; seq++) {
            double interest = r2(remain * i);
            double part = seq == months ? r2(remain) : r2(monthly - interest);
            double amount = r2(part + interest);
            remain = r2(remain - part);
            jdbc.update("INSERT INTO fin_repay(loan_id, seq, due_date, amount, principal, interest) VALUES(?,?,?,?,?,?)",
                    loanId, seq, start.plusMonths(seq), amount, part, interest);
        }
    }

    private Map<String, Object> loanView(long userId, String loanNo) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, loan_no loanNo, kind, order_no orderNo, title, principal, months, rate, monthly, " +
                        "paid_months paidMonths, status, DATE_FORMAT(create_time,'%Y-%m-%d %H:%i') createTime " +
                        "FROM fin_loan WHERE loan_no = ? AND user_id = ?", loanNo, userId);
        if (rows.isEmpty()) throw new IllegalStateException("借款记录不存在");
        Map<String, Object> loan = rows.get(0);
        loan.put("schedule", jdbc.queryForList(
                "SELECT seq, DATE_FORMAT(due_date,'%Y-%m-%d') due, amount, principal, interest, status, " +
                        "DATE_FORMAT(paid_time,'%Y-%m-%d %H:%i') paidTime FROM fin_repay WHERE loan_id = ? ORDER BY seq",
                ((Number) loan.get("id")).longValue()));
        return loan;
    }

    // ==================== 还款 ====================

    public Map<String, Object> repay(long userId, String loanNo, String mode) {
        Map<String, Object> loan = loanView(userId, loanNo);
        if (!"还款中".equals(loan.get("status"))) throw new IllegalStateException("该笔借款已结清");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> schedule = (List<Map<String, Object>>) loan.get("schedule");
        List<Map<String, Object>> unpaid = new ArrayList<>();
        for (Map<String, Object> s : schedule) if (!"已还".equals(s.get("status"))) unpaid.add(s);
        if (unpaid.isEmpty()) throw new IllegalStateException("没有待还账单");

        boolean payoff = "payoff".equals(mode);
        double principalDue = 0;
        for (Map<String, Object> s : unpaid) principalDue += num(s.get("principal"));
        // 提前结清 = 剩余本金 + 当期利息；还本期 = 当期本息
        double due = payoff ? r2(principalDue + num(unpaid.get(0).get("interest"))) : num(unpaid.get(0).get("amount"));
        double wallet = num(account(userId).get("wallet"));
        if (wallet < due) throw new IllegalStateException("钱包余额不足（需 ¥" + due + "），请先充值");

        walletAdd(userId, -due);
        long loanId = ((Number) loan.get("id")).longValue();
        String now = LocalDateTime.now().format(DT);
        if (payoff) {
            jdbc.update("UPDATE fin_repay SET status = '已还', paid_time = ? WHERE loan_id = ? AND status <> '已还'", now, loanId);
            jdbc.update("UPDATE fin_loan SET paid_months = months, status = '已结清' WHERE id = ?", loanId);
        } else {
            Map<String, Object> first = unpaid.get(0);
            jdbc.update("UPDATE fin_repay SET status = '已还', paid_time = ? WHERE loan_id = ? AND seq = ?",
                    now, loanId, ((Number) first.get("seq")).intValue());
            jdbc.update("UPDATE fin_loan SET paid_months = paid_months + 1 WHERE id = ?", loanId);
            jdbc.update("UPDATE fin_loan SET status = '已结清' WHERE id = ? AND paid_months >= months", loanId);
        }
        if ("installment".equals(loan.get("kind"))) {
            double freed = payoff ? principalDue : num(unpaid.get(0).get("principal"));
            jdbc.update("UPDATE fin_account SET credit_used = GREATEST(0, ROUND(credit_used - ?, 2)) WHERE user_id = ?", freed, userId);
        }
        tx(userId, "还款", -due, payoff ? "提前结清 · " + loanNo : "按期还款 · " + loanNo, loanNo);
        return loanView(userId, loanNo);
    }

    // ==================== 钱包充值 ====================

    public Map<String, Object> topup(long userId, double amount, String channel) {
        amount = r2(amount);
        if (amount < 1 || amount > 50000) throw new IllegalStateException("充值金额需在 ¥1 - ¥50,000 之间");
        walletAdd(userId, amount);
        String name = CHANNEL_NAMES.getOrDefault(channel, "在线支付");
        tx(userId, "充值", amount, name + "充值", null);
        return account(userId);
    }

    // ==================== 出行保障 ====================

    public Map<String, Object> buyPolicy(long userId, String plan, String insured, String destination,
                                         String startDate, int days) {
        double[] caps = PLAN_CAPS.get(plan);
        if (caps == null) throw new IllegalStateException("请选择保障方案");
        if (insured == null || insured.trim().isEmpty()) throw new IllegalStateException("请输入被保人姓名");
        if (destination == null || destination.trim().isEmpty()) throw new IllegalStateException("请选择目的地");
        LocalDate start;
        try {
            start = LocalDate.parse(startDate, D);
        } catch (Exception e) {
            throw new IllegalStateException("请选择起保日期");
        }
        if (start.isBefore(LocalDate.now())) throw new IllegalStateException("起保日期不能早于今天");
        if (days < 1 || days > 30) throw new IllegalStateException("保障天数需在 1-30 天之间");

        double price = caps[0];
        double wallet = num(account(userId).get("wallet"));
        if (wallet < price) throw new IllegalStateException("钱包余额不足（需 ¥" + price + "），请先充值");
        String policyNo = no("PL");
        walletAdd(userId, -price);
        jdbc.update("INSERT INTO fin_policy(user_id, policy_no, plan, plan_name, price, insured, destination, start_date, end_date) " +
                        "VALUES(?,?,?,?,?,?,?,?,?)",
                userId, policyNo, plan, PLAN_NAMES.get(plan), price, insured.trim(), destination.trim(),
                start, start.plusDays(days - 1));
        tx(userId, "投保", -price, PLAN_NAMES.get(plan) + " · " + destination.trim(), policyNo);
        return policyView(userId, policyNo);
    }

    private Map<String, Object> policyView(long userId, String policyNo) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT policy_no policyNo, plan, plan_name planName, price, insured, destination, " +
                        "DATE_FORMAT(start_date,'%Y-%m-%d') startDate, DATE_FORMAT(end_date,'%Y-%m-%d') endDate, " +
                        "status, DATE_FORMAT(create_time,'%Y-%m-%d %H:%i') createTime FROM fin_policy " +
                        "WHERE policy_no = ? AND user_id = ?", policyNo, userId);
        if (rows.isEmpty()) throw new IllegalStateException("保单不存在");
        return rows.get(0);
    }

    public Map<String, Object> claim(long userId, String policyNo, String type, double amount, String reason) {
        Map<String, Object> policy = policyView(userId, policyNo);
        if ("已退保".equals(policy.get("status"))) throw new IllegalStateException("已退保的保单不能申请理赔");
        Integer idx = switch (type == null ? "" : type) {
            case "delay" -> 1;
            case "cancel" -> 2;
            case "medical" -> 3;
            default -> null;
        };
        if (idx == null) throw new IllegalStateException("请选择理赔类型");
        if (amount <= 0) throw new IllegalStateException("请输入申请金额");
        long policyId = jdbc.queryForObject("SELECT id FROM fin_policy WHERE policy_no = ?", Long.class, policyNo);
        double cap = PLAN_CAPS.get(policy.get("plan"))[idx];
        double paid = num(jdbc.queryForList(
                "SELECT COALESCE(SUM(payout),0) s FROM fin_claim WHERE policy_id = ? AND type = ? AND status = '已赔付'",
                policyId, type).get(0).get("s"));
        double remaining = r2(cap - paid);
        if (remaining <= 0) throw new IllegalStateException("该保障项的赔付额度已用完");
        double payout = r2(Math.min(amount, remaining));

        String claimNo = no("CL");
        String now = LocalDateTime.now().format(DT);
        jdbc.update("INSERT INTO fin_claim(policy_id, user_id, claim_no, type, amount, payout, reason, status, review_time) " +
                        "VALUES(?,?,?,?,?,?,?,?,?)",
                policyId, userId, claimNo, type, r2(amount), payout, reason, "已赔付", now);
        walletAdd(userId, payout);
        tx(userId, "理赔赔付", payout, CLAIM_NAMES.get(type) + "理赔 · " + policyNo, claimNo);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("claimNo", claimNo);
        out.put("policyNo", policyNo);
        out.put("type", type);
        out.put("amount", r2(amount));
        out.put("payout", payout);
        out.put("status", "已赔付");
        out.put("msg", "审核通过，赔付 ¥" + payout + " 已入钱包");
        return out;
    }

    public Map<String, Object> surrender(long userId, String policyNo) {
        Map<String, Object> policy = policyView(userId, policyNo);
        if (!"保障中".equals(policy.get("status"))) throw new IllegalStateException("只有保障中的保单可以退保");
        LocalDate start = LocalDate.parse(String.valueOf(policy.get("startDate")), D);
        if (!start.isAfter(LocalDate.now())) throw new IllegalStateException("保障已生效，无法退保");
        double price = num(policy.get("price"));
        jdbc.update("UPDATE fin_policy SET status = '已退保' WHERE policy_no = ? AND user_id = ?", policyNo, userId);
        walletAdd(userId, price);
        tx(userId, "退保退款", price, "退保 · " + policyNo, policyNo);
        return policyView(userId, policyNo);
    }
}