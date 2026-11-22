<template>
  <div v-loading="loading" class="p-2 dashboard">
    <t-alert v-if="errorMsg" theme="error" :title="errorMsg" close-btn class="!mb-[10px]" @close="errorMsg = ''" />

    <!-- 欢迎语与品牌 Slogan -->
    <div class="welcome">
      <div class="welcome-main">
        <div class="welcome-title">{{ welcomeText }}</div>
        <div class="welcome-slogan">采购 · 销售 · 库存 · 财务一体化 · 开源可二次开发</div>
      </div>
      <div class="welcome-date">{{ todayText }}</div>
    </div>

    <!-- 方案二：4 核心复合指标卡（销售利润 / 账款往来 / 应付采购 / 仓储资产） -->
    <t-row :gutter="16" class="mb-4">
      <!-- 1. 销售与利润 -->
      <t-col :xs="12" :sm="6" :lg="3">
        <div class="modern-kpi" @click="go('/erp-biz/sale')">
          <div class="modern-kpi__head">
            <span class="modern-kpi__title">本月销售额</span>
            <span class="modern-kpi__icon-box modern-kpi__icon-box--blue"><ShopIcon /></span>
          </div>
          <div class="modern-kpi__value">
            <span class="modern-kpi__currency">¥</span>
            <span class="modern-kpi__number">{{ formatMoney(num(summary?.monthSales)) }}</span>
          </div>
          <div class="modern-kpi__footer">
            <div class="flex items-center truncate">
              <span class="text-xs erp-text-secondary mr-1">本月毛利</span>
              <span :class="profitClass" class="font-semibold">¥{{ formatMoney(num(summary?.monthGrossProfit)) }}</span>
            </div>
            <t-tag :theme="num(summary?.monthGrossProfit) >= 0 ? 'success' : 'danger'" variant="light" size="small">
              <template #icon>
                <TrendingUpIcon v-if="num(summary?.monthGrossProfit) >= 0" />
                <TrendingDownIcon v-else />
              </template>
              {{ grossMarginText }}
            </t-tag>
          </div>
        </div>
      </t-col>

      <!-- 2. 应收与已收 -->
      <t-col :xs="12" :sm="6" :lg="3">
        <div class="modern-kpi" @click="go('/erp-finance/finance')">
          <div class="modern-kpi__head">
            <span class="modern-kpi__title">未收账款 (应收)</span>
            <span class="modern-kpi__icon-box modern-kpi__icon-box--amber"><WalletIcon /></span>
          </div>
          <div class="modern-kpi__value">
            <span class="modern-kpi__currency">¥</span>
            <span class="modern-kpi__number">{{ formatMoney(num(summary?.unreceived)) }}</span>
          </div>
          <div class="modern-kpi__footer">
            <div class="flex items-center truncate">
              <span class="text-xs erp-text-secondary mr-1">本月已收</span>
              <span class="font-semibold text-emerald-600 dark:text-emerald-400">¥{{ formatMoney(num(summary?.monthReceiptAmount)) }}</span>
            </div>
            <span class="modern-kpi__link" @click.stop="go('/erp-finance/finance/receipt')"> 收款明细 <ChevronRightIcon size="12px" /> </span>
          </div>
        </div>
      </t-col>

      <!-- 3. 应付与采购 -->
      <t-col :xs="12" :sm="6" :lg="3">
        <div class="modern-kpi" @click="go('/erp-finance/finance')">
          <div class="modern-kpi__head">
            <span class="modern-kpi__title">未付账款 (应付)</span>
            <span class="modern-kpi__icon-box modern-kpi__icon-box--indigo"><CreditcardIcon /></span>
          </div>
          <div class="modern-kpi__value">
            <span class="modern-kpi__currency">¥</span>
            <span class="modern-kpi__number">{{ formatMoney(num(summary?.unpaid)) }}</span>
          </div>
          <div class="modern-kpi__footer">
            <div class="flex items-center truncate">
              <span class="text-xs erp-text-secondary mr-1">本月采购</span>
              <span class="font-semibold text-slate-800 dark:text-slate-200">¥{{ formatMoney(num(summary?.monthPurchaseAmount)) }}</span>
            </div>
            <span class="text-xs erp-text-secondary opacity-70">含运杂费</span>
          </div>
        </div>
      </t-col>

      <!-- 4. 仓储与预警 -->
      <t-col :xs="12" :sm="6" :lg="3">
        <div class="modern-kpi" @click="go('/erp-doc/stock')">
          <div class="modern-kpi__head">
            <span class="modern-kpi__title">成品在库估值</span>
            <span class="modern-kpi__icon-box modern-kpi__icon-box--emerald"><LayersIcon /></span>
          </div>
          <div class="modern-kpi__value">
            <span class="modern-kpi__currency">¥</span>
            <span class="modern-kpi__number">{{ formatMoney(num(summary?.finishedStockAmount)) }}</span>
          </div>
          <div class="modern-kpi__footer">
            <span class="text-xs erp-text-secondary truncate mr-1">
              成品 {{ qtyText(summary?.finishedStockQty) }} · 原料 {{ qtyText(summary?.materialStockQty) }}
            </span>
            <t-tag
              v-if="(stockAlert?.lowCount ?? 0) > 0"
              theme="danger"
              variant="light"
              size="small"
              class="cursor-pointer"
              @click.stop="go('/erp-doc/stock?tab=ALERT')"
            >
              低预警 {{ stockAlert?.lowCount }}
            </t-tag>
            <t-tag
              v-else-if="(stockAlert?.highCount ?? 0) > 0"
              theme="warning"
              variant="light"
              size="small"
              class="cursor-pointer"
              @click.stop="go('/erp-doc/stock?tab=ALERT')"
            >
              高库存 {{ stockAlert?.highCount }}
            </t-tag>
            <t-tag v-else theme="success" variant="light" size="small">库存健康</t-tag>
          </div>
        </div>
      </t-col>
    </t-row>

    <!-- 经营趋势：TDesign Starter 的 dashboard-chart-card 规范（无描边卡 + 标题/副标题 + #actions 筛选） -->
    <div class="erp-row-container">
      <t-card :bordered="false" class="erp-chart-card" title="经营趋势" subtitle="面积：销售额 / 成本 · 折线：收款 / 毛利 / 毛利率">
        <template #actions>
          <t-radio-group v-model="trendMonths" size="small" variant="default-filled" @change="loadTrend">
            <t-radio-button :value="6">近 6 月</t-radio-button>
            <t-radio-button :value="12">近 12 月</t-radio-button>
          </t-radio-group>
        </template>
        <t-empty v-if="!trendMonthsList.length" description="暂无趋势数据" size="small" />
        <div v-else ref="chartRef" class="chart-box"></div>
      </t-card>
    </div>

    <!-- 最近单据 + 草稿待办（DOC/35 T0：与上半 KPI 卡统一为无描边表面） -->
    <t-row :gutter="16" class="mt-[10px]">
      <t-col :xs="12" :lg="6">
        <div class="erp-surface erp-panel">
          <div class="erp-panel__head">
            <span class="erp-panel-title">最近单据</span>
            <span class="erp-panel-sub">四类单据按业务日期合并，最多 10 条</span>
          </div>
          <t-table
            class="erp-table"
            row-key="__key"
            :data="recentDocs"
            :columns="recentColumns"
            :loading="loading"
            :hover="true"
            :resizable="true"
            size="small"
            cell-empty-content="-"
            empty="暂无单据"
            @column-resize-change="(ctx: any) => saveColumnWidths('dashboard/recent-docs', ctx.columnsWidth)"
          >
            <template #docType="{ row }">
              <t-tag :theme="tagTheme(docTypeMeta(row.type).tagType)" size="small">{{ docTypeMeta(row.type).label }}</t-tag>
            </template>
            <template #bizDate="{ row }">{{ toDate(row.bizDate) }}</template>
            <template #amount="{ row }">{{ docAmount(row.amount) }}</template>
            <template #operation="{ row }">
              <t-tooltip content="查看" placement="top">
                <t-button theme="primary" variant="text" shape="square" size="small" @click="handleViewDoc(row)">
                  <template #icon><BrowseIcon /></template>
                </t-button>
              </t-tooltip>
            </template>
          </t-table>
        </div>
      </t-col>

      <t-col :xs="12" :lg="6">
        <div class="erp-surface erp-panel">
          <div class="erp-panel__head">
            <span class="erp-panel-title">草稿待办</span>
            <span class="erp-panel-sub">共 {{ draftTotal }} 条</span>
          </div>
          <div v-if="draftTotal === 0" class="draft-empty">无待办</div>
          <div v-else class="draft-list">
            <div v-for="item in draftItems" :key="item.key" class="draft-row" @click="go(item.path)">
              <span class="draft-name">{{ item.label }}</span>
              <t-badge
                :count="item.count"
                :show-zero="true"
                :color="item.count > 0 ? 'var(--td-error-color)' : 'var(--td-gray-color-6)'"
                class="draft-badge"
              >
                <span class="draft-dot"></span>
              </t-badge>
            </div>
          </div>
        </div>
      </t-col>
    </t-row>

    <!-- 本月往来明细：排行 + 行内展开单据 -->
    <div class="erp-surface erp-panel mt-[10px]">
      <div class="erp-panel__head">
        <div class="flex items-center gap-3 flex-wrap">
          <span class="erp-panel-title">本月往来明细</span>
          <t-radio-group v-model="partnerType" size="small" @change="handlePartnerTypeChange">
            <t-radio-button value="CUSTOMER">客户</t-radio-button>
            <t-radio-button value="SUPPLIER">供货商</t-radio-button>
          </t-radio-group>
          <span class="erp-panel-sub">{{ partnerRank?.month || '' }} · 仅统计已确认单据</span>
        </div>
        <span class="erp-panel-sub">点击行展开单据明细</span>
      </div>

      <t-table
        class="erp-table"
        row-key="partyId"
        :data="rankItems"
        :columns="rankColumns"
        :loading="rankLoading"
        :hover="true"
        :resizable="true"
        :expanded-row-keys="expandedRowKeys"
        size="small"
        cell-empty-content="-"
        :empty="partnerType === 'CUSTOMER' ? '本月暂无客户往来' : '本月暂无供货商往来'"
        @expand-change="handleExpandChange"
        @column-resize-change="(ctx: any) => saveColumnWidths('dashboard/partner-rank', ctx.columnsWidth)"
      >
        <template #party="{ row }">
          <span class="text-xs erp-text-secondary">{{ row.partyCode || '' }}</span>
          <span class="ml-2">{{ row.partyName || '-' }}</span>
        </template>
        <template #docCount="{ row }">{{ row.docCount ?? 0 }} 张</template>
        <template #amount="{ row }">
          <span class="font-medium">{{ formatMoney(num(row.amount)) }}</span>
        </template>
        <template #grossProfit="{ row }">{{ formatMoney(num(row.grossProfit)) }}</template>
        <template #grossMargin="{ row }">{{ marginText(row.grossMargin) }}</template>
        <template #receiptAmount="{ row }">{{ formatMoney(num(row.receiptAmount)) }}</template>
        <template #paymentAmount="{ row }">{{ formatMoney(num(row.paymentAmount)) }}</template>

        <!-- 展开区：该往来单位本月单据明细，按需加载并缓存 -->
        <template #expandedRow="{ row }">
          <div class="px-3 py-2">
            <t-table
              class="erp-table"
              row-key="__key"
              :data="partnerDocsMap[String(row.partyId)] ?? []"
              :columns="partnerDocColumns"
              :loading="partnerLoadingId === String(row.partyId)"
              :hover="true"
              :resizable="true"
              size="small"
              cell-empty-content="-"
              empty="本月暂无单据"
              @column-resize-change="(ctx: any) => saveColumnWidths('dashboard/partner-docs', ctx.columnsWidth)"
            >
              <template #docType="{ row: doc }">
                <t-tag :theme="tagTheme(partnerDocTypeMeta(doc.type).tagType)" size="small">{{ partnerDocTypeMeta(doc.type).label }}</t-tag>
              </template>
              <template #bizDate="{ row: doc }">{{ toDate(doc.bizDate) }}</template>
              <template #amount="{ row: doc }">{{ formatMoney(num(doc.amount)) }}</template>
              <template #grossProfit="{ row: doc }">
                {{ doc.type === 'SALE' ? formatMoney(num(doc.grossProfit)) : '—' }}
              </template>
            </t-table>
          </div>
        </template>
      </t-table>
    </div>
  </div>
