-- =====================================================================
-- 迁移：订单改签记录表（2026-11-22）
-- 适用：已经用旧版 init.sql 建过库、不想重建表（不想丢订单）的环境。
-- 全新环境直接跑 init.sql 即可，本文件无需执行。
--
-- 执行方式（cmd；密码走 MYSQL_PWD 环境变量，不写进命令行，避免出现在进程列表/history）：
--   set CTRIP_DB_PASSWORD=你的密码
--   set MYSQL_PWD=%CTRIP_DB_PASSWORD%
--   "C:\mysql-8.0.44-winx64\bin\mysql.exe" -u root ^
--       --default-character-set=utf8mb4 ctrip_travel < sql\migration-2026-11-22-order-change.sql
--   set MYSQL_PWD=
--
-- 注意：订单查询（OrderService.SELECT_ORDER_COLUMNS）会带一个统计改签次数的子查询，
-- 这张表不存在时整条订单列表都会报错，所以升级后端前必须先跑本文件。
-- =====================================================================

CREATE TABLE IF NOT EXISTS order_change (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_no VARCHAR(32) NOT NULL,
  seq INT NOT NULL,                        -- 第几次改签，从 1 开始
  old_item_id BIGINT, new_item_id BIGINT,
  old_title VARCHAR(256), new_title VARCHAR(256),
  old_date_text VARCHAR(128), new_date_text VARCHAR(128),
  old_choice VARCHAR(64), new_choice VARCHAR(64),
  old_unit_price DECIMAL(10,2), old_total_price DECIMAL(10,2),
  new_unit_price DECIMAL(10,2), new_total_price DECIMAL(10,2),
  fee DECIMAL(10,2) NOT NULL DEFAULT 0,    -- 改签手续费
  diff DECIMAL(10,2) NOT NULL DEFAULT 0,   -- 新票款 + 手续费 - 原票款；正数为补差价，负数为退差额
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_change_order (order_no),
  -- 同一单的改签序号唯一：并发改签时由唯一索引兜底，不会写出两个「第 1 次」
  UNIQUE KEY uk_change_seq (order_no, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 核对：应看到 order_change 的建表语句
SHOW CREATE TABLE order_change;