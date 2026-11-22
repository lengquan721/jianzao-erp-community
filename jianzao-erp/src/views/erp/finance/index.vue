<template>
  <div class="p-2">
    <!-- 往来类型切换：t-tabs 的 change 事件替代 EP 的 tab-change -->
    <t-tabs v-model="partyType" class="!mb-[10px]" @change="handlePartyTypeChange">
      <t-tab-panel label="应收（客户）" value="AR" />
      <t-tab-panel label="应付（供应商）" value="AP" />
    </t-tabs>

    <!-- 汇总卡片：KPI 表面沿用 .erp-surface（无描边 + 柔阴影），栅格 24→12 -->
    <t-row :gutter="16" class="!mb-[10px]">
      <t-col :xs="6" :sm="6" :md="3">
        <div class="erp-surface stat-card">
          <div class="stat-title">{{ partyType === 'AR' ? '总应收' : '总应付' }}</div>
          <div class="stat-value stat-total">{{ statMoney(summary.totalAmount) }}</div>
        </div>
      </t-col>
      <t-col :xs="6" :sm="6" :md="3">
        <div class="erp-surface stat-card">
          <div class="stat-title">已核销</div>
          <div class="stat-value stat-settled">{{ statMoney(summary.settledAmount) }}</div>
        </div>
      </t-col>
      <t-col :xs="6" :sm="6" :md="3">
        <div class="erp-surface stat-card">
          <div class="stat-title">未核销</div>
          <div class="stat-value stat-unsettled">{{ statMoney(summary.unsettledAmount) }}</div>
        </div>
      </t-col>
      <t-col :xs="6" :sm="6" :md="3">
        <div class="erp-surface stat-card">
          <div class="stat-title">期初余额</div>
          <div class="stat-value">{{ statMoney(summary.openingAmount) }}</div>
        </div>
      </t-col>
    </t-row>

    <div class="erp-surface erp-panel">
      <div class="erp-panel__head">
        <div>
          <span class="font-medium">{{ partyType === 'AR' ? '按客户汇总' : '按供应商汇总' }}</span>
          <span class="ml-3 erp-text-secondary">点击行查看该往来单位的单据级台账明细</span>
        </div>
        <right-toolbar :search="false" @query-table="getSummary" />
      </div>

      <t-table
        v-loading="loading"
        class="erp-table"
        row-key="partyId"
        :data="parties"
        :columns="partyColumns"
        :hover="true"
        :resizable="true"
        :active-row-keys="currentParty ? [currentParty.partyId] : []"
        size="small"
        bordered
        cell-empty-content="-"
        :empty="summaryFailed ? '汇总加载失败，请重试' : '暂无往来数据'"
        @row-click="(ctx: any) => handleRowClick(ctx.row)"
        @column-resize-change="(ctx: any) => saveColumnWidths('finance/party-summary', ctx.columnsWidth)"
      >
        <template #partyName="{ row }">{{ row.partyName || '-' }}</template>
        <template #amount="{ row }">{{ formatMoney(row.amount) }}</template>
        <template #settled="{ row }">{{ formatMoney(row.settled) }}</template>
        <template #unsettled="{ row }">{{ formatMoney(row.unsettled) }}</template>
        <template #opening="{ row }">{{ formatMoney(row.opening) }}</template>
      </t-table>
      <pagination
        v-show="partyTotal > 0"
        v-model:page="summaryQuery.pageNum"
        v-model:limit="summaryQuery.pageSize"
        :total="partyTotal"
        @pagination="handleSummaryPage"
      />
    </div>

    <!-- 台账明细 -->
    <div v-if="currentParty" class="erp-surface erp-panel">
      <div class="erp-panel__head">
        <span class="font-medium">台账明细 - {{ currentParty.partyName || '-' }}</span>
        <t-button theme="primary" variant="text" @click="backToSummary">
          <template #icon><ArrowLeftIcon /></template>
          返回汇总
        </t-button>
      </div>

      <t-table
        v-loading="detailLoading"
        class="erp-table"
        row-key="id"
        :data="detailList"
        :columns="detailColumns"
        :hover="true"
        :resizable="true"
        size="small"
        bordered
        cell-empty-content="-"
        :empty="detailFailed ? '明细加载失败，请重试' : '暂无台账明细'"
        @column-resize-change="(ctx: any) => saveColumnWidths('finance/ledger-detail', ctx.columnsWidth)"
      >
        <template #bizDate="{ row }">{{ toDate(row.bizDate) }}</template>
        <template #sourceNo="{ row }">{{ sourceNo(row) }}</template>
        <template #sourceType="{ row }">{{ sourceTypeLabel(row.sourceType) }}</template>
        <template #amount="{ row }">{{ formatMoney(row.amount) }}</template>
        <template #settledAmount="{ row }">{{ formatMoney(row.settledAmount) }}</template>
        <template #unsettledAmount="{ row }">{{ formatMoney(row.unsettledAmount) }}</template>
        <template #status="{ row }">
          <StatusTag kind="arAp" :value="row.status" :status-text="row.statusText" />
        </template>
      </t-table>
      <pagination
        v-show="detailTotal > 0"
        v-model:page="detailQuery.pageNum"
        v-model:limit="detailQuery.pageSize"
        :total="detailTotal"
        @pagination="getDetail"
      />
    </div>
  </div>
