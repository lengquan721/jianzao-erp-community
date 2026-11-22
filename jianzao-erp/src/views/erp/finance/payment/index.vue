<template>
  <div class="p-2">
    <t-row :gutter="20">
      <t-col :span="12">
        <transition :enter-active-class="proxy?.animate.searchAnimate.enter" :leave-active-class="proxy?.animate.searchAnimate.leave">
          <div v-show="showSearch" class="mb-[10px]">
            <t-card :bordered="false" class="erp-filter-card">
              <t-form ref="queryFormRef" :data="queryParams" class="erp-filter-row" label-align="right" :label-width="'auto'" @submit="handleQuery">
                <t-form-item label="供应商" name="supplierId">
                  <PartnerSelect v-model="queryParams.supplierId" kind="supplier" placeholder="全部供应商" clearable class="query-w-md" />
                </t-form-item>
                <t-form-item label="状态" name="status">
                  <t-select v-model="queryParams.status" placeholder="单据状态" clearable class="query-w-sm" :options="statusOptions" />
                </t-form-item>
                <div class="erp-filter-actions">
                  <t-button theme="primary" @click="handleQuery">搜索</t-button>
                  <t-button theme="default" variant="outline" @click="resetQuery">重置</t-button>
                </div>
              </t-form>
            </t-card>
          </div>
        </transition>

        <div class="erp-surface erp-panel">
          <div class="erp-panel__head">
            <t-button v-hasPermi="['erp:finance:payment:add']" theme="primary" variant="outline" @click="handleAdd">
              <template #icon><PlusIcon /></template>
              新增付款单
            </t-button>
            <right-toolbar v-model:show-search="showSearch" @query-table="getList"></right-toolbar>
          </div>

          <t-table
            class="erp-table"
            row-key="id"
            :data="paymentList"
            :columns="listColumns"
            :loading="loading"
            :hover="true"
            :resizable="true"
            size="small"
            bordered
            cell-empty-content="-"
            :empty="'暂无付款单'"
            @column-resize-change="(ctx: any) => saveColumnWidths('finance-payment/list', ctx.columnsWidth)"
          >
            <template #amount="{ row }">{{ formatMoney(row.amount) }}</template>
            <template #bizDate="{ row }">{{ toDate(row.bizDate) }}</template>
            <template #status="{ row }">
              <StatusTag kind="bill" :value="row.status" :status-text="row.statusText" />
            </template>
            <template #operation="{ row }">
              <div class="flex items-center justify-center gap-1">
                <!-- v-hasPermi 会 removeChild，与 t-tooltip 包裹的 t-button 组合会留游离触发元素（DOC/36 P6），权限判断改外层 checkPermi -->
                <t-tooltip v-if="checkPermi(['erp:finance:payment:query'])" content="查看" placement="top">
                  <t-button theme="primary" variant="text" shape="square" size="small" @click="handleView(row)">
                    <template #icon><BrowseIcon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.status === 'DRAFT' && checkPermi(['erp:finance:payment:edit'])" content="编辑" placement="top">
                  <t-button theme="primary" variant="text" shape="square" size="small" @click="handleEdit(row)">
                    <template #icon><Edit1Icon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.status === 'DRAFT' && checkPermi(['erp:finance:payment:confirm'])" content="确认" placement="top">
                  <t-button theme="success" variant="text" shape="square" size="small" @click="handleConfirm(row)">
                    <template #icon><CheckCircleIcon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.status === 'CONFIRMED' && checkPermi(['erp:finance:payment:withdraw'])" content="撤回" placement="top">
                  <t-button theme="warning" variant="text" shape="square" size="small" @click="handleWithdraw(row)">
                    <template #icon><RollbackIcon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.status === 'CONFIRMED' && checkPermi(['erp:finance:payment:void'])" content="作废" placement="top">
                  <t-button theme="danger" variant="text" shape="square" size="small" @click="handleVoid(row)">
                    <template #icon><CloseCircleIcon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.status === 'CONFIRMED' && checkPermi(['erp:print:render'])" content="打印" placement="top">
                  <t-button theme="primary" variant="text" shape="square" size="small" @click="printDialogRef?.open('payment', row.id, row.no)">
                    <template #icon><PrintIcon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.status === 'DRAFT' && checkPermi(['erp:finance:payment:remove'])" content="删除" placement="top">
                  <t-button theme="danger" variant="text" shape="square" size="small" @click="handleDelete(row)">
                    <template #icon><DeleteIcon /></template>
                  </t-button>
                </t-tooltip>
              </div>
            </template>
          </t-table>

          <pagination
            v-show="total > 0"
            v-model:page="queryParams.pageNum"
            v-model:limit="queryParams.pageSize"
            :total="total"
            @pagination="getList"
          />
        </div>
      </t-col>
    </t-row>

    <FinancePrintDialog ref="printDialogRef" />

    <!-- 新增 / 编辑 / 查看 付款单 -->
    <t-dialog v-model:visible="dialog.visible" :header="dialog.title" width="92%" top="3vh" attach="body">
      <!-- 弹窗内容超出视口时由 .t-dialog__wrap 纵向滚动；原 :body-style 落在普通 div 上无效，随迁移一并删除 -->
      <t-form ref="paymentFormRef" :data="form" :rules="rules" label-align="top" :disabled="readonly">
        <!-- TDesign 为 12 栅格：原 6/5/5/4/4 折半后为 3/2.5/2.5/2/2，整数化后拆两行，每行合计 12 -->
        <t-row :gutter="16">
          <t-col :span="3">
            <t-form-item label="供应商" name="supplierId">
              <PartnerSelect v-model="form.supplierId" kind="supplier" placeholder="请选择供应商" @change="handlePartyChange" />
            </t-form-item>
          </t-col>
          <t-col :span="3">
            <t-form-item label="业务日期" name="bizDate">
              <t-date-picker v-model="form.bizDate" value-type="YYYY-MM-DD" placeholder="请选择日期" style="width: 100%" />
            </t-form-item>
          </t-col>
          <t-col :span="3">
            <t-form-item label="付款金额" name="amount">
              <t-input-number v-model="form.amount" :decimal-places="2" :min="0" theme="normal" align="right" style="width: 100%" />
            </t-form-item>
          </t-col>
          <t-col :span="3">
            <!-- 结算账户：新建必填（后端 saveHead 同口径）；存量单只有账户文本时不标必填 -->
            <t-form-item label="结算账户" name="accountId" :required-mark="!form.id">
              <t-select v-model="form.accountId" filterable placeholder="请选择结算账户" style="width: 100%" :options="accountSelectOptions" />
            </t-form-item>
          </t-col>
        </t-row>
        <t-row :gutter="16">
          <t-col :span="12">
            <t-form-item label="备注" name="remark">
              <t-input v-model="form.remark" placeholder="备注" />
            </t-form-item>
          </t-col>
        </t-row>
      </t-form>

      <div class="erp-detail-bar">
        <div class="flex items-center gap-2">
          <span class="erp-detail-bar__title">核销明细</span>
          <t-tag v-if="readonly" theme="default" size="small">已确认/已作废单据不可修改</t-tag>
          <t-tag v-else-if="!form.supplierId" theme="default" size="small">请先选择供应商</t-tag>
        </div>
      </div>

      <t-table
        class="erp-table"
        row-key="arApId"
        :data="allocRows"
        :columns="allocColumns"
        :loading="detailLoading"
        :hover="true"
        :resizable="true"
        size="small"
        bordered
        cell-empty-content="-"
        :empty="'该供应商暂无未结清台账'"
        @column-resize-change="(ctx: any) => saveColumnWidths('finance-payment/alloc', ctx.columnsWidth)"
      >
        <template #checked="{ row }">
          <t-checkbox v-model="row.checked" :disabled="readonly" />
        </template>
        <template #sourceNo="{ row }">{{ row.sourceNo || row.arApId }}</template>
        <template #sourceType="{ row }">{{ sourceTypeLabel(row.sourceType) }}</template>
        <template #amount="{ row }">{{ formatMoney(row.amount) }}</template>
        <template #settledAmount="{ row }">{{ formatMoney(row.settledAmount) }}</template>
        <template #unsettledAmount="{ row }">{{ formatMoney(row.unsettledAmount) }}</template>
        <template #allocAmount="{ row }">
          <t-input-number
            v-model="row.allocAmount"
            :decimal-places="2"
            :min="0"
            :max="row.unsettledAmount"
            theme="normal"
            align="right"
            :disabled="readonly || !row.checked"
            style="width: 100%"
          />
        </template>
      </t-table>

      <template #footer>
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-4 text-sm">
            <span
              >核销合计：<b :class="balanced ? 'amount-ok' : 'amount-bad'">{{ formatMoney(allocSum) }}</b></span
            >
            <span
              >付款金额：<b class="amount-main">{{ formatMoney(form.amount) }}</b></span
            >
            <t-tag v-if="balanced" theme="success" size="small">核销已平衡</t-tag>
            <t-tag v-else theme="danger" size="small">核销合计须等于付款金额</t-tag>
          </div>
          <div class="flex items-center gap-2">
            <t-button theme="default" variant="outline" @click="dialog.visible = false">{{ readonly ? '关 闭' : '取 消' }}</t-button>
            <t-button v-if="!readonly" theme="primary" variant="outline" @click="submitForm(false)">保存草稿</t-button>
            <t-button v-if="!readonly && checkPermi(['erp:finance:payment:confirm'])" theme="success" :disabled="!balanced" @click="submitForm(true)"
              >保存并确认</t-button
            >
          </div>
        </div>
      </template>
    </t-dialog>
  </div>
