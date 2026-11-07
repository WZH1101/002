#!/usr/bin/env bash
# =====================================================================
# 悦途预订平台后端 · 冒烟回归脚本（零测试依赖）
#
# 为什么是这个脚本而不是一堆单元测试：
#   本地 Maven 仓库（D:\Maven\m2repo）里没有 junit / mockito / h2，也不打算为测试联网下载它们。
#   于是把这一轮加固后的行为契约用 curl + node 钉下来——改完后端跑一遍，回归当场就能看见。
#
# 用法：
#   1) 先启动后端（另开一个窗口）：
#        双击 ctrip-backend\run-backend.bat
#      或在 ctrip-backend 目录执行：
#        D:\Maven\apache-maven-3.9.9\bin\mvn.cmd -o -Dmaven.repo.local=D:\Maven\m2repo spring-boot:run
#   2) 在 ctrip-backend 目录执行：  bash qa/smoke.sh
#   环境变量：
#        BASE        后端地址，默认 http://localhost:8080
#        MYSQL_BIN   mysql.exe 全路径；MYSQL_PWD 口令。两者都给出时：
#                    · 「超时自动取消」用例可以直接把 pay_deadline 改到过去，几秒钟就能验完；
#                    · 跑完自动清理测试数据。
#        SMOKE_SWEEP 设为 1 时额外验证 OrderExpiryTask 定时扫描（要等下一次扫描，最长约 90 秒）
#
# 耗时：正常十几秒。只有一种情况要多等一分钟——本次运行真的新建了 smoke_a 账号，
#   那么它的注册码还压在「同一邮箱 60 秒只能发一条」的冷却里，
#   第 16 节（邮箱验证码登录）会等到冷却过去再发登录码。第二次跑就不会再等。
#   同理，第 17 节末尾压满扫码限额、第 18 节末尾压满授权限额之后，同一分钟内重跑会整体跳过这两节。
#
# 覆盖的契约（对应本轮安全加固 + 订单状态机 + 五种登录 / 三种注册）：
#   登录门禁 / 口令强度 / 登录失败锁定 / 注册限频 / 服务端计价 / 幂等重复提交 /
#   订单归属隔离 / 手机号脱敏 / 批量取消需显式确认 / 改密吊销全部令牌 / CORS 来源白名单；
#   下单为「待支付」+ 支付窗口 / 支付→已支付 / 已支付只能退款不能取消 / 退款回补库存 /
#   已取消、已退款可删除 / 待支付、已支付不可删除 / 超时未支付自动取消并回补库存；
#   手机验证码（发码限流 / 一次性 / 号码唯一归属）/ 手机号注册 /
#   邮箱验证码注册与登录（格式校验 / 一次性 / 重复邮箱 / 跨场景共用重发冷却 /
#     邮箱可直接当登录账号）/ 「用户名+密码」注册已下线 /
#   第三方授权注册（模拟授权页 / 身份只能取自服务端注册表 / 令牌只送达一次 /
#     同一 openId 落回同一账号 / 拒绝与过期都不发令牌 / 每分钟发起上限）/
#   扫码登录状态机（PENDING→SCANNED→CONFIRMED→只送达一次、取消、过期、确认页不出回环地址）/
#   7×24 客服规则问答（演示口径、转人工兜底、空消息与限流）
#
# 说明：测试账号叫 smoke_a / smoke_b / smoke_c / smoke_sms，都是**邮箱注册**建的号
#       （用户名由服务端生成，形如 esmoke_a；登录时填邮箱或填这个用户名都可以）；
#       手机号用 1390000xxxx 号段，邮箱用 @qa.smoke.test 域，第三方只动微信/QQ/微博/支付宝/百度里
#       预置的三个演示身份之一——都可以反复运行，账号已存在时自动改走登录，不消耗注册额度。
#       用例结束时订单都已删除或已取消，需要彻底清掉残留请用带上 MYSQL_BIN/MYSQL_PWD 的自动清理。
# =====================================================================
set -uo pipefail

BASE="${BASE:-http://localhost:8080}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

PASS=0; FAIL=0; SKIP=0
ok()   { printf '  [PASS] %s\n' "$1"; PASS=$((PASS+1)); }
no()   { printf '  [FAIL] %s\n         期望：%s\n         实际：%s\n' "$1" "$2" "$3"; FAIL=$((FAIL+1)); }
skip() { printf '  [SKIP] %s —— %s\n' "$1" "$2"; SKIP=$((SKIP+1)); }
say()  { printf '%s\n' "$1"; }
section() { printf '\n== %s ==\n' "$1"; }

HTTP=""; LAST=""; TOKEN=""

# req METHOD PATH [JSON_BODY_FILE] [TOKEN] → HTTP=状态码，LAST=响应体
req() {
    local m="$1" p="$2" bf="${3:-}" tk="${4:-}"
    local args=(-s -o "$TMP/resp.json" -w '%{http_code}' -X "$m" "$BASE$p")
    [ -n "$bf" ] && args+=(--data-binary "@$bf" -H 'Content-Type: application/json; charset=UTF-8')
    [ -n "$tk" ] && args+=(-H "Authorization: Bearer $tk")
    HTTP=$(curl "${args[@]}" 2>/dev/null)
    LAST=$(cat "$TMP/resp.json" 2>/dev/null)
    [ -z "$LAST" ] && LAST="(空响应)"
}

# f 路径 —— 从上一次响应里取字段，如 f data.token / f msg；取不到输出空串
f() {
    node -e '
    const fs = require("fs");
    let j; try { j = JSON.parse(fs.readFileSync(process.argv[1], "utf8")); } catch (e) { process.exit(0); }
    let v = j;
    for (const k of process.argv[2].split(".")) { if (v == null) break; v = v[k]; }
    process.stdout.write(v == null || v === undefined ? "" : String(v));
    ' "$TMP/resp.json" "$1"
}

# json_body 输出文件 key value [key value ...] —— 用 node 写文件，绕开 Git Bash 传参时的 GBK 编码问题
json_body() {
    node -e '
    // node -e 时 process.argv[0] 是 node 自己，用户参数从 argv[1] 起：
    // argv[1]=输出文件，之后成对是 key/value。
    const fs = require("fs");
    const a = process.argv.slice(1), o = {};
    for (let i = 1; i + 1 < a.length; i += 2) o[a[i]] = a[i + 1];
    fs.writeFileSync(a[0], JSON.stringify(o));
    ' "$@"
}

assert_eq()  { if [ "$2" = "$3" ]; then ok "$1"; else no "$1" "$3" "$2"; fi; }
assert_has() { case "$2" in *"$3"*) ok "$1";; *) no "$1" "包含「$3」" "$2";; esac; }
assert_lack(){ case "$2" in *"$3"*) no "$1" "不含「$3」" "$2";; *) ok "$1";; esac; }

# ---------------------------------------------------------------------
say "目标：$BASE（$(date '+%Y-%m-%d %H:%M:%S')）"

section '0. 服务与状态页'
req GET /
assert_eq '状态页 HTTP 200' "$HTTP" '200'
assert_has '状态页是自定义页面而非 Whitelabel' "$LAST" '悦途预订平台'
assert_has '状态页写明了支付窗口' "$LAST" '分钟未支付'
# 状态页是「接口清单」的唯一对外说明，登录/注册方式改了它必须跟着改——
# 之前它就还挂着「注册两种方式：用户名+密码」，等于官方页面在替一条已下线的通道打广告。
assert_has '状态页写的是五种登录' "$LAST" '登录五种方式'
assert_has '状态页写的是三种注册' "$LAST" '注册三种方式'
assert_has '状态页把授权页标成模拟' "$LAST" '本站模拟的授权页'
assert_has '状态页说明用户名由服务端生成' "$LAST" '用户名一律由服务端自动生成'

section '1. 未登录不能读写订单（登录门禁）'
req GET /api/orders
assert_eq '匿名订单列表返回 401' "$HTTP" '401'
assert_has '匿名订单列表 code=401' "$LAST" '"code":401'
json_body "$TMP/anon-order.json" type hotel itemId 1 choice 大床房 quantity 1 name 匿名者 phone 13900000000
req POST /api/orders "$TMP/anon-order.json"
assert_eq '匿名下单返回 401' "$HTTP" '401'
req DELETE /api/orders
assert_eq '匿名清空订单返回 401' "$HTTP" '401'
req POST /api/orders/1/pay
assert_eq '匿名支付订单返回 401' "$HTTP" '401'
req POST /api/orders/1/refund
assert_eq '匿名退款返回 401' "$HTTP" '401'
req DELETE /api/orders/1
assert_eq '匿名删除订单返回 401' "$HTTP" '401'

