-- Version: V20261120_01 (single-purpose, manual MySQL8 incremental patch).
-- Adds only biz_sale_return.refund_account_id; NEVER backfills historical rows.
-- Select the intended schema explicitly; use a dedicated connection and stop on errors.
-- Requires MySQL >= 8.0.12 / InnoDB / INSTANT ADD COLUMN support; no COPY fallback.
-- Run serially. The routine name below is reserved to this patch, not overwritten.
-- On CALL failure the routine can remain: inspect the error/ownership, then see README.md.
SET NAMES utf8mb4;

DELIMITER $$
CREATE PROCEDURE `erp_patch_v20261120_01_refund_account`()
SQL SECURITY INVOKER
BEGIN
    DECLARE v_table_count INT DEFAULT 0;
    DECLARE v_column_count INT DEFAULT 0;
    DECLARE v_compatible_count INT DEFAULT 0;
    DECLARE v_previous_lock_wait BIGINT UNSIGNED DEFAULT 0;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        SET SESSION lock_wait_timeout = v_previous_lock_wait;
        RESIGNAL;
    END;

    SET v_previous_lock_wait = @@SESSION.lock_wait_timeout;
    SET SESSION lock_wait_timeout = 10;

    IF VERSION() NOT LIKE '8.%' OR VERSION() LIKE '%MariaDB%' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '[V20261120_01] MySQL8 is required';
    END IF;

    SELECT COUNT(*) INTO v_table_count
      FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'biz_sale_return'
       AND TABLE_TYPE = 'BASE TABLE'
       AND ENGINE = 'InnoDB';
    IF v_table_count <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '[V20261120_01] expected existing InnoDB biz_sale_return';
    END IF;

    SELECT COUNT(*), COALESCE(SUM(
               DATA_TYPE = 'bigint'
               AND LOCATE('unsigned', LOWER(COLUMN_TYPE)) = 0
               AND LOCATE('zerofill', LOWER(COLUMN_TYPE)) = 0
               AND IS_NULLABLE = 'YES'
               AND COLUMN_DEFAULT IS NULL
               AND COALESCE(GENERATION_EXPRESSION, '') = ''
               AND EXTRA = ''
           ), 0)
      INTO v_column_count, v_compatible_count
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'biz_sale_return'
       AND COLUMN_NAME = 'refund_account_id';

    IF v_column_count > 0 AND v_compatible_count <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '[V20261120_01] incompatible refund_account_id; no changes made';
    END IF;

    IF v_column_count = 0 THEN
        ALTER TABLE `biz_sale_return`
            ADD COLUMN `refund_account_id` BIGINT NULL DEFAULT NULL
                COMMENT '草稿及确认时选定的退款结算账户',
            ALGORITHM=INSTANT;
    END IF;

    SET SESSION lock_wait_timeout = v_previous_lock_wait;
END$$
DELIMITER ;

CALL `erp_patch_v20261120_01_refund_account`();
DROP PROCEDURE `erp_patch_v20261120_01_refund_account`;
