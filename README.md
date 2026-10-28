# 悦途旅行预订 · 前端单页 + Java 后端

一个可跑起来的在线旅行预订演示项目：酒店 / 门票·活动 / 火车票 / 机票 / 汽车·船票 / 度假线路的搜索与下单，
配一个 Spring Boot + MySQL 的真实后端。**没有前端也能读完后端**：所有业务规则都落在服务端，页面只是其中一种调用方式。

```
悦途旅行预订.html          前端单文件应用（Vue 2.6.14，约 4300 行脚本），双击即可运行
ctrip-backend/             后端（Spring Boot 3.2.5 + Java 21 + MySQL 8）
  ├─ src/main/java/com/ctrip/
  │    controller/          HTTP 层：产品搜索、账号、订单、企业商旅、状态页
  │    service/             业务层：计价与库存、订单状态机、会话与口令、定时清理
  │    config/              跨域策略 + 登录态拦截器
  │    api/                 统一响应体 {code,msg,data}
  ├─ sql/                   init.sql 建库建表 + 增量迁移脚本
  ├─ qa/smoke.sh            零依赖冒烟回归脚本（82 条断言）
  └─ run-backend.bat        一键启动
```

---

## 1. 启动

前提：JDK 21、MySQL 8（本机 3306）、Maven 及其**离线仓库** `D:\Maven\m2repo`。
本项目全程用离线仓库构建，不联网下载依赖，命令里必须带上 `-Dmaven.repo.local`：

```bat
:: 1) 建库导数据（首次或想重置时）
ctrip-backend\init-db.bat

:: 2) 启动后端（端口 8080）
ctrip-backend\run-backend.bat
:: 等价命令：
cd ctrip-backend
D:\Maven\apache-maven-3.9.9\bin\mvn.cmd -o -Dmaven.repo.local=D:\Maven\m2repo spring-boot:run

:: 3) 打开前端
:: 直接双击「悦途旅行预订.html」即可；页面会请求 http://localhost:8080/api/**
```

自检：浏览器访问 <http://localhost:8080/> 会看到状态页，列出数据库实时统计与接口清单；
`http://localhost:8080/api/hotels?city=北京` 返回 JSON 即说明库通了。

前端的行为分三种，页面顶部会显示当前是哪种：

- **后端可用**：搜索、下单、库存、订单全部走 MySQL，刷新页面数据仍在。
- **后端不可用**：自动降级为本地演示数据（随机生成班次、订单存 `localStorage`），页面照常能走完流程。
- **混合**：特色体验活动、国际/港澳台航班这两类只有前端有数据，即使连着后端也在本地成单（MySQL 里没有对应商品记录）。

---

## 2. 接口与鉴权

| 接口 | 说明 | 需要登录 |
|---|---|---|
| `GET /api/hotels`、`/attractions`、`/trains`、`/flights`、`/buses`、`/ships`、`/vacations`、`/cars`、`/shop`、`/giftcards` | 商品搜索 | 否 |
| `POST /api/auth/register` / `login` / `logout`、`GET /api/auth/me`、`POST /api/auth/password` | 账号与会话 | 见说明 |
| `POST /api/orders` | 下单，生成**待支付**订单并锁定库存 | 是 |
| `GET /api/orders` | 本人订单列表（四种状态都返回） | 是 |
| `POST /api/orders/{orderNo}/pay` | 支付 | 是 |
| `POST /api/orders/{orderNo}/cancel` | 取消待支付订单 | 是 |
| `POST /api/orders/{orderNo}/refund` | 退款（已支付 → 已退款） | 是 |
| `DELETE /api/orders/{orderNo}` | 删除已取消 / 已退款的订单记录 | 是 |
| `DELETE /api/orders?confirm=YES` | 批量取消本人全部待支付订单 | 是 |
| `POST /api/biztravel` | 企业商旅提交 | 是 |
| `GET /api/biztravel?company=` | 企业商旅状态查询（无个人信息） | 否 |