section '2. 口令强度与注册校验'
# 「用户名 + 密码」注册已下线，所以弱口令反例改走邮箱注册这条路：
# 后端把格式校验（邮箱 → 验证码位数 → 口令强度）排在限流与验码之前，
# 于是这条反例既不消耗注册额度，也不需要真的先发一个码。
json_body "$TMP/weak.json" email "smoke_weak_$$@qa.smoke.test" code 123456 password 123456
req POST /api/auth/register "$TMP/weak.json"
case "$LAST" in
    *'过于频繁'*) skip '弱口令被拒' '本机这一小时的注册额度已用满（可加大 ctrip.auth.max-register-per-hour 后重启后端）' ;;
    *) assert_has '弱口令注册被拒并给出规则' "$LAST" '至少 8 位' ;;
esac
# 注册入口只认「带 email」或「带 code」两种载荷；两者都不给就是过去那种用户名注册，直接拒
json_body "$TMP/legacy-reg.json" username "smoke_legacy_$$" password 'Smoke@2026legacy'
req POST /api/auth/register "$TMP/legacy-reg.json"
assert_has '用户名+密码注册已下线，并指路新的三种方式' "$LAST" '已下线'

# 测试账号：smoke_a 走完整状态机，smoke_b 验证归属隔离，smoke_c 验证失败锁定
PW_A='Smoke@2026a'; PW_B='Smoke@2026b'; PW_A2='Smoke@2026c'
phone_a='13900001111'
# 每个测试账号配一个邮箱：注册靠它，登录后也可以直接拿它当账号（用户名由服务端生成，形如 esmoke_a）
mail_domain='qa.smoke.test'
mail_a="smoke_a@$mail_domain"; mail_b="smoke_b@$mail_domain"; mail_c="smoke_c@$mail_domain"
# 上一次「给 smoke_a 发码」的时刻 + 冷却秒数：邮箱验证码登录一节要等它过去才能拿到新码
MAIL_FREEZE_A=0

login_as() { # login_as 邮箱或用户名 口令 → 成功则设 TOKEN，失败置空
    json_body "$TMP/login.json" username "$1" password "$2"
    req POST /api/auth/login "$TMP/login.json"
    TOKEN=$(f data.token)
}

register_or_login() { # register_or_login 账号名 口令 邮箱 —— 先试登录（重复运行的快路径），没有账号才用邮箱注册
    local name="$1" pw="$2" mail="$3"
    login_as "$mail" "$pw"
    if [ -n "$TOKEN" ]; then say "  · 账号 $mail 已存在，直接登录（不消耗注册额度）"; return 0; fi
    json_body "$TMP/reg-send.json" email "$mail"
    req POST /api/auth/email/register-code "$TMP/reg-send.json"
    local rc; rc=$(f data.demoCode)
    if [ -z "$rc" ]; then
        case "$LAST" in
            *'验证码已发送'*) skip "注册 $mail" '上一条验证码还在重发冷却里，等一分钟再跑' ;;
            *'过于频繁'*)     skip "注册 $mail" '发码配额用满（单邮箱每小时 5 条 / 单 IP 每小时 20 条）' ;;
            *)               no "注册发码 $mail" 'code 0' "$LAST" ;;
        esac
        return 1
    fi
    MAIL_FREEZE_A=$(( $(date +%s) + 60 ))
    json_body "$TMP/reg.json" email "$mail" code "$rc" nickname "冒烟测试$name" phone "$phone_a" password "$pw"
    req POST /api/auth/register "$TMP/reg.json"
    case "$LAST" in
        *'"code":0'*) say "  · 新注册账号 $mail，服务端生成的用户名是 $(f data.username)" ;;
        *'已被注册'*) no "注册 $mail" '新账号' "同邮箱已存在但口令对不上（历史遗留），请手工删掉 app_user 里 email=$mail 这一行" ;;
        *'过于频繁'*) skip "注册 $mail" '本机这一小时的注册额度已用满；重启后端会连同内存计数一起清零' ;;
        *) no "注册 $mail" 'code 0 / 已被注册 / 限流' "$LAST" ;;
    esac
    login_as "$mail" "$pw"
    if [ -z "$TOKEN" ]; then no "登录 $mail" '拿到 token' "$LAST"; return 1; fi
    return 0
}

section '3. 注册 / 登录（会话落库）'
register_or_login smoke_a "$PW_A" "$mail_a" || { say '前置账号不可用，后续用例无法继续'; exit 1; }
TOK_A="$TOKEN"; UN_A=$(f data.username)
req GET '/api/auth/me?token=not-a-real-token'
# /api/auth/me 不在拦截器的保护清单里（它自己判定会话），所以是 HTTP 200 + 业务失败，而不是 401
assert_has '伪造令牌查身份被拒' "$LAST" '登录已过期'
req GET /api/auth/me '' "$TOK_A"
assert_has '有效令牌能查回本人身份' "$LAST" "\"username\":\"$UN_A\""
assert_has '身份里回带了注册邮箱（它也能当登录账号）' "$LAST" "$mail_a"
assert_has '用户名由服务端按邮箱前缀生成，不是自选的' "$UN_A" 'esmoke_a'
register_or_login smoke_b "$PW_B" "$mail_b" || exit 1
TOK_B="$TOKEN"

# 直连 MySQL 的能力：决定「超时自动取消」用例是快速验证还是跳过
HAS_DB=0
if [ -n "${MYSQL_BIN:-}" ] && [ -x "${MYSQL_BIN}" ] && [ -n "${MYSQL_PWD:-}" ]; then HAS_DB=1; fi
sql() { "$MYSQL_BIN" -u "${CTRIP_DB_USER:-root}" --default-character-set=utf8mb4 -D ctrip_travel -N -B -e "$1" 2>/dev/null; }

# 取北京第一间酒店房型的余房数（库存回补是否到位，就看它）
rooms() {
    req GET '/api/hotels?city=%E5%8C%97%E4%BA%AC'
    ITEM_ID=$(f data.0.id); CHOICE=$(f data.0.rooms.0.name)
    PRICE=$(f data.0.rooms.0.price); COUNT=$(f data.0.rooms.0.count)
}

# new_order 幂等键前缀 —— 建一笔待支付订单，返回订单号（全局变量 ORDER_NO/UNIT/TOTAL）
new_order() {
    local tag="$1"
    rooms
    local before="$COUNT"
    json_body "$TMP/o.json" type hotel itemId "$ITEM_ID" choice "$CHOICE" quantity 1 days 1 \
        name 冒烟联系人 phone "$phone_a" title "冒烟测试订单$tag" dateText '2026-11-01 至 2026-11-02' \
        idemKey "C$(date +%s)$tag$$"
    req POST /api/orders "$TMP/o.json" "$TOK_A"
    ORDER_NO=$(f data.orderNo)
    UNIT=$(f data.unitPrice); TOTAL=$(f data.totalPrice); BEFORE="$before"
}

section '4. 下单：服务端计价、锁库存、状态为待支付'
rooms
COUNT0="$COUNT"
say "  · 下单对象：酒店 id=$ITEM_ID 房型=$CHOICE 挂牌价=$PRICE 余房=$COUNT0"
IDEM="C$(date +%s)000$$"
json_body "$TMP/order.json" type hotel itemId "$ITEM_ID" choice "$CHOICE" quantity 1 days 1 \
    name 冒烟联系人 phone "$phone_a" title 冒烟测试订单 dateText '2026-11-01 至 2026-11-02' idemKey "$IDEM"
req POST /api/orders "$TMP/order.json" "$TOK_A"
ORDER_NO=$(f data.orderNo)
assert_has '下单成功返回订单号' "$ORDER_NO" 'C'
assert_eq '单价取自服务端而非前端' "$(f data.unitPrice)" "$PRICE"
assert_eq '新订单状态为待支付（钱还没付，只是锁库存）' "$(f data.status)" '待支付'
assert_eq '待支付可支付' "$(f data.payable)" 'true'
assert_eq '待支付可取消' "$(f data.cancellable)" 'true'
assert_eq '未付款不可退款' "$(f data.refundable)" 'false'
assert_eq '未付款不可删除' "$(f data.deletable)" 'false'
assert_has '服务端记录了幂等键所属会话（cancellable）' "$LAST" '"cancellable":true'

# 支付窗口：payExpireMs 应落在「现在 + 窗口」附近，前端倒计时读的就是它
DEADLINE_MS=$(f data.payExpireMs)
NOW_MS=$(node -e 'process.stdout.write(String(Date.now()))')
WINDOW=$(node -e 'const a=process.argv;process.stdout.write(String(Math.round((Number(a[2])-Number(a[1]))/60000)))' "$NOW_MS" "$DEADLINE_MS")
if [ -n "$DEADLINE_MS" ] && [ "$WINDOW" -ge 1 ] && [ "$WINDOW" -le 20 ]; then
    ok "返回支付截止时间，距今 ${WINDOW} 分钟"
else
    no '返回支付截止时间' 'payExpireMs 距今 1-20 分钟' "payExpireMs=$DEADLINE_MS 距今=$WINDOW 分钟"
fi
assert_has '出参带可读的支付截止时间串' "$(f data.payDeadline)" '20'

req GET '/api/hotels?city=%E5%8C%97%E4%BA%AC'
COUNT1=$(f data.0.rooms.0.count)
assert_eq '下单后余房 -1（未支付也锁库存）' "$COUNT1" "$((COUNT0 - 1))"