</template>

<script setup name="ErpFinancePayment" lang="ts">
import {
  addPayment,
  arApDetail,
  confirmPayment,
  delPayment,
  getPayment,
  listPayment,
  updatePayment,
  voidPayment,
  withdrawPayment
} from '@/api/erp/finance';
import { ERP_AR_AP_SOURCE_TYPE, ERP_BILL_STATUS } from '@/api/erp/enums';
import { accountOptions as accountOptionsFn } from '@/api/erp/account';
import type { FinAccountOption } from '@/api/erp/account';
import type { ErpAmount, ErpArApSourceType, ErpBillStatus, ErpId, FinArApVO, FinPaymentForm, FinPaymentVO } from '@/api/erp/types';
import { formatMoney, isAllocationBalanced, roundMoney, sumAllocation } from '@/utils/erp/calc';
import { checkPermi } from '@/utils/permission';
// 列宽记忆：拖拽后按表名存 localStorage，进页面还原（见 hooks/useTableColumnWidth）
import { mergeSavedWidths, saveColumnWidths } from '@/hooks/useTableColumnWidth';
import PartnerSelect from '@/components/erp/PartnerSelect.vue';
import StatusTag from '@/components/erp/StatusTag.vue';
import FinancePrintDialog from '@/components/erp/FinancePrintDialog.vue';
import { BrowseIcon, CheckCircleIcon, CloseCircleIcon, DeleteIcon, Edit1Icon, PlusIcon, PrintIcon, RollbackIcon } from 'tdesign-icons-vue-next';