**令牌读取优先级**：`Authorization: Bearer <token>` > `X-Token` > 查询参数 `?token=`。
查询参数只为兼容旧页面保留，它会进入访问日志与浏览器历史，前端已改为走 Header。

**受保护范围**由 `AuthInterceptor` 统一拦截 `/api/orders/**` 与 `/api/biztravel`（后者按 HTTP 方法放行 GET）。
拦截器挡下时返回 HTTP 401 + `{"code":401,...}`；`/api/auth/me` 不在拦截清单内，它自己判定会话，
未登录时返回 HTTP 200 + `{"code":1,"msg":"登录已过期，请重新登录"}`，前端据 `code` 清理登录态。

**登录门禁在「提交订单」这一步生效**：未登录点「提交预订」会弹出登录面板，预订表单里已填的内容不丢，
登录成功后自动补齐联系人并回到原弹窗。企业商旅提交同样如此。

---

## 3. 订单状态机与支付倒计时

```
            支付（15 分钟内）              退款
  待支付 ────────────────▶ 已支付 ────────────────▶ 已退款 ──┐
     │                                                      │
     │ 用户取消 / 超时自动取消                                │ 删除记录
     └────────────────────▶ 已取消 ◀─────────────────────────┘
```

- **下单只锁库存，不算付款**。库存（余票 / 余房）在 `ProductService.priceAndDeduct` 里计价并扣减，
  取消、退款、超时都通过 `ProductService.restoreStock` 原样回补——以前只有扣没有加，删一单库存就被永久磨掉。
- **支付窗口**：`ctrip.order.pay-deadline-minutes`（默认 15 分钟）。订单表存 `pay_deadline`，
  接口同时返回 `payExpireMs`（epoch 毫秒）与 `payDeadline`（可读串），前端倒计时读的就是它。
- **超时自动取消**两条路径：
  1. `OrderExpiryTask` 每 `ctrip.order.expiry-scan-ms`（默认 60 秒）扫一次
     `status='待支付' AND pay_deadline<=NOW()`，逐单取消并回补库存（单次最多 200 条）。
  2. 用户卡着最后一秒点「立即支付」时，`payOrder` 先就地取消再返回「支付超时，订单已自动取消，请重新下单」，
     倒计时因此没法被无限续期。
- **前端心跳**每 1 秒刷新一次剩余时间，归零时立刻把页面上的单子标为已取消（不必等后端下一轮扫描）；
  切回标签页时补一跳——浏览器会把后台页面的 `setInterval` 降频到每分钟一次，不补就会出现倒计时停在旧数字。
- **订单中心四个按钮**：去支付 / 取消订单 / 申请退款 / 删除，显示与否由服务端返回的
  `payable` / `cancellable` / `refundable` / `deletable` 决定，而不是前端猜状态——
  别人的订单、无主的历史订单，四个标志都是 `false`，按钮直接不出现。
- **幂等**：`idem_key`（前端沿用自己预生成的订单号）配合唯一索引 `uk_order_idem (idem_key, user_id)`。
  双击「提交订单」或网络重试只会有一笔真单、只扣一次库存。
  键的作用域限定到**人**：订单号是 `C+毫秒时间戳`，两个用户在同一毫秒下单不该被误判成重复提交。

---

## 4. 安全设计

