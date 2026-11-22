-- =====================================================================
-- 2026-11-22 悦途金融：拿去花授信分期 / 信用微贷 / 出行保障 / 钱包账单
-- 幂等：全部 CREATE TABLE IF NOT EXISTS，可重复执行
-- =====================================================================

-- 金融账户：一人一户。授信状态 none/approved/rejected；wallet 为钱包余额，
-- 微贷放款、还款、充值、投保、理赔赔付、退保退款全部经钱包走并记 fin_tx 流水。
CREATE TABLE IF NOT EXISTS fin_account (
  user_id BIGINT PRIMARY KEY,
  real_name VARCHAR(32), id_card VARCHAR(18),
  income INT DEFAULT 0, job VARCHAR(32),
  credit_status VARCHAR(16) NOT NULL DEFAULT 'none',
  credit_limit DECIMAL(12,2) NOT NULL DEFAULT 0,
  credit_used DECIMAL(12,2) NOT NULL DEFAULT 0,
  credit_score INT NOT NULL DEFAULT 0,
  wallet DECIMAL(12,2) NOT NULL DEFAULT 0,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 借款：kind=cash 信用微贷（放款入钱包）/ installment 拿去花分期（占用授信额度，关联订单）
CREATE TABLE IF NOT EXISTS fin_loan (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  loan_no VARCHAR(32) NOT NULL UNIQUE,
  kind VARCHAR(16) NOT NULL,
  order_no VARCHAR(32), title VARCHAR(128),
  principal DECIMAL(12,2) NOT NULL,
  months INT NOT NULL, rate DECIMAL(5,2) NOT NULL,
  monthly DECIMAL(12,2) NOT NULL,
  paid_months INT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT '还款中',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_loan_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 还款计划：等额本息逐期拆分；状态 待还/逾期/已还，逾期由总览查询按 due_date 刷新
CREATE TABLE IF NOT EXISTS fin_repay (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  loan_id BIGINT NOT NULL, seq INT NOT NULL,
  due_date DATE NOT NULL,
  amount DECIMAL(12,2) NOT NULL,
  principal DECIMAL(12,2) NOT NULL,
  interest DECIMAL(12,2) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT '待还',
  paid_time DATETIME,
  KEY idx_repay_loan (loan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 保单：方案 basic/plus/family，保障期 [start_date, end_date]，状态 保障中/已过期/已退保
CREATE TABLE IF NOT EXISTS fin_policy (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  policy_no VARCHAR(32) NOT NULL UNIQUE,
  plan VARCHAR(16) NOT NULL, plan_name VARCHAR(32) NOT NULL,
  price DECIMAL(12,2) NOT NULL,
  insured VARCHAR(32) NOT NULL, destination VARCHAR(32) NOT NULL,
  start_date DATE NOT NULL, end_date DATE NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT '保障中',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_policy_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 理赔：按保单+类型累计赔付不超过方案限额；审核通过即赔付入钱包
CREATE TABLE IF NOT EXISTS fin_claim (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  policy_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
  claim_no VARCHAR(32) NOT NULL UNIQUE,
  type VARCHAR(16) NOT NULL,
  amount DECIMAL(12,2) NOT NULL, payout DECIMAL(12,2) NOT NULL,
  reason VARCHAR(255),
  status VARCHAR(16) NOT NULL DEFAULT '审核中',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  review_time DATETIME,
  KEY idx_claim_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 钱包流水：每笔资金变动记一行，balance_after 为变动后余额，账单页直接展示
CREATE TABLE IF NOT EXISTS fin_tx (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  kind VARCHAR(16) NOT NULL,
  amount DECIMAL(12,2) NOT NULL,
  balance_after DECIMAL(12,2) NOT NULL,
  memo VARCHAR(128), ref_no VARCHAR(32),
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_tx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;