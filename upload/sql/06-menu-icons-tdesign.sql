-- ============================================================
-- 迁移：按菜单语义统一切换到 TDesign 图标
-- 目标：① 旧 RuoYi/Element Plus 图标名全部换成 TDesign 图标；
--       ② 按菜单名称重新匹配语义，消除不同菜单复用同一图标。
-- 只按 menu_id 更新本产品固定菜单，可重复执行；末尾保留旧名兜底，
-- 兼容客户自定义菜单。全新安装由 init/06 在 05-erp-seed.sql 之后执行。
-- ============================================================

SET NAMES utf8mb4;

-- 顶级目录
UPDATE sys_menu SET icon = 'setting'     WHERE menu_id = 1;     -- 系统管理
UPDATE sys_menu SET icon = 'building-1'  WHERE menu_id = 6;     -- 租户管理（平台多租户）
UPDATE sys_menu SET icon = 'folder'      WHERE menu_id = 3220;  -- 资料管理
UPDATE sys_menu SET icon = 'server'      WHERE menu_id = 2;     -- 系统监控
UPDATE sys_menu SET icon = 'cart'        WHERE menu_id = 3260;  -- 采购管理
UPDATE sys_menu SET icon = 'tools'       WHERE menu_id = 3;     -- 系统工具
UPDATE sys_menu SET icon = 'shop'        WHERE menu_id = 3200;  -- 销售管理
UPDATE sys_menu SET icon = 'link'        WHERE menu_id = 4;     -- PLUS官网
UPDATE sys_menu SET icon = 'bug'         WHERE menu_id = 5;     -- 测试菜单
UPDATE sys_menu SET icon = 'layers'      WHERE menu_id = 3210;  -- 库存管理
UPDATE sys_menu SET icon = 'wallet'      WHERE menu_id = 3230;  -- 财务管理
UPDATE sys_menu SET icon = 'adjustment'  WHERE menu_id = 3080;  -- 系统设置
UPDATE sys_menu SET icon = 'app'         WHERE menu_id = 3000;  -- 业务管理

-- 系统管理
UPDATE sys_menu SET icon = 'user'            WHERE menu_id = 100;  -- 用户管理
UPDATE sys_menu SET icon = 'user-add'        WHERE menu_id = 131;  -- 分配角色（隐藏明细）
UPDATE sys_menu SET icon = 'usergroup'       WHERE menu_id = 101;  -- 角色管理
UPDATE sys_menu SET icon = 'user-arrow-right' WHERE menu_id = 130; -- 分配用户（隐藏明细）
UPDATE sys_menu SET icon = 'menu'            WHERE menu_id = 102;  -- 菜单管理
UPDATE sys_menu SET icon = 'tree-round-dot'  WHERE menu_id = 103;  -- 部门管理
UPDATE sys_menu SET icon = 'user-circle'     WHERE menu_id = 104;  -- 岗位管理
UPDATE sys_menu SET icon = 'book-open'       WHERE menu_id = 105;  -- 字典管理
UPDATE sys_menu SET icon = 'book'            WHERE menu_id = 132;  -- 字典数据（隐藏明细）
UPDATE sys_menu SET icon = 'filter'          WHERE menu_id = 106;  -- 参数设置
UPDATE sys_menu SET icon = 'notification'    WHERE menu_id = 107;  -- 通知公告
UPDATE sys_menu SET icon = 'history'         WHERE menu_id = 108;  -- 日志管理
UPDATE sys_menu SET icon = 'folder-open'     WHERE menu_id = 118;  -- 文件管理
UPDATE sys_menu SET icon = 'file-setting'    WHERE menu_id = 133;  -- 文件配置管理（隐藏明细）
UPDATE sys_menu SET icon = 'mobile'          WHERE menu_id = 123;  -- 客户端管理

-- 系统监控
UPDATE sys_menu SET icon = 'user-checked' WHERE menu_id = 109;  -- 在线用户
UPDATE sys_menu SET icon = 'data-base'    WHERE menu_id = 113;  -- 缓存监控
UPDATE sys_menu SET icon = 'chart-bar'    WHERE menu_id = 117;  -- Admin监控
UPDATE sys_menu SET icon = 'time'         WHERE menu_id = 120;  -- 任务调度中心

-- 系统工具
UPDATE sys_menu SET icon = 'code'      WHERE menu_id = 115;  -- 代码生成
UPDATE sys_menu SET icon = 'file-code' WHERE menu_id = 116;  -- 修改生成配置（隐藏明细）

-- 测试菜单
UPDATE sys_menu SET icon = 'file-1'          WHERE menu_id = 1500;  -- 测试单表
UPDATE sys_menu SET icon = 'tree-square-dot' WHERE menu_id = 1506;  -- 测试树表

-- 租户管理
UPDATE sys_menu SET icon = 'building' WHERE menu_id = 121;  -- 租户管理
UPDATE sys_menu SET icon = 'gift'     WHERE menu_id = 122;  -- 租户套餐管理