req POST /api/orders "$TMP/order.json" "$TOK_A"
assert_eq '重复提交同一幂等键返回原单，不产生新单' "$(f data.orderNo)" "$ORDER_NO"
req GET '/api/hotels?city=%E5%8C%97%E4%BA%AC'
assert_eq '重复提交没有二次扣库存' "$(f data.0.rooms.0.count)" "$COUNT1"

section '5. 订单归属隔离与手机号脱敏'
req GET /api/orders '' "$TOK_A"
assert_has '本人列表能看到刚下的单' "$LAST" "$ORDER_NO"
assert_lack '列表不返回明文手机号' "$LAST" "$phone_a"
assert_has '列表返回掩码手机号' "$LAST" '139****1111'
req GET /api/orders '' "$TOK_B"
assert_lack '别人的列表看不到这单' "$LAST" "$ORDER_NO"
for act in pay cancel refund; do
    req POST "/api/orders/$ORDER_NO/$act" '' "$TOK_B"
    assert_has "他人不能${act}这单" "$LAST" '不属于当前账号'
done
req DELETE "/api/orders/$ORDER_NO" '' "$TOK_B"
assert_has '他人不能删除这单' "$LAST" '不属于当前账号'

section '6. 支付：待支付 → 已支付'
req POST "/api/orders/$ORDER_NO/pay" '' "$TOK_A"
assert_eq '支付成功，状态变为已支付' "$(f data.status)" '已支付'
[ -n "$(f data.payTime)" ] && ok '支付时间已留痕' || no '支付时间已留痕' '非空 payTime' '空'
assert_eq '已支付不可重复支付' "$(f data.payable)" 'false'
assert_eq '已支付可退款' "$(f data.refundable)" 'true'
req POST "/api/orders/$ORDER_NO/pay" '' "$TOK_A"
assert_has '重复支付被拒' "$LAST" '无需重复支付'
req GET '/api/hotels?city=%E5%8C%97%E4%BA%AC'
assert_eq '支付不动库存（下单时已扣）' "$(f data.0.rooms.0.count)" "$COUNT1"

req POST "/api/orders/$ORDER_NO/cancel" '' "$TOK_A"
assert_has '已支付订单不能当成取消处理' "$LAST" '请走退款'

section '7. 退款 + 删除：已支付 → 已退款 → 记录可删'
req DELETE "/api/orders/$ORDER_NO" '' "$TOK_A"
assert_has '已支付订单不允许直接删除（会丢支付凭据）' "$LAST" '只有已取消或已退款'
req POST "/api/orders/$ORDER_NO/refund" '' "$TOK_A"
assert_eq '退款成功，状态变为已退款' "$(f data.status)" '已退款'
[ -n "$(f data.refundTime)" ] && ok '退款时间已留痕' || no '退款时间已留痕' '非空 refundTime' '空'
req GET '/api/hotels?city=%E5%8C%97%E4%BA%AC'
assert_eq '退款后余房回到下单前' "$(f data.0.rooms.0.count)" "$COUNT0"
req POST "/api/orders/$ORDER_NO/refund" '' "$TOK_A"
assert_has '重复退款被拒' "$LAST" '已退款'
req DELETE "/api/orders/$ORDER_NO" '' "$TOK_A"
assert_has '已退款订单可以删除' "$LAST" "已删除订单 $ORDER_NO"
req GET /api/orders '' "$TOK_A"
assert_lack '删除后列表不再返回这条订单' "$LAST" "$ORDER_NO"

section '8. 主动取消：待支付 → 已取消 → 记录可删'
new_order cancel
ORDER_NO2="$ORDER_NO"
say "  · 新订单 $ORDER_NO2（余房 $BEFORE）"
req GET '/api/hotels?city=%E5%8C%97%E4%BA%AC'
assert_eq '取消用例下单后余房 -1' "$(f data.0.rooms.0.count)" "$((BEFORE - 1))"
req POST "/api/orders/$ORDER_NO2/cancel" '' "$TOK_A"
assert_eq '本人取消成功' "$(f data.status)" '已取消'
[ -n "$(f data.cancelTime)" ] && ok '取消时间已留痕' || no '取消时间已留痕' '非空 cancelTime' '空'
assert_eq '已取消可删除' "$(f data.deletable)" 'true'
assert_eq '已取消不能再支付' "$(f data.payable)" 'false'
req GET '/api/hotels?city=%E5%8C%97%E4%BA%AC'
assert_eq '取消后余房回到下单前' "$(f data.0.rooms.0.count)" "$BEFORE"
req POST "/api/orders/$ORDER_NO2/cancel" '' "$TOK_A"
assert_has '重复取消被拒' "$LAST" '已取消'
req POST "/api/orders/$ORDER_NO2/refund" '' "$TOK_A"
assert_has '未支付即取消的单没有退款可言' "$LAST" '无需退款'
req POST "/api/orders/$ORDER_NO2/pay" '' "$TOK_A"
assert_has '已取消的单不能再支付' "$LAST" '已取消'

section '9. 超时未支付自动取消'
if [ "$HAS_DB" = '0' ]; then
    skip '超时自动取消' '未提供 MYSQL_BIN / MYSQL_PWD：无法把 pay_deadline 改到过去，跳过（其余用例不受影响）'
else
    new_order expire
    ORDER_NO3="$ORDER_NO"
    req GET '/api/hotels?city=%E5%8C%97%E4%BA%AC'
    assert_eq '超时用例下单后余房 -1' "$(f data.0.rooms.0.count)" "$((BEFORE - 1))"
    sql "UPDATE travel_order SET pay_deadline = NOW() - INTERVAL 1 MINUTE WHERE order_no = '$ORDER_NO3'"
    say "  · 已把 $ORDER_NO3 的支付截止改到 1 分钟前（等价于用户一直没收银台付款）"
    req POST "/api/orders/$ORDER_NO3/pay" '' "$TOK_A"
    assert_has '支付超时被拒并提示重新下单' "$LAST" '支付超时'
    req GET '/api/orders' '' "$TOK_A"
    case "$LAST" in
        *"$ORDER_NO3"*'已取消'*) ok '超时订单服务端已就地取消（不必等定时任务）' ;;
        *) no '超时订单已取消' '订单状态为已取消' "$(printf '%s' "$LAST" | head -c 160)" ;;
    esac
    req GET '/api/hotels?city=%E5%8C%97%E4%BA%AC'
    assert_eq '超时取消后余房回到下单前' "$(f data.0.rooms.0.count)" "$BEFORE"

    if [ "${SMOKE_SWEEP:-0}" = '1' ]; then
        say '  · SMOKE_SWEEP=1：再验证一次纯定时任务路径（等下一轮扫描，最长 90 秒）'
        new_order sweep
        ORDER_NO4="$ORDER_NO"
        sql "UPDATE travel_order SET pay_deadline = NOW() - INTERVAL 1 MINUTE WHERE order_no = '$ORDER_NO4'"
        FOUND=''
        for i in $(seq 1 18); do
            sleep 5
            if [ "$(sql "SELECT status FROM travel_order WHERE order_no = '$ORDER_NO4'")" = '已取消' ]; then FOUND=1; break; fi
        done
        if [ -n "$FOUND" ]; then
            ok 'OrderExpiryTask 已把超时订单自动取消（用户没点任何按钮）'
            req GET '/api/hotels?city=%E5%8C%97%E4%BA%AC'
            assert_eq '定时任务同样回补了库存' "$(f data.0.rooms.0.count)" "$BEFORE"
        else
            no 'OrderExpiryTask 定时扫描' '90 秒内状态变为已取消' '仍是待支付，检查 @EnableScheduling 与 ctrip.order.expiry-scan-ms'
        fi
        req DELETE "/api/orders/$ORDER_NO4" '' "$TOK_A" >/dev/null
    else
        skip 'OrderExpiryTask 定时扫描' '未设 SMOKE_SWEEP=1（要等下一轮扫描，最长 90 秒）'
    fi
    req DELETE "/api/orders/$ORDER_NO3" '' "$TOK_A"
    assert_has '超时取消的订单也支持删除' "$LAST" '已删除'
fi

section '10. 批量取消与显式确认'
req DELETE /api/orders '' "$TOK_A"
assert_has '不带 confirm=YES 被拒' "$LAST" 'confirm=YES'
new_order bulk
req DELETE '/api/orders?confirm=YES' '' "$TOK_A"
assert_has '确认后只批量取消本人待支付订单' "$LAST" '已取消本人待支付订单'
req GET '/api/orders' '' "$TOK_A"
assert_has '批量取消后订单仍在列表里（状态已取消，留痕可对账）' "$LAST" '已取消'
req GET '/api/hotels?city=%E5%8C%97%E4%BA%AC'
assert_eq '批量取消后余房回到下单前' "$(f data.0.rooms.0.count)" "$BEFORE"
req DELETE '/api/orders?confirm=YES' '' "$TOK_A"
assert_has '没有待支付订单时返回 0 条而不是报错' "$LAST" '待支付订单 0 条'