interface AllocRow {
  arApId: ErpId;
  bizDate?: string;
  sourceNo?: string;
  sourceType?: string;
  amount: number;
  settledAmount: number;
  unsettledAmount: number;
  checked: boolean;
  allocAmount: number;
}

interface PaymentFormState {
  id?: ErpId;
  supplierId?: ErpId;
  bizDate: string;
  amount: number;
  /** 结算账户ID；存量单为空时只读展示 account 文本 */
  accountId?: ErpId;
  /** 存量账户文本（只读回显） */
  accountText: string;
  remark: string;
}

interface PaymentQueryState extends PageQuery {
  supplierId?: ErpId;
  status: string;
}

const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const printDialogRef = ref<InstanceType<typeof FinancePrintDialog>>();

const statusOptions = Object.entries(ERP_BILL_STATUS).map(([value, item]) => ({ value: value as ErpBillStatus, label: item.label }));

/** 主列表列配置：t-table 用 columns 数组，插槽名与 cell 字符串同名（DOC/35 §5） */
const listColumns: any[] = [
  { colKey: 'no', title: '单号', align: 'center', minWidth: 140, ellipsis: true },
  { colKey: 'supplierName', title: '供应商', align: 'center', minWidth: 160, ellipsis: true },
  { colKey: 'amount', title: '付款金额', align: 'right', width: 140, cell: 'amount' },
  { colKey: 'bizDate', title: '业务日期', align: 'center', width: 120, cell: 'bizDate' },
  { colKey: 'account', title: '结算账户', align: 'center', minWidth: 120, ellipsis: true },
  { colKey: 'status', title: '状态', align: 'center', width: 100, cell: 'status' },
  { colKey: 'remark', title: '备注', align: 'center', minWidth: 140, ellipsis: true },
  { colKey: 'operation', title: '操作', align: 'center', width: 160, cell: 'operation' }
];
mergeSavedWidths('finance-payment/list', listColumns);

