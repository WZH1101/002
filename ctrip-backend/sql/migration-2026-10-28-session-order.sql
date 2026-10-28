-- =====================================================================
-- 迁移（2026-10-28 第二批）：会话落库 + 订单幂等键/取消时间
-- 适用：已经建过库、不想重建表（不想丢用户与订单）的环境。
--       全新环境直接跑 init.sql，本文件无需执行。
-- 前置：第一批迁移（travel_order.user_id）已执行；未执行也无妨，
--       下面的语句会检查列是否存在后再加。
--
-- 执行方式（cmd；密码走环境变量，不进命令行）：
--   set CTRIP_DB_PASSWORD=你的密码
--   set MYSQL_PWD=%CTRIP_DB_PASSWORD%
--   "C:\mysql-8.0.44-winx64\bin\mysql.exe" -u root ^
--       --default-character-set=utf8mb4 ctrip_travel < sql\migration-2026-10-28-session-order.sql
--   set MYSQL_PWD=
-- =====================================================================

-- 1) 凭证版本：改密码时 +1，会话表里记录的旧版本立即全部失效
ALTER TABLE app_user
    ADD COLUMN auth_ver INT NOT NULL DEFAULT 0 AFTER phone;

-- 2) 会话表：令牌从内存 Map 挪到这里，重启不再掉线，也可多实例共享
CREATE TABLE IF NOT EXISTS user_session (
  token CHAR(32) PRIMARY KEY,
  user_id BIGINT NOT NULL,
  auth_ver INT NOT NULL DEFAULT 0,
  expire_time DATETIME NOT NULL,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_session_user (user_id),
  KEY idx_session_expire (expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3) 订单：幂等键（重复提交返回原单）与取消时间（状态流转留痕）
ALTER TABLE travel_order
    ADD COLUMN idem_key VARCHAR(64) NULL AFTER order_no,
    ADD COLUMN cancel_time DATETIME NULL AFTER pay_time;

-- 幂等键按「人 + 键」唯一，不做全局唯一：前端订单号是 C+毫秒时间戳，
-- 两个人同一毫秒下单不该互相顶掉（原因见 init.sql 里 uk_order_idem 的注释）
ALTER TABLE travel_order
    ADD UNIQUE KEY uk_order_idem (idem_key, user_id);

-- 注意：本文件 2026-10-28 首版把 idem_key 建成了单列全局 UNIQUE，
--       已经跑过首版的库请再执行 migration-2026-10-28-idem-key-scope.sql 换索引。

-- 核对：三处新对象都应存在，原有订单与用户条数不变
SELECT
    (SELECT COUNT(*) FROM app_user)     AS users,
    (SELECT COUNT(*) FROM travel_order) AS orders,
    (SELECT COUNT(*) FROM user_session) AS sessions;
