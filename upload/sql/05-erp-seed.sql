-- ============================================================
-- 简造ERP社区版 · 菜单与基础种子数据
-- 依赖：01-ruoyi-system.sql、04-erp-schema.sql
-- 菜单结构：按业务动作分七个一级菜单（工作台/资料管理/采购管理/销售管理/库存管理/财务管理/系统设置），
--           3200/3210/3220/3230 复用旧一级目录，3080 复用为「系统设置」，
--           menu_id 与旧版「业务管理」单目录结构保持一致，3000 目录保留为历史占位（空）。
-- 说明：超级管理员（role_id=1）默认拥有全部权限；
--       其他角色请在“角色管理”中勾选 ERP 菜单/按钮。
-- ============================================================

SET NAMES utf8mb4;

-- 顶层目录
INSERT IGNORE INTO sys_menu VALUES
('3200', '销售管理', '0', '4', 'erp-biz', NULL, '', 1, 0, 'M', '0', '0', '', 'dashboard', 103, 1, NOW(), NULL, NULL, '业务模块'),
('3210', '库存管理', '0', '5', 'erp-doc', NULL, '', 1, 0, 'M', '0', '0', '', 'shopping', 103, 1, NOW(), NULL, NULL, '单据模块'),
('3220', '资料管理', '0', '2', 'erp-master', NULL, '', 1, 0, 'M', '0', '0', '', 'tree', 103, 1, NOW(), NULL, NULL, '资料模块'),
('3230', '财务管理', '0', '6', 'erp-finance', NULL, '', 1, 0, 'M', '0', '0', '', 'money', 103, 1, NOW(), NULL, NULL, '财务结算模块'),
('3260', '采购管理', '0', '3', 'erp-purchase', NULL, '', 1, 0, 'M', '0', '0', '', 'shopping', 103, 1, NOW(), NULL, NULL, '采购业务模块');

-- 工作台（一级入口）：只读看板，登录即可访问
INSERT IGNORE INTO sys_menu VALUES
('3010', '工作台', '0', '1', 'dashboard', 'erp/dashboard/index', '', 1, 0, 'C', '0', '0', 'erp:dashboard:view', 'dashboard', 103, 1, NOW(), NULL, NULL, '工作台');