/** 核销明细列配置：核销列为行内勾选，本次核销金额为行内数字控件 */
const allocColumns: any[] = [
  { colKey: 'checked', title: '核销', width: 60, align: 'center', cell: 'checked' },
  { colKey: 'bizDate', title: '业务日期', align: 'center', width: 120 },
  { colKey: 'sourceNo', title: '单号', align: 'center', minWidth: 150, ellipsis: true, cell: 'sourceNo' },
  { colKey: 'sourceType', title: '来源', align: 'center', width: 110, cell: 'sourceType' },
  { colKey: 'amount', title: '金额', align: 'right', width: 140, cell: 'amount' },
  { colKey: 'settledAmount', title: '已核销', align: 'right', width: 140, cell: 'settledAmount' },
  { colKey: 'unsettledAmount', title: '未核销', align: 'right', width: 140, cell: 'unsettledAmount' },
  { colKey: 'allocAmount', title: '本次核销金额', width: 170, align: 'right', cell: 'allocAmount' }
];
mergeSavedWidths('finance-payment/alloc', allocColumns);

/** 启用账户下拉（DOC/25 §4.5，页面加载取一次） */
const accountOptions = ref<FinAccountOption[]>([]);
/** 账户下拉选项：t-select 需要 { label, value }，不能用 v-for el-option */
const accountSelectOptions = computed(() => accountOptions.value.map((acc) => ({ label: acc.name, value: acc.id })));
const loadAccountOptions = async () => {
  try {
    const res = await accountOptionsFn();
    accountOptions.value = res.data ?? [];
  } catch {
    accountOptions.value = [];
  }
};

const paymentList = ref<FinPaymentVO[]>([]);
const loading = ref(true);
const detailLoading = ref(false);
const showSearch = ref(true);
const total = ref(0);
const paymentFormRef = ref<any>();
/** 查询表单：已换 t-form，ref 提供 reset()（EP 是 resetFields()） */
const queryFormRef = ref<any>();

const dialog = reactive<DialogOption>({
  visible: false,
  title: ''
});

/** 只读（查看已确认/已作废单据） */
const readonly = ref(false);

/** 核销明细行 */
const allocRows = ref<AllocRow[]>([]);

const createForm = (): PaymentFormState => ({
  id: undefined,
  supplierId: undefined,
  bizDate: new Date().toISOString().slice(0, 10),
  amount: 0,
  accountId: undefined,
  accountText: '',
  remark: ''
});

const form = reactive<PaymentFormState>(createForm());

const queryParams = reactive<PaymentQueryState>({
  pageNum: 1,
  pageSize: 10,
  supplierId: undefined,
  status: ''
});

// t-form 的 rules 形状与 EP 的 ElFormRules 不兼容，类型放宽
const rules: Record<string, any> = {
  supplierId: [{ required: true, message: '供应商不能为空', trigger: 'change' }],
  bizDate: [{ required: true, message: '业务日期不能为空', trigger: 'change' }],
  amount: [{ required: true, message: '付款金额不能为空', trigger: 'blur' }]
};

const num = (value: number | string | undefined | null): number => {
  const n = Number(value ?? 0);
  return Number.isFinite(n) ? n : 0;
};

/** 日期只取 yyyy-MM-dd */
const toDate = (value: string | undefined): string => (value ? String(value).slice(0, 10) : '');

const sourceTypeLabel = (sourceType?: string): string => {
  if (!sourceType) return '-';
  return ERP_AR_AP_SOURCE_TYPE[sourceType as ErpArApSourceType]?.label ?? sourceType;
};

/** 选中的核销明细 */
const selectedAllocations = computed(() =>
  allocRows.value.filter((row) => row.checked && row.allocAmount > 0).map((row) => ({ arApId: row.arApId, amount: row.allocAmount }))
);

/** 核销合计 */
const allocSum = computed(() => sumAllocation(selectedAllocations.value));

/** 核销合计是否与付款金额平衡 */
const balanced = computed(() => isAllocationBalanced(form.amount, selectedAllocations.value));