</template>

<script setup name="ErpDashboard" lang="ts">
import * as echarts from 'echarts';
import { useRouter } from 'vue-router';
import { dashboardStockAlert, dashboardPartnerMonth, dashboardPartnerRank, dashboardSummary, dashboardTrend } from '@/api/erp/dashboard';
import type {
  DashboardStockAlertVO,
  DashboardPartnerDoc,
  DashboardPartnerRankItem,
  DashboardPartnerRankVO,
  DashboardRecentDoc,
  DashboardSummaryVO,
  DashboardTrendVO,
  ErpAmount,
  ErpPartnerType
} from '@/api/erp/types';
import {
  BrowseIcon,
  ShopIcon,
  WalletIcon,
  CreditcardIcon,
  LayersIcon,
  TrendingUpIcon,
  TrendingDownIcon,
  ChevronRightIcon
} from 'tdesign-icons-vue-next';
import { formatMoney, formatQty } from '@/utils/erp/calc';
// 列宽记忆：拖拽后按表名存 localStorage，进页面还原（见 hooks/useTableColumnWidth）
import { mergeSavedWidths, saveColumnWidths } from '@/hooks/useTableColumnWidth';
import { useUserStore } from '@/store/modules/user';

const router = useRouter();
const userStore = useUserStore();

