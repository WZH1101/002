-- =====================================================================
-- 迁移（2026-10-28 第五批）：手机验证码登录 + 扫码登录
-- 背景：登录此前只有「账号密码」一条路。补两种常见方式：
--       ① 手机验证码：服务端随机 6 位码、5 分钟有效、60 秒可重发、每小时限量、
--          校验失败计数达上限即作废、一次性使用；表里只存 PBKDF2 哈希，不存明文。
--       ② 扫码登录：电脑生成场景号 → 手机打开确认页登录并确认 → 电脑轮询领取会话令牌。
--          手机页与电脑轮询要读写同一条记录，所以场景必须落库而不是放内存。
-- 全新环境直接跑 init.sql，本文件无需执行。
--
-- 执行方式（cmd；密码走环境变量，不进命令行）：
--   set MYSQL_PWD=你的密码
--   "C:\mysql-8.0.44-winx64\bin\mysql.exe" -u root ^
--       --default-character-set=utf8mb4 ctrip_travel < sql\migration-2026-10-28-login-sms-qrcode.sql
--   set MYSQL_PWD=
-- =====================================================================

CREATE TABLE IF NOT EXISTS sms_code (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  phone VARCHAR(16) NOT NULL,
  scene VARCHAR(16) NOT NULL DEFAULT 'login',
  code_hash VARCHAR(128) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  used TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  expire_time DATETIME NOT NULL,
  KEY idx_sms_phone (phone, scene, used, expire_time),
  KEY idx_sms_expire (expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS login_qrcode (
  scene CHAR(32) PRIMARY KEY,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  user_id BIGINT NULL,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  scan_time DATETIME NULL,
  confirm_time DATETIME NULL,
  expire_time DATETIME NOT NULL,
  KEY idx_qr_expire (expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 核对：两张表都在，原有数据条数不变
SELECT (SELECT COUNT(*) FROM sms_code)    AS sms_rows,
       (SELECT COUNT(*) FROM login_qrcode) AS qr_rows,
       (SELECT COUNT(*) FROM app_user)     AS users,
       (SELECT COUNT(*) FROM user_session) AS sessions;

-- 提示：一个手机号对应多个账号时，后端会拒绝验证码登录并让用户改用账号密码。
-- 演示库里 13800138000 被多个账号共用，可以先看这里：
SELECT phone, COUNT(*) AS accounts FROM app_user
 WHERE phone IS NOT NULL AND phone <> ''
 GROUP BY phone HAVING accounts > 1;