section '11. 改密码吊销全部会话'
json_body "$TMP/pwd-weak.json" oldPassword "$PW_A" newPassword 12345678
req POST /api/auth/password "$TMP/pwd-weak.json" "$TOK_A"
assert_has '改密同样校验口令强度' "$LAST" '至少 8 位'
json_body "$TMP/pwd.json" oldPassword 'WrongPass123' newPassword "$PW_A2"
req POST /api/auth/password "$TMP/pwd.json" "$TOK_A"
assert_has '原密码不对不能改密' "$LAST" '原密码不正确'
json_body "$TMP/pwd.json" oldPassword "$PW_A" newPassword "$PW_A2"
req POST /api/auth/password "$TMP/pwd.json" "$TOK_A"
assert_eq '改密成功' "$(f code)" '0'
req GET /api/auth/me '' "$TOK_A"
assert_has '旧令牌立即失效（auth_ver 已递增）' "$LAST" '登录已过期'
login_as "$mail_a" "$PW_A"
assert_eq '旧密码不再能登录' "$TOKEN" ''
login_as "$mail_a" "$PW_A2"
if [ -z "$TOKEN" ]; then no '新密码能登录' '拿到 token' "$LAST"; else ok '新密码能登录'; fi
TOK_A="$TOKEN"
# 改回初始口令，保证脚本可以重复运行（这一步同样会吊销上面的令牌）
json_body "$TMP/pwd-back.json" oldPassword "$PW_A2" newPassword "$PW_A"
req POST /api/auth/password "$TMP/pwd-back.json" "$TOK_A"
assert_eq '口令已还原，便于下次重跑' "$(f code)" '0'
# 用注册邮箱当登录账号也能成：账号密码那一栏收的是「用户名或邮箱」
login_as "$mail_a" "$PW_A"
if [ -z "$TOKEN" ]; then no '用邮箱当账号也能密码登录' '拿到 token' "$LAST"; else ok '用邮箱当账号也能密码登录'; fi
login_as "$UN_A" "$PW_A"
if [ -z "$TOKEN" ]; then no '用服务端生成的用户名同样能登录' '拿到 token' "$LAST"; else ok '用服务端生成的用户名同样能登录'; fi

section '12. 登录失败锁定（防爆破）'
login_as "$mail_c" "$PW_B"
case "$LAST" in
    *'临时锁定'*) skip '失败锁定' 'smoke_c 仍在 15 分钟锁定期内（上次运行留下的），等过期后重跑' ;;
    *)
        [ -z "$TOKEN" ] && register_or_login smoke_c "$PW_B" "$mail_c" >/dev/null 2>&1
        for i in 1 2 3 4 5; do
            json_body "$TMP/bad.json" username "$mail_c" password WrongPass999
            req POST /api/auth/login "$TMP/bad.json"
        done
        assert_has '第 5 次失败已提示锁定' "$LAST" '临时锁定'
        login_as "$mail_c" "$PW_B"
        assert_has '锁定期间即使密码正确也不放行' "$LAST" '临时锁定'
        ;;
esac

section '13. 手机验证码登录（发码 / 限流 / 一次性 / 唯一归属）'
phone_s='13900003333'; PW_SMS='Smoke@2026s'; SMS_OK=0
mail_sms="smoke_sms@$mail_domain"
# 这个号码只挂 smoke_sms 一个账号：验证码登录要求「号码唯一归属」，
# 不能借用 smoke_a~c 共用的 13900001111（那正是本节要单独验证的反例）。
# 现在没有用户名注册了，这个专用账号也走邮箱注册，只是顺手把 phone_s 填进去。
login_as "$mail_sms" "$PW_SMS"
if [ -n "$TOKEN" ]; then
    say '  · smoke_sms 是上次运行留下的，直接复用'
else
    json_body "$TMP/reg-sms-send.json" email "$mail_sms"
    req POST /api/auth/email/register-code "$TMP/reg-sms-send.json"
    RC_SMS=$(f data.demoCode)
    if [ -n "$RC_SMS" ]; then
        json_body "$TMP/reg-sms.json" email "$mail_sms" code "$RC_SMS" nickname 冒烟短信 phone "$phone_s" password "$PW_SMS"
        req POST /api/auth/register "$TMP/reg-sms.json"
        case "$LAST" in
            *'"code":0'*) ok '为验证码用例注册专用账号 smoke_sms（邮箱注册 + 绑定手机号）' ;;
            *'已被注册'*) say '  · smoke_sms 的邮箱上次就建好了，直接登录' ;;
            *'过于频繁'*) skip '手机验证码用例' '本机这一小时的注册额度已用满（重启后端可清零内存计数）' ;;
            *) no '注册 smoke_sms' 'code 0 / 已被注册 / 限流' "$LAST" ;;
        esac
    else
        skip '手机验证码用例' "给 $mail_sms 发注册码被拒：$(printf '%s' "$LAST" | head -c 120)"
    fi
    login_as "$mail_sms" "$PW_SMS"
fi
UN_SMS=$(f data.username)
if [ -z "$TOKEN" ]; then
    skip '手机验证码用例' 'smoke_sms 登录不上（多半是这一小时的注册额度用满、账号没建起来），请调大 ctrip.auth.max-register-per-hour 或重启后端再跑'
else
    TOK_SMS="$TOKEN"; SMS_OK=1
    # —— 号码归属：未注册、一号多账号，都在发码这一步就拦下
    json_body "$TMP/sms-none.json" phone 13900009999
    req POST /api/auth/sms/send "$TMP/sms-none.json"
    assert_has '未注册号码不给发码' "$LAST" '未注册'
    json_body "$TMP/sms-multi.json" phone "$phone_a"
    req POST /api/auth/sms/send "$TMP/sms-multi.json"
    assert_has '一号挂多账号时要求改用账号密码登录' "$LAST" '账号密码登录'

    # —— 正常发码
    json_body "$TMP/sms-send.json" phone "$phone_s"
    req POST /api/auth/sms/send "$TMP/sms-send.json"
    CODE=$(f data.demoCode)
    case "$LAST" in
        *'"code":0'*)
            if [ -n "$CODE" ]; then ok '发码成功，演示模式回显了明文码（expose-sms-code=true）'
            else skip '验证码登录后续用例' '后端已关闭 demoCode 回显，脚本拿不到码值（上线应有的状态）'; fi ;;
        *'验证码已发送'*) skip '发码' '上一条验证码还在重发冷却里（sms-resend-seconds），等一分钟再跑' ;;
        *'过于频繁'*)     skip '发码' '该号码这一小时的验证码条数已用满（sms-max-per-hour）' ;;
        *)                no '发码' 'code 0' "$LAST" ;;
    esac

    if [ -n "$CODE" ]; then
        req POST /api/auth/sms/send "$TMP/sms-send.json"
        assert_has '同一号码冷却期内不允许重发' "$LAST" '验证码已发送'
        json_body "$TMP/sms-bad.json" phone "$phone_s" code 000000
        req POST /api/auth/sms/login "$TMP/sms-bad.json"
        assert_has '错码与过期给同一句提示（不做进度探针）' "$LAST" '验证码不正确或已失效'
        json_body "$TMP/sms-short.json" phone "$phone_s" code 12345
        req POST /api/auth/sms/login "$TMP/sms-short.json"
        assert_has '验证码长度先于校验被钉死' "$LAST" '6位短信验证码'

        json_body "$TMP/sms-ok.json" phone "$phone_s" code "$CODE"
        req POST /api/auth/sms/login "$TMP/sms-ok.json"
        assert_eq '验证码登录成功' "$(f code)" '0'
        req GET /api/auth/me '' "$(f data.token)"
        assert_has '验证码登录签发的会话与密码登录同构' "$LAST" "\"username\":\"$UN_SMS\""
        req POST /api/auth/sms/login "$TMP/sms-ok.json"
        assert_has '同一验证码不能复用第二次' "$LAST" '验证码不正确或已失效'
    fi
fi

section '14. 手机号注册：验证码直接建号并登录'
phone_r='13900005555'
# 注册发码与登录发码是两个场景，规则正好相反：注册只给「未注册」的号码发码。
# 本节的两条对照都依赖「有个已注册号码」，上一节没建起 smoke_sms 时只能跳过，不该报失败。
if [ "$SMS_OK" = '1' ]; then
    json_body "$TMP/reg-code-taken.json" phone "$phone_s"
    req POST /api/auth/sms/register-code "$TMP/reg-code-taken.json"
    assert_has '已注册号码拿不到注册码' "$LAST" '该手机号已注册'
else
    skip '已注册号码拿不到注册码' 'smoke_sms 本轮没建成，缺少「已注册号码」这个对照'
fi