const loading = ref(false);
const errorMsg = ref('');

const summary = ref<DashboardSummaryVO | null>(null);
const stockAlert = ref<DashboardStockAlertVO | null>(null);
const trend = ref<DashboardTrendVO | null>(null);
const trendMonths = ref(6);

const chartRef = ref<HTMLElement | null>(null);
let chart: echarts.ECharts | null = null;

/** 后端 BigDecimal 以字符串下发，统一转数字后再运算 */
const num = (value: ErpAmount | undefined | null): number => {
  const n = Number(value ?? 0);
  return Number.isFinite(n) ? n : 0;
};

const toDate = (value: string | undefined | null): string => (value ? String(value).slice(0, 10) : '-');

/** 单据金额：生产单无金额，显示破折号 */
const docAmount = (value: ErpAmount | undefined | null): string => {
  if (value === undefined || value === null || value === '') return '—';
  return formatMoney(num(value));
};

/** 库存数量：整数值不展示无意义的小数位 */
const qtyText = (value: ErpAmount | undefined | null): string => {
  const n = num(value);
  return Number.isInteger(n) ? n.toLocaleString('zh-CN') : formatQty(n);
};

const todayText = computed<string>(() => {
  const now = new Date();
  const week = ['日', '一', '二', '三', '四', '五', '六'][now.getDay()];
  return `${now.getFullYear()} 年 ${now.getMonth() + 1} 月 ${now.getDate()} 日 星期${week}`;
});

