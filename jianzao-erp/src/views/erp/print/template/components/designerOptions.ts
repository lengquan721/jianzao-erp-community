import type { PrintBlockType, PrintDesignBlock, PrintDesignSchema, PrintTableColumn } from '@/api/erp/types';

/**
 * 纸张尺寸（mm），用于画布比例换算与分页参考
 */
export const PAPER_SIZES: Record<string, { width: number; height: number; label: string }> = {
  A4: { width: 210, height: 297, label: 'A4' },
  A5: { width: 148, height: 210, label: 'A5' },
  '241x93': { width: 241, height: 93, label: '三联单 241×93' },
  '241x140': { width: 241, height: 140, label: '三联单 241×140' }
};

/**
 * 组件库定义：type → 中文名、图标、默认属性
 */
export interface PaletteItem {
  type: PrintBlockType;
  label: string;
  desc: string;
  icon: string;
  create: () => PrintDesignBlock;
}

let seq = 0;
const nextId = (type: string) => `${type.toLowerCase()}-${Date.now().toString(36)}-${(seq++).toString(36)}`;

/**
 * 生成区块的唯一 id：删除/重排后仍需保证唯一，故用时间戳 + 自增序号
 */
export function newBlockId(type: string): string {
  return nextId(type);
}

/**
 * 默认表格列：与内置送货单一致，客户按需勾选/排序
 */
export function defaultItemColumns(): PrintTableColumn[] {
  return [
    { key: 'index', label: '序号', width: 6, align: 'center', visible: true, numeric: false },
    { key: 'name', label: '商品名称', width: null, align: 'left', visible: true, numeric: false },
    { key: 'spec', label: '规格', width: 16, align: 'left', visible: true, numeric: false },
    { key: 'unit', label: '单位', width: 8, align: 'center', visible: true, numeric: false },
    { key: 'qty', label: '数量', width: 10, align: 'right', visible: true, numeric: true },
    { key: 'price', label: '单价', width: 12, align: 'right', visible: true, numeric: true },
    { key: 'amount', label: '金额', width: 12, align: 'right', visible: true, numeric: true }
  ];
}

/**
 * 默认对账表格列
 */
export function defaultStatementColumns(): PrintTableColumn[] {
  return [
    { key: 'date', label: '日期', width: 11, align: 'center', visible: true, numeric: false },
    { key: 'no', label: '单号', width: 16, align: 'left', visible: true, numeric: false },
    { key: 'productName', label: '商品名称', width: null, align: 'left', visible: true, numeric: false },
    { key: 'qtyText', label: '数量', width: 9, align: 'right', visible: true, numeric: true },
    { key: 'price', label: '单价', width: 9, align: 'right', visible: true, numeric: true },
    { key: 'amount', label: '金额', width: 11, align: 'right', visible: true, numeric: true },
    { key: 'settled', label: '已收/已付', width: 11, align: 'right', visible: true, numeric: true },
    { key: 'unsettled', label: '未结', width: 11, align: 'right', visible: true, numeric: true }
  ];
}

/**
 * 组件库：左侧面板按此渲染，拖入画布时调用 create() 生成区块
 */
export const PALETTE: PaletteItem[] = [
  {
    type: 'TITLE',
    label: '标题',
    desc: '单据名称，居中大字',
    icon: 'FontSizeIcon',
    create: () => ({
      id: newBlockId('title'),
      type: 'TITLE',
      props: { text: '送 货 单', subText: '' },
      style: { fontSize: 20, textAlign: 'center' }
    })
  },
  {
    type: 'TEXT',
    label: '文本',
    desc: '固定文案、页脚备注',
    icon: 'FontIcon',
    create: () => ({
      id: newBlockId('text'),
      type: 'TEXT',
      props: { text: '在此输入文本内容' },
      style: { textAlign: 'left', fontSize: 12 }
    })
  },
  {
    type: 'FIELD',
    label: '字段',
    desc: '绑定单号、客户等变量',
    icon: 'ViewListIcon',
    create: () => ({
      id: newBlockId('field'),
      type: 'FIELD',
      props: { fieldKey: 'doc.no', label: '单号', showLabel: true },
      style: { textAlign: 'left', fontSize: 12 }
    })
  },
  {
    type: 'IMAGE',
    label: 'Logo',
    desc: '公司 Logo 图片',
    icon: 'ImageIcon',
    create: () => ({
      id: newBlockId('image'),
      type: 'IMAGE',
      props: { src: '', width: 120 },
      style: { textAlign: 'center' }
    })
  },
  {
    type: 'TABLE',
    label: '表格',
    desc: '明细行，自动分页',
    icon: 'TableIcon',
    create: () => ({
      id: newBlockId('table'),
      type: 'TABLE',
      props: {
        dataSource: 'items',
        columns: defaultItemColumns(),
        showHeader: true,
        bordered: true
      },
      style: {}
    })
  },
  {
    type: 'DIVIDER',
    label: '分割线',
    desc: '横向分隔',
    icon: 'MinusIcon',
    create: () => ({
      id: newBlockId('divider'),
      type: 'DIVIDER',
      props: { thickness: 1, color: '#333' },
      style: { marginTop: 10, marginBottom: 10 }
    })
  },
  {
    type: 'SIGNATURE',
    label: '签章',
    desc: '制单、仓管、客户签收',
    icon: 'SignatureIcon',
    create: () => ({
      id: newBlockId('signature'),
      type: 'SIGNATURE',
      props: { labels: ['制单', '仓管', '客户签收'] },
      style: {}
    })
  },
  {
    type: 'SPACER',
    label: '留白',
    desc: '纵向间距',
    icon: 'SpaceIcon',
    create: () => ({
      id: newBlockId('spacer'),
      type: 'SPACER',
      props: { height: 12 },
      style: {}
    })
  },
  {
    type: 'PAGE_BREAK',
    label: '分页符',
    desc: '强制换页',
    icon: 'PageBreakIcon',
    create: () => ({
      id: newBlockId('pagebreak'),
      type: 'PAGE_BREAK',
      props: {},
      style: {}
    })
  }
];

/**
 * 新建空白组件树
 */
export function emptySchema(): PrintDesignSchema {
  return {
    schemaVersion: 1,
    paper: 'A4',
    pageMargin: 12,
    fontFamily: '"Microsoft YaHei", "PingFang SC", sans-serif',
    fontSize: 12,
    blocks: []
  };
}

/**
 * 深拷贝组件树：设计器编辑草稿需要与原数据解耦，避免取消后污染列表
 */
export function cloneSchema(schema?: PrintDesignSchema | null): PrintDesignSchema {
  if (!schema) {
    return emptySchema();
  }
  return JSON.parse(JSON.stringify(schema)) as PrintDesignSchema;
}

/**
 * 解析后端返回的 schemaJson 字符串，解析失败返回 null（视为源码模式）
 */
export function parseSchema(json?: string | null): PrintDesignSchema | null {
  if (!json || !json.trim()) {
    return null;
  }
  try {
    const parsed = JSON.parse(json) as PrintDesignSchema;
    if (!parsed || !Array.isArray(parsed.blocks)) {
      return null;
    }
    return parsed;
  } catch {
    return null;
  }
}

/**
 * 区块在画布上的中文名，用于图层列表与属性面板标题
 */
export function blockLabel(type: PrintBlockType): string {
  return PALETTE.find((p) => p.type === type)?.label ?? type;
}