# 只带 phone、不带 code：既不是邮箱注册也不是手机号注册，落到「已下线」那句提示——
# 这条断言同时钉住「注册接口不再接受任何自选用户名」
json_body "$TMP/reg-nocode.json" phone "$phone_r" nickname 冒烟无码
req POST /api/auth/register "$TMP/reg-nocode.json"
case "$LAST" in
    *'已下线'*) ok '缺验证码的手机号注册不会退化成用户名注册' ;;
    *'过于频繁'*) skip '缺验证码的手机号注册' '注册额度用满：一次完整运行要注册 6 个以上账号，同一小时反复跑请调大 max-register-per-hour 或重启后端' ;;
    *) no '缺验证码的手机号注册' '提示「用户名+密码注册已下线」' "$LAST" ;;
esac
# 带了 code 但码不对：校验先于建号与限流，不消耗注册额度（verifyRegisterCode 查不到记录直接 false）
json_body "$TMP/reg-badcode.json" phone "$phone_r" code 000000
req POST /api/auth/register "$TMP/reg-badcode.json"
assert_has '验证码不正确不能建号' "$LAST" '验证码不正确或已失效'

json_body "$TMP/reg-send.json" phone "$phone_r"
req POST /api/auth/sms/register-code "$TMP/reg-send.json"
REG_CODE=$(f data.demoCode)
case "$LAST" in
    *'"code":0'*) [ -n "$REG_CODE" ] && ok '未注册号码可拿到注册验证码' ;;
    *'验证码已发送'*) skip '手机号注册全链路' '上一条注册码还在重发冷却里，等一分钟再跑' ;;
    *'过于频繁'*)     skip '手机号注册全链路' '发码配额用满（单号码每小时 5 条 / 单 IP 每小时 20 条）' ;;
    *)                no '注册发码' 'code 0' "$LAST" ;;
esac
if [ -n "$REG_CODE" ]; then
    # 昵称故意填超长（6+60+1 字）：app_user.nickname 是 VARCHAR(50)，
    # 早先直接建号会撞 MySQL「Data too long」变成 500，现在后端按 50 字截断
    LONG_NICK="冒烟手机用户$(printf 'A%.0s' {1..60})Z"
    json_body "$TMP/reg-phone.json" phone "$phone_r" code "$REG_CODE" nickname "$LONG_NICK"
    req POST /api/auth/register "$TMP/reg-phone.json"
    if [ "$(f code)" != '0' ]; then
        case "$LAST" in
            *'过于频繁'*) skip '手机号注册全链路' '注册额度用满：码已消耗但号没建成，重启后端清零计数后重跑' ;;
            *) no '手机号注册成功' 'code 0' "$LAST" ;;
        esac
    else
        ok '手机号注册成功'
        assert_has '用户名按 m+手机号 自动生成' "$(f data.username)" "$phone_r"
        assert_has '注册后直接签发会话（免二次登录）' "$LAST" '"token"'
        req GET /api/auth/me '' "$(f data.token)"
        assert_has '新账号的昵称按填写值落库' "$LAST" '冒烟手机用户'
        assert_lack '超长昵称被截断而不是报错（尾巴上的 Z 落不了库）' "$(f data.nickname)" 'Z'
        # 同一个码不能既注册又留着再用；号码此时已注册，注册通道对它关闭
        req POST /api/auth/register "$TMP/reg-phone.json"
        assert_has '验证码一次性，用过即废' "$LAST" '验证码不正确或已失效'
        json_body "$TMP/reg-again.json" phone "$phone_r"
        req POST /api/auth/sms/register-code "$TMP/reg-again.json"
        assert_has '刚注册的号码不能再走注册通道' "$LAST" '该手机号已注册'
    fi
fi

section '15. 邮箱注册：格式校验 / 一次性 / 重复邮箱 / 跨场景共用冷却'
mail_r="smoke_mail_r@$mail_domain"
# 反例 1：形态不对的邮箱在发码这一步就拒掉，不占限流、不落库
json_body "$TMP/mail-badfmt.json" email 'not-an-email'
req POST /api/auth/email/register-code "$TMP/mail-badfmt.json"
assert_has '非法邮箱格式直接拒' "$LAST" '有效的邮箱地址'
# 反例 2：已注册的邮箱走不通注册通道（存在性检查排在重发冷却之前，所以刚注册完也能验）
json_body "$TMP/mail-taken.json" email "$mail_a"
req POST /api/auth/email/register-code "$TMP/mail-taken.json"
assert_has '已注册邮箱拿不到注册码' "$LAST" '该邮箱已注册'
# 反例 3：不带码/带错码都不能建号，且不消耗注册额度
json_body "$TMP/mail-nocode.json" email "$mail_r" nickname 冒烟邮箱
req POST /api/auth/register "$TMP/mail-nocode.json"
assert_has '缺验证码的邮箱注册被拒' "$LAST" '6位邮箱验证码'
json_body "$TMP/mail-badcode.json" email "$mail_r" code 000000
req POST /api/auth/register "$TMP/mail-badcode.json"
assert_has '邮箱注册同样要验码，错码不能建号' "$LAST" '验证码不正确或已失效'

json_body "$TMP/mail-send.json" email "$mail_r"
req POST /api/auth/email/register-code "$TMP/mail-send.json"
MAIL_CODE=$(f data.demoCode)
case "$LAST" in
    *'"code":0'*) [ -n "$MAIL_CODE" ] && ok '未注册邮箱可拿到注册验证码' ;;
    *'验证码已发送'*) skip '邮箱注册全链路' '上一条邮箱验证码还在重发冷却里，等一分钟再跑' ;;
    *'过于频繁'*)     skip '邮箱注册全链路' '发码配额用满（单邮箱每小时 5 条 / 单 IP 每小时 20 条）' ;;
    *)                no '邮箱注册发码' 'code 0' "$LAST" ;;
esac
if [ -n "$MAIL_CODE" ]; then
    # 邮箱和短信的有效期不同：这一条钉住配置真的透到了响应里（前端提示文案读的就是它）
    assert_eq '邮箱验证码有效期 10 分钟（比短信的 5 分钟长）' "$(f data.expireSeconds)" '600'
    LONG_NICK_MAIL="冒烟邮箱用户$(printf 'A%.0s' {1..60})Z"
    json_body "$TMP/mail-reg.json" email "$mail_r" code "$MAIL_CODE" nickname "$LONG_NICK_MAIL" password 'Smoke@2026m'
    req POST /api/auth/register "$TMP/mail-reg.json"
    if [ "$(f code)" != '0' ]; then
        case "$LAST" in
            *'过于频繁'*) skip '邮箱注册全链路' '注册额度用满：码已消耗但号没建成，重启后端清零计数后重跑' ;;
            *) no '邮箱注册成功' 'code 0' "$LAST" ;;
        esac
    else
        ok '邮箱注册成功'
        assert_has '用户名按 e+邮箱前缀 自动生成' "$(f data.username)" 'esmoke_mail_r'
        assert_has '注册后直接签发会话（免二次登录）' "$LAST" '"token"'
        assert_has '会话里回带了注册邮箱' "$LAST" "$mail_r"
        req GET /api/auth/me '' "$(f data.token)"
        assert_has '新账号的昵称按填写值落库' "$LAST" '冒烟邮箱用户'
        assert_lack '超长昵称被截断而不是报错（尾巴上的 Z 落不了库）' "$(f data.nickname)" 'Z'
        req POST /api/auth/register "$TMP/mail-reg.json"
        assert_has '邮箱验证码一次性，用过即废' "$LAST" '验证码不正确或已失效'
        json_body "$TMP/mail-again.json" email "$mail_r"
        req POST /api/auth/email/register-code "$TMP/mail-again.json"
        assert_has '刚注册的邮箱不能再走注册通道' "$LAST" '该邮箱已注册'
        # 重发冷却是「按邮箱」而不是「按邮箱+场景」：注册码刚发过，登录码也得等同样的 60 秒。
        # 这一条既是断言，也是下一节为什么要等一分钟的原因说明。
        json_body "$TMP/mail-cross.json" email "$mail_r"
        req POST /api/auth/email/send "$TMP/mail-cross.json"
        assert_has '注册码与登录码共用同一重发冷却（不是各发各的）' "$LAST" '验证码已发送'
    fi
fi

section '16. 邮箱验证码登录（未注册 / 错码 / 一次性 / 邮箱当账号）'
mail_login_code() { # mail_login_code 邮箱 → MAIL_LOGIN_CODE（拿到空值就直说，不硬等）
    local mail="$1"
    MAIL_LOGIN_CODE=''
    json_body "$TMP/mail-login.json" email "$mail"
    req POST /api/auth/email/send "$TMP/mail-login.json"
    MAIL_LOGIN_CODE=$(f data.demoCode)
    if [ -z "$MAIL_LOGIN_CODE" ]; then
        case "$LAST" in
            *'验证码已发送'*)
                say "  · $mail 上一条验证码还在 60 秒重发冷却里（注册码与登录码共用同一窗口，上一节刚证明过），等它过去再发一次"
                sleep 61
                req POST /api/auth/email/send "$TMP/mail-login.json"
                MAIL_LOGIN_CODE=$(f data.demoCode) ;;
            *'未注册'*) say "  · $mail 没建成账号，本节只能跳过" ;;
            *) say "  · 发码被拒：$(printf '%s' "$LAST" | head -c 120)" ;;
        esac
    fi
}
json_body "$TMP/mail-none.json" email "nobody_$$@$mail_domain"
req POST /api/auth/email/send "$TMP/mail-none.json"
assert_has '未注册邮箱不给发登录码' "$LAST" '未注册'