/** 详情与台账共用归属序号，关闭、重开与卸载也会失效。 */
let ledgerSeq = 0;
watch(
  () => dialog.visible,
  (visible) => {
    if (!visible) {
      ++ledgerSeq;
      allocRows.value = [];
      detailLoading.value = false;
    }
  },
  { flush: 'sync' }
);
onBeforeUnmount(() => {
  ++ledgerSeq;
});

/** 加载供应商未结清台账 */
const loadLedger = async (supplierId?: ErpId) => {
  const seq = ++ledgerSeq;
  allocRows.value = [];
  detailLoading.value = !!supplierId;
  if (!supplierId) return seq;
  try {
    const res = await arApDetail('AP', supplierId, true);
    if (seq !== ledgerSeq) return seq;
    const list = (res.data ?? []) as FinArApVO[];
    allocRows.value = list
      .filter((item) => item.status !== 'VOID' && num(item.unsettledAmount) > 0)
      .map((item) => ({
        arApId: item.id,
        bizDate: toDate(item.bizDate),
        sourceNo: item.sourceNo,
        sourceType: item.sourceType,
        amount: num(item.amount),
        settledAmount: num(item.settledAmount),
        unsettledAmount: num(item.unsettledAmount),
        checked: false,
        allocAmount: 0
      }));
  } catch {
    if (seq === ledgerSeq) allocRows.value = [];
  } finally {
    if (seq === ledgerSeq) detailLoading.value = false;
  }
  return seq;
};

/** 回显已保存的核销明细并与台账合并 */
const applyAllocations = (allocations: { arApId: ErpId; amount: ErpAmount; sourceNo?: string; sourceType?: string }[]) => {
  (allocations ?? []).forEach((alloc) => {
    const hit = allocRows.value.find((row) => String(row.arApId) === String(alloc.arApId));
    if (hit) {
      hit.sourceNo ||= alloc.sourceNo;
      hit.sourceType ||= alloc.sourceType;
      hit.checked = true;
      hit.allocAmount = num(alloc.amount);
    } else {
      allocRows.value.push({
        arApId: alloc.arApId,
        bizDate: undefined,
        sourceNo: alloc.sourceNo,
        sourceType: alloc.sourceType,
        amount: 0,
        settledAmount: 0,
        unsettledAmount: num(alloc.amount),
        checked: true,
        allocAmount: num(alloc.amount)
      });
    }
  });
};

/** 查询付款单列表 */
const getList = async () => {
  loading.value = true;
  try {
    const res = await listPayment(queryParams);
    paymentList.value = res.rows ?? [];
    total.value = res.total ?? 0;
  } finally {
    loading.value = false;
  }
};

/** 搜索按钮操作 */
const handleQuery = () => {
  queryParams.pageNum = 1;
  getList();
};

/** 重置按钮操作 */
const resetQuery = () => {
  queryFormRef.value?.reset();
  queryParams.pageNum = 1;
  queryParams.supplierId = undefined;
  queryParams.status = '';
  handleQuery();
};

/** 表单重置 */
const resetForm = () => {
  ++ledgerSeq;
  detailLoading.value = false;
  Object.assign(form, createForm());
  allocRows.value = [];
  readonly.value = false;
  paymentFormRef.value?.clearValidate();
};

/** 新增按钮操作 */
const handleAdd = async () => {
  resetForm();
  dialog.visible = true;
  dialog.title = '新增付款单';
};

/** 切换供应商时重新加载台账 */
const handlePartyChange = async () => {
  if (readonly.value) return;
  await loadLedger(form.supplierId);
};

/** 载入详情 */
const loadDetail = async (id: ErpId, isReadonly: boolean) => {
  let seq = ++ledgerSeq;
  detailLoading.value = true;
  try {
    const res = await getPayment(id);
    if (seq !== ledgerSeq) return;
    const detail = res.data;
    Object.assign(form, {
      id: detail.id,
      supplierId: detail.supplierId,
      bizDate: toDate(detail.bizDate),
      amount: num(detail.amount),
      accountId: detail.accountId ?? undefined,
      accountText: detail.account || '',
      remark: detail.remark || ''
    });
    readonly.value = isReadonly;
    seq = await loadLedger(detail.supplierId);
    if (seq !== ledgerSeq) return;
    applyAllocations(detail.allocations ?? []);
  } finally {
    if (seq === ledgerSeq) detailLoading.value = false;
  }
};

