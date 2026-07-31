ALTER TABLE `ai_token_usage_period`
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (`app_user_id`, `period_start`, `period_end`);

ALTER TABLE `ai_token_usage_ledger`
    ADD COLUMN `quota_period_start` datetime(6) NULL
        COMMENT 'Exact inclusive quota window start reserved by this call' AFTER `reserved_tokens`,
    ADD COLUMN `quota_period_end` datetime(6) NULL
        COMMENT 'Exact exclusive quota window end reserved by this call' AFTER `quota_period_start`,
    ADD KEY `ai_token_usage_ledger_stale_reservation_idx` (`status`, `reserved_at`);
