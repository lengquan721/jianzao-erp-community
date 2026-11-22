-- ============================================================
-- 简造ERP社区版 · 业务表结构
-- 依赖：先执行 01-ruoyi-system.sql（RuoYi-Vue-Plus 系统表）
-- 约定：
--   tenant_id VARCHAR(20) DEFAULT '000000'（沿用 RuoYi 租户体系）
--   审计字段：create_dept/create_by/create_time/update_by/update_time/del_flag
--   金额 DECIMAL(18,2)，数量 DECIMAL(18,3)，单价/成本 DECIMAL(18,4)
-- ============================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 主数据
-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS md_product (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  code VARCHAR(32) NOT NULL COMMENT '编码',
  name VARCHAR(100) NOT NULL COMMENT '名称',
  spec VARCHAR(100) DEFAULT '' COMMENT '规格',
  unit VARCHAR(16) NOT NULL DEFAULT '' COMMENT '单位',
  type VARCHAR(16) NOT NULL DEFAULT 'MATERIAL' COMMENT 'PRODUCT=成品 MATERIAL=原料',
  category_id BIGINT NULL COMMENT '商品分类ID（NULL=未分类）',
  supplier_id BIGINT NULL COMMENT '所属供应商ID（NULL=未指定）',
  qty_scale TINYINT NOT NULL DEFAULT 0 COMMENT '数量精度位数 0~3（0=整数）',
  cost_price DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '当前参考成本单价（含分摊运杂费）',
  purchase_price DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '默认采购单价（原料）',
  tax_type VARCHAR(20) NOT NULL DEFAULT 'NO_TAX' COMMENT 'NO_TAX不含税/VAT_INVOICE增值税发票/GENERAL_INVOICE普通发票',
  freight DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '默认运杂费（元/单位，手工分摊时使用）',
  min_stock DECIMAL(18,3) NULL COMMENT '库存下限（NULL=不启用预警）',
  max_stock DECIMAL(18,3) NULL COMMENT '库存上限（NULL=不启用预警）',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  remark VARCHAR(500) DEFAULT '' COMMENT '备注',
  create_dept BIGINT DEFAULT NULL COMMENT '创建部门',
  create_by BIGINT DEFAULT NULL COMMENT '创建人',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_by BIGINT DEFAULT NULL COMMENT '更新人',
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  del_flag CHAR(1) NOT NULL DEFAULT '0' COMMENT '删除标志 0存在 1删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_md_product_code (tenant_id, code),
  KEY idx_md_product_name (tenant_id, name),
  KEY idx_md_product_type (tenant_id, type),
  KEY idx_md_product_category (tenant_id, category_id),
  KEY idx_md_product_supplier (tenant_id, supplier_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品/原料';

CREATE TABLE IF NOT EXISTS md_product_category (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  code VARCHAR(32) NOT NULL COMMENT '分类编码',
  name VARCHAR(64) NOT NULL COMMENT '分类名称',
  sort INT NOT NULL DEFAULT 0 COMMENT '排序（升序）',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  remark VARCHAR(255) DEFAULT '' COMMENT '备注',
  create_dept BIGINT DEFAULT NULL COMMENT '创建部门',
  create_by BIGINT DEFAULT NULL COMMENT '创建人',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_by BIGINT DEFAULT NULL COMMENT '更新人',
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  del_flag CHAR(1) NOT NULL DEFAULT '0' COMMENT '删除标志 0存在 1删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_md_product_category_code (tenant_id, code),
  KEY idx_md_product_category_name (tenant_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品分类';

CREATE TABLE IF NOT EXISTS md_product_recipe (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  product_id BIGINT NOT NULL COMMENT '成品ID',
  material_id BIGINT NOT NULL COMMENT '原料ID',
  qty_per_unit DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '单位成品用量',
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  UNIQUE KEY uk_recipe (tenant_id, product_id, material_id),
  KEY idx_recipe_material (tenant_id, material_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单层配比';

CREATE TABLE IF NOT EXISTS md_supplier (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  code VARCHAR(32) NOT NULL COMMENT '编码',
  name VARCHAR(100) NOT NULL COMMENT '名称',
  contact VARCHAR(50) DEFAULT '' COMMENT '联系人',
  phone VARCHAR(30) DEFAULT '' COMMENT '电话',
  address VARCHAR(200) DEFAULT '' COMMENT '地址',
  opening_payable DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '期初应付',
  tax_no VARCHAR(50) DEFAULT '' COMMENT '税号',
  tax_rate DECIMAL(8,4) NOT NULL DEFAULT 0 COMMENT '默认税率%',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  remark VARCHAR(500) DEFAULT '',
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  UNIQUE KEY uk_md_supplier_code (tenant_id, code),
  KEY idx_md_supplier_name (tenant_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商';

CREATE TABLE IF NOT EXISTS md_customer (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  code VARCHAR(32) NOT NULL COMMENT '编码',
  name VARCHAR(100) NOT NULL COMMENT '名称',
  contact VARCHAR(50) DEFAULT '' COMMENT '联系人',
  phone VARCHAR(30) DEFAULT '' COMMENT '电话',
  address VARCHAR(200) DEFAULT '' COMMENT '地址',
  opening_debt DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '期初欠款（期初应收）',
  tax_no VARCHAR(50) DEFAULT '' COMMENT '税号',
  tax_rate DECIMAL(8,4) NOT NULL DEFAULT 0 COMMENT '默认税率%',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  remark VARCHAR(500) DEFAULT '',
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  UNIQUE KEY uk_md_customer_code (tenant_id, code),
  KEY idx_md_customer_name (tenant_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户';

CREATE TABLE IF NOT EXISTS md_warehouse (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  code VARCHAR(32) NOT NULL COMMENT '编码',
  name VARCHAR(100) NOT NULL COMMENT '名称',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  remark VARCHAR(500) DEFAULT '',
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  UNIQUE KEY uk_md_warehouse_code (tenant_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库';

-- ------------------------------------------------------------
-- 库存
-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS st_stock (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  product_id BIGINT NOT NULL COMMENT '产品ID',
  warehouse_id BIGINT NOT NULL COMMENT '仓库ID',
  qty DECIMAL(18,3) NOT NULL DEFAULT 0 COMMENT '当前数量',
  avg_cost DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '移动加权平均成本',
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  UNIQUE KEY uk_st_stock (tenant_id, product_id, warehouse_id),
  KEY idx_st_stock_warehouse (tenant_id, warehouse_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存快照';CREATE TABLE IF NOT EXISTS st_stock_log (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  biz_type VARCHAR(20) NOT NULL COMMENT 'OPENING/PURCHASE/PROD_IN/PROD_OUT/SALE/OTHER_OUT/OTHER_IN/CHECK/SALE_RETURN/PURCHASE_RETURN/QUICK_IN/VOID',
  biz_no VARCHAR(32) NOT NULL DEFAULT '' COMMENT '单据号',
  biz_id BIGINT DEFAULT NULL COMMENT '单据ID',
  product_id BIGINT NOT NULL COMMENT '产品ID',
  warehouse_id BIGINT NOT NULL COMMENT '仓库ID',
  qty DECIMAL(18,3) NOT NULL DEFAULT 0 COMMENT '有符号数量：入正出负',
  unit_cost DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '单位成本',
  amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '金额',
  partner_type VARCHAR(16) DEFAULT 'NONE' COMMENT 'SUPPLIER/CUSTOMER/NONE',
  partner_id BIGINT DEFAULT NULL COMMENT '供应商/客户ID',
  balance_after DECIMAL(18,3) DEFAULT NULL COMMENT '变动后结存',
  biz_date DATE NOT NULL COMMENT '业务日期',
  seq INT NOT NULL DEFAULT 0 COMMENT '同单据内顺序',
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  KEY idx_stock_log_product (tenant_id, product_id, biz_date),
  KEY idx_stock_log_biz (tenant_id, biz_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存流水';

CREATE TABLE IF NOT EXISTS st_opening (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  product_id BIGINT NOT NULL COMMENT '产品ID',
  warehouse_id BIGINT NOT NULL COMMENT '仓库ID',
  qty DECIMAL(18,3) NOT NULL DEFAULT 0 COMMENT '期初数量',
  unit_cost DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '期初成本',
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CONFIRMED',
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  UNIQUE KEY uk_st_opening (tenant_id, product_id, warehouse_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='期初库存';

-- ------------------------------------------------------------
-- 采购 / 销售
-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS biz_purchase (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  no VARCHAR(32) NOT NULL COMMENT '单号',
  supplier_id BIGINT NOT NULL COMMENT '供应商ID',
  biz_date DATE NOT NULL COMMENT '业务日期',
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CONFIRMED/VOID',
  tax_type VARCHAR(20) NOT NULL DEFAULT 'VAT_INVOICE' COMMENT '发票类型 VAT_INVOICE含税/NO_TAX不含税',
  tax_rate DECIMAL(8,4) NOT NULL DEFAULT 13 COMMENT '税率%（整单统一）',
  goods_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '货品金额',
  freight_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '运杂费总额',
  freight_alloc_method VARCHAR(20) NOT NULL DEFAULT 'BY_QTY' COMMENT 'BY_AMOUNT/BY_QTY/MANUAL',
  total_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '应付合计=货品+运杂费',
  remark VARCHAR(500) DEFAULT '',
  confirmed_by BIGINT DEFAULT NULL,
  confirmed_at DATETIME DEFAULT NULL,
  voided_by BIGINT DEFAULT NULL,
  voided_at DATETIME DEFAULT NULL,
  withdrawn_by BIGINT DEFAULT NULL,
  withdrawn_at DATETIME DEFAULT NULL,
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  UNIQUE KEY uk_biz_purchase_no (tenant_id, no),
  KEY idx_biz_purchase_date (tenant_id, biz_date),
  KEY idx_biz_purchase_supplier (tenant_id, supplier_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购入库单';

CREATE TABLE IF NOT EXISTS biz_purchase_item (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  order_id BIGINT NOT NULL COMMENT '采购单ID',
  product_id BIGINT NOT NULL COMMENT '产品ID',
  qty DECIMAL(18,3) NOT NULL DEFAULT 0 COMMENT '数量',
  price DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '采购单价',
  tax_type VARCHAR(20) NOT NULL DEFAULT 'NO_TAX' COMMENT '发票类型快照',
  tax_rate DECIMAL(8,4) NOT NULL DEFAULT 0 COMMENT '税率%',
  amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '货品金额=数量×单价',
  freight_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '本行分摊运杂费',
  freight_per_unit DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '每件运杂费',
  cost_price DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '成本单价（含分摊运杂费）',
  remark VARCHAR(500) DEFAULT '',
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  KEY idx_purchase_item_order (tenant_id, order_id),
  KEY idx_purchase_item_product (tenant_id, product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购明细';

CREATE TABLE IF NOT EXISTS biz_sale (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  no VARCHAR(32) NOT NULL COMMENT '单号',
  customer_id BIGINT NOT NULL COMMENT '客户ID',
  biz_date DATE NOT NULL COMMENT '业务日期',
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CONFIRMED/VOID',
  tax_type VARCHAR(20) NOT NULL DEFAULT 'VAT_INVOICE' COMMENT '发票类型 VAT_INVOICE含税/NO_TAX不含税',
  tax_rate DECIMAL(8,4) NOT NULL DEFAULT 13 COMMENT '税率%（整单统一）',
  goods_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '货品金额（不含运杂费）',
  freight_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '运杂费总额',
  freight_alloc_method VARCHAR(20) NOT NULL DEFAULT 'BY_QTY' COMMENT '运杂费分摊方式 BY_QTY/BY_AMOUNT/MANUAL',
  total_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '销售金额',
  cost_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '成本金额快照',
  gross_profit DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '毛利=销售-成本',
  gross_margin DECIMAL(8,4) NOT NULL DEFAULT 0 COMMENT '毛利率%',
  remark VARCHAR(500) DEFAULT '',
  confirmed_by BIGINT DEFAULT NULL,
  confirmed_at DATETIME DEFAULT NULL,
  voided_by BIGINT DEFAULT NULL,
  voided_at DATETIME DEFAULT NULL,
  withdrawn_by BIGINT DEFAULT NULL,
  withdrawn_at DATETIME DEFAULT NULL,
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  UNIQUE KEY uk_biz_sale_no (tenant_id, no),
  KEY idx_biz_sale_date (tenant_id, biz_date),
  KEY idx_biz_sale_customer (tenant_id, customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售出库单';

CREATE TABLE IF NOT EXISTS biz_sale_item (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  order_id BIGINT NOT NULL COMMENT '销售单ID',
  product_id BIGINT NOT NULL COMMENT '产品ID',
  new_product_snapshot JSON NULL COMMENT '现录现卖商品快照（含入库数量，确认时建品）',
  qty DECIMAL(18,3) NOT NULL DEFAULT 0 COMMENT '数量',
  price DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '单价（一单一价）',
  tax_type VARCHAR(20) NOT NULL DEFAULT 'NO_TAX' COMMENT '发票类型快照',
  tax_rate DECIMAL(8,4) NOT NULL DEFAULT 13 COMMENT '税率%（继承表头快照）',
  amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '金额',
  unit_cost DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '成本单价快照（含分摊运杂费）',
  cost_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '成本金额',
  gross_profit DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '毛利',
  remark VARCHAR(500) DEFAULT '',
  seq INT NOT NULL DEFAULT 0 COMMENT '同单据内顺序',
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  KEY idx_sale_item_order (tenant_id, order_id),
  KEY idx_sale_item_product (tenant_id, product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售明细';

CREATE TABLE IF NOT EXISTS biz_sale_return (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  no VARCHAR(32) NOT NULL COMMENT '退货单号（XT前缀）',
  sale_id BIGINT NOT NULL COMMENT '原销售出库单ID',
  sale_no VARCHAR(32) NOT NULL COMMENT '原单号快照',
  customer_id BIGINT NOT NULL COMMENT '客户ID（=原单客户）',
  biz_date DATE NOT NULL COMMENT '业务日期',
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CONFIRMED/VOID',
  total_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '退货金额=Σ行(qty×price)',
  return_cost_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '成本回冲额=Σ行return_cost，确认时回填',
  need_refund TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否退款给客户',
  refund_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '退款金额',
  refund_account_id BIGINT DEFAULT NULL COMMENT '草稿及确认时选定的退款结算账户',
  refund_receipt_id BIGINT DEFAULT NULL COMMENT '联动红字收款单ID',
  remark VARCHAR(500) DEFAULT '',
  confirmed_by BIGINT DEFAULT NULL,
  confirmed_at DATETIME DEFAULT NULL,
  voided_by BIGINT DEFAULT NULL,
  voided_at DATETIME DEFAULT NULL,
  withdrawn_by BIGINT DEFAULT NULL,
  withdrawn_at DATETIME DEFAULT NULL,
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  UNIQUE KEY uk_biz_sale_return_no (tenant_id, no),
  KEY idx_sale_return_sale (tenant_id, sale_id),
  KEY idx_sale_return_customer (tenant_id, customer_id),
  KEY idx_sale_return_date (tenant_id, biz_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售退货单';

CREATE TABLE IF NOT EXISTS biz_sale_return_item (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  order_id BIGINT NOT NULL COMMENT '退货单头ID',
  sale_item_id BIGINT NOT NULL COMMENT '原销售明细行ID',
  product_id BIGINT NOT NULL COMMENT '产品ID',
  qty DECIMAL(18,3) NOT NULL DEFAULT 0 COMMENT '退货数量',
  price DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '退货单价',
  amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '金额=qty×price',
  unit_cost DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '成本快照=原行unit_cost，确认时回填',
  return_cost DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '成本回冲额=qty×unit_cost，确认时回填',
  remark VARCHAR(500) DEFAULT '',
  seq INT NOT NULL DEFAULT 0 COMMENT '同单据内顺序',
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  KEY idx_sale_return_item_order (tenant_id, order_id),
  KEY idx_sale_return_item_src (tenant_id, sale_item_id),
  KEY idx_sale_return_item_product (tenant_id, product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售退货明细';

CREATE TABLE IF NOT EXISTS biz_purchase_return (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  no VARCHAR(32) NOT NULL COMMENT '退货单号（CT前缀）',
  purchase_id BIGINT NOT NULL COMMENT '原采购入库单ID',
  purchase_no VARCHAR(32) NOT NULL COMMENT '原单号快照',
  supplier_id BIGINT NOT NULL COMMENT '供应商ID（=原单供应商）',
  biz_date DATE NOT NULL COMMENT '业务日期',
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CONFIRMED/VOID',
  total_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '退货金额=Σ行(qty×price)',
  return_cost_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '成本回冲额=Σ行return_cost，确认时回填',
  remark VARCHAR(500) DEFAULT '',
  confirmed_by BIGINT DEFAULT NULL,
  confirmed_at DATETIME DEFAULT NULL,
  voided_by BIGINT DEFAULT NULL,
  voided_at DATETIME DEFAULT NULL,
  withdrawn_by BIGINT DEFAULT NULL,
  withdrawn_at DATETIME DEFAULT NULL,
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  UNIQUE KEY uk_biz_purchase_return_no (tenant_id, no),
  KEY idx_purchase_return_purchase (tenant_id, purchase_id),
  KEY idx_purchase_return_supplier (tenant_id, supplier_id),
  KEY idx_purchase_return_date (tenant_id, biz_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购退货单';

CREATE TABLE IF NOT EXISTS biz_purchase_return_item (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  order_id BIGINT NOT NULL COMMENT '退货单头ID',
  purchase_item_id BIGINT NOT NULL COMMENT '原采购明细行ID',
  product_id BIGINT NOT NULL COMMENT '产品ID',
  qty DECIMAL(18,3) NOT NULL DEFAULT 0 COMMENT '退货数量',
  price DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '退货单价',
  amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '金额=qty×price',
  unit_cost DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '成本快照=原行cost_price，确认时回填',
  return_cost DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '成本回冲额=qty×unit_cost，确认时回填',
  remark VARCHAR(500) DEFAULT '',
  seq INT NOT NULL DEFAULT 0 COMMENT '同单据内顺序',
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  KEY idx_purchase_return_item_order (tenant_id, order_id),
  KEY idx_purchase_return_item_src (tenant_id, purchase_item_id),
  KEY idx_purchase_return_item_product (tenant_id, product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购退货明细';
CREATE TABLE IF NOT EXISTS fin_ar_ap (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  party_type VARCHAR(8) NOT NULL COMMENT 'AR=应收 AP=应付',
  party_id BIGINT NOT NULL COMMENT '客户/供应商ID',
  source_type VARCHAR(16) NOT NULL COMMENT 'SALE/PURCHASE/OPENING',
  source_id BIGINT DEFAULT NULL COMMENT '来源单据/期初ID',
  biz_date DATE NOT NULL COMMENT '业务日期',
  amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '应收/应付金额',
  settled_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '已核销金额',
  status VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/PARTIAL/SETTLED/VOID',
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  KEY idx_ar_ap_party (tenant_id, party_type, party_id, status),
  KEY idx_ar_ap_source (tenant_id, source_type, source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应收应付台账';

CREATE TABLE IF NOT EXISTS fin_account (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  code VARCHAR(32) NOT NULL COMMENT '账户编码',
  name VARCHAR(50) NOT NULL COMMENT '账户名称（≤50字符，与收付款/其他收支 account 列同宽，回填不截断）',
  type VARCHAR(16) NOT NULL DEFAULT 'CASH' COMMENT '类型 CASH现金/BANK银行/WECHAT微信/ALIPAY支付宝/OTHER其他',
  opening_balance DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '期初余额（动账发生前可修改）',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  remark VARCHAR(500) DEFAULT '' COMMENT '备注',
  create_dept BIGINT DEFAULT NULL COMMENT '创建部门',
  create_by BIGINT DEFAULT NULL COMMENT '创建人',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_by BIGINT DEFAULT NULL COMMENT '更新人',
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  del_flag CHAR(1) NOT NULL DEFAULT '0' COMMENT '删除标志 0存在 1删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_fin_account_code (tenant_id, code),
  KEY idx_fin_account_name (tenant_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='结算账户';

CREATE TABLE IF NOT EXISTS fin_receipt (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  no VARCHAR(32) NOT NULL COMMENT '收款单号',
  customer_id BIGINT NOT NULL COMMENT '客户ID',
  amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '收款金额',
  biz_date DATE NOT NULL COMMENT '收款日期',
  account VARCHAR(50) DEFAULT '' COMMENT '收款账户',
  account_id BIGINT NULL COMMENT '结算账户ID（NULL=旧单沿用 account 文本）',
  negative TINYINT(1) NOT NULL DEFAULT 0 COMMENT '红字标志：1=退款单，确认时对alloc台账做反核销（unsettle）而非核销',
  remark VARCHAR(500) DEFAULT '',
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CONFIRMED/VOID',
  confirmed_by BIGINT DEFAULT NULL,
  confirmed_at DATETIME DEFAULT NULL,
  withdrawn_by BIGINT DEFAULT NULL,
  withdrawn_at DATETIME DEFAULT NULL,
  voided_by BIGINT DEFAULT NULL,
  voided_at DATETIME DEFAULT NULL,
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  UNIQUE KEY uk_fin_receipt_no (tenant_id, no),
  KEY idx_fin_receipt_customer (tenant_id, customer_id, biz_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收款单';

CREATE TABLE IF NOT EXISTS fin_receipt_alloc (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  receipt_id BIGINT NOT NULL COMMENT '收款单ID',
  ar_ap_id BIGINT NOT NULL COMMENT '应收台账ID',
  amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '核销金额',
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  KEY idx_receipt_alloc_receipt (tenant_id, receipt_id),
  KEY idx_receipt_alloc_ar (tenant_id, ar_ap_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收款核销';

CREATE TABLE IF NOT EXISTS fin_payment (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  no VARCHAR(32) NOT NULL COMMENT '付款单号',
  supplier_id BIGINT NOT NULL COMMENT '供应商ID',
  amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '付款金额',
  biz_date DATE NOT NULL COMMENT '付款日期',
  account VARCHAR(50) DEFAULT '' COMMENT '付款账户',
  account_id BIGINT NULL COMMENT '结算账户ID（NULL=旧单沿用 account 文本）',
  remark VARCHAR(500) DEFAULT '',
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CONFIRMED/VOID',
  confirmed_by BIGINT DEFAULT NULL,
  confirmed_at DATETIME DEFAULT NULL,
  withdrawn_by BIGINT DEFAULT NULL,
  withdrawn_at DATETIME DEFAULT NULL,
  voided_by BIGINT DEFAULT NULL,
  voided_at DATETIME DEFAULT NULL,
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  UNIQUE KEY uk_fin_payment_no (tenant_id, no),
  KEY idx_fin_payment_supplier (tenant_id, supplier_id, biz_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='付款单';

CREATE TABLE IF NOT EXISTS fin_payment_alloc (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  payment_id BIGINT NOT NULL COMMENT '付款单ID',
  ar_ap_id BIGINT NOT NULL COMMENT '应付台账ID',
  amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '核销金额',
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  KEY idx_payment_alloc_payment (tenant_id, payment_id),
  KEY idx_payment_alloc_ar (tenant_id, ar_ap_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='付款核销';

CREATE TABLE IF NOT EXISTS fin_opening (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  party_type VARCHAR(8) NOT NULL COMMENT 'AR/AP',
  party_id BIGINT NOT NULL COMMENT '客户/供应商ID',
  amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '期初金额',
  -- 历史 NULL 保持未知；仅新建/编辑时由用户显式指定，不按上线时间回填。
  opening_date DATE DEFAULT NULL COMMENT '期初截止日（含当日），确认时作为台账 biz_date',
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CONFIRMED',
  remark VARCHAR(500) DEFAULT '',
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  UNIQUE KEY uk_fin_opening (tenant_id, party_type, party_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='期初往来';

CREATE TABLE IF NOT EXISTS fin_statement_log (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  customer_id BIGINT NOT NULL COMMENT '客户ID',
  month CHAR(7) NOT NULL COMMENT '账期 yyyy-MM',
  snapshot_json LONGTEXT COMMENT '对账快照JSON',
  pdf_path VARCHAR(300) DEFAULT '' COMMENT 'PDF路径',
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  KEY idx_statement_customer_month (tenant_id, customer_id, month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对账单快照';

-- ------------------------------------------------------------
-- 其他出库 / 其他收支 / 成本台账
-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS biz_other_out (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  no VARCHAR(32) NOT NULL COMMENT '单号',
  biz_date DATE NOT NULL COMMENT '业务日期',
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CONFIRMED/VOID',
  cost_category VARCHAR(30) NOT NULL DEFAULT 'PRODUCTION_CONSUMABLE' COMMENT '成本分类：PRODUCTION_CONSUMABLE/OFFICE/MAINTENANCE/OTHER',
  cost_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '出库成本合计',
  remark VARCHAR(500) DEFAULT '',
  confirmed_by BIGINT DEFAULT NULL,
  confirmed_at DATETIME DEFAULT NULL,
  withdrawn_by BIGINT DEFAULT NULL,
  withdrawn_at DATETIME DEFAULT NULL,
  voided_by BIGINT DEFAULT NULL,
  voided_at DATETIME DEFAULT NULL,
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  UNIQUE KEY uk_biz_other_out_no (tenant_id, no),
  KEY idx_other_out_date (tenant_id, biz_date),
  KEY idx_other_out_category (tenant_id, cost_category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='其他出库单';

CREATE TABLE IF NOT EXISTS biz_other_out_item (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  order_id BIGINT NOT NULL COMMENT '其他出库单ID',
  product_id BIGINT NOT NULL COMMENT '产品/消耗品ID',
  qty DECIMAL(18,3) NOT NULL DEFAULT 0 COMMENT '数量',
  unit_cost DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '成本单价快照',
  amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '成本金额',
  remark VARCHAR(500) DEFAULT '',
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  KEY idx_other_out_item_order (tenant_id, order_id),
  KEY idx_other_out_item_product (tenant_id, product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='其他出库明细';

CREATE TABLE IF NOT EXISTS biz_other_in (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  no VARCHAR(32) NOT NULL COMMENT '单号',
  biz_date DATE NOT NULL COMMENT '业务日期',
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CONFIRMED/VOID',
  reason VARCHAR(30) NOT NULL DEFAULT 'OTHER' COMMENT '入库原因：STOCKTAKING_IN/REWORK_IN/GIFT_IN/TRANSFER_IN/RETURN_IN/OTHER',
  amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '入库货值合计',
  remark VARCHAR(500) DEFAULT '',
  confirmed_by BIGINT DEFAULT NULL,
  confirmed_at DATETIME DEFAULT NULL,
  withdrawn_by BIGINT DEFAULT NULL,
  withdrawn_at DATETIME DEFAULT NULL,
  voided_by BIGINT DEFAULT NULL,
  voided_at DATETIME DEFAULT NULL,
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  UNIQUE KEY uk_biz_other_in_no (tenant_id, no),
  KEY idx_other_in_date (tenant_id, biz_date),
  KEY idx_other_in_reason (tenant_id, reason)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='其他入库单';

CREATE TABLE IF NOT EXISTS biz_other_in_item (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  order_id BIGINT NOT NULL COMMENT '其他入库单ID',
  product_id BIGINT NOT NULL COMMENT '产品ID',
  qty DECIMAL(18,3) NOT NULL DEFAULT 0 COMMENT '数量',
  unit_cost DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '录入入库单价（确认时原样入账）',
  amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '金额',
  remark VARCHAR(500) DEFAULT '',
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  KEY idx_other_in_item_order (tenant_id, order_id),
  KEY idx_other_in_item_product (tenant_id, product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='其他入库明细';

CREATE TABLE IF NOT EXISTS fin_other_flow (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  no VARCHAR(32) NOT NULL COMMENT '单号',
  flow_type VARCHAR(16) NOT NULL COMMENT 'INCOME=其他收入 EXPENSE=其他支出',
  category VARCHAR(30) NOT NULL DEFAULT 'OTHER' COMMENT '分类',
  amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '金额',
  biz_date DATE NOT NULL COMMENT '业务日期',
  account VARCHAR(50) DEFAULT '' COMMENT '账户',
  account_id BIGINT NULL COMMENT '结算账户ID（NULL=旧单沿用 account 文本）',
  affect_cost TINYINT NOT NULL DEFAULT 0 COMMENT '是否计入综合成本（支出）',
  remark VARCHAR(500) DEFAULT '',
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CONFIRMED/VOID',
  confirmed_by BIGINT DEFAULT NULL,
  confirmed_at DATETIME DEFAULT NULL,
  withdrawn_by BIGINT DEFAULT NULL,
  withdrawn_at DATETIME DEFAULT NULL,
  voided_by BIGINT DEFAULT NULL,
  voided_at DATETIME DEFAULT NULL,
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  UNIQUE KEY uk_fin_other_flow_no (tenant_id, no),
  KEY idx_other_flow_type_date (tenant_id, flow_type, biz_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='其他收支单';

CREATE TABLE IF NOT EXISTS fin_cost_ledger (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  cost_type VARCHAR(30) NOT NULL COMMENT 'PRODUCTION_MATERIAL/OTHER_OUT/OTHER_EXPENSE',
  source_type VARCHAR(20) NOT NULL COMMENT 'PRODUCTION/OTHER_OUT/OTHER_FLOW',
  source_id BIGINT DEFAULT NULL COMMENT '来源ID',
  source_no VARCHAR(32) DEFAULT '' COMMENT '来源单号',
  biz_date DATE NOT NULL COMMENT '业务日期',
  amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '成本金额',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/REVERSED',
  remark VARCHAR(500) DEFAULT '',
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  KEY idx_cost_ledger_date (tenant_id, biz_date, cost_type),
  KEY idx_cost_ledger_source (tenant_id, source_type, source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='成本台账（综合成本）';

-- ------------------------------------------------------------
-- 打印
-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS print_template (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  code VARCHAR(50) NOT NULL COMMENT '模板编码',
  name VARCHAR(100) NOT NULL COMMENT '模板名称',
  scene VARCHAR(30) NOT NULL COMMENT 'SALE_DELIVERY/STATEMENT/STATEMENT_RANGE/PURCHASE_IN/RECEIPT/PAYMENT',
  customer_id BIGINT DEFAULT NULL COMMENT '客户专属模板，可空',
  engine VARCHAR(20) NOT NULL DEFAULT 'THYMELEAF' COMMENT '渲染引擎',
  variables_json TEXT COMMENT '变量说明',
  content LONGTEXT COMMENT 'HTML模板',
  schema_json LONGTEXT COMMENT '设计器组件树 JSON，空表示源码模式',
  paper VARCHAR(20) NOT NULL DEFAULT 'A4' COMMENT 'A4/A5/241x140',
  version INT NOT NULL DEFAULT 1 COMMENT '版本号',
  is_default TINYINT NOT NULL DEFAULT 0 COMMENT '是否默认',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  UNIQUE KEY uk_print_template_code (tenant_id, code),
  KEY idx_print_template_scene (tenant_id, scene, customer_id, is_default)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='打印模板';

-- ------------------------------------------------------------
-- 单据编号序列
-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS sys_sequence (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  biz_type VARCHAR(10) NOT NULL COMMENT 'CG/SC/XS/SK/FK/QT/SR/ZC/PD/XT/CT',
  seq_date CHAR(8) NOT NULL COMMENT 'yyyyMMdd',
  current_value INT NOT NULL DEFAULT 0 COMMENT '当前流水',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sys_sequence (tenant_id, biz_type, seq_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单号序列';

-- ------------------------------------------------------------
-- 订单层：采购订单 / 销售订单 / 变更留痕（DOC/31 §5.7）
-- 订单只承载承诺：不写库存、不生成应收应付；biz_purchase / biz_sale 仍为执行单
-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS biz_purchase_order (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  no VARCHAR(32) NOT NULL COMMENT '订单号 CGD+yyyyMMdd+4位流水',
  supplier_id BIGINT NOT NULL COMMENT '供应商ID',
  biz_date DATE NOT NULL COMMENT '下单日期',
  due_date DATE DEFAULT NULL COMMENT '派生：最早未交行交期；全部交完/关闭时取全部行的最早交期（不置空）',
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CONFIRMED/VOID',
  exec_status VARCHAR(16) NOT NULL DEFAULT 'NOT_STARTED' COMMENT 'NOT_STARTED/PARTIAL/FULL/CLOSED',
  supplier_order_no VARCHAR(64) NOT NULL DEFAULT '' COMMENT '供应商单号（对方单号）',
  buyer_id BIGINT DEFAULT NULL COMMENT '跟单人 sys_user.id',
  buyer_name VARCHAR(50) NOT NULL DEFAULT '' COMMENT '跟单人姓名快照',
  contact_name VARCHAR(64) NOT NULL DEFAULT '' COMMENT '供应商联系人（转单时快照到执行单）',
  contact_phone VARCHAR(32) NOT NULL DEFAULT '' COMMENT '供应商联系电话（转单时快照到执行单）',
  allow_delay_days INT NOT NULL DEFAULT 0 COMMENT '允许延迟天数，逾期=今天>交期+N',
  over_tol_pct DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '超收容差%，允许累计入库<=订单量*(1+pct/100)',
  tax_type VARCHAR(20) NOT NULL DEFAULT 'VAT_INVOICE' COMMENT '发票类型 VAT_INVOICE含税/NO_TAX不含税',
  tax_rate DECIMAL(8,4) NOT NULL DEFAULT 13 COMMENT '税率%（整单统一）',
  goods_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '订单金额（不含运杂费）=Σ行amount；不叫total_amount，避免与执行单含运费口径混用',
  remark VARCHAR(500) DEFAULT '' COMMENT '备注',
  confirmed_by BIGINT DEFAULT NULL,
  confirmed_at DATETIME DEFAULT NULL,
  withdrawn_by BIGINT DEFAULT NULL,
  withdrawn_at DATETIME DEFAULT NULL,
  voided_by BIGINT DEFAULT NULL,
  voided_at DATETIME DEFAULT NULL,
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  UNIQUE KEY uk_biz_purchase_order_no (tenant_id, no),
  KEY idx_biz_po_status (tenant_id, status, exec_status, due_date),
  KEY idx_biz_po_supplier (tenant_id, supplier_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购订单';

CREATE TABLE IF NOT EXISTS biz_purchase_order_item (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  order_id BIGINT NOT NULL COMMENT '采购订单ID',
  seq INT NOT NULL DEFAULT 0 COMMENT '同单内顺序（0起，与biz_purchase_item/biz_sale_item一致）',
  product_id BIGINT NOT NULL COMMENT '产品ID',
  qty DECIMAL(18,3) NOT NULL DEFAULT 0 COMMENT '订单量',
  price DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '采购单价',
  tax_type VARCHAR(20) NOT NULL DEFAULT 'NO_TAX' COMMENT '发票类型快照',
  tax_rate DECIMAL(8,4) NOT NULL DEFAULT 0 COMMENT '税率%快照',
  amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '金额=数量×单价',
  due_date DATE NOT NULL COMMENT '约定交期（我方向供应商要求的到货日）',
  promised_date DATE DEFAULT NULL COMMENT '供应商回签承诺交期（不参与逾期判定）',
  executed_qty DECIMAL(18,3) NOT NULL DEFAULT 0 COMMENT '累计已执行净额（重算覆盖写）',
  closed_qty DECIMAL(18,3) NOT NULL DEFAULT 0 COMMENT '手工关闭量（关闭时冻结，不变式：closed_flag=1时remain=0）',
  closed_flag TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1=已关闭剩余',
  close_reason VARCHAR(200) DEFAULT '' COMMENT '关闭原因',
  closed_by BIGINT DEFAULT NULL,
  closed_at DATETIME DEFAULT NULL,
  remark VARCHAR(500) DEFAULT '',
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  KEY idx_biz_poi_order (tenant_id, order_id, seq),
  KEY idx_biz_poi_product (tenant_id, product_id),
  KEY idx_biz_poi_due (tenant_id, closed_flag, due_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购订单明细';

CREATE TABLE IF NOT EXISTS biz_sale_order (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  no VARCHAR(32) NOT NULL COMMENT '订单号 XSD+yyyyMMdd+4位流水',
  customer_id BIGINT NOT NULL COMMENT '客户ID',
  biz_date DATE NOT NULL COMMENT '下单日期',
  due_date DATE DEFAULT NULL COMMENT '派生：最早未交行交期；全部交完/关闭时取全部行的最早交期（不置空）',
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CONFIRMED/VOID',
  exec_status VARCHAR(16) NOT NULL DEFAULT 'NOT_STARTED' COMMENT 'NOT_STARTED/PARTIAL/FULL/CLOSED',
  customer_order_no VARCHAR(64) NOT NULL DEFAULT '' COMMENT '客户单号（对方单号）',
  salesperson_id BIGINT DEFAULT NULL COMMENT '跟单人 sys_user.id',
  salesperson_name VARCHAR(50) NOT NULL DEFAULT '' COMMENT '跟单人姓名快照',
  contact_name VARCHAR(64) NOT NULL DEFAULT '' COMMENT '客户联系人（转单时快照到执行单）',
  contact_phone VARCHAR(32) NOT NULL DEFAULT '' COMMENT '客户联系电话（转单时快照到执行单）',
  delivery_address VARCHAR(255) NOT NULL DEFAULT '' COMMENT '送货地址（转单时快照到执行单）',
  allow_delay_days INT NOT NULL DEFAULT 0 COMMENT '允许延迟天数，逾期=今天>交期+N',
  over_tol_pct DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '超发容差%，M1前端只读、默认0（D25）',
  tax_type VARCHAR(20) NOT NULL DEFAULT 'VAT_INVOICE' COMMENT '发票类型 VAT_INVOICE含税/NO_TAX不含税',
  tax_rate DECIMAL(8,4) NOT NULL DEFAULT 13 COMMENT '税率%（整单统一）',
  goods_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '订单金额（不含运杂费）=Σ行amount',
  remark VARCHAR(500) DEFAULT '' COMMENT '备注',
  confirmed_by BIGINT DEFAULT NULL,
  confirmed_at DATETIME DEFAULT NULL,
  withdrawn_by BIGINT DEFAULT NULL,
  withdrawn_at DATETIME DEFAULT NULL,
  voided_by BIGINT DEFAULT NULL,
  voided_at DATETIME DEFAULT NULL,
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  UNIQUE KEY uk_biz_sale_order_no (tenant_id, no),
  KEY idx_biz_so_status (tenant_id, status, exec_status, due_date),
  KEY idx_biz_so_customer (tenant_id, customer_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售订单';

CREATE TABLE IF NOT EXISTS biz_sale_order_item (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  order_id BIGINT NOT NULL COMMENT '销售订单ID',
  seq INT NOT NULL DEFAULT 0 COMMENT '同单内顺序（0起）',
  product_id BIGINT NOT NULL COMMENT '产品ID（订单行不支持现录现卖，见§1.2）',
  qty DECIMAL(18,3) NOT NULL DEFAULT 0 COMMENT '订单量',
  price DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '销售单价',
  tax_type VARCHAR(20) NOT NULL DEFAULT 'NO_TAX' COMMENT '发票类型快照',
  tax_rate DECIMAL(8,4) NOT NULL DEFAULT 0 COMMENT '税率%快照',
  amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '金额=数量×单价',
  due_date DATE NOT NULL COMMENT '约定交期（下游客户要求的到货日）',
  promised_date DATE DEFAULT NULL COMMENT '我方答复客户的承诺交期（不参与逾期判定）',
  executed_qty DECIMAL(18,3) NOT NULL DEFAULT 0 COMMENT '累计已执行净额（重算覆盖写）',
  closed_qty DECIMAL(18,3) NOT NULL DEFAULT 0 COMMENT '手工关闭量（冻结，不变式：closed_flag=1时remain=0）',
  closed_flag TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1=已关闭剩余',
  close_reason VARCHAR(200) DEFAULT '' COMMENT '关闭原因',
  closed_by BIGINT DEFAULT NULL,
  closed_at DATETIME DEFAULT NULL,
  remark VARCHAR(500) DEFAULT '',
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  KEY idx_biz_soi_order (tenant_id, order_id, seq),
  KEY idx_biz_soi_product (tenant_id, product_id),
  KEY idx_biz_soi_due (tenant_id, closed_flag, due_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售订单明细';

CREATE TABLE IF NOT EXISTS biz_order_change_log (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户编号',
  order_type VARCHAR(16) NOT NULL COMMENT 'PURCHASE/SALE',
  order_id BIGINT NOT NULL COMMENT '订单头ID',
  order_no VARCHAR(32) NOT NULL DEFAULT '' COMMENT '订单号快照',
  item_id BIGINT DEFAULT NULL COMMENT '订单行ID，头级变更为空',
  field_key VARCHAR(32) NOT NULL COMMENT 'qty/price/due_date/promised_date/closed_qty/closed_flag/close_reason/buyer/supplier_order_no/customer_order_no/allow_delay_days/over_tol_pct/contact_name/contact_phone/delivery_address/status',
  old_value VARCHAR(255) NOT NULL DEFAULT '' COMMENT '原值（字符串化；地址需 255）',
  new_value VARCHAR(255) NOT NULL DEFAULT '' COMMENT '新值（字符串化；地址需 255）',
  reason VARCHAR(200) NOT NULL DEFAULT '' COMMENT '变更原因（qty/due_date/allow_delay_days/over_tol_pct/closed_qty/close_reason/delivery_address 必填）',
  operator_id BIGINT DEFAULT NULL COMMENT '操作人',
  operator_name VARCHAR(50) NOT NULL DEFAULT '' COMMENT '操作人姓名快照',
  operate_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  create_dept BIGINT DEFAULT NULL,
  create_by BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by BIGINT DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  del_flag CHAR(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  KEY idx_biz_ocl (tenant_id, order_type, order_id, operate_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单变更留痕';

-- ------------------------------------------------------------
-- 存量表增列：9 条 ALTER（13 个新列 + 5 个索引对象，DOC/31 §5.7 + §0.1 R1）
-- ------------------------------------------------------------
ALTER TABLE biz_purchase
  ADD COLUMN source_order_id BIGINT DEFAULT NULL COMMENT '来源采购订单ID，NULL=直开入库单' AFTER no,
  ADD COLUMN source_order_no VARCHAR(32) DEFAULT NULL COMMENT '来源采购订单号快照' AFTER source_order_id,
  ADD KEY idx_biz_purchase_source (tenant_id, source_order_id);

-- R1：联系人与电话从订单头快照到执行单头，详情与打印不回查订单
ALTER TABLE biz_purchase
  ADD COLUMN contact_name VARCHAR(64) NOT NULL DEFAULT '' COMMENT '联系人（转单时从订单快照）' AFTER total_amount,
  ADD COLUMN contact_phone VARCHAR(32) NOT NULL DEFAULT '' COMMENT '联系电话（转单时从订单快照）' AFTER contact_name;

ALTER TABLE biz_purchase_item
  ADD COLUMN source_order_item_id BIGINT DEFAULT NULL COMMENT '来源采购订单行ID' AFTER order_id,
  ADD KEY idx_purchase_item_source (tenant_id, source_order_item_id);

-- P0-1：init 建表缺 seq，实体与服务已用它（新装环境采购保存/确认会 Unknown column 'seq'）
ALTER TABLE biz_purchase_item
  ADD COLUMN seq INT NOT NULL DEFAULT 0 COMMENT '同单内顺序（0起，与biz_sale_item一致）' AFTER product_id;

ALTER TABLE biz_sale
  ADD COLUMN source_order_id BIGINT DEFAULT NULL COMMENT '来源销售订单ID，NULL=直开出库单' AFTER no,
  ADD COLUMN source_order_no VARCHAR(32) DEFAULT NULL COMMENT '来源销售订单号快照' AFTER source_order_id,
  ADD KEY idx_biz_sale_source (tenant_id, source_order_id);

-- R1：销售侧比采购侧多一个送货地址，三项一起快照
ALTER TABLE biz_sale
  ADD COLUMN contact_name VARCHAR(64) NOT NULL DEFAULT '' COMMENT '联系人（转单时从订单快照）' AFTER total_amount,
  ADD COLUMN contact_phone VARCHAR(32) NOT NULL DEFAULT '' COMMENT '联系电话（转单时从订单快照）' AFTER contact_name,
  ADD COLUMN delivery_address VARCHAR(255) NOT NULL DEFAULT '' COMMENT '送货地址（转单时从订单快照）' AFTER contact_phone;

ALTER TABLE biz_sale_item
  ADD COLUMN source_order_item_id BIGINT DEFAULT NULL COMMENT '来源销售订单行ID' AFTER order_id,
  ADD KEY idx_sale_item_source (tenant_id, source_order_item_id);

-- D15：只约束活跃行，VOID 行不限条数（VOID→NULL，唯一索引忽略 NULL）
ALTER TABLE fin_ar_ap
  ADD COLUMN active_flag TINYINT GENERATED ALWAYS AS (IF(status = 'VOID', NULL, 1)) STORED
    COMMENT '活跃行标志：非VOID=1，VOID=NULL';

ALTER TABLE fin_ar_ap
  ADD UNIQUE KEY uk_fin_ar_ap_source (tenant_id, source_type, source_id, active_flag);