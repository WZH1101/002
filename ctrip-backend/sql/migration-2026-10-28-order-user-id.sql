-- =====================================================================
-- 迁移：给订单加归属人（2026-10-28）
-- 适用：已经用旧版 init.sql 建过库、不想重建表（不想丢订单）的环境。
-- 全新环境直接跑 init.sql 即可，本文件无需执行。
--
-- 执行方式（cmd；密码走 MYSQL_PWD 环境变量，不写进命令行，避免出现在进程列表/history）：
--   set CTRIP_DB_PASSWORD=你的密码
--   set MYSQL_PWD=%CTRIP_DB_PASSWORD%
--   "C:\mysql-8.0.44-winx64\bin\mysql.exe" -u root ^
--       --default-character-set=utf8mb4 ctrip_travel < sql\migration-2026-10-28-order-user-id.sql
--   set MYSQL_PWD=
-- =====================================================================

-- 可空：加列之前的历史订单没有归属人，保持 NULL，列表接口会把它们一并发给
-- 任意已登录用户（宁可多看见，也不让谁的单突然消失）。
ALTER TABLE travel_order
    ADD COLUMN user_id BIGINT NULL AFTER item_id,
    ADD KEY idx_order_user (user_id);

-- 核对：应看到 user_id 列（MUL 索引）且原有条数不变
SELECT COUNT(*) AS order_rows, COUNT(user_id) AS rows_with_owner FROM travel_order;