| 主题 | 做法 |
|---|---|
| 口令存储 | PBKDF2-HMAC-SHA256，12 万次迭代，格式 `pbkdf2$迭代数$盐$哈希`；旧的 SHA-256 记录在登录成功时透明升级，历史账号不必强制改密 |
| 口令强度 | 至少 8 位且同时含字母与数字，注册、改密两处校验，前端先拦一道、后端为准 |
| 会话 | 令牌存 `user_session` 表（不是内存 Map），重启服务不掉登录态；`app_user.auth_ver` 每次改密递增，签发时记录的 `auth_ver` 一旦不等于当前值，该用户全部旧令牌同时失效 |
| 防爆破 | 同一「用户名@IP」连续失败 5 次锁定 15 分钟，**第 5 次失败当场就说明已锁定**；用户不存在与密码错误返回同一句提示，避免账号枚举 |
| 注册限流 | 同一 IP 每小时最多 5 次注册尝试 |
| 归属隔离 | 订单写操作先校验 `user_id` 等于当前会话用户；不存在与没权限回同一句「订单不存在或不属于当前账号」，避免用错误码探测订单号 |
| 服务端计价 | 请求里的金额一律丢弃，单价与总价由服务端按商品表计算 |
| 个人信息 | 列表与详情接口回传的手机号统一掩码 `138****5678`；库里仍存明文（改签、退票、客服核对要用），但接口不再整表吐号 |
| 跨域 | 只放行 `http://localhost:*`、`http://127.0.0.1:*`；`file://` 打开页面的 `Origin: null` 由 `allow-file-origin` 单独控制；允许的头部显式列举，不用 `*` |
| 危险操作 | `DELETE /api/orders` 必须带 `?confirm=YES`，且语义已从「物理删除全部订单」改成「批量取消待支付并回补库存」 |
| SQL | 全部走 `JdbcTemplate` 参数绑定；状态页的统计表名是写死的白名单 |

口令不在仓库里：数据库账号密码取环境变量 `CTRIP_DB_USER` / `CTRIP_DB_PASSWORD`，
`application.yml` 里的默认值只为「clone 后能直接跑」。`.gitignore` 已排除 `.env`、`application-local.yml`、日志与构建产物。

---

## 5. 配置项

`ctrip-backend/src/main/resources/application.yml`：

```yaml
ctrip:
  cors:
    allowed-origins: http://localhost:*,http://127.0.0.1:*
    allow-file-origin: true          # 上线设 false，否则任何本地网页都能冒充页面来源
  auth:
    max-login-failures: 5            # 触发锁定的连续失败次数
    lock-minutes: 15                 # 锁定时长
    max-register-per-hour: 5         # 同一 IP 每小时注册尝试上限
  order:
    pay-deadline-minutes: 15         # 支付窗口，超时自动取消
    expiry-scan-ms: 60000            # 过期扫描间隔
```

前端本地演示模式的窗口在 `悦途旅行预订.html` 的 `localPayWindowMin`（同样 15 分钟），
两边不一致时用户看到的倒计时会和实际判定条件脱节。

---

## 6. 回归验证

没有单元测试框架——本地离线仓库里没有 junit / mockito / h2，也没有为测试联网下载它们。
替代方案是零依赖的冒烟脚本 `qa/smoke.sh`（curl + node），把上面这些行为逐条钉住，**当前 82 条断言全绿**。

```bat
:: 先启动后端，然后：
cd ctrip-backend
bash qa/smoke.sh

:: 想顺带验证「定时任务真的会扫掉超时订单」，再提供 MySQL 客户端：
set MYSQL_BIN=C:\mysql-8.0.44-winx64\bin\mysql.exe
set MYSQL_PWD=你的密码
set SMOKE_SWEEP=1
bash qa/smoke.sh
```

覆盖：状态页、匿名读写全部 401、口令强度、注册/登录、会话落库与改密吊销、服务端计价与扣库存、
幂等重复提交、归属隔离与越权、手机号掩码、**待支付 → 支付 → 退款 → 删除** 全链路、
**待支付 → 取消 → 删除**、支付超时（接口就地取消 + `OrderExpiryTask` 扫描两条路径）、
批量取消需显式确认且只动待支付、登录失败锁定、CORS 三种来源。

脚本用的测试账号固定叫 `smoke_a / smoke_b / smoke_c`，结束时会自动清掉账号、会话与订单，
并重复运行安全（先试登录再注册，避免消耗每小时 5 次的注册额度）。
提供了 `MYSQL_BIN/MYSQL_PWD` 时会直接连库把截止时刻改到过去，几秒就能验完超时逻辑。

---

## 7. 数据库