const welcomeText = computed<string>(() => {
  const hour = new Date().getHours();
  const period = hour < 6 ? '凌晨好' : hour < 12 ? '上午好' : hour < 14 ? '中午好' : hour < 18 ? '下午好' : '晚上好';
  const who = userStore.nickname;
  return who ? `${period}，${who}` : `${period}，欢迎使用简造ERP社区版`;
});

/* ------------------------------ 卡片 ------------------------------ */

/** 毛利率（后端返回本月毛利率百分数，如 "71.75"） */
const grossMarginText = computed<string>(() => {
  const value = summary.value?.monthGrossMargin;
  if (value === undefined || value === null || value === '') return '-';
  const rate = Number(value);
  if (!Number.isFinite(rate)) return '-';
  return `${rate.toFixed(2)}%`;
});

const profitClass = computed<string>(() => {
  const profit = num(summary.value?.monthGrossProfit);
  if (profit > 0) return 'text-emerald-600';
  if (profit < 0) return 'text-red-500';
  return '';
});

/* ------------------------------ 最近单据 ------------------------------ */

const DOC_TYPES: Record<string, { label: string; tagType: 'primary' | 'success' | 'warning' | 'info' | 'danger'; path: string }> = {
  SALE: { label: '销售出库', tagType: 'primary', path: '/erp-biz/sale' },
  PURCHASE: { label: '采购入库', tagType: 'success', path: '/erp-purchase/purchase' },
  RECEIPT: { label: '收款单', tagType: 'info', path: '/erp-finance/finance/receipt' },
  PAYMENT: { label: '付款单', tagType: 'danger', path: '/erp-finance/finance/payment' }
};

/** 单据类型→标签配色：t-tag 没有 info 主题，落到 default */
const tagTheme = (type: 'primary' | 'success' | 'warning' | 'info' | 'danger') => (type === 'info' ? 'default' : type);

const docTypeMeta = (type: string) => DOC_TYPES[type] ?? { label: type || '-', tagType: 'info' as const, path: '/erp-biz/sale' };

/** 最近单据：TDesign 的 rowKey 只接受字符串字段名，行对象上补 __key（只读列表，不参与提交） */
const recentDocs = computed<DashboardRecentDoc[]>(() =>
  (summary.value?.recentDocs ?? []).map((row, index) => ({ ...row, __key: `recent-${index}` }))
);