</template>

<script setup name="ErpFinance" lang="ts">
import { arApDetailPage, arApSummaryPage } from '@/api/erp/finance';
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { ERP_AR_AP_SOURCE_TYPE } from '@/api/erp/enums';
import type { ErpArApSourceType, ErpPartyType, FinArApPartySummary, FinArApVO } from '@/api/erp/types';
import { formatMoney } from '@/utils/erp/calc';
// 列宽记忆：拖拽后按表名存 localStorage，进页面还原（见 hooks/useTableColumnWidth）
import { mergeSavedWidths, saveColumnWidths } from '@/hooks/useTableColumnWidth';
import StatusTag from '@/components/erp/StatusTag.vue';
import { ArrowLeftIcon } from 'tdesign-icons-vue-next';

/** 往来类型：AR 应收 / AP 应付 */
const partyType = ref<ErpPartyType>('AR');

const loading = ref(false);
const detailLoading = ref(false);

/** 汇总数据 */
const summary = reactive({
  totalAmount: 0,
  settledAmount: 0,
  unsettledAmount: 0,
  openingAmount: 0
});

const parties = ref<FinArApPartySummary[]>([]);
const detailList = ref<FinArApVO[]>([]);
/** 当前选中的往来单位 */
const currentParty = ref<FinArApPartySummary | undefined>();

/** 汇总表列配置：t-table 用 columns 数组，cell 名与 #插槽同名（DOC/35 §5） */
const partyColumns: any[] = [
  { colKey: 'partyName', title: '往来单位', align: 'center', minWidth: 200, ellipsis: true, cell: 'partyName' },
  { colKey: 'amount', title: '总额', align: 'right', width: 150, cell: 'amount' },
  { colKey: 'settled', title: '已核销', align: 'right', width: 150, cell: 'settled' },
  { colKey: 'unsettled', title: '未核销', align: 'right', width: 150, cell: 'unsettled' },
  { colKey: 'opening', title: '期初', align: 'right', width: 150, cell: 'opening' }
];
mergeSavedWidths('finance/party-summary', partyColumns);

/** 台账明细列配置：单号无值时由 sourceNo() 按来源类型回退，故整列走 cell 插槽 */
const detailColumns: any[] = [
  { colKey: 'bizDate', title: '业务日期', align: 'center', width: 120, cell: 'bizDate' },
  { colKey: 'sourceNo', title: '单号', align: 'center', minWidth: 150, ellipsis: true, cell: 'sourceNo' },
  { colKey: 'sourceType', title: '来源', align: 'center', width: 110, cell: 'sourceType' },
  { colKey: 'amount', title: '金额', align: 'right', width: 140, cell: 'amount' },
  { colKey: 'settledAmount', title: '已核销', align: 'right', width: 140, cell: 'settledAmount' },
  { colKey: 'unsettledAmount', title: '未核销', align: 'right', width: 140, cell: 'unsettledAmount' },
  { colKey: 'status', title: '状态', align: 'center', width: 100, cell: 'status' }
];
mergeSavedWidths('finance/ledger-detail', detailColumns);

const toDate = (value: string | undefined): string => (value ? String(value).slice(0, 10) : '');

const sourceTypeLabel = (sourceType?: string): string => {
  if (!sourceType) return '-';
  return ERP_AR_AP_SOURCE_TYPE[sourceType as ErpArApSourceType]?.label ?? sourceType;
};

