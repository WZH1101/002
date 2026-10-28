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
# 覆盖的契约（对应本轮安全加固 + 订单状态机）：
#   登录门禁 / 口令强度 / 登录失败锁定 / 注册限频 / 服务端计价 / 幂等重复提交 /
#   订单归属隔离 / 手机号脱敏 / 批量取消需显式确认 / 改密吊销全部令牌 / CORS 来源白名单；
#   下单为「待支付」+ 支付窗口 / 支付→已支付 / 已支付只能退款不能取消 / 退款回补库存 /
#   已取消、已退款可删除 / 待支付、已支付不可删除 / 超时未支付自动取消并回补库存
#
# 说明：测试账号固定叫 smoke_a / smoke_b / smoke_c（可反复运行；已存在时自动改走登录）。
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
json_body "$TMP/weak.json" username "smoke_weak_$$" password 123456
req POST /api/auth/register "$TMP/weak.json"
case "$LAST" in
    *'过于频繁'*) skip '弱口令被拒' '本机这一小时的注册额度已用满（可加大 ctrip.auth.max-register-per-hour 后重启后端）' ;;
    *) assert_has '弱口令注册被拒并给出规则' "$LAST" '至少 8 位' ;;
esac

# 测试账号：smoke_a 走完整状态机，smoke_b 验证归属隔离，smoke_c 验证失败锁定
PW_A='Smoke@2026a'; PW_B='Smoke@2026b'; PW_A2='Smoke@2026c'
phone_a='13900001111'

login_as() { # login_as 用户名 口令 → 成功则设 TOKEN，失败置空
    json_body "$TMP/login.json" username "$1" password "$2"
    req POST /api/auth/login "$TMP/login.json"
    TOKEN=$(f data.token)
}

register_or_login() { # register_or_login 用户名 口令 —— 先试登录（重复运行的快路径），没有账号才注册
    login_as "$1" "$2"
    if [ -n "$TOKEN" ]; then say "  · 账号 $1 已存在，直接登录（不消耗注册额度）"; return 0; fi
    json_body "$TMP/reg.json" username "$1" password "$2" nickname "冒烟测试$1" phone "$phone_a"
    req POST /api/auth/register "$TMP/reg.json"
    case "$LAST" in
        *'"code":0'*) say "  · 新注册账号 $1" ;;
        *'已被注册'*) no "注册 $1" '新账号' "同名账号已存在但口令对不上（历史遗留），请手工删除 app_user 里的 $1" ;;
        *'过于频繁'*) skip "注册 $1" '本机这一小时的注册额度已用满；重启后端会连同内存计数一起清零' ;;
        *) no "注册 $1" 'code 0 / 已被注册 / 限流' "$LAST" ;;
    esac
    login_as "$1" "$2"
    if [ -z "$TOKEN" ]; then no "登录 $1" '拿到 token' "$LAST"; return 1; fi
    return 0
}

section '3. 注册 / 登录（会话落库）'
register_or_login smoke_a "$PW_A" || { say '前置账号不可用，后续用例无法继续'; exit 1; }
TOK_A="$TOKEN"
req GET '/api/auth/me?token=not-a-real-token'
# /api/auth/me 不在拦截器的保护清单里（它自己判定会话），所以是 HTTP 200 + 业务失败，而不是 401
assert_has '伪造令牌查身份被拒' "$LAST" '登录已过期'
req GET /api/auth/me '' "$TOK_A"
assert_has '有效令牌能查回本人身份' "$LAST" '"username":"smoke_a"'
register_or_login smoke_b "$PW_B" || exit 1
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
login_as smoke_a "$PW_A"
assert_eq '旧密码不再能登录' "$TOKEN" ''
login_as smoke_a "$PW_A2"
if [ -z "$TOKEN" ]; then no '新密码能登录' '拿到 token' "$LAST"; else ok '新密码能登录'; fi
TOK_A="$TOKEN"
# 改回初始口令，保证脚本可以重复运行（这一步同样会吊销上面的令牌）
json_body "$TMP/pwd-back.json" oldPassword "$PW_A2" newPassword "$PW_A"
req POST /api/auth/password "$TMP/pwd-back.json" "$TOK_A"
assert_eq '口令已还原，便于下次重跑' "$(f code)" '0'

section '12. 登录失败锁定（防爆破）'
login_as smoke_c "$PW_B"
case "$LAST" in
    *'临时锁定'*) skip '失败锁定' 'smoke_c 仍在 15 分钟锁定期内（上次运行留下的），等过期后重跑' ;;
    *)
        [ -z "$TOKEN" ] && register_or_login smoke_c "$PW_B" >/dev/null 2>&1
        for i in 1 2 3 4 5; do
            json_body "$TMP/bad.json" username smoke_c password WrongPass999
            req POST /api/auth/login "$TMP/bad.json"
        done
        assert_has '第 5 次失败已提示锁定' "$LAST" '临时锁定'
        login_as smoke_c "$PW_B"
        assert_has '锁定期间即使密码正确也不放行' "$LAST" '临时锁定'
        ;;
esac

section '13. CORS 来源白名单'
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

section '14. 清理测试数据'
if [ "$HAS_DB" = '1' ]; then
    sql "DELETE s FROM user_session s JOIN app_user u ON u.id=s.user_id WHERE u.username LIKE 'smoke_%';
         DELETE o FROM travel_order o JOIN app_user u ON u.id=o.user_id WHERE u.username LIKE 'smoke_%';
         DELETE FROM app_user WHERE username LIKE 'smoke_%';" \
        && ok '已通过 MySQL 清掉冒烟测试账号、会话与订单' \
        || no 'MySQL 清理' '执行成功' '执行失败，请用文末 SQL 手工清理'
    say '  （hotel 等商品的余房数被测试单改动过，需要还原请重跑 sql/init.sql）'
else
    skip '自动清理' '未提供 MYSQL_BIN 与 MYSQL_PWD'
fi

say ''
say "结果：通过 $PASS · 失败 $FAIL · 跳过 $SKIP"
if [ "$FAIL" -gt 0 ]; then
    say '请手工清理测试数据（在 ctrip_travel 库执行）：'
    say "  DELETE s FROM user_session s JOIN app_user u ON u.id=s.user_id WHERE u.username LIKE 'smoke_%';"
    say "  DELETE o FROM travel_order o JOIN app_user u ON u.id=o.user_id WHERE u.username LIKE 'smoke_%';"
    say "  DELETE FROM app_user WHERE username LIKE 'smoke_%';"
    exit 1
fi
exit 0