/** 最近单据列配置：t-table 用 columns 数组，插槽名与 cell 字符串同名（DOC/35 §5） */
const recentColumns: any[] = [
  { colKey: 'type', title: '类型', align: 'center', width: 100, cell: 'docType' },
  { colKey: 'no', title: '单号', align: 'center', minWidth: 150, ellipsis: true },
  { colKey: 'bizDate', title: '日期', align: 'center', width: 110, cell: 'bizDate' },
  { colKey: 'amount', title: '金额', align: 'right', width: 140, className: 'num-cell', cell: 'amount' },
  { colKey: 'operation', title: '操作', align: 'center', width: 80, cell: 'operation' }
];
mergeSavedWidths('dashboard/recent-docs', recentColumns);

const handleViewDoc = (row: DashboardRecentDoc) => {
  go(docTypeMeta(row.type).path);
};

/* ------------------------------ 草稿待办 ------------------------------ */

const draftItems = computed(() => {
  const todos = summary.value?.draftTodos ?? {};
  return [
    { key: 'sale', label: '销售草稿', count: num(todos.saleDrafts), path: '/erp-biz/sale' },
    { key: 'purchase', label: '采购草稿', count: num(todos.purchaseDrafts), path: '/erp-purchase/purchase' },
    { key: 'receipt', label: '收款草稿', count: num(todos.receiptDrafts), path: '/erp-finance/finance/receipt' },
    { key: 'payment', label: '付款草稿', count: num(todos.paymentDrafts), path: '/erp-finance/finance/payment' }
  ];
});

const draftTotal = computed<number>(() => draftItems.value.reduce((sum, item) => sum + item.count, 0));

/* ------------------------------ 本月往来明细 ------------------------------ */

const partnerType = ref<ErpPartnerType>('CUSTOMER');
const rankLoading = ref(false);
const partnerRank = ref<DashboardPartnerRankVO | null>(null);
/** 各往来单位的单据明细缓存，key 为往来单位 ID */
const partnerDocsMap = ref<Record<string, DashboardPartnerDoc[]>>({});
/** 正在加载明细的往来单位 ID */
const partnerLoadingId = ref<string>('');
/** 已展开的行：TDesign 用受控 expandedRowKeys，值为 rowKey（partyId）原值 */
const expandedRowKeys = ref<Array<string | number>>([]);

const PARTNER_DOC_TYPES: Record<string, { label: string; tagType: 'primary' | 'success' | 'warning' | 'info' | 'danger' }> = {
  SALE: { label: '销售出库', tagType: 'primary' },
  PURCHASE: { label: '采购入库', tagType: 'success' },
  RECEIPT: { label: '收款单', tagType: 'info' },
  PAYMENT: { label: '付款单', tagType: 'danger' }
};

const partnerDocTypeMeta = (type: string) => PARTNER_DOC_TYPES[type] ?? { label: type || '-', tagType: 'info' as const };

const rankItems = computed<DashboardPartnerRankItem[]>(() => partnerRank.value?.items ?? []);

/** 往来排行列配置：客户口径比供货商多毛利/毛利率/收款三列，供货商口径只有付款 */
const rankColumns = computed<any[]>(() => {
  const cols: any[] = [
    { colKey: 'partyName', title: '单位', minWidth: 200, ellipsis: true, cell: 'party' },
    { colKey: 'docCount', title: '本月单据数', align: 'center', width: 110, cell: 'docCount' },
    { colKey: 'amount', title: partnerType.value === 'CUSTOMER' ? '本月销售额' : '本月采购额', align: 'right', width: 140, cell: 'amount' }
  ];
  if (partnerType.value === 'CUSTOMER') {
    cols.push(
      { colKey: 'grossProfit', title: '本月毛利', align: 'right', width: 120, cell: 'grossProfit' },
      { colKey: 'grossMargin', title: '毛利率', align: 'right', width: 100, cell: 'grossMargin' },
      { colKey: 'receiptAmount', title: '本月收款', align: 'right', width: 130, cell: 'receiptAmount' }
    );
  } else {
    cols.push({ colKey: 'paymentAmount', title: '本月付款', align: 'right', width: 130, cell: 'paymentAmount' });
  }
  return mergeSavedWidths('dashboard/partner-rank', cols);
});

/** 展开区单据列配置：毛利列只在客户口径出现 */
const partnerDocColumns = computed<any[]>(() => {
  const cols: any[] = [
    { colKey: 'type', title: '类型', align: 'center', width: 100, cell: 'docType' },
    { colKey: 'no', title: '单号', align: 'center', minWidth: 150, ellipsis: true },
    { colKey: 'bizDate', title: '日期', align: 'center', width: 110, cell: 'bizDate' },
    { colKey: 'amount', title: '金额', align: 'right', width: 130, cell: 'amount' }
  ];
  if (partnerType.value === 'CUSTOMER') {
    cols.push({ colKey: 'grossProfit', title: '毛利', align: 'right', width: 120, cell: 'grossProfit' });
  }
  cols.push({ colKey: 'remark', title: '备注', align: 'center', minWidth: 120, ellipsis: true });
  return mergeSavedWidths('dashboard/partner-docs', cols);
});