/** 台账单号：后端 VO 未回填 sourceNo 时按来源类型回退 */
const sourceNo = (row: FinArApVO): string => {
  if (row.sourceNo) return row.sourceNo;
  if (row.sourceType === 'SALE') return `销售单 #${row.sourceId ?? '-'}`;
  if (row.sourceType === 'PURCHASE') return `采购单 #${row.sourceId ?? '-'}`;
  if (row.sourceType === 'OPENING') return `期初 #${row.sourceId ?? '-'}`;
  return '-';
};

const summaryQuery = reactive({ pageNum: 1, pageSize: 20 });
const detailQuery = reactive({ pageNum: 1, pageSize: 20 });
const partyTotal = ref(0);
const detailTotal = ref(0);
const summaryFailed = ref(false);
const detailFailed = ref(false);
let summaryRequest = 0;
let detailRequest = 0;

// 失败/加载中不能把旧页或零值当作真实全量统计展示。
const statMoney = (value: number) => (summaryFailed.value || loading.value ? '-' : formatMoney(value));

/** 加载全量金额合计 + 当前页的往来单位分组，不对 parties 做本地分页/重新求和。 */
const getSummary = async () => {
  const requestId = ++summaryRequest;
  loading.value = true;
  summaryFailed.value = false;
  parties.value = [];
  try {
    const res = await arApSummaryPage({ partyType: partyType.value, ...summaryQuery });
    if (requestId !== summaryRequest) return;
    const data = res.data;
    summary.totalAmount = Number(data.totalAmount);
    summary.settledAmount = Number(data.settledAmount);
    summary.unsettledAmount = Number(data.unsettledAmount);
    summary.openingAmount = Number(data.openingAmount);
    parties.value = data.parties;
    partyTotal.value = Number(data.total);
  } catch {
    if (requestId !== summaryRequest) return;
    summaryFailed.value = true;
    parties.value = [];
    partyTotal.value = 0;
  } finally {
    if (requestId === summaryRequest) loading.value = false;
  }
};

/** 指定往来单位的数据库明细页；日期/来源/状态不在前端二次过滤。 */
const getDetail = async () => {
  if (!currentParty.value) return;
  const requestId = ++detailRequest;
  detailLoading.value = true;
  detailFailed.value = false;
  detailList.value = [];
  try {
    const res = await arApDetailPage({ partyType: partyType.value, partyId: currentParty.value.partyId, ...detailQuery });
    if (requestId !== detailRequest) return;
    detailList.value = res.rows;
    detailTotal.value = Number(res.total);
  } catch {
    if (requestId !== detailRequest) return;
    detailFailed.value = true;
    detailList.value = [];
    detailTotal.value = 0;
  } finally {
    if (requestId === detailRequest) detailLoading.value = false;
  }
};

/** 点击另一单位必须从第一页开始，之前单位的延迟响应不能覆盖新明细。 */
const handleRowClick = async (row: FinArApPartySummary) => {
  currentParty.value = row;
  detailQuery.pageNum = 1;
  detailTotal.value = 0;
  await getDetail();
};

/** 返回汇总视图并使在途明细失效。 */
const backToSummary = () => {
  ++detailRequest;
  currentParty.value = undefined;
  detailList.value = [];
  detailTotal.value = 0;
  detailQuery.pageNum = 1;
  detailLoading.value = false;
  detailFailed.value = false;
};

const handleSummaryPage = () => {
  backToSummary();
  getSummary();
};

/** 切换应收/应付：两个分页游标均复位，旧类型请求不能覆盖当前类型。 */
const handlePartyTypeChange = async () => {
  summaryQuery.pageNum = 1;
  backToSummary();
  await getSummary();
};

onMounted(() => {
  getSummary();
});
onBeforeUnmount(() => {
  ++summaryRequest;
  ++detailRequest;
});
</script>

<style scoped lang="scss">
.stat-card {
  padding: 14px 18px;
}

.stat-title {
  font-size: 13px;
  color: var(--td-text-color-secondary);
}

.stat-value {
  margin-top: 6px;
  font-size: 24px;
  font-weight: 600;
  line-height: 1.2;
}

.stat-total {
  color: var(--td-brand-color);
}

.stat-settled {
  color: var(--td-success-color);
}

.stat-unsettled {
  color: var(--td-warning-color);
}
</style>