/** 编辑按钮操作 */
const handleEdit = async (row: FinPaymentVO) => {
  resetForm();
  dialog.visible = true;
  dialog.title = `编辑付款单 ${row.no ?? ''}`;
  await loadDetail(row.id, false);
};

/** 查看按钮操作 */
const handleView = async (row: FinPaymentVO) => {
  resetForm();
  dialog.visible = true;
  dialog.title = `查看付款单 ${row.no ?? ''}`;
  await loadDetail(row.id, true);
};

/** 组装提交数据 */
const buildPayload = (confirm: boolean): FinPaymentForm => ({
  id: form.id,
  supplierId: form.supplierId,
  bizDate: form.bizDate,
  amount: roundMoney(form.amount),
  accountId: form.accountId,
  account: form.accountText,
  remark: form.remark,
  confirm,
  allocations: selectedAllocations.value.map((item) => ({ arApId: item.arApId, amount: roundMoney(item.amount) }))
});

/** 提交按钮 */
const submitForm = async (confirm: boolean) => {
  // t-form 的 validate() 失败不 reject：全通过才回 true，其余回错误对象（es/form/form.mjs formatValidateResult）
  const r = await paymentFormRef.value?.validate().catch(() => false);
  const ok = r === true || r === undefined;
  if (!ok || readonly.value || (confirm && !checkPermi(['erp:finance:payment:confirm']))) return;
  if (!form.supplierId) {
    proxy?.$modal.msgWarning('请选择供应商');
    return;
  }
  if (!(form.amount > 0)) {
    proxy?.$modal.msgWarning('付款金额必须大于 0');
    return;
  }
  // 结算账户：新建必填；存量单沿用 account 文本（与后端 saveHead 口径一致）
  if ((!form.id && !form.accountId) || (form.id && !form.accountId && !form.accountText)) {
    proxy?.$modal.msgWarning('请选择结算账户');
    return;
  }
  if (!balanced.value) {
    proxy?.$modal.msgWarning(`核销合计(${formatMoney(allocSum.value)})必须等于付款金额(${formatMoney(form.amount)})`);
    return;
  }
  const payload = buildPayload(confirm);
  if (form.id) {
    await updatePayment(payload);
  } else {
    await addPayment(payload);
  }
  proxy?.$modal.msgSuccess(confirm ? '已保存并确认' : '已保存草稿');
  dialog.visible = false;
  await getList();
};

/** 确认按钮操作 */
const handleConfirm = async (row: FinPaymentVO) => {
  try {
    await proxy?.$modal.confirm(`是否确认付款单「${row.no ?? ''}」？确认后将核销对应应付台账。`);
  } catch {
    return;
  }
  await confirmPayment(row.id);
  proxy?.$modal.msgSuccess('已确认');
  await getList();
};

/** 撤回按钮操作 */
const handleWithdraw = async (row: FinPaymentVO) => {
  try {
    await proxy?.$modal.confirm(`是否撤回付款单「${row.no ?? ''}」？撤回后核销将被反核销并回到草稿。`);
  } catch {
    return;
  }
  await withdrawPayment(row.id);
  proxy?.$modal.msgSuccess('已撤回');
  await getList();
};

/** 作废按钮操作 */
const handleVoid = async (row: FinPaymentVO) => {
  try {
    await proxy?.$modal.confirm(`是否作废付款单「${row.no ?? ''}」？作废为终态，不可再修改。`);
  } catch {
    return;
  }
  await voidPayment(row.id);
  proxy?.$modal.msgSuccess('已作废');
  await getList();
};

/** 删除按钮操作（仅草稿可删） */
const handleDelete = async (row: FinPaymentVO) => {
  try {
    await proxy?.$modal.confirm(`是否删除付款单「${row.no ?? ''}」？删除后不可恢复。`);
  } catch {
    return;
  }
  await delPayment(row.id);
  proxy?.$modal.msgSuccess('已删除');
  await getList();
};

onMounted(() => {
  getList();
  loadAccountOptions();
});
</script>

<style scoped lang="scss">
/* 功能色当文字用：走 --app-text-* 固定两档（实测 ≥4.5:1），不用 EP 的 dark-2 色阶 */
.amount-main {
  color: var(--app-text-primary);
}

.amount-ok {
  color: var(--app-text-success);
}

.amount-bad {
  color: var(--app-text-danger);
}
</style>