/** 毛利率百分数展示，无值时显示破折号 */
const marginText = (value: ErpAmount | undefined | null): string => {
  if (value === undefined || value === null || value === '') return '—';
  const rate = Number(value);
  return Number.isFinite(rate) ? `${rate.toFixed(2)}%` : '—';
};

const loadRank = async () => {
  rankLoading.value = true;
  try {
    const res = await dashboardPartnerRank(partnerType.value);
    partnerRank.value = res.data ?? null;
  } catch (e) {
    partnerRank.value = null;
    errorMsg.value = '往来排行加载失败，请稍后重试';
  } finally {
    rankLoading.value = false;
  }
};

/** 切换往来单位类型时清空明细缓存与展开态，客户 ID 与供货商 ID 属不同 ID 空间 */
const handlePartnerTypeChange = () => {
  partnerDocsMap.value = {};
  expandedRowKeys.value = [];
  loadRank();
};

/** 展开行时按需加载该往来单位的单据明细，已加载过则直接复用缓存（TDesign 回调：keys + { currentRowData }） */
const handleExpandChange = async (keys: Array<string | number>, options: { currentRowData: Record<string, any> }) => {
  expandedRowKeys.value = keys;
  const row = options.currentRowData as DashboardPartnerRankItem;
  if (!keys.includes(row.partyId)) return;

  const key = String(row.partyId);
  if (partnerDocsMap.value[key]) return;

  partnerLoadingId.value = key;
  try {
    const res = await dashboardPartnerMonth(partnerType.value, row.partyId);
    // 明细行无业务主键，补 __key 供 t-table 的 rowKey 使用（只读列表，不参与提交）
    const docs = (res.data?.docs ?? []).map((doc, index) => ({ ...doc, __key: `${key}-${index}` }));
    partnerDocsMap.value = { ...partnerDocsMap.value, [key]: docs };
  } catch (e) {
    partnerDocsMap.value = { ...partnerDocsMap.value, [key]: [] };
    errorMsg.value = '往来明细加载失败，请稍后重试';
  } finally {
    partnerLoadingId.value = '';
  }
};

/* ------------------------------ 趋势图 ------------------------------ */

const trendMonthsList = computed<string[]>(() => trend.value?.months ?? []);

/** 运行时取 CSS 变量：ECharts 不解析 var()，亮暗切换后需重取并重绘（DOC/34 §7） */
const chartColor = (name: string): string => getComputedStyle(document.documentElement).getPropertyValue(`--app-chart-${name}`).trim() || '#409EFF';

