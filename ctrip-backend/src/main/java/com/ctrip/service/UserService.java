package com.ctrip.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户注册 / 登录 / 会话 / 改密。
 *
 * <p>口令存储：PBKDF2-HMAC-SHA256，120000 轮迭代 + 每用户随机盐，
 * 编码格式 {@code pbkdf2$迭代数$盐$哈希十六进制}，整体长度 95 字符以内，
 * 落在 {@code app_user.pwd_hash VARCHAR(128)}。相比原先的单次 SHA-256（GPU 可高速爆破），
 * 慢哈希把每次校验成本抬高约三个数量级。历史 SHA-256 账号仍可登录，校验通过后透明升级。
 *
 * <p>会话：存 {@code user_session} 表而不是内存 Map。内存 Map 有两个问题——重启即全员掉线、
 * 多实例部署时 A 机器签发的令牌 B 机器不认。表里同时记录签发时的 {@code app_user.auth_ver}，
 * 改密码把 auth_ver +1，所有旧令牌立刻失效（此前改密码不会吊销任何令牌）。
 *
 * <p>登录限速：按「登录标识 + 客户端 IP」计数，连续失败 5 次锁 15 分钟；注册按 IP 限频。
 * 没有这层，任何人都能拿字典对着 /api/auth/login 无限撞库。
 * 计数器是单机的（内存 Map），多实例部署应换成 Redis；对本项目够用，且不做成跨实例一致是有意取舍。
 *
 * <p><b>注册不再接受「用户名 + 密码」</b>（2026-10-28 下线）：那条通道只证明「这个用户名没人占」，
 * 不证明申请人持有任何东西，是批量注册小号的正门——同一 IP 每小时限流只是缓解，不是解决。
 * 现在三种注册方式都必须先通过一次归属校验：手机号验证码、邮箱验证码、第三方账号授权。
 * 用户名因此一律服务端生成（{@code m+手机号} / {@code e+邮箱前缀} / {@code 渠道缩写+随机十六进制}），
 * 用户记住的凭据是手机号或邮箱；账号密码登录仍然可用，只是用户名从「自己挑」变成「注册时给的那个」，
 * 输入框里填邮箱同样能登（{@link #login}）。
 *
 * <p>四种登录方式共用同一套会话签发（{@link #sessionFor}）：账号密码（本类）、
 * 手机验证码（{@link SmsCodeService} 验码后由 {@link #loginBySms} 落账号）、
 * 邮箱验证码（{@link EmailCodeService} 验码后由 {@link #loginByEmailCode} 落账号）、
 * 扫码（手机确认页登录后由 {@link QrLoginService} 调 {@link #issueSession}）；
 * 第三方授权则由 {@link OAuthService} 领取时调 {@link #bindOrCreateByOAuth} 换账号。
 * 这些路签出来的令牌完全同构，所以订单等后续接口的鉴权口径不需要区分登录方式。
 */
@Service
public class UserService {

    private static final long TOKEN_TTL_SECONDS = 2 * 60 * 60;

    /**
     * 连续失败多少次后锁定 / 锁定时长（分钟）/ 同一 IP 每小时可注册的账号数。
     * 做成配置项而不是写死的常量：默认值按「公网可访问」设定，本机开发和 qa/smoke.sh
     * 需要在同一个小时里反复注册几个测试账号，否则会被自己的防刷机制挡住。
     * 覆盖方式（application.yml 或环境变量，取其一）：
     * <pre>
     *   ctrip:
     *     auth:
     *       max-login-failures: 5
     *       lock-minutes: 15
     *       max-register-per-hour: 20
     * </pre>
     */
    private final int maxLoginFailures;
    private final long loginLockMinutes;
    private final int maxRegistersPerHour;

    private final JdbcTemplate jdbc;
    private final SmsCodeService sms;
    private final EmailCodeService email;

    public UserService(JdbcTemplate jdbc,
                       SmsCodeService sms,
                       EmailCodeService email,
                       @Value("${ctrip.auth.max-login-failures:5}") int maxLoginFailures,
                       @Value("${ctrip.auth.lock-minutes:15}") long lockMinutes,
                       @Value("${ctrip.auth.max-register-per-hour:5}") int maxRegistersPerHour) {
        this.jdbc = jdbc;
        this.sms = sms;
        this.email = email;
        this.maxLoginFailures = Math.max(1, maxLoginFailures);
        this.loginLockMinutes = Math.max(1, lockMinutes);
        this.maxRegistersPerHour = Math.max(1, maxRegistersPerHour);
    }

    /**
     * 口令强度：至少 8 位，且同时包含字母与数字。
     * 只用于注册和改密——历史上 6 位口令的账号不能被登录逻辑拒绝，否则等于把人锁在门外。
     */
    private static final String PWD_RULE = "密码需至少 8 位，且同时包含字母和数字";

    /**
     * 会话校验：令牌未过期、且签发时的 auth_ver 仍等于用户当前的 auth_ver。
     * 改密码后 auth_ver 变了，这条查询就查不到，旧令牌自然失效。
     */
    private static final String SQL_SESSION_USER =
            "SELECT u.id, u.username, u.nickname, u.phone, u.email FROM user_session s"
                    + " JOIN app_user u ON u.id = s.user_id"
                    + " WHERE s.token = ? AND s.expire_time > NOW() AND s.auth_ver = u.auth_ver";

    /** 验证码登录（手机 / 邮箱）与按 id 取账号的列，必须与 sessionFor 用到的字段一致 */
    private static final String USER_COLUMNS = "id, username, nickname, phone, email, auth_ver";

    /** 同上，但用于与 user_oauth 联查：两张表都有 id/nickname，不带 u. 前缀 MySQL 会报列名歧义 */
    private static final String USER_COLUMNS_U = "u.id, u.username, u.nickname, u.phone, u.email, u.auth_ver";

    /** 手机验证码登录：按号码取账号 */
    private static final String USER_BY_PHONE =
            "SELECT " + USER_COLUMNS + " FROM app_user WHERE phone = ? ORDER BY id";

    /** 邮箱验证码登录：按邮箱取账号（email 有唯一索引，正常只可能一行） */
    private static final String USER_BY_EMAIL =
            "SELECT " + USER_COLUMNS + " FROM app_user WHERE email = ? ORDER BY id";

    /** 扫码登录 / 第三方授权：确认页已经验过身份，这里按 id 取回账号签发会话 */
    private static final String USER_FOR_SESSION =
            "SELECT " + USER_COLUMNS + " FROM app_user WHERE id = ?";

    /**
     * 一次「用户名@IP」的失败记录。
     *
     * <p>带 lastFailure 有两个用处：一是「连续失败」本来就该有时间含义——一周里零散输错 5 次
     * 不该触发锁定；二是给内存计数器一个合法的淘汰依据，公网长跑不会只增不减。
     */
    private record Attempt(int failures, Instant lockedUntil, Instant lastFailure) {}

    /** 失败计数器的存活时间：超过一小时没再失败过，之前的计数作废（锁定期本身仍按 lock-minutes 走） */
    private static final Duration ATTEMPT_TTL = Duration.ofHours(1);
    /** 内存计数器的条数上限，超过就淘汰过期条目（见 throttleRegister / recordFailure） */
    private static final int COUNTER_CAP = 10_000;

    /** 登录失败计数与锁定期（单机内存，见类注释的取舍说明） */
    private final Map<String, Attempt> loginAttempts = new ConcurrentHashMap<>();

    /** 「选账号」凭证的存活时间：验证码刚验过，趁这个信任窗口把账号挑定 */
    private static final Duration CHOOSE_TTL = Duration.ofMinutes(5);

    /**
     * 一个手机号挂着多个账号时，验码通过后下发的一次性「选账号」凭证。
     *
     * <p>为什么需要它：验证码只证明「这个号码在申请人手里」，不证明他想登录（或重置）其中哪一个。
     * 原先的做法是直接拒绝并让用户改用账号密码，而那条通道已下线，等于把这类账号彻底锁在门外。
     *
     * <p>只存内存、5 分钟过期、取出即作废：它不像登录令牌需要跨重启存活（会话为什么进表见类注释），
     * 重启丢了大不了重新收一次码，代价远小于为它单开一张表。
     */
    private final Map<String, PendingChoose> pendingChooses = new ConcurrentHashMap<>();

    /** 凭证背后的上下文：场景 + 号码 + 发凭证时查到的候选账号 */
    private record PendingChoose(String scene, String phone, Instant expireAt,
                                 List<Map<String, Object>> accounts) { }

    /** 注册频率窗口：IP -> {次数, 窗口起始秒} */
    private final Map<String, long[]> registerWindow = new ConcurrentHashMap<>();

    // ==================== 注册 / 登录 ====================
    //
    // 这里不再有 register(username, password)：用户名+密码注册只证明「这个用户名没人占」，
    // 不证明申请人持有任何东西，等于给批量注册小号开了正门。
    // 现在三个入口（registerBySms / registerByEmail / bindOrCreateByOAuth）都要求先通过一次
    // 归属校验，用户名一律服务端生成；「用户名+密码」只剩登录这一半（见 login）。

    /**
     * 手机验证码注册：手机号 + 注册码 + （可选）口令。
     *
     * <p>手机号是唯一凭据，注册码只保证「这个号码还没注册过」（见
     * {@link SmsCodeService#sendRegisterCode} 的取舍），所以这里要求号码唯一；
     * 用户名按 {@code m + 手机号} 自动生成，之后可以用验证码登录，也可用手机号当用户名试密码登录。
     *
     * <p>口令选填：填了必须符合强度规则；留空则生成一个用户不知道的随机口令——
     * 账号只能靠验证码登录，这正是「手机号注册」的预期形态。留空生成随机口令比存「无口令」
     * 标记安全：pwd_hash 永远是有效哈希，登录路径不存在「这条记录没有口令」的特殊分支。
     *
     * @return 与登录同构的会话信息（token/id/username/nickname/phone/email），注册成功即登录
     */
    public Map<String, Object> registerBySms(Map<String, Object> req, String clientIp) {
        String phone = str(req.get("phone"));
        String code = str(req.get("code"));
        String nickname = str(req.get("nickname"));
        String password = str(req.get("password"));

        if (phone == null || !phone.matches("1[3-9]\\d{9}")) {
            throw new IllegalStateException("请输入11位有效手机号码");
        }
        if (code == null || !code.matches("\\d{6}")) {
            throw new IllegalStateException("请输入6位短信验证码");
        }
        if (password != null && !password.isEmpty() && !isStrongPassword(password)) {
            throw new IllegalStateException(PWD_RULE);
        }
        // 限流放在验码之前：反过来的话，被限流的那一次已经把唯一的一张码消费掉了，
        // 用户等一小时额度恢复之外还要重新发一条，纯亏。
        throttleRegister(clientIp);
        if (!sms.verifyRegisterCode(phone, code)) {
            throw new IllegalStateException("验证码不正确或已失效");
        }

        Long exists = jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE phone = ?", Long.class, phone);
        if (exists != null && exists > 0) {
            // 发码时说「未注册」，此刻又已存在：对方先一步注册了同一个号码，或注册码与号码对不上
            throw new IllegalStateException("该手机号刚刚已被注册，请直接登录");
        }
        // 用户名自动生成：手机号是唯一凭据，m+手机号 也是唯一的；正则允许字母数字下划线
        String username = uniqueUsername("m" + phone);
        nickname = clampNickname(nickname, "手机用户" + phone.substring(7));
        if (password == null || password.isEmpty()) {
            // 随机口令：用户不知道它，只能验证码登录；保证同时含字母和数字（前缀 Yt + 十六进制）
            password = randomPassword();
        }

        insertUser(username, password, Crypto.randomSaltHex(), nickname, phone, null);
        return sessionFor(jdbc.queryForMap(
                "SELECT " + USER_COLUMNS + " FROM app_user WHERE username = ?", username));
    }

    /**
     * 邮箱验证码注册：邮箱 + 注册码 + （可选）口令，规则与 {@link #registerBySms} 一一对应。
     *
     * <p>与手机注册同一套凭据强度：注册码证明「这个邮箱还没被注册过」且「发码时这个邮箱收得到信」，
     * 用户名按 {@code e + 邮箱前缀} 生成，之后可以用「邮箱 + 密码」或「邮箱验证码」登录。
     *
     * <p>口令同样选填：留空则给一个用户不知道的随机口令，账号只能靠邮箱验证码登录——
     * 这是邮箱这条路的兜底（没有口令也能用），也是它与手机那条路的唯一共同点。
     */
    public Map<String, Object> registerByEmail(Map<String, Object> req, String clientIp) {
        String emailAddr = EmailCodeService.normalize(str(req.get("email")));
        String code = str(req.get("code"));
        String nickname = str(req.get("nickname"));
        String password = str(req.get("password"));
        String phone = str(req.get("phone"));

        if (!EmailCodeService.isEmail(emailAddr)) {
            throw new IllegalStateException("请输入有效的邮箱地址");
        }
        if (code == null || !code.matches("\\d{6}")) {
            throw new IllegalStateException("请输入6位邮箱验证码");
        }
        if (password != null && !password.isEmpty() && !isStrongPassword(password)) {
            throw new IllegalStateException(PWD_RULE);
        }
        if (phone != null && !phone.isEmpty() && !phone.matches("1[3-9]\\d{9}")) {
            throw new IllegalStateException("请输入11位有效手机号码");
        }
        // 同 registerBySms：限流与验码的先后顺序是有意的
        throttleRegister(clientIp);
        if (!this.email.verifyRegisterCode(emailAddr, code)) {
            throw new IllegalStateException("验证码不正确或已失效");
        }

        Long exists = jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE email = ?", Long.class, emailAddr);
        if (exists != null && exists > 0) {
            throw new IllegalStateException("该邮箱刚刚已被注册，请直接登录");
        }
        String username = uniqueUsername("e" + usernameSeed(emailAddr));
        nickname = clampNickname(nickname, "邮箱用户" + emailAddr.substring(0, Math.min(emailAddr.indexOf('@'), 8)));

        insertUser(username,
                (password == null || password.isEmpty()) ? randomPassword() : password,
                Crypto.randomSaltHex(), nickname, (phone == null || phone.isEmpty()) ? null : phone, emailAddr);
        return sessionFor(jdbc.queryForMap(
                "SELECT " + USER_COLUMNS + " FROM app_user WHERE username = ?", username));
    }

    /**
     * 第三方授权换账号：{@code (provider, openId)} 已绑过就登那个账号，没绑过就地建一个。
     *
     * <p>「没绑过就建号」就是第三方注册的完整含义——本站不需要也不能要求用户先填一遍表单，
     * 渠道给的 openId 就是这个人的身份。昵称/头像取渠道返回值，只用于展示，
     * 真名、手机号、邮箱这些第三方并不提供（微信只给 openid 与头像昵称，除非另走授权）。
     *
     * <p>用户名用 {@code 渠道缩写 + 8 位随机十六进制}：不是给人记的（记的是渠道），
     * 只要满足 UNIQUE 且不至于被账号密码登录猜中即可。口令是用户不知道的随机串，
     * 因此第三方账号只能靠该渠道再次授权登录——与「留空口令的手机号注册账号」同一形态。
     *
     * <p>并发口径：{@code UNIQUE(provider, open_id)} 是真兜底。先插 app_user 再插绑定，
     * 万一两个不同场景同时给同一个 openId 建号，后到的那个撞绑定唯一键——此时把它自己刚建的那行
     * 用户删掉（用户名是本次随机生成的，只有这一条请求可能插入过它，删除不会误伤别人），
     * 再改读先赢的那条绑定。不做成事务是为了不在这个规模引入回滚复杂度；
     * 上面那段「删自己刚建的行」把两种结果都收敛到「同一个 openId 只对应一个账号」。
     */
    public Map<String, Object> bindOrCreateByOAuth(String provider, String openId, String nickname, String avatar) {
        List<Map<String, Object>> bound = jdbc.queryForList(
                "SELECT " + USER_COLUMNS_U + " FROM user_oauth o JOIN app_user u ON u.id = o.user_id"
                        + " WHERE o.provider = ? AND o.open_id = ?", provider, openId);
        if (!bound.isEmpty()) {
            jdbc.update("UPDATE user_oauth SET nickname = ?, avatar = ?, last_login_time = NOW()"
                    + " WHERE provider = ? AND open_id = ?", clampProviderText(nickname), avatar, provider, openId);
            return sessionFor(bound.get(0));
        }

        String username = uniqueUsername(providerPrefix(provider) + Crypto.randomToken().substring(0, 8));
        String display = clampNickname(clampProviderText(nickname), providerName(provider) + "用户");
        insertUser(username, randomPassword(), Crypto.randomSaltHex(), display, null, null);
        Long userId = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, username);
        try {
            jdbc.update("INSERT INTO user_oauth(provider, open_id, user_id, nickname, avatar, last_login_time)"
                            + " VALUES(?,?,?,?,?,NOW())",
                    provider, openId, userId, clampProviderText(nickname), avatar);
        } catch (DuplicateKeyException e) {
            // 另一个场景在同一瞬间给同一个 openId 建过号：留下先建的那个，回收本次多建的一行
            jdbc.update("DELETE FROM app_user WHERE id = ? AND username = ?", userId, username);
            List<Map<String, Object>> winner = jdbc.queryForList(
                    "SELECT " + USER_COLUMNS_U + " FROM user_oauth o JOIN app_user u ON u.id = o.user_id"
                            + " WHERE o.provider = ? AND o.open_id = ?", provider, openId);
            if (winner.isEmpty()) {
                throw new IllegalStateException("第三方账号绑定失败，请重新授权");
            }
            jdbc.update("UPDATE user_oauth SET last_login_time = NOW() WHERE provider = ? AND open_id = ?",
                    provider, openId);
            return sessionFor(winner.get(0));
        }
        return sessionFor(jdbc.queryForMap(
                "SELECT " + USER_COLUMNS + " FROM app_user WHERE id = ?", userId));
    }

    // ==================== 建号小工具 ====================

    /** 用户不知道的随机口令：保证同时含字母和数字（前缀 Yt + 十六进制），满足 PWD_RULE */
    private static String randomPassword() {
        return "Yt" + Crypto.randomToken().substring(0, 16);
    }

    /**
     * 让自动生成的用户名落到 UNIQUE 之外。
     * 理论上只有存量库里已经有一个手工建的同名账号才会走到加后缀这条路
     * （邮箱前缀相同、或手机号已被 m+号码 占过），所以 base 冲突时补 4 位随机十六进制。
     */
    private String uniqueUsername(String base) {
        Long taken = jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE username = ?", Long.class, base);
        if (taken == null || taken == 0) return base;
        return base + "_" + Crypto.randomToken().substring(0, 4);
    }

    /** 邮箱前缀里只有字母数字下划线能进用户名，其余一律换成下划线，并截到 24 位 */
    private static String usernameSeed(String emailAddr) {
        String local = emailAddr.substring(0, emailAddr.indexOf('@')).replaceAll("[^A-Za-z0-9_]", "_");
        return local.length() > 24 ? local.substring(0, 24) : local;
    }

    /** 渠道昵称列是 VARCHAR(64)，第三方给的花名可能带 emoji 甚至超长 */
    private static String clampProviderText(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;
        return t.length() > 64 ? t.substring(0, 64) : t;
    }

    // 渠道的用户名前缀与显示名都取 OAuthService 那张注册表：本类与它之间只有静态调用，
    // 不注入 bean（OAuthService 反过来依赖本类，注入会成环）。加渠道只改那一个地方
    private static String providerPrefix(String provider) {
        return OAuthService.providerPrefix(provider);
    }

    private static String providerName(String provider) {
        return OAuthService.providerName(provider);
    }

    /**
     * 账号密码登录。用户名这一栏现在同时接受注册时生成的用户名、绑定的邮箱
     * （用户名正则不允许 '@'，两者不会撞车，所以一条 {@code username = ? OR email = ?} 就够，
     * 不存在「这个字符串既是某人的用户名又是另一个人的邮箱」）。
     *
     * <p>口令注册通道下线后，用户名是 {@code m+手机号} / {@code e+邮箱前缀} / {@code wx_随机} 这类
     * 机器生成的串，让人去记它没有意义——能记住的是手机号和邮箱，所以邮箱也必须能登。
     *
     * <p>失败锁定按「提交的标识 + IP」计数：同一个人换着填用户名和邮箱会得到两个计数器，
     * 这是可接受的（两个标识都得各撞 5 次才锁），换取的是不必先解析身份再决定锁谁。
     */
    public Map<String, Object> login(Map<String, Object> req, String clientIp) {
        String identifier = str(req.get("username"));
        String password = str(req.get("password"));
        if (identifier == null || password == null) {
            throw new IllegalStateException("请输入用户名（或邮箱）和密码");
        }
        String emailAddr = EmailCodeService.isEmail(identifier) ? EmailCodeService.normalize(identifier) : null;

        String key = identifier + '@' + (clientIp == null ? "-" : clientIp);
        checkNotLocked(key);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, username, pwd_hash, salt, nickname, phone, email, auth_ver FROM app_user"
                        + " WHERE username = ?" + (emailAddr == null ? "" : " OR email = ?"),
                emailAddr == null ? new Object[]{identifier} : new Object[]{identifier, emailAddr});
        if (rows.isEmpty() || !passwordMatches(password, (String) rows.get(0).get("pwd_hash"), (String) rows.get(0).get("salt"))) {
            // 用户不存在与密码错误返回同样的提示，避免账号枚举；但两者都要计入失败次数
            if (recordFailure(key)) {
                // 恰好这一次越过阈值：当场说明已锁定，而不是让人再试一次才发现（冒烟脚本第 12 节钉住这个行为）
                throw new IllegalStateException("密码错误次数过多，账号已临时锁定，请 " + loginLockMinutes + " 分钟后再试");
            }
            throw new IllegalStateException("用户名或密码错误");
        }
        loginAttempts.remove(key);

        Map<String, Object> row = rows.get(0);
        String stored = (String) row.get("pwd_hash");
        String salt = (String) row.get("salt");
        // 旧版 SHA-256 记录在此透明升级为 PBKDF2，历史账号无需强制改密
        if (!Crypto.isKdfStored(stored)) {
            jdbc.update("UPDATE app_user SET pwd_hash = ? WHERE id = ?", encodePassword(password, salt), row.get("id"));
        }
        return sessionFor(row);
    }

    /**
     * 手机验证码登录。验证码本身由 {@link SmsCodeService} 校验（有效期、重发间隔、
     * 每小时上限、尝试次数、一次性），这里只负责「号码 → 账号 → 签发会话」。
     *
     * <p>不再叠一层登录失败锁定：6 位码的尝试次数已经被 sms-max-attempts 钉死并在达到上限时
     * 作废，重发另有每小时配额；同一件事做两套计数器反而会出现「哪个先到上限」说不清的情况。
     *
     * <p>一个号码挂着多个账号时不再直接拒绝：验码通过后返回 needChoose + 候选列表，
     * 用户挑定账号再带 chooseToken 请求一次即可拿到会话（验证码不重复消费）。
     */
    public Map<String, Object> loginBySms(String phone, String code, String chooseToken, String username) {
        if (phone == null || !phone.matches("1[3-9]\\d{9}")) {
            throw new IllegalStateException("请输入11位有效手机号码");
        }
        // 第二步：码已在上一请求验过（并作废），这里只凭一次性凭证把账号挑定
        if (chooseToken != null && !chooseToken.isBlank()) {
            return sessionFor(consumeChooseTicket(SmsCodeService.SCENE_LOGIN, chooseToken, phone, username));
        }
        if (code == null || !code.matches("\\d{6}")) {
            throw new IllegalStateException("请输入6位短信验证码");
        }
        if (!sms.verifyLoginCode(phone, code)) {
            throw new IllegalStateException("验证码不正确或已失效");
        }
        // 号码可能挂着多个账号（演示库里 13800138000 就有三个）。验过码说明号码归属成立，
        // 剩下的只是「登哪一个」，所以既不猜也不拒，交给用户自己选。
        List<Map<String, Object>> rows = jdbc.queryForList(USER_BY_PHONE, phone);
        if (rows.isEmpty()) {
            throw new IllegalStateException("该手机号未注册，请先注册");
        }
        if (rows.size() > 1) {
            return needChooseAccount(SmsCodeService.SCENE_LOGIN, phone, rows);
        }
        return sessionFor(rows.get(0));
    }

    /** 发一张一次性「选账号」凭证；顺手清掉已过期的，避免这张表只增不减 */
    private String issueChooseTicket(String scene, String phone, List<Map<String, Object>> rows) {
        Instant now = Instant.now();
        pendingChooses.entrySet().removeIf(e -> e.getValue().expireAt().isBefore(now));
        String token = Crypto.randomToken();
        pendingChooses.put(token, new PendingChoose(scene, phone, now.plus(CHOOSE_TTL), List.copyOf(rows)));
        return token;
    }

    /**
     * 用凭证换取候选账号中的一行。场景与号码都必须对得上——
     * 否则能拿一张登录凭证去走重置接口（或反过来），把「已证明持有该号码」挪作他用。
     */
    private Map<String, Object> consumeChooseTicket(String scene, String token, String phone, String username) {
        PendingChoose p = (token == null || token.isBlank()) ? null : pendingChooses.remove(token);
        if (p == null || p.expireAt().isBefore(Instant.now())
                || !p.scene().equals(scene) || !p.phone().equals(phone)) {
            throw new IllegalStateException("账号选择已失效，请重新获取验证码");
        }
        for (Map<String, Object> row : p.accounts()) {
            if (username != null && username.equals(row.get("username"))) {
                return row;
            }
        }
        throw new IllegalStateException("请选择要操作的账号");
    }

    /** 需要用户挑账号时的返回体：前端按 needChoose 渲染单选列表（昵称空时回落用户名） */
    private Map<String, Object> needChooseAccount(String scene, String phone, List<Map<String, Object>> rows) {
        List<Map<String, Object>> accounts = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String name = (String) row.get("username");
            Object nick = row.get("nickname");
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("username", name);
            one.put("nickname", nick == null || String.valueOf(nick).isBlank() ? name : nick);
            accounts.add(one);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("needChoose", true);
        res.put("chooseScene", scene);
        res.put("chooseToken", issueChooseTicket(scene, phone, rows));
        res.put("accounts", accounts);
        return res;
    }

    /**
     * 邮箱验证码登录。与 {@link #loginBySms} 同一套：码由 {@link EmailCodeService} 校验
     * （有效期、重发间隔、每小时上限、尝试次数、一次性），这里只做「邮箱 → 账号 → 签发会话」。
     *
     * <p>同样不叠登录失败锁定：6 位码的尝试次数已被 email-max-attempts 钉死并在达上限时作废。
     */
    public Map<String, Object> loginByEmailCode(String emailAddr, String code) {
        String normalized = EmailCodeService.normalize(emailAddr);
        if (!EmailCodeService.isEmail(emailAddr)) {
            throw new IllegalStateException("请输入有效的邮箱地址");
        }
        if (code == null || !code.matches("\\d{6}")) {
            throw new IllegalStateException("请输入6位邮箱验证码");
        }
        if (!email.verifyLoginCode(normalized, code)) {
            throw new IllegalStateException("验证码不正确或已失效");
        }
        // email 上有唯一索引，正常只可能 0 或 1 行；仍按 size 判断，与手机号那条路同一写法，
        // 免得将来有人手工塞了重复邮箱时这里静默登录了第一个
        List<Map<String, Object>> rows = jdbc.queryForList(USER_BY_EMAIL, normalized);
        if (rows.isEmpty()) {
            throw new IllegalStateException("该邮箱未注册，请先注册或用账号密码登录");
        }
        if (rows.size() > 1) {
            throw new IllegalStateException("该邮箱绑定了 " + rows.size()
                    + " 个账号，无法用验证码登录，请改用账号密码登录");
        }
        return sessionFor(rows.get(0));
    }

    /**
     * 扫码确认后为指定账号签发会话。手机点「确认登录」时已经把那个账号的令牌验过了，
     * 电脑这边领取的是新签发的一条会话，不是把手机那条令牌搬过去。
     */
    public Map<String, Object> issueSession(long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(USER_FOR_SESSION, userId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("账号不存在或已被删除");
        }
        return sessionFor(rows.get(0));
    }

    /** 写一条 user_session 并组装登录返回体；token 与表里那条记录一一对应 */
    private Map<String, Object> sessionFor(Map<String, Object> row) {
        String token = Crypto.randomToken();
        jdbc.update("INSERT INTO user_session(token, user_id, auth_ver, expire_time) VALUES(?,?,?,?)",
                token, row.get("id"), row.get("auth_ver"),
                Timestamp.from(Instant.now().plusSeconds(TOKEN_TTL_SECONDS)));
        purgeExpiredSessions();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("id", row.get("id"));
        out.put("username", row.get("username"));
        out.put("nickname", row.get("nickname"));
        out.put("phone", row.get("phone"));
        out.put("email", row.get("email"));
        return out;
    }

    // ==================== 会话 ====================

    /** 供拦截器判断 token 是否仍然有效，不抛异常 */
    public boolean isValidToken(String token) {
        return findAuthUser(token) != null;
    }

    /**
     * 会话身份。不抛异常，令牌无效时返回 null——便于控制器在拦截器之后安全地二次读取，
     * 万一令牌恰好在两次读取之间失效，也只是这一次请求被拒，不会变成 500。
     */
    public record AuthUser(long id, String username, String nickname, String phone, String email) {}

    public AuthUser findAuthUser(String token) {
        if (token == null || token.isEmpty()) return null;
        List<Map<String, Object>> rows = jdbc.queryForList(SQL_SESSION_USER, token);
        if (rows.isEmpty()) return null;
        Map<String, Object> r = rows.get(0);
        return new AuthUser(((Number) r.get("id")).longValue(),
                (String) r.get("username"), (String) r.get("nickname"),
                (String) r.get("phone"), (String) r.get("email"));
    }

    public Map<String, Object> me(String token) {
        AuthUser u = findAuthUser(token);
        if (u == null) {
            throw new IllegalStateException("登录已过期，请重新登录");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", u.id());
        out.put("username", u.username());
        out.put("nickname", u.nickname());
        out.put("phone", u.phone());
        // 邮箱只回给持有该账号会话的人，与 phone 同一口径（订单接口上的掩码是针对「别人的联系人号码」）
        out.put("email", u.email());
        // 个人中心还要展示：注册时间 + 已授权的第三方应用。user_oauth 由 OAuthService 写入，这里只读；
        // 渠道显示名走 OAuthService 的静态注册表，不注入 bean（那边依赖本类，反过来会成环）
        out.put("createTime", fmtTime(jdbc.queryForObject(
                "SELECT create_time FROM app_user WHERE id = ?", Timestamp.class, u.id())));
        List<Map<String, Object>> bindings = new ArrayList<>();
        for (Map<String, Object> b : jdbc.queryForList("SELECT provider, nickname, create_time, last_login_time"
                + " FROM user_oauth WHERE user_id = ? ORDER BY id", u.id())) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("provider", b.get("provider"));
            one.put("providerName", OAuthService.providerName((String) b.get("provider")));
            one.put("nickname", b.get("nickname"));
            one.put("bindTime", fmtTime(b.get("create_time")));
            one.put("lastLoginTime", fmtTime(b.get("last_login_time")));
            bindings.add(one);
        }
        out.put("oauth", bindings);
        out.put("loginWays", loginWayCount(u.id(), u.phone(), u.email()));
        return out;
    }

    // ==================== 个人中心：解绑 ====================

    /**
     * 解绑手机号。
     *
     * <p>解完必须还剩至少一种登录方式（邮箱或其他第三方绑定）：本站已下线账号密码登录，
     * 把最后一种登录凭据也解掉等于当场把这个账号锁死，而且找回来还得靠同一个已解绑的号码收码。
     */
    public String unbindPhone(String token) {
        AuthUser u = requireUser(token);
        if (u.phone() == null || u.phone().isBlank()) {
            throw new IllegalStateException("当前账号未绑定手机号");
        }
        if (loginWayCount(u.id(), u.phone(), u.email()) - 1 <= 0) {
            throw new IllegalStateException("手机号是该账号唯一的登录方式，解绑后将无法登录；请先绑定邮箱或第三方账号再来解绑");
        }
        jdbc.update("UPDATE app_user SET phone = NULL WHERE id = ?", u.id());
        return "手机号已解绑";
    }

    /** 解绑一个第三方应用（微信 / QQ / 微博）；与解绑手机号同一道「至少留一种登录方式」的门 */
    public String unbindOAuth(String token, String provider) {
        AuthUser u = requireUser(token);
        if (!OAuthService.isKnownProvider(provider)) {
            throw new IllegalStateException("不支持的授权渠道");
        }
        Integer bound = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_oauth WHERE user_id = ? AND provider = ?",
                Integer.class, u.id(), provider);
        if (bound == null || bound == 0) {
            throw new IllegalStateException("该渠道尚未绑定");
        }
        // 先判断再删：删了才发现「没别的登录方式了」就已经把用户锁在门外了
        if (loginWayCount(u.id(), u.phone(), u.email()) - 1 <= 0) {
            throw new IllegalStateException("这是该账号唯一的登录方式，解绑后将无法登录；请先绑定手机号或邮箱再来解绑");
        }
        jdbc.update("DELETE FROM user_oauth WHERE user_id = ? AND provider = ?", u.id(), provider);
        return "已解绑" + OAuthService.providerName(provider);
    }

    /** 当前可用的登录方式条数：手机号 / 邮箱 / 每一个第三方绑定各算一种 */
    private int loginWayCount(long userId, String phone, String email) {
        int n = 0;
        if (phone != null && !phone.isBlank()) n++;
        if (email != null && !email.isBlank()) n++;
        Integer oauth = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_oauth WHERE user_id = ?", Integer.class, userId);
        return n + (oauth == null ? 0 : oauth);
    }

    /** 自校验会话：个人中心与解绑接口都拿它取当前账号，无效令牌统一报「登录已过期」 */
    private AuthUser requireUser(String token) {
        AuthUser u = findAuthUser(token);
        if (u == null) {
            throw new IllegalStateException("登录已过期，请重新登录");
        }
        return u;
    }

    /** 数据库时间列到人读的字符串；列里可能是 Timestamp 也可能是 LocalDateTime，两处都要兼容 */
    private static String fmtTime(Object v) {
        if (v == null) return null;
        if (v instanceof Timestamp t) return t.toLocalDateTime().format(MY_TIME);
        if (v instanceof java.time.LocalDateTime dt) return dt.format(MY_TIME);
        return String.valueOf(v);
    }

    private static final DateTimeFormatter MY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 退出登录：只删当前这一条会话，其他设备上的令牌不受影响 */
    public void logout(String token) {
        if (token != null && !token.isEmpty()) {
            jdbc.update("DELETE FROM user_session WHERE token = ?", token);
        }
    }

    // ==================== 改密 ====================

    /**
     * 校验旧口令后改密。改完把 auth_ver +1 并清空该用户全部会话：
     * 此前「改密码不会吊销旧令牌」——即使口令泄露被对方改掉，受害者手上的会话也照样能用。
     * 因此调用方在成功后应要求重新登录。
     */
    public void changePassword(String token, String oldPassword, String newPassword) {
        AuthUser u = findAuthUser(token);
        if (u == null) {
            throw new IllegalStateException("登录已过期，请重新登录");
        }
        if (oldPassword == null || newPassword == null) {
            throw new IllegalStateException("请输入原密码和新密码");
        }
        if (!isStrongPassword(newPassword)) {
            throw new IllegalStateException(PWD_RULE);
        }
        if (newPassword.equals(oldPassword)) {
            throw new IllegalStateException("新密码不能与原密码相同");
        }

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT pwd_hash, salt FROM app_user WHERE id = ?", u.id());
        if (!passwordMatches(oldPassword, (String) row.get("pwd_hash"), (String) row.get("salt"))) {
            throw new IllegalStateException("原密码不正确");
        }

        applyPasswordReset(u.id(), u.username(), u.email(), newPassword);
    }

    /**
     * 找回密码（手机号）：手机号 + 重置验证码 + 新密码。
     *
     * <p>与 {@link #changePassword} 的关键区别是不校验旧密码——正因为用户想不起来才走这条路，
     * 账号归属改由「收到发到该号码的验证码」证明（见 {@link SmsCodeService#sendResetCode} 的登录式归属校验）。
     *
     * <p>号码必须已注册；挂着多个账号时与登录同一套：验码通过后返回 needChoose + 候选列表，
     * 用户挑定账号再带 chooseToken 与新密码请求一次即可完成重置。
     *
     * @return 需要选账号时返回候选列表载荷；重置完成返回 null（成功与否由调用方的异常决定）
     */
    public Map<String, Object> resetPasswordByPhone(String phone, String code, String newPassword,
                                                    String chooseToken, String username) {
        if (phone == null || !phone.matches("1[3-9]\\d{9}")) {
            throw new IllegalStateException("请输入11位有效手机号码");
        }
        if (!isStrongPassword(newPassword)) {
            throw new IllegalStateException(PWD_RULE);
        }
        // 第二步：码已验过，凭凭证定位账号后直接重置
        if (chooseToken != null && !chooseToken.isBlank()) {
            Map<String, Object> row = consumeChooseTicket(SmsCodeService.SCENE_RESET, chooseToken, phone, username);
            applyPasswordReset(((Number) row.get("id")).longValue(), (String) row.get("username"),
                    (String) row.get("email"), newPassword);
            return null;
        }
        if (code == null || !code.matches("\\d{6}")) {
            throw new IllegalStateException("请输入6位短信验证码");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(USER_BY_PHONE, phone);
        if (rows.isEmpty()) {
            throw new IllegalStateException("该手机号未注册");
        }
        if (!sms.verifyResetCode(phone, code)) {
            throw new IllegalStateException("验证码不正确或已失效");
        }
        if (rows.size() > 1) {
            return needChooseAccount(SmsCodeService.SCENE_RESET, phone, rows);
        }
        Map<String, Object> row = rows.get(0);
        applyPasswordReset(((Number) row.get("id")).longValue(), (String) row.get("username"),
                (String) row.get("email"), newPassword);
        return null;
    }

    /**
     * 找回密码（邮箱）：与手机号那条同构，只是凭据换成邮箱。email 有唯一索引，正常只归属一个账号。
     */
    public void resetPasswordByEmail(String emailAddr, String code, String newPassword) {
        String normalized = EmailCodeService.normalize(emailAddr);
        if (!EmailCodeService.isEmail(emailAddr)) {
            throw new IllegalStateException("请输入有效的邮箱地址");
        }
        if (code == null || !code.matches("\\d{6}")) {
            throw new IllegalStateException("请输入6位邮箱验证码");
        }
        if (!isStrongPassword(newPassword)) {
            throw new IllegalStateException(PWD_RULE);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(USER_BY_EMAIL, normalized);
        if (rows.isEmpty()) {
            throw new IllegalStateException("该邮箱未注册");
        }
        if (rows.size() > 1) {
            throw new IllegalStateException("该邮箱绑定了 " + rows.size() + " 个账号，无法自动定位，请联系客服");
        }
        if (!email.verifyResetCode(normalized, code)) {
            throw new IllegalStateException("验证码不正确或已失效");
        }
        Map<String, Object> row = rows.get(0);
        applyPasswordReset(((Number) row.get("id")).longValue(), (String) row.get("username"),
                (String) row.get("email"), newPassword);
    }

    /**
     * 重置口令的统一落库：换盐重算哈希、auth_ver +1、清空该用户全部会话、清失败锁定计数。
     * 改密与找回密码共用这段——两者对外的效果（旧令牌全部失效、需重新登录）本就一致。
     */
    private void applyPasswordReset(long userId, String username, String emailAddr, String newPassword) {
        String salt = Crypto.randomSaltHex();
        jdbc.update("UPDATE app_user SET pwd_hash = ?, salt = ?, auth_ver = auth_ver + 1 WHERE id = ?",
                encodePassword(newPassword, salt), salt, userId);
        jdbc.update("DELETE FROM user_session WHERE user_id = ?", userId);
        // 失败计数按「提交的标识 + IP」维度残留，用户名与邮箱两种填法都要清，否则重置完还是被锁着
        loginAttempts.keySet().removeIf(k -> (username != null && k.startsWith(username + "@"))
                || (emailAddr != null && k.startsWith(emailAddr + "@")));
    }

    // ==================== 建号 ====================

    /** 昵称列是 VARCHAR(50)：超长截断，而不是让 MySQL 报「Data too long」变成一句裸 500 */
    private static String clampNickname(String nickname, String fallback) {
        String n = (nickname == null || nickname.isEmpty()) ? fallback : nickname;
        return n.length() > 50 ? n.substring(0, 50) : n;
    }

    /**
     * 插入账号。上面的 COUNT 预检查只是为了给一句更清楚的提示，真正的唯一性由
     * {@code app_user.username}（以及 {@code email}）的 UNIQUE 索引兜底：两个请求同时注册
     * 同一个用户名/邮箱时后到的那个会撞键——不转成业务异常就是一句 500。
     *
     * <p>用户名一律由调用方生成（{@code m+手机号} / {@code e+邮箱前缀} / {@code 渠道+随机串}），
     * 口令注册通道下线后这里不再接受用户自己填的用户名。
     */
    private void insertUser(String username, String password, String salt,
                            String nickname, String phone, String emailAddr) {
        try {
            jdbc.update("INSERT INTO app_user(username, pwd_hash, salt, nickname, phone, email) VALUES(?,?,?,?,?,?)",
                    username, encodePassword(password, salt), salt, nickname, phone, emailAddr);
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("该账号刚刚已被注册，请直接登录或换一个手机号/邮箱");
        }
    }

    // ==================== 限速 ====================

    private void checkNotLocked(String key) {
        Attempt a = loginAttempts.get(key);
        if (a == null || a.lockedUntil() == null) return;
        Instant now = Instant.now();
        if (a.lockedUntil().isAfter(now)) {
            long minutes = Math.max(1, Duration.between(now, a.lockedUntil()).toMinutes() + 1);
            throw new IllegalStateException("失败次数过多，账号已临时锁定，请 " + minutes + " 分钟后再试");
        }
        loginAttempts.remove(key);
    }

    /**
     * 记一次登录失败。
     *
     * @return 本次失败是否刚好把账号推进锁定期——调用方据此在同一个响应里就说「已临时锁定」，
     *         省掉「第 N 次只说密码错误、第 N+1 次才说锁定」这种让人以为还能再试的误导。
     */
    private boolean recordFailure(String key) {
        Instant now = Instant.now();
        if (loginAttempts.size() > COUNTER_CAP) {
            loginAttempts.values().removeIf(a -> a.lastFailure().plus(ATTEMPT_TTL).isBefore(now));
        }
        Attempt a = loginAttempts.compute(key, (k, old) -> {
            // 上一次失败已经是一小时前：重新开始数，否则「连续失败 5 次」会变成「累计失败 5 次」
            int previous = old == null || old.lastFailure().plus(ATTEMPT_TTL).isBefore(now) ? 0 : old.failures();
            int failures = previous + 1;
            Instant locked = failures >= maxLoginFailures
                    ? now.plus(Duration.ofMinutes(loginLockMinutes)) : null;
            return new Attempt(failures, locked, now);
        });
        return a != null && a.lockedUntil() != null;
    }

    private void throttleRegister(String clientIp) {
        String ip = clientIp == null ? "-" : clientIp;
        long nowHour = System.currentTimeMillis() / 3_600_000;
        // 内存计数器只增不减迟早吃满堆：到阈值就把「不属于当前小时」的条目清掉
        if (registerWindow.size() > COUNTER_CAP) registerWindow.values().removeIf(v -> v[1] != nowHour);
        long[] win = registerWindow.compute(ip, (k, v) -> {
            if (v == null || v[1] != nowHour) return new long[]{1, nowHour};
            v[0]++;
            return v;
        });
        if (win != null && win[0] > maxRegistersPerHour) {
            throw new IllegalStateException("注册过于频繁，请稍后再试（同一网络每小时最多 " + maxRegistersPerHour + " 个账号）");
        }
    }

    /** 过期会话顺手清掉；保留一天内的记录便于排查「为什么刚才还能用」 */
    private void purgeExpiredSessions() {
        jdbc.update("DELETE FROM user_session WHERE expire_time < DATE_SUB(NOW(), INTERVAL 1 DAY)");
    }

    // ==================== 口令散列（统一走 Crypto） ====================

    private static boolean isStrongPassword(String password) {
        return password != null
                && password.length() >= 8
                && password.matches(".*[A-Za-z].*")
                && password.matches(".*\\d.*");
    }

    /** 生成 pbkdf2$迭代数$盐$哈希 形式的存储值 */
    private static String encodePassword(String password, String salt) {
        return Crypto.encode(password, salt);
    }

    /** 兼容校验：新格式走 PBKDF2，旧的单次 SHA-256 记录按原算法比对 */
    private static boolean passwordMatches(String raw, String stored, String salt) {
        if (raw == null || stored == null || salt == null) return false;
        if (Crypto.isKdfStored(stored)) return Crypto.matchesKdf(raw, stored);
        return Crypto.constantTimeEquals(Crypto.sha256Hex(raw + salt), stored);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }
}
