-- =====================================================================
-- 迁移（2026-10-28 第四批）：支付倒计时与退款状态
-- 背景：原来「下单即已支付」，没有待支付这一步，于是
--       ① 用户提交订单后关掉页面，库存被永久占住（没人支付也没人取消）；
--       ② 已支付订单只能取消，没有退款这一说，状态和真实业务对不上；
--       ③ 已取消/已退款的单子想从列表里删掉也没有入口。
-- 现在状态机四态：待支付 →（支付）已支付 →（退款）已退款
--                待支付 →（用户取消 / 超时自动取消）已取消
--       只有已取消、已退款的订单允许物理删除（未付或未退的钱不能凭空抹掉记录）。
-- 全新库直接跑 init.sql，本文件无需执行。
--
-- 执行方式（cmd；密码走环境变量，不进命令行）：
--   set MYSQL_PWD=你的密码
--   "C:\mysql-8.0.44-winx64\bin\mysql.exe" -u root ^
--       --default-character-set=utf8mb4 ctrip_travel < sql\migration-2026-10-28-pay-deadline.sql
--   set MYSQL_PWD=
-- =====================================================================

ALTER TABLE travel_order
    ADD COLUMN refund_time DATETIME NULL COMMENT '退款成功时间' AFTER cancel_time,
    ADD COLUMN pay_deadline DATETIME NULL COMMENT '待支付截止时间，超时自动取消' AFTER refund_time,
    MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT '待支付' COMMENT '待支付/已支付/已取消/已退款',
    ADD KEY idx_order_status_deadline (status, pay_deadline);

-- 历史数据不动：老订单当时就是「已支付」，保持原样；
-- 万一手动画过待支付单没有截止时间，按默认 15 分钟窗口补上，避免定时任务把它们当场取消。
UPDATE travel_order
   SET pay_deadline = DATE_ADD(create_time, INTERVAL 15 MINUTE)
 WHERE status = '待支付' AND pay_deadline IS NULL;

-- 核对：列已就位，老订单状态与条数不变
SELECT COUNT(*) AS orders,
       SUM(status = '待支付')  AS pending,
       SUM(status = '已支付')  AS paid,
       SUM(status = '已取消')  AS cancelled,
       SUM(status = '已退款')  AS refunded
    FROM travel_order;
SHOW COLUMNS FROM travel_order LIKE '%time%';
SHOW COLUMNS FROM travel_order LIKE 'pay_deadline';