const renderChart = () => {
  if (!trendMonthsList.value.length) {
    chart?.dispose();
    chart = null;
    return;
  }
  if (!chartRef.value) return;
  if (!chart) chart = echarts.init(chartRef.value);

  const data = trend.value;
  const months = data?.months ?? [];
  const sales = (data?.sales ?? []).map(num);
  const cost = (data?.cost ?? []).map(num);
  const receipts = (data?.receipts ?? []).map(num);
  const profit = (data?.profit ?? []).map(num);
  /** 毛利率辅助轴：销售额为 0 的月份不画点 */
  const marginRate = sales.map((value, index) => (value === 0 ? null : Number(((profit[index] ?? 0) / value) * 100).toFixed(2)));

  const isDark = document.documentElement.classList.contains('dark');
  const axisColor = isDark ? '#8592b5' : '#98a2bd';
  const splitColor = isDark ? 'rgba(255,255,255,.08)' : '#e8ecf6';
  const salesColor = chartColor('sales');
  const costColor = chartColor('cost');
  const receiptsColor = chartColor('receipts');
  const profitColor = chartColor('profit');
  /** 面积渐变：销售额 / 成本用同色系上深下浅，视觉上比默认双柱更有层次 */
  const areaFill = (hex: string) => ({
    type: 'linear' as const,
    x: 0,
    y: 0,
    x2: 0,
    y2: 1,
    colorStops: [
      { offset: 0, color: `${hex}59` },
      { offset: 1, color: `${hex}00` }
    ]
  });

  const option: echarts.EChartsOption = {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross', link: [{ xAxisIndex: 'all' }] },
      formatter: (params: any) => {
        const list = Array.isArray(params) ? params : [params];
        let html = `${list[0]?.axisValue ?? ''}<br/>`;
        list.forEach((item: any) => {
          if (item.value === null || item.value === undefined) return;
          const text = item.seriesName === '毛利率' ? `${Number(item.value).toFixed(2)}%` : formatMoney(item.value);
          html += `${item.marker}${item.seriesName}：${text}<br/>`;
        });
        return html;
      }
    },
    legend: {
      type: 'scroll',
      top: 0,
      right: 0,
      icon: 'roundRect',
      itemWidth: 10,
      itemHeight: 4,
      itemGap: 18,
      textStyle: { color: axisColor, fontSize: 12 },
      data: ['销售额', '成本', '收款', '毛利', '毛利率']
    },
    grid: { left: 56, right: 56, top: 44, bottom: 28, containLabel: true },
    xAxis: {
      type: 'category',
      data: months,
      boundaryGap: false,
      axisLine: { lineStyle: { color: splitColor } },
      axisTick: { show: false },
      axisLabel: { color: axisColor, formatter: (value: string) => (value ? value.slice(2) : value) }
    },
    yAxis: [
      {
        type: 'value',
        name: '金额',
        nameTextStyle: { color: axisColor },
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: axisColor },
        splitLine: { lineStyle: { color: splitColor, type: 'solid' } }
      },
      {
        type: 'value',
        name: '毛利率',
        nameTextStyle: { color: axisColor },
        axisLine: { show: false },
        axisTick: { show: false },
        splitLine: { show: false },
        axisLabel: { color: axisColor, formatter: '{value}%' }
      }
    ],
    series: [
      {
        name: '销售额',
        type: 'line',
        data: sales,
        smooth: 0.35,
        symbol: 'circle',
        symbolSize: 7,
        showSymbol: false,
        itemStyle: { color: salesColor },
        lineStyle: { width: 2.6, color: salesColor },
        areaStyle: { color: areaFill(salesColor) },
        /** 峰值直接标在点上，不用靠图例猜（TDesign Starter 的画法） */
        markPoint: {
          symbol: 'circle',
          symbolSize: 9,
          data: [{ type: 'max', name: '峰值' }],
          itemStyle: { color: salesColor },
          label: { show: false }
        }
      },
      {
        name: '成本',
        type: 'line',
        data: cost,
        smooth: 0.35,
        symbol: 'circle',
        symbolSize: 6,
        showSymbol: false,
        itemStyle: { color: costColor },
        lineStyle: { width: 2.2, color: costColor },
        areaStyle: { color: areaFill(costColor) }
      },
      {
        name: '收款',
        type: 'line',
        data: receipts,
        smooth: 0.35,
        showSymbol: false,
        itemStyle: { color: receiptsColor },
        lineStyle: { width: 2, color: receiptsColor }
      },
      {
        name: '毛利',
        type: 'line',
        data: profit,
        smooth: 0.35,
        showSymbol: false,
        itemStyle: { color: profitColor },
        lineStyle: { width: 2, color: profitColor }
      },
      {
        name: '毛利率',
        type: 'line',
        yAxisIndex: 1,
        data: marginRate,
        smooth: 0.35,
        connectNulls: false,
        showSymbol: false,
        itemStyle: { color: axisColor },
        lineStyle: { width: 2, type: 'dashed', color: axisColor }
      }
    ]
  };

  chart.setOption(option, { notMerge: true });
  chart.resize();
};

const handleResize = () => {
  chart?.resize();
};

/* ------------------------------ 数据加载 ------------------------------ */

const loadSummary = async () => {
  const res = await dashboardSummary();
  summary.value = res.data ?? null;
};

/** 预警卡片独立加载：失败不拖垮主数据 */
const loadStockAlert = async () => {
  try {
    const res = await dashboardStockAlert();
    stockAlert.value = res.data ?? null;
  } catch {
    stockAlert.value = null;
  }
};

const loadTrend = async () => {
  const res = await dashboardTrend(trendMonths.value);
  trend.value = res.data ?? null;
  await nextTick();
  renderChart();
};

const loadAll = async () => {
  loading.value = true;
  errorMsg.value = '';
  try {
    await Promise.all([loadSummary(), loadTrend(), loadRank(), loadStockAlert()]);
  } catch (e) {
    summary.value = null;
    trend.value = null;
    errorMsg.value = '工作台数据加载失败，请稍后重试或检查后端服务';
  } finally {
    loading.value = false;
    await nextTick();
    renderChart();
  }
};

const go = (path: string) => {
  router.push(path);
};