-- 采购管理：采购入库
INSERT IGNORE INTO sys_menu VALUES
('3020', '采购入库', '3260', '1', 'purchase', 'erp/purchase/index', '', 1, 0, 'C', '0', '0', 'erp:purchase:list', 'shopping-cart', 103, 1, NOW(), NULL, NULL, '采购入库'),
('3021', '采购新增', '3020', '1', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:purchase:add', '#', 103, 1, NOW(), NULL, NULL, ''),
('3022', '采购编辑', '3020', '2', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:purchase:edit', '#', 103, 1, NOW(), NULL, NULL, ''),
('3023', '采购删除', '3020', '3', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:purchase:remove', '#', 103, 1, NOW(), NULL, NULL, ''),
('3024', '采购审核', '3020', '4', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:purchase:audit', '#', 103, 1, NOW(), NULL, NULL, ''),
('3025', '采购作废', '3020', '5', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:purchase:void', '#', 103, 1, NOW(), NULL, NULL, ''),
('3026', '采购导出', '3020', '6', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:purchase:export', '#', 103, 1, NOW(), NULL, NULL, ''),
('3027', '采购撤回', '3020', '7', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:purchase:withdraw', '#', 103, 1, NOW(), NULL, NULL, ''),
('3160', '采购查看', '3020', '8', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:purchase:query', '#', 103, 1, NOW(), NULL, NULL, '');

-- 销售管理：销售出库
INSERT IGNORE INTO sys_menu VALUES
('3040', '销售出库', '3200', '1', 'sale', 'erp/sale/index', '', 1, 0, 'C', '0', '0', 'erp:sale:list', 'money', 103, 1, NOW(), NULL, NULL, '销售出库'),
('3041', '销售新增', '3040', '1', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:sale:add', '#', 103, 1, NOW(), NULL, NULL, ''),
('3042', '销售编辑', '3040', '2', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:sale:edit', '#', 103, 1, NOW(), NULL, NULL, ''),
('3043', '销售删除', '3040', '3', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:sale:remove', '#', 103, 1, NOW(), NULL, NULL, ''),
('3044', '销售审核', '3040', '4', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:sale:audit', '#', 103, 1, NOW(), NULL, NULL, ''),
('3045', '销售作废', '3040', '5', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:sale:void', '#', 103, 1, NOW(), NULL, NULL, ''),
('3046', '销售导出', '3040', '6', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:sale:export', '#', 103, 1, NOW(), NULL, NULL, ''),
('3047', '销售撤回', '3040', '7', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:sale:withdraw', '#', 103, 1, NOW(), NULL, NULL, ''),
('3162', '销售查看', '3040', '8', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:sale:query', '#', 103, 1, NOW(), NULL, NULL, '');

-- 库存管理：其他出库
INSERT IGNORE INTO sys_menu VALUES
('3110', '其他出库', '3210', '3', 'other-out', 'erp/other-out/index', '', 1, 0, 'C', '0', '0', 'erp:other-out:list', 'upload', 103, 1, NOW(), NULL, NULL, '其他出库'),
('3111', '其他出库新增', '3110', '1', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:other-out:add', '#', 103, 1, NOW(), NULL, NULL, ''),
('3112', '其他出库编辑', '3110', '2', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:other-out:edit', '#', 103, 1, NOW(), NULL, NULL, ''),
('3113', '其他出库删除', '3110', '3', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:other-out:remove', '#', 103, 1, NOW(), NULL, NULL, ''),
('3114', '其他出库审核', '3110', '4', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:other-out:audit', '#', 103, 1, NOW(), NULL, NULL, ''),
('3115', '其他出库撤回', '3110', '5', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:other-out:withdraw', '#', 103, 1, NOW(), NULL, NULL, ''),
('3116', '其他出库作废', '3110', '6', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:other-out:void', '#', 103, 1, NOW(), NULL, NULL, ''),
('3163', '其他出库查看', '3110', '7', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:other-out:query', '#', 103, 1, NOW(), NULL, NULL, '');

-- 库存管理：其他入库
INSERT IGNORE INTO sys_menu VALUES
('3180', '其他入库', '3210', '2', 'other-in', 'erp/other-in/index', '', 1, 0, 'C', '0', '0', 'erp:other-in:list', 'download', 103, 1, NOW(), NULL, NULL, '其他入库'),
('3181', '其他入库新增', '3180', '1', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:other-in:add', '#', 103, 1, NOW(), NULL, NULL, ''),
('3182', '其他入库编辑', '3180', '2', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:other-in:edit', '#', 103, 1, NOW(), NULL, NULL, ''),
('3183', '其他入库删除', '3180', '3', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:other-in:remove', '#', 103, 1, NOW(), NULL, NULL, ''),
('3184', '其他入库审核', '3180', '4', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:other-in:audit', '#', 103, 1, NOW(), NULL, NULL, ''),
('3185', '其他入库撤回', '3180', '5', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:other-in:withdraw', '#', 103, 1, NOW(), NULL, NULL, ''),
('3186', '其他入库作废', '3180', '6', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:other-in:void', '#', 103, 1, NOW(), NULL, NULL, ''),
('3187', '其他入库查看', '3180', '7', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:other-in:query', '#', 103, 1, NOW(), NULL, NULL, '');

-- 库存管理：库存查询
INSERT IGNORE INTO sys_menu VALUES
('3050', '库存查询', '3210', '5', 'stock', 'erp/stock/index', '', 1, 0, 'C', '0', '0', 'erp:stock:list', 'chart', 103, 1, NOW(), NULL, NULL, '库存查询'),
('3051', '库存导出', '3050', '1', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:stock:export', '#', 103, 1, NOW(), NULL, NULL, ''),
('3164', '库存流水查看', '3050', '2', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:stock:query', '#', 103, 1, NOW(), NULL, NULL, '');

-- 财务管理：应收应付
INSERT IGNORE INTO sys_menu VALUES
('3060', '应收应付', '3230', '1', 'finance', 'erp/finance/index', '', 1, 0, 'C', '0', '0', 'erp:finance:list', 'money', 103, 1, NOW(), NULL, NULL, '应收应付'),
('3061', '收款', '3060', '1', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:receipt', '#', 103, 1, NOW(), NULL, NULL, ''),
('3062', '付款', '3060', '2', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:payment', '#', 103, 1, NOW(), NULL, NULL, ''),
('3063', '财务导出', '3060', '3', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:export', '#', 103, 1, NOW(), NULL, NULL, '');

-- 财务管理：对账单
INSERT IGNORE INTO sys_menu VALUES
('3070', '对账单', '3230', '7', 'statement', 'erp/statement/index', '', 1, 0, 'C', '0', '0', 'erp:finance:statement', 'documentation', 103, 1, NOW(), NULL, NULL, '对账单'),
('3071', '对账打印', '3070', '1', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:statement:print', '#', 103, 1, NOW(), NULL, NULL, '');

-- 资料管理：四个子项直挂一级目录（去掉原 3080 中间层）
INSERT IGNORE INTO sys_menu VALUES
('3081', '商品资料', '3220', '1', 'product', 'erp/master/product/index', '', 1, 0, 'C', '0', '0', 'erp:master:product:list', 'category', 103, 1, NOW(), NULL, NULL, '产品资料'),
('30811', '产品新增', '3081', '1', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:master:product:add', '#', 103, 1, NOW(), NULL, NULL, ''),
('30812', '产品编辑', '3081', '2', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:master:product:edit', '#', 103, 1, NOW(), NULL, NULL, ''),
('30813', '产品删除', '3081', '3', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:master:product:remove', '#', 103, 1, NOW(), NULL, NULL, ''),
('30814', '产品导出', '3081', '4', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:master:product:export', '#', 103, 1, NOW(), NULL, NULL, ''),
('3165', '产品查看', '3081', '5', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:master:product:query', '#', 103, 1, NOW(), NULL, NULL, ''),
('30815', '产品导入', '3081', '6', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:master:product:import', '#', 103, 1, NOW(), NULL, NULL, ''),
('30816', '商品分类维护', '3081', '7', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:product-category:list', '#', 103, 1, NOW(), NULL, NULL, '商品资料-分类维护入口'),
('30817', '商品分类详情', '3081', '8', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:product-category:query', '#', 103, 1, NOW(), NULL, NULL, ''),
('30818', '商品分类新增', '3081', '9', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:product-category:add', '#', 103, 1, NOW(), NULL, NULL, ''),
('30819', '商品分类修改', '3081', '10', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:product-category:edit', '#', 103, 1, NOW(), NULL, NULL, ''),
('30820', '商品分类删除', '3081', '11', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:product-category:remove', '#', 103, 1, NOW(), NULL, NULL, ''),
('3082', '供应商资料', '3220', '2', 'supplier', 'erp/master/supplier/index', '', 1, 0, 'C', '0', '0', 'erp:master:supplier:list', 'peoples', 103, 1, NOW(), NULL, NULL, '供应商资料'),
('30821', '供应商新增', '3082', '1', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:master:supplier:add', '#', 103, 1, NOW(), NULL, NULL, ''),
('30822', '供应商编辑', '3082', '2', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:master:supplier:edit', '#', 103, 1, NOW(), NULL, NULL, ''),
('30823', '供应商删除', '3082', '3', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:master:supplier:remove', '#', 103, 1, NOW(), NULL, NULL, ''),
('30824', '供应商导出', '3082', '4', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:master:supplier:export', '#', 103, 1, NOW(), NULL, NULL, ''),
('3166', '供应商查看', '3082', '5', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:master:supplier:query', '#', 103, 1, NOW(), NULL, NULL, ''),
('30825', '供应商导入', '3082', '6', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:master:supplier:import', '#', 103, 1, NOW(), NULL, NULL, ''),
('3083', '客户资料', '3220', '3', 'customer', 'erp/master/customer/index', '', 1, 0, 'C', '0', '0', 'erp:master:customer:list', 'user', 103, 1, NOW(), NULL, NULL, '客户资料'),
('30831', '客户新增', '3083', '1', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:master:customer:add', '#', 103, 1, NOW(), NULL, NULL, ''),
('30832', '客户编辑', '3083', '2', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:master:customer:edit', '#', 103, 1, NOW(), NULL, NULL, ''),
('30833', '客户删除', '3083', '3', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:master:customer:remove', '#', 103, 1, NOW(), NULL, NULL, ''),
('30834', '客户导出', '3083', '4', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:master:customer:export', '#', 103, 1, NOW(), NULL, NULL, ''),
('3167', '客户查看', '3083', '5', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:master:customer:query', '#', 103, 1, NOW(), NULL, NULL, ''),
('30835', '客户导入', '3083', '6', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:master:customer:import', '#', 103, 1, NOW(), NULL, NULL, ''),
('3084', '仓库资料', '3220', '4', 'warehouse', 'erp/master/warehouse/index', '', 1, 0, 'C', '0', '0', 'erp:master:warehouse:list', 'component', 103, 1, NOW(), NULL, NULL, '仓库资料'),
('30841', '仓库新增', '3084', '1', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:master:warehouse:add', '#', 103, 1, NOW(), NULL, NULL, ''),
('30842', '仓库编辑', '3084', '2', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:master:warehouse:edit', '#', 103, 1, NOW(), NULL, NULL, ''),
('30843', '仓库删除', '3084', '3', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:master:warehouse:remove', '#', 103, 1, NOW(), NULL, NULL, ''),
('30844', '仓库导出', '3084', '4', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:master:warehouse:export', '#', 103, 1, NOW(), NULL, NULL, ''),
('30845', '仓库导入', '3084', '5', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:master:warehouse:import', '#', 103, 1, NOW(), NULL, NULL, '');

-- 库存管理：库存期初（原「期初设置」，往来期初已拆出）
INSERT IGNORE INTO sys_menu VALUES
('3090', '库存期初', '3210', '4', 'opening', 'erp/opening/index', '', 1, 0, 'C', '0', '0', 'erp:stock:opening', 'edit', 103, 1, NOW(), NULL, NULL, '期初设置'),
('3091', '期初编辑', '3090', '1', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:stock:opening:edit', '#', 103, 1, NOW(), NULL, NULL, ''),
('3092', '期初确认', '3090', '2', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:stock:opening:confirm', '#', 103, 1, NOW(), NULL, NULL, ''),
('3093', '期初导出', '3090', '3', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:stock:opening:export', '#', 103, 1, NOW(), NULL, NULL, ''),
('3094', '期初导入', '3090', '4', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:stock:opening:import', '#', 103, 1, NOW(), NULL, NULL, ''),
('3097', '期初删除', '3090', '5', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:stock:opening:remove', '#', 103, 1, NOW(), NULL, NULL, '草稿删除（本次新增）');

-- 租户套餐绑定（幂等）：仅在未包含 3097 时追加
UPDATE sys_tenant_package
SET menu_ids = CONCAT(COALESCE(menu_ids, ''), ',3097')
WHERE FIND_IN_SET('3097', COALESCE(menu_ids, '')) = 0
  AND CHAR_LENGTH(COALESCE(menu_ids, '')) + 5 <= 3000;

-- 财务管理：往来期初（原「期初设置」中的往来页签，DOC/26 §3 拆出）
INSERT IGNORE INTO sys_menu VALUES
('3250', '往来期初', '3230', '2', 'opening-finance', 'erp/opening/index', '', 1, 0, 'C', '0', '0', 'erp:finance:opening',
 'money', 103, 1, NOW(), NULL, NULL, '应收应付期初（原期初设置-往来期初页签）'),
('3171', '往来期初维护', '3250', '1', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:opening', '#', 103, 1, NOW(), NULL, NULL, ''),
('3172', '往来期初导出', '3250', '2', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:opening:export', '#', 103, 1, NOW(), NULL, NULL, ''),
('3173', '往来期初导入', '3250', '3', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:opening:import', '#', 103, 1, NOW(), NULL, NULL, '');

-- 系统设置：一级目录（复用原「资料管理」二级目录 3080）
INSERT IGNORE INTO sys_menu VALUES
('3080', '系统设置', '0', '7', 'master', NULL, '', 1, 0, 'M', '0', '0', '', 'tree', 103, 1, NOW(), NULL, NULL, 'ERP 资料管理');

-- 系统设置：打印模板
INSERT IGNORE INTO sys_menu VALUES
('3100', '打印模板', '3080', '1', 'print/template', 'erp/print/template/index', '', 1, 0, 'C', '0', '0', 'erp:print:template:list', 'pdf', 103, 1, NOW(), NULL, NULL, '打印模板'),
('3101', '模板新增', '3100', '1', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:print:template:add', '#', 103, 1, NOW(), NULL, NULL, ''),
('3102', '模板编辑', '3100', '2', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:print:template:edit', '#', 103, 1, NOW(), NULL, NULL, ''),
('3103', '模板删除', '3100', '3', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:print:template:remove', '#', 103, 1, NOW(), NULL, NULL, ''),
('3104', '模板预览', '3100', '4', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:print:template:preview', '#', 103, 1, NOW(), NULL, NULL, ''),
('3105', '打印渲染', '3100', '5', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:print:render', '#', 103, 1, NOW(), NULL, NULL, '');

-- 财务管理：其他收支
INSERT IGNORE INTO sys_menu VALUES
('3120', '其他收支', '3230', '5', 'finance/other-flow', 'erp/finance/other-flow/index', '', 1, 0, 'C', '0', '0', 'erp:finance:other-flow:list', 'money', 103, 1, NOW(), NULL, NULL, '其他收入/支出'),
('3121', '其他收支新增', '3120', '1', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:other-flow:add', '#', 103, 1, NOW(), NULL, NULL, ''),
('3122', '其他收支编辑', '3120', '2', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:other-flow:edit', '#', 103, 1, NOW(), NULL, NULL, ''),
('3123', '其他收支删除', '3120', '3', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:other-flow:remove', '#', 103, 1, NOW(), NULL, NULL, ''),
('3124', '其他收支确认', '3120', '4', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:other-flow:confirm', '#', 103, 1, NOW(), NULL, NULL, ''),
('3125', '其他收支撤回', '3120', '5', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:other-flow:withdraw', '#', 103, 1, NOW(), NULL, NULL, ''),
('3126', '其他收支作废', '3120', '6', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:other-flow:void', '#', 103, 1, NOW(), NULL, NULL, ''),
('3170', '其他收支查看', '3120', '7', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:other-flow:query', '#', 103, 1, NOW(), NULL, NULL, '');

-- 财务管理：综合成本
INSERT IGNORE INTO sys_menu VALUES
('3130', '综合成本', '3230', '6', 'finance/cost-report', 'erp/finance/cost-report/index', '', 1, 0, 'C', '0', '0', 'erp:finance:cost-report', 'chart', 103, 1, NOW(), NULL, NULL, '综合成本/利润'),
('3131', '综合成本导出', '3130', '1', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:cost-report:export', '#', 103, 1, NOW(), NULL, NULL, '');

-- 财务管理：收款单（针对客户）
INSERT IGNORE INTO sys_menu VALUES
('3140', '收款单', '3230', '3', 'finance/receipt', 'erp/finance/receipt/index', '', 1, 0, 'C', '0', '0', 'erp:finance:receipt:list', 'money', 103, 1, NOW(), NULL, NULL, '客户收款单'),
('3141', '收款单新增', '3140', '1', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:receipt:add', '#', 103, 1, NOW(), NULL, NULL, ''),
('3142', '收款单编辑', '3140', '2', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:receipt:edit', '#', 103, 1, NOW(), NULL, NULL, ''),
('3143', '收款单删除', '3140', '3', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:receipt:remove', '#', 103, 1, NOW(), NULL, NULL, ''),
('3144', '收款单确认', '3140', '4', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:receipt:confirm', '#', 103, 1, NOW(), NULL, NULL, ''),
('3145', '收款单撤回', '3140', '5', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:receipt:withdraw', '#', 103, 1, NOW(), NULL, NULL, ''),
('3146', '收款单作废', '3140', '6', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:receipt:void', '#', 103, 1, NOW(), NULL, NULL, ''),
('3147', '收款单打印', '3140', '7', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:receipt:print', '#', 103, 1, NOW(), NULL, NULL, ''),
('3168', '收款单查看', '3140', '8', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:receipt:query', '#', 103, 1, NOW(), NULL, NULL, '');

-- 财务管理：付款单（针对供应商）
INSERT IGNORE INTO sys_menu VALUES
('3150', '付款单', '3230', '4', 'finance/payment', 'erp/finance/payment/index', '', 1, 0, 'C', '0', '0', 'erp:finance:payment:list', 'money', 103, 1, NOW(), NULL, NULL, '供应商付款单'),
('3151', '付款单新增', '3150', '1', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:payment:add', '#', 103, 1, NOW(), NULL, NULL, ''),
('3152', '付款单编辑', '3150', '2', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:payment:edit', '#', 103, 1, NOW(), NULL, NULL, ''),
('3153', '付款单删除', '3150', '3', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:payment:remove', '#', 103, 1, NOW(), NULL, NULL, ''),
('3154', '付款单确认', '3150', '4', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:payment:confirm', '#', 103, 1, NOW(), NULL, NULL, ''),
('3155', '付款单撤回', '3150', '5', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:payment:withdraw', '#', 103, 1, NOW(), NULL, NULL, ''),
('3156', '付款单作废', '3150', '6', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:payment:void', '#', 103, 1, NOW(), NULL, NULL, ''),
('3157', '付款单打印', '3150', '7', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:payment:print', '#', 103, 1, NOW(), NULL, NULL, ''),
('3169', '付款单查看', '3150', '8', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:payment:query', '#', 103, 1, NOW(), NULL, NULL, '');

-- 销售管理 / 采购管理：退货单（DOC/24）
INSERT IGNORE INTO sys_menu VALUES
('3095', '销售退货', '3200', '2', 'sale-return', 'erp/sale-return/index', '', 1, 0, 'C', '0', '0', 'erp:sale-return:list', 'refresh', 103, 1, NOW(), NULL, NULL, '销售退货单'),
('3211', '销售退货新增', '3095', '1', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:sale-return:add', '#', 103, 1, NOW(), NULL, NULL, ''),
('3212', '销售退货编辑', '3095', '2', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:sale-return:edit', '#', 103, 1, NOW(), NULL, NULL, ''),
('3213', '销售退货审核', '3095', '3', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:sale-return:audit', '#', 103, 1, NOW(), NULL, NULL, ''),
('3214', '销售退货撤回', '3095', '4', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:sale-return:withdraw', '#', 103, 1, NOW(), NULL, NULL, ''),
('3215', '销售退货作废', '3095', '5', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:sale-return:void', '#', 103, 1, NOW(), NULL, NULL, ''),
('3217', '销售退货删除', '3095', '7', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:sale-return:remove', '#', 103, 1, NOW(), NULL, NULL, ''),
('3216', '销售退货查看', '3095', '6', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:sale-return:query', '#', 103, 1, NOW(), NULL, NULL, ''),
('3096', '采购退货', '3260', '2', 'purchase-return', 'erp/purchase-return/index', '', 1, 0, 'C', '0', '0', 'erp:purchase-return:list', 'shopping', 103, 1, NOW(), NULL, NULL, '采购退货单'),
('3221', '采购退货新增', '3096', '1', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:purchase-return:add', '#', 103, 1, NOW(), NULL, NULL, ''),
('3222', '采购退货编辑', '3096', '2', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:purchase-return:edit', '#', 103, 1, NOW(), NULL, NULL, ''),
('3223', '采购退货审核', '3096', '3', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:purchase-return:audit', '#', 103, 1, NOW(), NULL, NULL, ''),
('3224', '采购退货撤回', '3096', '4', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:purchase-return:withdraw', '#', 103, 1, NOW(), NULL, NULL, ''),
('3225', '采购退货作废', '3096', '5', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:purchase-return:void', '#', 103, 1, NOW(), NULL, NULL, ''),
('3227', '采购退货删除', '3096', '7', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:purchase-return:remove', '#', 103, 1, NOW(), NULL, NULL, ''),
('3226', '采购退货查看', '3096', '6', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:purchase-return:query', '#', 103, 1, NOW(), NULL, NULL, '');

-- 租户套餐绑定（幂等）
UPDATE sys_tenant_package
SET menu_ids = CONCAT(menu_ids, ',3095,3096,3211,3212,3213,3214,3215,3216,3217,3221,3222,3223,3224,3225,3226,3227')
WHERE FIND_IN_SET(3095, menu_ids) = 0
   OR menu_ids IS NULL;

-- 财务管理：结算账户（DOC/25；财务目录 3230 下 order 8 放末尾）
INSERT IGNORE INTO sys_menu VALUES
('3240', '结算账户', '3230', '8', 'finance/account', 'erp/finance/account/index', '', 1, 0, 'C', '0', '0', 'erp:finance:account:list', 'money', 103, 1, NOW(), NULL, NULL, '结算账户主数据与余额流水'),
('3241', '账户查询', '3240', '1', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:account:query', '#', 103, 1, NOW(), NULL, NULL, ''),
('3242', '账户新增', '3240', '2', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:account:add', '#', 103, 1, NOW(), NULL, NULL, ''),
('3243', '账户编辑', '3240', '3', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:account:edit', '#', 103, 1, NOW(), NULL, NULL, ''),
('3244', '账户删除', '3240', '4', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:finance:account:remove', '#', 103, 1, NOW(), NULL, NULL, '');

-- 租户套餐绑定（幂等）
UPDATE sys_tenant_package
SET menu_ids = CONCAT(menu_ids, ',3240,3241,3242,3243,3244')
WHERE FIND_IN_SET(3240, menu_ids) = 0
   OR menu_ids IS NULL;

-- 系统：运行日志查看（挂「日志管理」目录 108 下，仅超管可见/分配）
INSERT IGNORE INTO sys_menu VALUES
('502', '运行日志', '108', '3', 'syslog', 'monitor/syslog/index', '', 1, 0, 'C', '0', '0', 'monitor:syslog:list', 'documentation', 103, 1, NOW(), NULL, NULL, '后端运行日志文件查看与下载');

-- 历史：原「业务管理」目录占位（空目录，不再使用）
INSERT IGNORE INTO sys_menu VALUES
('3000', '业务管理', '0', '99', 'erp', NULL, '', 1, 0, 'M', '1', '0', '', 'shopping', 103, 1, NOW(), NULL, NULL, '已拆分至业务/单据/资料/财务结算');

-- 默认仓库
INSERT IGNORE INTO md_warehouse
(id, tenant_id, code, name, status, create_dept, create_by, create_time, del_flag)
VALUES
(1, '000000', 'MAIN', '主仓', 1, 103, 1, NOW(), '0');

-- ------------------------------------------------------------
-- 采购订单 / 销售订单（DOC/31 M1：16 条菜单与按钮）
-- M2 的导出/看板（3278/3288/3290）随功能可用后再建，此处不占位
-- ------------------------------------------------------------
INSERT IGNORE INTO sys_menu VALUES
('3270', '采购订单', '3260', '0', 'purchase-order', 'erp/order/purchase/index', '', 1, 0, 'C', '0', '0', 'erp:purchase-order:list', 'shopping', 103, 1, NOW(), NULL, NULL, '采购订单'),
('3271', '采购订单查看', '3270', '1', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:purchase-order:query', '#', 103, 1, NOW(), NULL, NULL, ''),
('3272', '采购订单新增', '3270', '2', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:purchase-order:add', '#', 103, 1, NOW(), NULL, NULL, ''),
('3273', '采购订单编辑', '3270', '3', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:purchase-order:edit', '#', 103, 1, NOW(), NULL, NULL, ''),
('3274', '采购订单删除', '3270', '4', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:purchase-order:remove', '#', 103, 1, NOW(), NULL, NULL, ''),
('3275', '采购订单确认', '3270', '5', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:purchase-order:audit', '#', 103, 1, NOW(), NULL, NULL, ''),
('3276', '采购订单撤回', '3270', '6', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:purchase-order:withdraw', '#', 103, 1, NOW(), NULL, NULL, ''),
('3277', '采购订单作废', '3270', '7', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:purchase-order:void', '#', 103, 1, NOW(), NULL, NULL, ''),
('3280', '销售订单', '3200', '0', 'sale-order', 'erp/order/sale/index', '', 1, 0, 'C', '0', '0', 'erp:sale-order:list', 'money', 103, 1, NOW(), NULL, NULL, '销售订单'),
('3281', '销售订单查看', '3280', '1', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:sale-order:query', '#', 103, 1, NOW(), NULL, NULL, ''),
('3282', '销售订单新增', '3280', '2', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:sale-order:add', '#', 103, 1, NOW(), NULL, NULL, ''),
('3283', '销售订单编辑', '3280', '3', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:sale-order:edit', '#', 103, 1, NOW(), NULL, NULL, ''),
('3284', '销售订单删除', '3280', '4', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:sale-order:remove', '#', 103, 1, NOW(), NULL, NULL, ''),
('3285', '销售订单确认', '3280', '5', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:sale-order:audit', '#', 103, 1, NOW(), NULL, NULL, ''),
('3286', '销售订单撤回', '3280', '6', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:sale-order:withdraw', '#', 103, 1, NOW(), NULL, NULL, ''),
('3287', '销售订单作废', '3280', '7', '', NULL, '', 1, 0, 'F', '0', '0', 'erp:sale-order:void', '#', 103, 1, NOW(), NULL, NULL, '');

-- 租户套餐绑定（幂等）：采购/销售两侧父菜单都未绑定时一次追加 16 个 ID；
-- menu_ids 为 varchar(3000)，16 个 ID 占 80 字符，超长的套餐整条跳过而不是报 1406。
UPDATE sys_tenant_package
SET menu_ids = CONCAT(COALESCE(menu_ids, ''), ',3270,3271,3272,3273,3274,3275,3276,3277,3280,3281,3282,3283,3284,3285,3286,3287')
WHERE FIND_IN_SET(3270, COALESCE(menu_ids, '')) = 0
  AND FIND_IN_SET(3280, COALESCE(menu_ids, '')) = 0
  AND CHAR_LENGTH(COALESCE(menu_ids, '')) + 80 <= 3000;

-- ------------------------------------------------------------
-- 订单层初始角色（DOC/31 §11.2）：boss 全量、clerk 除 remove/withdraw/void
-- 不复用超级管理员/测试角色，避免“人人都是超管”；角色绑定由管理员在用户管理里分配
-- ------------------------------------------------------------
INSERT IGNORE INTO sys_role VALUES
(5, '000000', '老板', 'boss', 5, 1, 1, 1, '0', '0', 103, 1, NOW(), NULL, NULL, 'ERP 订单层：全部菜单与按钮'),
(6, '000000', '跟单人', 'clerk', 6, 1, 1, 1, '0', '0', 103, 1, NOW(), NULL, NULL, 'ERP 订单层：录单改单确认，不含删单/撤回/作废');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
(5, 3270), (5, 3271), (5, 3272), (5, 3273), (5, 3274), (5, 3275), (5, 3276), (5, 3277),
(5, 3280), (5, 3281), (5, 3282), (5, 3283), (5, 3284), (5, 3285), (5, 3286), (5, 3287),
(6, 3270), (6, 3271), (6, 3272), (6, 3273), (6, 3275),
(6, 3280), (6, 3281), (6, 3282), (6, 3283), (6, 3285);

-- 工作台对所有内置角色可见
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES (5, 3010), (6, 3010);



-- ------------------------------------------------------------
-- 社区版范围：移除上游 demo 模块菜单（测试单表 / 测试树表）
-- 代码仍在 jianzao-demo 模块中，但默认不向业务用户暴露入口
-- ------------------------------------------------------------
DELETE FROM sys_role_menu WHERE menu_id IN (5, 1500, 1501, 1502, 1503, 1504, 1505, 1506, 1507, 1508, 1509, 1510, 1511);
DELETE FROM sys_menu WHERE menu_id IN (5, 1500, 1501, 1502, 1503, 1504, 1505, 1506, 1507, 1508, 1509, 1510, 1511);

-- 演示账号使用中性邮箱，避免个人中心显示上游作者的示例邮箱
UPDATE sys_user SET email = 'admin@example.com' WHERE user_id = 1 AND email = 'crazyLionLi@163.com';
