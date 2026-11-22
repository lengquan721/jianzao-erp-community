-- ------------------------------------------------------------
-- 打印模板可视化设计器：组件树 JSON 列
-- 用途：有 schema_json 的模板走可视化设计器编辑，为空的走 HTML 源码模式
-- ------------------------------------------------------------

-- 已部署环境升级：MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，用存储过程判断
DROP PROCEDURE IF EXISTS add_schema_json_if_absent;
DELIMITER $$
CREATE PROCEDURE add_schema_json_if_absent()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'print_template'
      AND COLUMN_NAME = 'schema_json'
  ) THEN
    ALTER TABLE print_template
      ADD COLUMN schema_json LONGTEXT COMMENT '设计器组件树 JSON，空表示源码模式'
      AFTER content;
  END IF;
END$$
DELIMITER ;

CALL add_schema_json_if_absent();
DROP PROCEDURE add_schema_json_if_absent;