/** 亮暗切换后重取图表色板并重绘（ECharts 不解析 CSS 变量） */
let themeObserver: MutationObserver | null = null;

onMounted(() => {
  loadAll();
  window.addEventListener('resize', handleResize);
  themeObserver = new MutationObserver(() => renderChart());
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
});

onUnmounted(() => {
  window.removeEventListener('resize', handleResize);
  themeObserver?.disconnect();
  themeObserver = null;
  chart?.dispose();
  chart = null;
});
</script>

<style scoped lang="scss">
.welcome {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 4px 2px 12px;
}

.welcome-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--td-text-color-primary);
}

.welcome-main {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.welcome-slogan {
  font-size: 12px;
  font-weight: 500;
  color: var(--td-brand-color);
  background: var(--td-brand-color-light);
  border: 1px solid var(--td-brand-color-light-focus, rgba(0, 82, 217, 0.15));
  padding: 2px 10px;
  border-radius: 4px;
  line-height: 20px;
}

.welcome-date {
  font-size: 12px;
  color: var(--td-text-color-secondary);
}

.stat-link {
  color: var(--td-brand-color);
  cursor: pointer;

  &:hover {
    text-decoration: underline;
  }
}

.chart-box {
  width: 100%;
  height: 340px;
}

.draft-empty {
  padding: 28px 0;
  text-align: center;
  color: var(--td-text-color-placeholder);
  font-size: 13px;
}

.draft-list {
  display: flex;
  flex-direction: column;
}

.draft-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 11px 4px;
  border-bottom: 1px solid var(--td-component-stroke);
  cursor: pointer;
  transition: background-color 0.2s;

  &:last-child {
    border-bottom: none;
  }

  &:hover {
    background-color: var(--td-bg-color-container-hover);
  }
}

.draft-name {
  font-size: 13px;
  color: var(--td-text-color-primary);
}

.draft-badge {
  margin-right: 12px;

  // 计数胶囊改行内参与布局：sup 默认绝对定位 + 上标基线，压回行内块并与圆点居中对齐
  :deep(.t-badge--circle),
  :deep(.t-badge--round) {
    position: static;
    display: inline-block;
    vertical-align: middle;
    transform: none;
  }
}

.draft-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

/* 现代复合 KPI 指标卡 */
.modern-kpi {
  position: relative;
  background: var(--td-bg-color-container);
  border: 1px solid var(--td-component-stroke);
  border-radius: var(--td-radius-medium, 8px);
  padding: 16px 18px 14px;
  cursor: pointer;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
  transition: all 0.2s cubic-bezier(0.38, 0, 0.24, 1);
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  min-height: 126px;
  margin-bottom: 16px;
  overflow: hidden;

  &:hover {
    transform: translateY(-2px);
    box-shadow: 0 6px 16px -2px rgba(0, 0, 0, 0.08);
    border-color: var(--td-brand-color-light-focus, rgba(0, 82, 217, 0.3));
  }
}

.modern-kpi__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.modern-kpi__title {
  font-size: 13px;
  font-weight: 500;
  color: var(--td-text-color-secondary);
}

.modern-kpi__icon-box {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  font-size: 18px;

  &--blue {
    background: var(--td-brand-color-light, rgba(0, 82, 217, 0.08));
    color: var(--td-brand-color);
  }

  &--amber {
    background: var(--td-warning-color-light, rgba(237, 123, 47, 0.1));
    color: var(--td-warning-color);
  }

  &--indigo {
    background: rgba(114, 46, 209, 0.08);
    color: #722ed1;
  }

  &--emerald {
    background: var(--td-success-color-light, rgba(0, 168, 112, 0.1));
    color: var(--td-success-color);
  }
}

.modern-kpi__value {
  font-size: 26px;
  font-weight: 700;
  line-height: 1.15;
  color: var(--td-text-color-primary);
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.02em;
  margin-bottom: 12px;
  display: flex;
  align-items: baseline;
}

.modern-kpi__currency {
  font-size: 15px;
  font-weight: 500;
  color: var(--td-text-color-placeholder);
  margin-right: 4px;
}

.modern-kpi__number {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
}

.modern-kpi__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding-top: 10px;
  border-top: 1px dashed var(--td-component-stroke);
  min-height: 32px;
  font-size: 12px;
}

.modern-kpi__link {
  font-size: 12px;
  color: var(--td-brand-color);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 2px;
  white-space: nowrap;
  transition: opacity 0.2s;

  &:hover {
    text-decoration: underline;
  }
}
</style>