`sql/init.sql` 建库 `ctrip_travel` 并导入演示数据（酒店、景点、火车、航班、班次、订单表）。
已经建过库、只想升级到最新表结构，按文件名顺序执行 `sql/migration-*.sql`：

| 脚本 | 内容 |
|---|---|
| `migration-2026-10-28-order-user-id.sql` | `travel_order` 加 `user_id`，实现按归属人隔离 |
| `migration-2026-10-28-session-order.sql` | 新增 `user_session` 表、`app_user.auth_ver`、订单幂等键 |
| `migration-2026-10-28-idem-key-scope.sql` | 幂等键唯一索引改成按人生效 `uk_order_idem (idem_key, user_id)` |
| `migration-2026-10-28-pay-deadline.sql` | 加 `pay_deadline` / `refund_time`，默认状态改为待支付，加超时扫描索引 |

每个迁移脚本都带前置检查与执行后的验证查询，**不会重建表、不会清掉已有订单**。
`init.sql` 会先 `DROP TABLE`，只在重置演示数据时用。

---

## 8. 演示边界（重要的都在这）

这些不是隐藏的问题，而是这个项目刻意没有做的部分：

- **没有真实资金**。`/pay` 只是把状态从待支付改成已支付；退款同理，
  前端「申请退票」展示的 80% 退款比例是页面演示规则，后端只记状态与时间，不产生资金流水。
- **短信验证码固定 888888**，只用于渠道校验演示，页面上有标注；没有对接任何短信服务。
- **AI 行程推荐是本地规则拼装的文案**，不是模型输出，页面上有标注。
- **特色体验活动 / 国际·港澳台航班 / 抢票 / 学生票 / 企业用车等**只在前端成单，MySQL 没有对应商品表。
- **限流与失败锁定计数在单机内存里**（`ConcurrentHashMap`），重启清零、多实例不共享。
  要真正防住分布式爆破需要落到数据库或 Redis，本项目规模下没做，代价写在这里。
- **数据库连接是明文口令 + `allowPublicKeyRetrieval=true`**，前提是「本机 MySQL、端口不出机器」。
  一旦 3306 暴露到局域网或公网，必须去掉这两项并改用 TLS。服务本身没有配 HTTPS。
- **加 `user_id` 之前那批无主历史订单**（`user_id IS NULL`）对任何登录用户都可见——
  这是为了避免升级后「突然丢单」，代价是无主订单谁都看不到写操作权限，四个动作标志恒为 `false`。
  需要彻底断开可以在库里补一条 `UPDATE travel_order SET user_id = <某人> WHERE user_id IS NULL`。
- **前端是单文件、CDN 引入 Vue 2**，没有构建步骤，也因此没有组件级测试；
  改动后至少跑一遍 `qa/smoke.sh` 并双击页面走完一次下单-支付-取消。

---

## 9. 常见问题

**下单/搜索接口返回 400 或中文乱码。** Windows 的 curl 在命令行里传中文 JSON 会被 GBK 转码。
把请求体写成 UTF-8 文件再用 `--data-binary "@文件"` 发送（`qa/smoke.sh` 就是这么做的）。

**`mvn` 卡住或报找不到依赖。** 必须带 `-o -Dmaven.repo.local=D:\Maven\m2repo` 走离线仓库；
联网解析会去中央仓库下载，本机没有那个网络条件。

**8080 被占用。** `netstat -ano | findstr 8080` 找到 PID 后结束对应进程，
或改 `application.yml` 的 `server.port`（前端的接口地址写死在页面里，改端口要一并改前端）。

**登录提示「已临时锁定」。** 连续 5 次密码错误的后果，默认 15 分钟自动解开；
重启后端会连同计数一起清零。

**重复运行冒烟脚本被「注册过于频繁」挡住。** 同一 IP 每小时 5 次注册尝试；
脚本会退回登录，账号若已被清理则重启后端再跑。

**页面上倒计时不动。** 浏览器对后台标签页的定时器降频所致，切回页面会立刻补上；
服务端的超时判定不依赖前端。