-- 日志管理
UPDATE sys_menu SET icon = 'file-edit' WHERE menu_id = 500;  -- 操作日志
UPDATE sys_menu SET icon = 'login'     WHERE menu_id = 501;  -- 登录日志
UPDATE sys_menu SET icon = 'terminal'  WHERE menu_id = 502;  -- 运行日志

-- 系统设置

-- 销售管理
UPDATE sys_menu SET icon = 'order'    WHERE menu_id = 3280;  -- 销售订单
UPDATE sys_menu SET icon = 'outbox'   WHERE menu_id = 3040;  -- 销售出库
UPDATE sys_menu SET icon = 'rollback' WHERE menu_id = 3095;  -- 销售退货

-- 库存管理
UPDATE sys_menu SET icon = 'arrow-down'   WHERE menu_id = 3180;  -- 其他入库
UPDATE sys_menu SET icon = 'arrow-up'     WHERE menu_id = 3110;  -- 其他出库
UPDATE sys_menu SET icon = 'calendar-1'   WHERE menu_id = 3090;  -- 库存期初
UPDATE sys_menu SET icon = 'search'       WHERE menu_id = 3050;  -- 库存查询

-- 资料管理
UPDATE sys_menu SET icon = 'catalog'       WHERE menu_id = 3081;  -- 商品资料
UPDATE sys_menu SET icon = 'building-2'    WHERE menu_id = 3082;  -- 供应商资料
UPDATE sys_menu SET icon = 'user-business' WHERE menu_id = 3083;  -- 客户资料
UPDATE sys_menu SET icon = 'store'         WHERE menu_id = 3084;  -- 仓库资料

-- 财务管理
UPDATE sys_menu SET icon = 'money'        WHERE menu_id = 3060;  -- 应收应付
UPDATE sys_menu SET icon = 'calendar-2'   WHERE menu_id = 3250;  -- 往来期初
UPDATE sys_menu SET icon = 'file'         WHERE menu_id = 3070;  -- 对账单
UPDATE sys_menu SET icon = 'creditcard'   WHERE menu_id = 3240;  -- 结算账户

-- 采购管理
UPDATE sys_menu SET icon = 'order-list' WHERE menu_id = 3270;  -- 采购订单
UPDATE sys_menu SET icon = 'download'   WHERE menu_id = 3020;  -- 采购入库
UPDATE sys_menu SET icon = 'swap-left'  WHERE menu_id = 3096;  -- 采购退货

-- 兜底：客户自定义菜单若仍是旧 RuoYi 图标名，一并换成 TDesign 等价图标
UPDATE sys_menu SET icon = 'setting'        WHERE icon = 'system';
UPDATE sys_menu SET icon = 'server'         WHERE icon = 'monitor';
UPDATE sys_menu SET icon = 'tools'          WHERE icon = 'tool';
UPDATE sys_menu SET icon = 'help-circle'    WHERE icon = 'guide';
UPDATE sys_menu SET icon = 'usergroup'      WHERE icon = 'peoples';
UPDATE sys_menu SET icon = 'usergroup'      WHERE icon = 'post';
UPDATE sys_menu SET icon = 'book-open'      WHERE icon = 'dict';
UPDATE sys_menu SET icon = 'tree-round-dot' WHERE icon = 'tree';
UPDATE sys_menu SET icon = 'tree-square-dot' WHERE icon = 'tree-table';
UPDATE sys_menu SET icon = 'time'           WHERE icon = 'job';
UPDATE sys_menu SET icon = 'task'           WHERE icon = 'list';
UPDATE sys_menu SET icon = 'history'        WHERE icon = 'log';
UPDATE sys_menu SET icon = 'login'          WHERE icon = 'logininfor';
UPDATE sys_menu SET icon = 'notification'   WHERE icon = 'message';
UPDATE sys_menu SET icon = 'user'           WHERE icon = 'online';
UPDATE sys_menu SET icon = 'data-base'      WHERE icon = 'redis';
UPDATE sys_menu SET icon = 'translate'      WHERE icon = 'international';
UPDATE sys_menu SET icon = 'shop'           WHERE icon = 'shopping';
UPDATE sys_menu SET icon = 'catalog'        WHERE icon = 'category';
UPDATE sys_menu SET icon = 'file'           WHERE icon = 'documentation';
UPDATE sys_menu SET icon = 'tools'          WHERE icon = 'build';
UPDATE sys_menu SET icon = 'widget'         WHERE icon = 'component';
UPDATE sys_menu SET icon = 'history'        WHERE icon = 'system-log-filled';
UPDATE sys_menu SET icon = 'file-pdf'       WHERE icon = 'pdf';
UPDATE sys_menu SET icon = 'upload'         WHERE icon = 'export';
UPDATE sys_menu SET icon = 'file-pdf'       WHERE icon = 'print';
UPDATE sys_menu SET icon = 'catalog'        WHERE icon = 'goods';
UPDATE sys_menu SET icon = 'chart-column' WHERE menu_id = 3130;  -- 综合成本
