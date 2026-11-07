-- =====================================================================
-- 迁移（2026-10-28 第六批）：邮箱注册 + 第三方账号注册
-- 背景：注册通道从「用户名+密码 / 手机号」改为「手机号 / 邮箱 / 第三方账号」。
--       用户名+密码注册下线后，用户名一律服务端生成，用户记住的凭据是手机号或邮箱，
--       因此 app_user 需要一列带唯一索引的 email；验证码登录同理需要一个已验过归属的邮箱。
-- 新增内容：
--       ① email_code —— 邮箱验证码，规则与 sms_code 完全同构（PBKDF2 哈希、一次性、
--          失败计数达上限即作废、按号码合算的跨场景重发与每小时配额）。
--       ② oauth_scene —— 第三方授权场景（PENDING→APPROVED→领取即删除），落库而不是放内存，
--          因为授权页与发起页是两次互不相干的请求。
--       ③ user_oauth —— 第三方账号与本站账号的绑定，UNIQUE(provider, open_id) 保证
--          「同一个人第二次授权落回同一个账号」。
-- 全新环境直接跑 init.sql，本文件无需执行。
--
-- 执行方式（cmd；密码走环境变量，不进命令行）：
--   set MYSQL_PWD=你的密码
--   "C:\mysql-8.0.44-winx64\bin\mysql.exe" -u root ^
--       --default-character-set=utf8mb4 ctrip_travel < sql\migration-2026-10-28-email-oauth.sql
--   set MYSQL_PWD=
--
-- 幂等性：ALTER TABLE ... ADD COLUMN 在 MySQL 8 没有 IF NOT EXISTS，重复执行会报
--   "Duplicate column name"；重复执行前先跑下面这段存在性检查，有结果集就说明还得改。
-- =====================================================================

-- 只有「还没有这一列」时才加，避免重复执行报错
SET @has_email := (SELECT COUNT(*) FROM information_schema.columns
                   WHERE table_schema = DATABASE() AND table_name = 'app_user' AND column_name = 'email');
SET @sql := IF(@has_email = 0,
               'ALTER TABLE app_user ADD COLUMN email VARCHAR(128) NULL UNIQUE AFTER phone',
               'SELECT ''app_user.email 已存在，跳过'' AS msg');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS email_code (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  email VARCHAR(128) NOT NULL,
  scene VARCHAR(16) NOT NULL DEFAULT 'register',
  code_hash VARCHAR(128) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  used TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  expire_time DATETIME NOT NULL,
  KEY idx_email_code_target (email, scene, used, expire_time),
  KEY idx_email_code_expire (expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS oauth_scene (
  state CHAR(32) PRIMARY KEY,
  provider VARCHAR(16) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  open_id VARCHAR(64) NULL,
  nickname VARCHAR(64) NULL,
  avatar VARCHAR(8) NULL,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  approve_time DATETIME NULL,
  expire_time DATETIME NOT NULL,
  KEY idx_oauth_expire (expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_oauth (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  provider VARCHAR(16) NOT NULL,
  open_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  nickname VARCHAR(64) NULL,
  avatar VARCHAR(8) NULL,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  last_login_time DATETIME NULL,
  UNIQUE KEY uk_oauth_identity (provider, open_id),
  KEY idx_oauth_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 核对：三张新表都在，原有账号条数不变；email 列对存量账号一律为 NULL（唯一索引不管 NULL）
SELECT (SELECT COUNT(*) FROM email_code)  AS email_code_rows,
       (SELECT COUNT(*) FROM oauth_scene) AS oauth_scene_rows,
       (SELECT COUNT(*) FROM user_oauth)  AS user_oauth_rows,
       (SELECT COUNT(*) FROM app_user)    AS users,
       (SELECT COUNT(*) FROM app_user WHERE email IS NOT NULL) AS users_with_email;

-- 提示：口令注册通道下线后，用户名是自动生成的，登录请改用手机号/邮箱（或注册时生成的用户名）。
SELECT id, username, nickname, phone, email FROM app_user ORDER BY id LIMIT 10;