mail_login_code "$mail_a"
if [ -z "$MAIL_LOGIN_CODE" ]; then
    skip '邮箱验证码登录后续用例' '拿不到明文码：可能是这一小时该邮箱的发码条数用满（email-max-per-hour），也可能是后端把 expose-email-code 关了（上线应有的状态）'
else
    ok '已注册邮箱可拿到登录验证码'
    json_body "$TMP/mail-login-short.json" email "$mail_a" code 12345
    req POST /api/auth/email/login "$TMP/mail-login-short.json"
    assert_has '验证码长度先于校验被钉死' "$LAST" '6位邮箱验证码'
    json_body "$TMP/mail-login-bad.json" email "$mail_a" code 000000
    req POST /api/auth/email/login "$TMP/mail-login-bad.json"
    assert_has '错码与过期给同一句提示（不做进度探针）' "$LAST" '验证码不正确或已失效'
    json_body "$TMP/mail-login-ok.json" email "$mail_a" code "$MAIL_LOGIN_CODE"
    req POST /api/auth/email/login "$TMP/mail-login-ok.json"
    assert_eq '邮箱验证码登录成功' "$(f code)" '0'
    req GET /api/auth/me '' "$(f data.token)"
    assert_has '邮箱登录签发的会话与密码登录同构' "$LAST" "\"username\":\"$UN_A\""
    req POST /api/auth/email/login "$TMP/mail-login-ok.json"
    assert_has '同一验证码不能复用第二次' "$LAST" '验证码不正确或已失效'
fi

section '17. 扫码登录：状态机 + 令牌只送达一次'
# 第 11 节改密已经把 TOK_A 吊销（改回原口令同样吊销），这里重新登录一次拿可用会话
login_as "$mail_a" "$PW_A"; TOK_A="$TOKEN"
# 预检一次「现在还能不能建场景」：本节末尾要压测每分钟创建限额，压满之后同一分钟内再跑，
# 每条用例都会以「过于频繁」收场——那是限流生效，不是缺陷。所以先探一下，整节改判跳过。
req POST /api/auth/qrcode
if [ -z "$TOK_A" ]; then
    skip '扫码登录用例' 'smoke_a 无法登录，后面的手机侧操作没有登录态可用'
elif [ "$(f code)" != '0' ]; then
    skip '扫码登录用例' '本分钟内创建二维码已达上限（预检就被拒），等 1 分钟后重跑即可'
else
    qr_new() { # 创建一次扫码场景 → SCENE / QR_URL
        req POST /api/auth/qrcode
        SCENE=$(f data.scene); QR_URL=$(f data.confirmUrl)
    }
    qr_poll() { req GET "/api/auth/qrcode/$1"; }

    qr_new
    assert_eq '电脑端创建场景成功' "$(f code)" '0'
    assert_eq '场景号是 32 位十六进制' "${#SCENE}" '32'
    assert_has '确认页地址带上了场景号' "$QR_URL" '/scan?scene='
    case "$QR_URL" in
        *'://localhost'*|*'://127.'*)
            skip '确认页地址手机可达' '本机取不到局域网 IPv4，地址退回 localhost；真机扫码请配 ctrip.auth.qrcode-confirm-base' ;;
        *) ok '确认页地址用的是局域网 IPv4，手机能直接打开' ;;
    esac
    qr_poll "$SCENE"
    assert_eq '刚创建时状态为 PENDING' "$(f data.status)" 'PENDING'
    req GET '/api/auth/qrcode/deadbeef'
    assert_eq '非十六进制的场景号一律当作不存在' "$(f data.status)" 'NOT_FOUND'

    # 手机侧三个接口都要求「这台设备已登录」，否则二维码等于公开的登录券
    req POST "/api/auth/qrcode/$SCENE/scan"
    assert_has '匿名扫码被拒（code 401）' "$LAST" '"code":401'
    req POST "/api/auth/qrcode/$SCENE/confirm"
    assert_has '匿名确认被拒' "$LAST" '"code":401'

    req POST "/api/auth/qrcode/$SCENE/scan" '' "$TOK_A"
    assert_eq '手机扫码上报成功' "$(f data.status)" 'SCANNED'
    qr_poll "$SCENE"
    assert_eq '电脑端看到已扫码' "$(f data.status)" 'SCANNED'
    assert_has '电脑端显示的是扫码那个账号的昵称' "$(f data.nickname)" '冒烟测试smoke_a'

    req POST "/api/auth/qrcode/$SCENE/confirm" '' "$TOK_A"
    assert_eq '手机点确认后状态为 CONFIRMED' "$(f data.status)" 'CONFIRMED'
    qr_poll "$SCENE"
    assert_eq '电脑端轮询到已确认' "$(f data.status)" 'CONFIRMED'
    QRT=$(f data.login.token)
    [ "${#QRT}" -eq 32 ] && ok '领取时当场签发会话令牌' || no '领取时当场签发会话令牌' '32 位 token' "$LAST"
    req GET /api/auth/me '' "$QRT"
    assert_has '扫码拿到的令牌就是 smoke_a 的会话' "$LAST" "\"username\":\"$UN_A\""
    qr_poll "$SCENE"
    assert_eq '同一次扫码的令牌只会被送达一次（场景行已删除）' "$(f data.status)" 'NOT_FOUND'

    # 取消路径：电脑不该干等到超时
    qr_new
    req POST "/api/auth/qrcode/$SCENE/scan" '' "$TOK_A" >/dev/null
    req POST "/api/auth/qrcode/$SCENE/reject" '' "$TOK_A"
    assert_eq '手机取消后状态为 REJECTED' "$(f data.status)" 'REJECTED'
    qr_poll "$SCENE"
    assert_eq '电脑端立刻看到已取消' "$(f data.status)" 'REJECTED'
    req POST "/api/auth/qrcode/$SCENE/confirm" '' "$TOK_A"
    assert_has '取消后不能再确认' "$LAST" '已取消'

    # 手机确认页：合法场景号出页面，非法场景号只回提示、不回显原值
    req GET "/scan?scene=$SCENE"
    assert_eq '确认页 HTTP 200' "$HTTP" '200'
    assert_has '确认页是服务端直出的中文页面' "$LAST" '确认登录'
    req GET '/scan?scene=%3Cscript%3E'
    assert_has '非法场景号不拼进页面（防 XSS）' "$LAST" '二维码无效'
    assert_lack '非法场景号原值不回显' "$LAST" 'script'

    # 过期：把到期时间改到过去，轮询应当钉成 EXPIRED（依赖 MySQL，未给 MYSQL_BIN 时跳过）
    if [ "$HAS_DB" = '1' ]; then
        qr_new
        sql "UPDATE login_qrcode SET expire_time = NOW() - INTERVAL 1 MINUTE WHERE scene = '$SCENE'" >/dev/null
        qr_poll "$SCENE"
        assert_eq '到期后轮询返回 EXPIRED' "$(f data.status)" 'EXPIRED'
        req POST "/api/auth/qrcode/$SCENE/scan" '' "$TOK_A"
        assert_has '过期二维码不能扫码' "$LAST" '已过期'

        # 手机在有效期内点了确认、电脑却在过期之后才来领取：令牌绝不能发出去。
        # 早先的写法是先判 CONFIRMED 再判有效期，等于给了一张过了期还能用的登录券。
        qr_new
        req POST "/api/auth/qrcode/$SCENE/scan"   '' "$TOK_A" >/dev/null
        req POST "/api/auth/qrcode/$SCENE/confirm" '' "$TOK_A" >/dev/null
        sql "UPDATE login_qrcode SET expire_time = NOW() - INTERVAL 1 MINUTE WHERE scene = '$SCENE'" >/dev/null
        qr_poll "$SCENE"
        assert_eq '已确认但已过期的场景不发放令牌' "$(f data.status)" 'EXPIRED'
        assert_lack '过期场景的轮询响应里不带 login 载荷' "$LAST" '"login"'
    else
        skip '二维码过期用例' '未提供 MYSQL_BIN 与 MYSQL_PWD，无法把 expire_time 改到过去'
        skip '已确认但已过期的场景不发放令牌' '同上'
    fi

    # 创建限流：这个接口不登录就能调，没有每分钟上限的话脚本可以把 login_qrcode 刷满。
    # 放在本节最后，因为它会把这个 IP 的窗口压满。
    QR_TRIPPED=0
    for _ in 1 2 3 4 5 6 7 8 9 10 11 12; do
        req POST /api/auth/qrcode
        case "$LAST" in *'过于频繁'*) QR_TRIPPED=1; break ;; esac
    done
    if [ "$QR_TRIPPED" = '1' ]; then
        ok '每分钟创建二维码有上限，压满后拒绝'
        assert_has '限流提示说清了限额' "$LAST" '每分钟最多'
    else
        skip '每分钟创建二维码有上限' '连压 12 次都没被拒：qrcode-max-per-minute 配得偏高（默认 10）'
    fi
