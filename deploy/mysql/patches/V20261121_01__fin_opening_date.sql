-- Version V20261121_01: manual, single-purpose MySQL8 incremental migration.
-- Select the intended application schema explicitly and stop on every error.
-- Adds ONLY fin_opening.opening_date DATE NULL DEFAULT NULL. NEVER backfill NULL.
-- Serial execution, dedicated connection, MySQL >= 8.0.12 / InnoDB / INSTANT required.
-- New databases use init DDL; NEVER replay init against an existing database.
-- On CALL failure inspect this reserved routine before manually cleaning/retrying it.
SET NAMES utf8mb4;

DELIMITER $$
CREATE PROCEDURE `erp_patch_v20261121_01_opening_date`()
SQL SECURITY INVOKER
BEGIN
    DECLARE v_tables INT DEFAULT 0;
    DECLARE v_identity INT DEFAULT 0;
    DECLARE v_keys INT DEFAULT 0;
    DECLARE v_columns INT DEFAULT 0;
    DECLARE v_compatible INT DEFAULT 0;
    DECLARE v_previous_lock_wait BIGINT UNSIGNED DEFAULT 0;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        SET SESSION lock_wait_timeout = v_previous_lock_wait;
        RESIGNAL;
    END;

    SET v_previous_lock_wait = @@SESSION.lock_wait_timeout;
    SET SESSION lock_wait_timeout = 10;
    IF VERSION() NOT LIKE '8.%' OR VERSION() LIKE '%MariaDB%'
       OR (SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(), '.', 2), '.', -1) = '0'
           AND CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(), '.', 3), '.', -1) AS UNSIGNED) < 12) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '[V20261121_01] MySQL >= 8.0.12 required';
    END IF;
    IF DATABASE() IS NULL OR DATABASE() IN ('mysql', 'information_schema', 'performance_schema', 'sys') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '[V20261121_01] explicitly select the application schema';
    END IF;

    SELECT COUNT(*) INTO v_tables FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fin_opening'
       AND TABLE_TYPE = 'BASE TABLE' AND ENGINE = 'InnoDB';
    IF v_tables <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '[V20261121_01] expected existing InnoDB fin_opening';
    END IF;

    -- Identify the expected table, not merely another object with the same name.
    -- BIGINT display width is immaterial on MySQL8; unsigned/zerofill are NOT compatible.
    SELECT COUNT(*) INTO v_identity FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fin_opening'
       AND COALESCE(GENERATION_EXPRESSION, '') = ''
       AND IS_NULLABLE = 'NO'
       AND ((COLUMN_NAME = 'id' AND DATA_TYPE = 'bigint' AND COLUMN_KEY = 'PRI'
             AND EXTRA = 'auto_increment' AND LOCATE('unsigned', LOWER(COLUMN_TYPE)) = 0
             AND LOCATE('zerofill', LOWER(COLUMN_TYPE)) = 0)
         OR (COLUMN_NAME = 'tenant_id' AND DATA_TYPE = 'varchar' AND CHARACTER_MAXIMUM_LENGTH = 20 AND EXTRA = '')
         OR (COLUMN_NAME = 'party_type' AND DATA_TYPE = 'varchar' AND CHARACTER_MAXIMUM_LENGTH = 8 AND EXTRA = '')
         OR (COLUMN_NAME = 'party_id' AND DATA_TYPE = 'bigint' AND EXTRA = ''
             AND LOCATE('unsigned', LOWER(COLUMN_TYPE)) = 0 AND LOCATE('zerofill', LOWER(COLUMN_TYPE)) = 0)
         OR (COLUMN_NAME = 'amount' AND DATA_TYPE = 'decimal' AND NUMERIC_PRECISION = 18 AND NUMERIC_SCALE = 2
             AND LOCATE('unsigned', LOWER(COLUMN_TYPE)) = 0 AND LOCATE('zerofill', LOWER(COLUMN_TYPE)) = 0 AND EXTRA = '')
         OR (COLUMN_NAME = 'status' AND DATA_TYPE = 'varchar' AND CHARACTER_MAXIMUM_LENGTH = 16 AND EXTRA = '')
         OR (COLUMN_NAME = 'del_flag' AND DATA_TYPE = 'char' AND CHARACTER_MAXIMUM_LENGTH = 1 AND EXTRA = ''));
    IF v_identity <> 7 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '[V20261121_01] incompatible fin_opening identity; no changes';
    END IF;
    SELECT COUNT(*) INTO v_keys FROM (
        SELECT INDEX_NAME FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fin_opening'
           AND NON_UNIQUE = 0 AND IS_VISIBLE = 'YES'
         GROUP BY INDEX_NAME
        HAVING GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'tenant_id,party_type,party_id'
           AND COUNT(*) = 3 AND SUM(SUB_PART IS NOT NULL) = 0
    ) AS opening_keys;
    IF v_keys < 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '[V20261121_01] expected full unique tenant/party key';
    END IF;

    SELECT COUNT(*), COALESCE(SUM(DATA_TYPE = 'date' AND LOWER(COLUMN_TYPE) = 'date'
               AND IS_NULLABLE = 'YES' AND COLUMN_DEFAULT IS NULL
               AND COALESCE(GENERATION_EXPRESSION, '') = '' AND EXTRA = ''), 0)
      INTO v_columns, v_compatible FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fin_opening' AND COLUMN_NAME = 'opening_date';
    IF v_columns > 0 AND v_compatible <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '[V20261121_01] incompatible opening_date; no changes';
    END IF;
    IF v_columns = 0 THEN
        ALTER TABLE `fin_opening`
            ADD COLUMN `opening_date` DATE NULL DEFAULT NULL
                COMMENT '期初截止日（含当日），确认时作为台账 biz_date',
            ALGORITHM=INSTANT;
    END IF;
    SET SESSION lock_wait_timeout = v_previous_lock_wait;
END$$
DELIMITER ;

CALL `erp_patch_v20261121_01_opening_date`();
DROP PROCEDURE `erp_patch_v20261121_01_opening_date`;
