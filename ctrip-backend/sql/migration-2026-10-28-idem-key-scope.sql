-- =====================================================================
-- 迁移（2026-10-28 第三批·小修正）：订单幂等键的约束范围
-- 背景：migration-2026-10-28-session-order.sql 的首版把 idem_key 建成了
--       单列全局 UNIQUE。前端订单号是 'C' + Date.now()，两个人在同一毫秒
--       各下一单是完全正常的，全局唯一会把后提交的那单误判成重复提交，
--       报「订单提交失败，请重试」——用户看不到任何重复，只是下不了单。
-- 正确约束：(idem_key, user_id) 组合唯一。同一人重复提交才命中，
--       MySQL 的 UNIQUE 索引允许任意多行 NULL，未带幂等键的老订单不受影响。
--
-- 只跑过首版迁移的库需要执行本文件；全新库直接 init.sql，跳过。
--
-- 执行方式（cmd；密码走环境变量，不进命令行）：
--   set MYSQL_PWD=你的密码
--   "C:\mysql-8.0.44-winx64\bin\mysql.exe" -u root ^
--       --default-character-set=utf8mb4 ctrip_travel < sql\migration-2026-10-28-idem-key-scope.sql
--   set MYSQL_PWD=
-- =====================================================================

-- 1) 确认当前索引形态（应看到 Key_name = idem_key 的单列唯一索引）
SELECT INDEX_NAME, COLUMN_NAME, NON_UNIQUE
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'travel_order'
    ORDER BY INDEX_NAME, SEQ_IN_INDEX;

-- 2) 换约束：先加组合唯一，再删旧的单列唯一。
--    顺序很重要——反过来会让中间那一小段窗口完全没有幂等保护。
--    若报 "Duplicate entry ... for key uk_order_idem"，说明库里已有同键同人重复行，
--    先按下面第 3 步查出来清理，再重跑本条。
ALTER TABLE travel_order ADD UNIQUE KEY uk_order_idem (idem_key, user_id);
ALTER TABLE travel_order DROP INDEX idem_key;

-- 3)（仅在需要时）找出重复的幂等键
--   SELECT idem_key, user_id, COUNT(*) c FROM travel_order
--    WHERE idem_key IS NOT NULL GROUP BY idem_key, user_id HAVING c > 1;

-- 核对：travel_order 应有 PRIMARY / uk_order_idem / idx_order_type / idx_order_user，且无单列 idem_key 索引
SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols, MIN(NON_UNIQUE) AS non_unique
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'travel_order'
    GROUP BY INDEX_NAME
    ORDER BY INDEX_NAME;