fi

section '18. 第三方账号授权注册（模拟授权页 / 令牌只送达一次 / 同一 openId 同一账号）'
# 演示站没有渠道 appid/secret 与 HTTPS 回调，所以「授权页」由本站直出（GET /oauth/authorize?state=）。
# 能验证的是这套状态机的纪律：身份只能取自服务端注册表、结果只能领取一次、
# 拒绝与过期都不发令牌。真正接渠道时，这一节换成对 /oauth/{provider}/callback 的验证。
oa_new() { # oa_new 渠道 → OA_STATE / OA_URL / OA_NAME
    json_body "$TMP/oa.json" provider "$1"
    req POST /api/auth/oauth "$TMP/oa.json"
    OA_STATE=$(f data.state); OA_URL=$(f data.authorizeUrl); OA_NAME=$(f data.providerName)
}
oa_poll() { req GET "/api/auth/oauth/$1"; }
oa_approve() { # oa_approve 场景 身份
    json_body "$TMP/oa-approve.json" identity "$2"
    req POST "/api/auth/oauth/$1/approve" "$TMP/oa-approve.json"
}

json_body "$TMP/oa-bad.json" provider douyin
req POST /api/auth/oauth "$TMP/oa-bad.json"
assert_has '未登记的渠道直接拒（渠道清单在服务端，不由前端指定）' "$LAST" '不支持的授权渠道'
req GET '/api/auth/oauth/%3Cscript%3E'
assert_eq '非法 state 一律当作不存在' "$(f data.status)" 'NOT_FOUND'

oa_new wechat
assert_eq '发起授权成功' "$(f code)" '0'
assert_eq 'state 是 32 位十六进制（它是这条链路上唯一的凭据）' "${#OA_STATE}" '32'
assert_has '授权页地址带上了 state' "$OA_URL" '/oauth/authorize?state='
assert_eq '渠道名由服务端给出' "$OA_NAME" '微信'
case "$OA_URL" in
    *'://localhost'*|*'://127.'*)
        skip '授权页地址别的设备能打开' '本机取不到局域网 IPv4，地址退回 localhost；对外演示请配 ctrip.auth.oauth-authorize-base' ;;
    *) ok '授权页地址用的是局域网 IPv4' ;;
esac
oa_poll "$OA_STATE"
assert_eq '刚发起时状态为 PENDING' "$(f data.status)" 'PENDING'
assert_lack '没授权之前轮询响应里不带 login 载荷' "$LAST" '"login"'

# 授权页本身：出演示昵称，不出 openId（openId 只留在库里，页面上拿到它等于给了伪造入口）
req GET "/oauth/authorize?state=$OA_STATE"
assert_eq '授权页 HTTP 200' "$HTTP" '200'
assert_has '授权页列出了可授权的演示身份' "$LAST" '阿晨在旅行'
assert_lack '授权页不暴露服务端注册表里的 openId' "$LAST" 'o_yuetu'
assert_has '授权页说明了授权范围（只给昵称与头像）' "$LAST" '公开昵称'
req GET '/oauth/authorize?state=%3Cscript%3Ealert(1)%3C/script%3E'
assert_has '非法 state 只回提示页' "$LAST" '授权链接无效'
assert_lack '非法 state 的原值不拼进页面（防 XSS）' "$LAST" 'alert(1)'

# 身份必须由服务端注册表认：假身份能选出来就等于「登录成任意 openId」
oa_approve "$OA_STATE" w9
assert_has '注册表以外的身份被拒' "$LAST" '请选择一个授权身份'
oa_poll "$OA_STATE"
assert_eq '被拒之后场景仍是 PENDING（没有就地作废，也没有偷偷授权）' "$(f data.status)" 'PENDING'
oa_approve "$OA_STATE" w1
assert_eq '同意授权成功' "$(f data.status)" 'APPROVED'
oa_approve "$OA_STATE" w2
assert_has '同一场景不能被授权第二次（更不能用第二个身份覆盖）' "$LAST" '已被处理'
oa_poll "$OA_STATE"
assert_eq '发起页轮询到已授权' "$(f data.status)" 'APPROVED'
assert_has '授权结果里带渠道昵称（就是页面上选的那个演示身份）' "$(f data.nickname)" '阿晨在旅行'
TOK_OA=$(f data.login.token)
OA_UID=$(f data.login.id)
OA_UN=$(f data.login.username)
[ "${#TOK_OA}" -eq 32 ] && ok '领取时当场签发会话令牌' || no '领取时当场签发会话令牌' '32 位 token' "$LAST"
assert_has '第三方建号的用户名按渠道前缀生成' "$OA_UN" 'wx_'
req GET /api/auth/me '' "$TOK_OA"
assert_has '拿到的令牌就是刚建的那个账号' "$LAST" "\"username\":\"$OA_UN\""
oa_poll "$OA_STATE"
assert_eq '同一次授权的令牌只会被送达一次（场景行已删除）' "$(f data.status)" 'NOT_FOUND'
# 领取之后场景行已经删掉了：授权页只能查到「这个 state 不存在」，于是回到无效链接提示页。
# 这里要守的是「不能重复操作」，不是某句措辞——表单和脚本必须在领取那一刻一起消失。
req GET "/oauth/authorize?state=$OA_STATE"
assert_has '领取后回到授权页只剩无效链接提示' "$LAST" '授权链接无效'
assert_lack '只读的授权页不再给按钮' "$LAST" '<button'
assert_lack '只读的授权页里没有任何脚本' "$LAST" 'fetch('

# 同一个第三方身份第二次授权：应该登回同一个账号，而不是再建一个
oa_new wechat
oa_approve "$OA_STATE" w1
oa_poll "$OA_STATE"
assert_has '同一 openId 第二次授权落回原账号' "$(f data.login.id)" "$OA_UID"
TOK_OA2=$(f data.login.token)
if [ "${#TOK_OA2}" -eq 32 ] && [ "$TOK_OA2" != "$TOK_OA" ]; then
    ok '第二次授权另外签发了新令牌（不是把旧会话原样递回来）'
else
    no '第二次授权另外签发了新令牌' '与第一次不同的 32 位 token' "$TOK_OA2"
fi

# 拒绝路径：发起页要立刻看到，且绝不发令牌
oa_new wechat
req POST "/api/auth/oauth/$OA_STATE/reject" >/dev/null
assert_eq '拒绝后状态为 REJECTED' "$(f data.status)" 'REJECTED'
oa_poll "$OA_STATE"
assert_eq '发起页立刻看到被拒绝' "$(f data.status)" 'REJECTED'
assert_lack '被拒绝的授权不发令牌' "$LAST" '"login"'

# 过期：授权了但发起页没来领取（用户中途关页面）——到期后既不能发令牌，也要能被轮询钉成 EXPIRED
if [ "$HAS_DB" = '1' ]; then
    oa_new wechat
    oa_approve "$OA_STATE" w2
    sql "UPDATE oauth_scene SET expire_time = NOW() - INTERVAL 1 MINUTE WHERE state = '$OA_STATE'" >/dev/null
    oa_poll "$OA_STATE"
    assert_eq '已授权但已过期的场景不发放令牌' "$(f data.status)" 'EXPIRED'
    assert_lack '过期场景的轮询响应里不带 login 载荷' "$LAST" '"login"'
else
    skip '已授权但已过期的场景不发放令牌' '未提供 MYSQL_BIN 与 MYSQL_PWD，无法把 expire_time 改到过去'
fi

# 后加的两个渠道：支付宝/百度不单独写一套代码，全靠那张渠道注册表驱动，所以这里各走一次完整授权
# 钉住「加一个渠道只需改注册表」这件事。它们与前端那一排圆标一一对应，
# 身份编号（a1/d1）也是从同一张表里来的。
# 这一段只花 2 次发起配额（放在限流那一节之前），建的号由末尾的 wx_/qq_/wb_/ap_/bd_ 清理兼底。
oa_new alipay
assert_eq '渠道名由服务端给出（支付宝）' "$OA_NAME" '支付宝'
req GET "/oauth/authorize?state=$OA_STATE"
assert_has '授权页列出了支付宝的演示身份' "$LAST" '阿栗的行李箱'
assert_has '授权页按渠道品牌色上色（与前端圆标同源）' "$LAST" '#1677ff'
oa_approve "$OA_STATE" a1
oa_poll "$OA_STATE"
assert_has '支付宝授权结果里带渠道昵称' "$(f data.nickname)" '阿栗的行李箱'
assert_has '支付宝建号的用户名前缀是 ap_' "$(f data.login.username)" 'ap_'

oa_new baidu
assert_eq '渠道名由服务端给出（百度）' "$OA_NAME" '百度'
oa_approve "$OA_STATE" d1
oa_poll "$OA_STATE"
assert_has '百度建号的用户名前缀是 bd_' "$(f data.login.username)" 'bd_'

# 发起限流：这个接口不登录就能调，一次发起写一行数据库，没有每分钟上限就能把 oauth_scene 刷满。
# 放最后，因为它会把这个 IP 的本分钟窗口压满（同一分钟内重跑本脚本，本节会整体跳过）。
OA_TRIPPED=0
for _ in $(seq 1 12); do
    req POST /api/auth/oauth "$TMP/oa.json"
    case "$LAST" in *'过于频繁'*) OA_TRIPPED=1; break ;; esac
done
if [ "$OA_TRIPPED" = '1' ]; then
    ok '每分钟发起授权有上限，压满后拒绝'
    assert_has '限流提示说清了限额' "$LAST" '每分钟最多'
else
    skip '每分钟发起授权有上限' '连压 12 次都没被拒：oauth-max-per-minute 配得偏高（默认 10）'
fi

section '19. 7×24 客服：规则问答（演示，未登录也可用）'
ask() { # ask 用例名 期望片段 问题
    json_body "$TMP/ask.json" message "$3"
    req POST /api/support/chat "$TMP/ask.json"
    assert_has "$1" "$(f data.reply)" "$2"
}
json_body "$TMP/ask.json" message '已支付的订单怎么申请退款'
req POST /api/support/chat "$TMP/ask.json"
assert_eq '未登录也能问客服' "$(f code)" '0'
assert_has '退款问题命中退款规则（优先于「订单」）' "$(f data.reply)" '申请退款'
assert_eq '回复标成规则演示来源，不冒充大模型' "$(f data.source)" 'rule-demo'
assert_eq '回复带热线' "$(f data.servicePhone)" '400-168-6688'
assert_eq '回复声明 7×24' "$(f data.hour24)" 'true'
ask '超时问题答的是自动取消与库存释放' '自动取消' '订单超时未支付会怎样'
ask '扫码问题答的是手机确认流程'     '确认登录' '扫码登录怎么用'
ask '登录问题答的是四种登录方式'      '四种登录方式' '支持哪些登录方式'
ask '注册问题答的是三种注册方式'      '三种方式' '怎么注册账号'
ask '邮箱注册答的是邮箱验证码这条通道'  '邮箱' '邮箱注册怎么弄'
ask '邮箱验证码答的是 10 分钟有效期'   '10 分钟' '邮箱验证码多久失效'
ask '第三方注册答的是渠道与自动生成'   '第三方' '微信登录怎么注册'
ask '验证码问题答的是有效期'          '验证码' '验证码多久有效'
ask '问「在吗」也有人接'              '7×24' '在吗'
json_body "$TMP/ask2.json" message '帮我写一篇介绍悦途旅行的推广文案'
req POST /api/support/chat "$TMP/ask2.json"
assert_has '没命中规则时转人工并给热线' "$(f data.reply)" '400-168-6688'
json_body "$TMP/ask3.json" message ''
req POST /api/support/chat "$TMP/ask3.json"
assert_has '空消息不调用模型直接拒' "$LAST" '请输入您想咨询的问题'

section '20. CORS 来源白名单'
req_preflight() { # 预检：$1=Origin
    curl -s -o /dev/null -D "$TMP/hdr.txt" -X OPTIONS "$BASE/api/orders" \
        -H "Origin: $1" -H 'Access-Control-Request-Method: POST' -H 'Access-Control-Request-Headers: authorization' >/dev/null
}
req_preflight 'http://localhost:5500'
grep -qi 'access-control-allow-origin: http://localhost:5500' "$TMP/hdr.txt" \
    && ok '本地来源预检通过' || no '本地来源预检通过' 'Access-Control-Allow-Origin: http://localhost:5500' "$(cat "$TMP/hdr.txt" | tr -d '\r' | grep -i 'access-control' | tr '\n' ' ')"
req_preflight 'https://evil.example.com'
grep -qi 'access-control-allow-origin: https://evil.example.com' "$TMP/hdr.txt" \
    && no '外部来源不在白名单' '不下发 ACAO 头' '下发了允许头' || ok '外部来源不在白名单'
req_preflight 'null'
grep -qi 'access-control-allow-origin: null' "$TMP/hdr.txt" \
    && ok '本地文件（Origin: null）放行，可用 ctrip.cors.allow-file-origin=false 关掉' \
    || skip '本地文件来源放行' '当前配置未允许 Origin: null（allow-file-origin=false）'

section '21. 清理测试数据'
# 本轮留下的账号有四种来源：邮箱注册（esmoke_*，邮箱都在 @qa.smoke.test 域）、
# 手机号注册（m1390*）、第三方授权建号（wx_/qq_/wb_/ap_/bd_ + 8 位十六进制）、
# 以及历史版本用用户名注册留下的 smoke_*——四条都要清，否则下次重跑会撞「已注册」。
SQL_USER_FILTER="email LIKE '%@qa.smoke.test' OR username LIKE 'esmoke\\_%' OR username LIKE 'smoke\\_%' \
OR username LIKE 'm1390%' OR username REGEXP '^(wx|qq|wb|ap|bd)_[0-9a-f]{8}'"
if [ "$HAS_DB" = '1' ]; then
    sql "DELETE FROM sms_code WHERE phone LIKE '1390000%';
         DELETE FROM email_code WHERE email LIKE '%@qa.smoke.test';
         DELETE FROM login_qrcode WHERE user_id IN (SELECT id FROM app_user WHERE $SQL_USER_FILTER);
         DELETE FROM login_qrcode WHERE status <> 'PENDING' OR expire_time < NOW();
         DELETE FROM oauth_scene WHERE status <> 'PENDING' OR expire_time < NOW();
         DELETE b FROM user_oauth b JOIN app_user u ON u.id=b.user_id WHERE $SQL_USER_FILTER;
         DELETE s FROM user_session s JOIN app_user u ON u.id=s.user_id WHERE $SQL_USER_FILTER;
         DELETE o FROM travel_order o JOIN app_user u ON u.id=o.user_id WHERE $SQL_USER_FILTER;
         DELETE FROM app_user WHERE $SQL_USER_FILTER;" \
        && ok '已通过 MySQL 清掉冒烟测试账号、会话、验证码、扫码/授权场景、第三方绑定与订单' \
        || no 'MySQL 清理' '执行成功' '执行失败，请用文末 SQL 手工清理'
    say '  （sms_code / email_code / login_qrcode / oauth_scene / user_oauth 都按测试号段、测试邮箱域与渠道用户名清理，'
    say '    init.sql 里的示例客户号码与真实用户不受影响）'
    say '  （hotel 等商品的余房数被测试单改动过，需要还原请重跑 sql/init.sql）'
else
    skip '自动清理' '未提供 MYSQL_BIN 与 MYSQL_PWD'
fi

say ''
say "结果：通过 $PASS · 失败 $FAIL · 跳过 $SKIP"
if [ "$FAIL" -gt 0 ]; then
    say '请手工清理测试数据（在 ctrip_travel 库执行）：'
    say "  DELETE FROM sms_code WHERE phone LIKE '1390000%';"
    say "  DELETE FROM email_code WHERE email LIKE '%@qa.smoke.test';"
    say "  DELETE FROM login_qrcode WHERE status <> 'PENDING' OR expire_time < NOW();"
    say "  DELETE FROM oauth_scene WHERE status <> 'PENDING' OR expire_time < NOW();"
    say "  DELETE b FROM user_oauth b JOIN app_user u ON u.id=b.user_id WHERE u.email LIKE '%@qa.smoke.test' OR u.username LIKE 'esmoke\\_%' OR u.username LIKE 'smoke\\_%' OR u.username LIKE 'm1390%' OR u.username REGEXP '^(wx|qq|wb)_[0-9a-f]{8}';"
    say "  DELETE s FROM user_session s JOIN app_user u ON u.id=s.user_id WHERE u.email LIKE '%@qa.smoke.test' OR u.username LIKE 'esmoke\\_%' OR u.username LIKE 'smoke\\_%' OR u.username LIKE 'm1390%' OR u.username REGEXP '^(wx|qq|wb)_[0-9a-f]{8}';"
    say "  DELETE o FROM travel_order o JOIN app_user u ON u.id=o.user_id WHERE u.email LIKE '%@qa.smoke.test' OR u.username LIKE 'esmoke\\_%' OR u.username LIKE 'smoke\\_%' OR u.username LIKE 'm1390%' OR u.username REGEXP '^(wx|qq|wb)_[0-9a-f]{8}';"
    say "  DELETE FROM app_user WHERE email LIKE '%@qa.smoke.test' OR username LIKE 'esmoke\\_%' OR username LIKE 'smoke\\_%' OR username LIKE 'm1390%' OR username REGEXP '^(wx|qq|wb)_[0-9a-f]{8}';"
    exit 1
fi
exit 0
