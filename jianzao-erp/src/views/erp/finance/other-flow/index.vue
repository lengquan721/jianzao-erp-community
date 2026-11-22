<template>
  <div class="p-2">
    <!-- 收支类型切换：tab 值即查询条件 flowType，t-tabs 的 change 事件替代 EP 的 tab-change -->
    <t-tabs v-model="flowType" class="!mb-[10px]" @change="handleFlowTypeChange">
      <t-tab-panel label="其他收入" value="INCOME" />
      <t-tab-panel label="其他支出" value="EXPENSE" />
    </t-tabs>

    <t-row :gutter="20">
      <t-col :span="12">
        <transition :enter-active-class="proxy?.animate.searchAnimate.enter" :leave-active-class="proxy?.animate.searchAnimate.leave">
          <div v-show="showSearch" class="erp-row-container">
            <t-card :bordered="false" class="erp-filter-card" title="筛选条件">
              <t-form ref="queryFormRef" :data="queryParams" class="erp-filter-row" label-align="right" :label-width="'auto'" @submit="handleQuery">
                <t-form-item label="分类" name="category">
                  <t-input v-model="queryParams.category" placeholder="请输入分类" clearable class="query-w-md" @enter="handleQuery" />
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
            <div class="flex items-center gap-2">
              <t-button v-hasPermi="['erp:finance:other-flow:add']" theme="primary" variant="outline" @click="handleAdd">
                <template #icon><PlusIcon /></template>
                新增{{ flowType === 'INCOME' ? '其他收入' : '其他支出' }}
              </t-button>
            </div>
            <right-toolbar v-model:show-search="showSearch" @query-table="getList"></right-toolbar>
          </div>

          <t-table
            class="erp-table"
            row-key="id"
            :data="flowList"
            :columns="listColumns"
            :loading="loading"
            :hover="true"
            :resizable="true"
            size="small"
            cell-empty-content="-"
            @column-resize-change="(ctx: any) => saveColumnWidths('finance-other-flow/list', ctx.columnsWidth)"
          >
            <template #flowType="{ row }">
              <t-tag :theme="(ERP_FLOW_TYPE[row.flowType]?.type as any) || 'default'" size="small">
                {{ ERP_FLOW_TYPE[row.flowType]?.label || row.flowType }}
              </t-tag>
            </template>
            <template #amount="{ row }">{{ formatMoney(row.amount) }}</template>
            <template #bizDate="{ row }">{{ toDate(row.bizDate) }}</template>
            <template #affectCost="{ row }">{{ row.affectCost === 1 ? '是' : '否' }}</template>
            <template #status="{ row }">
              <StatusTag kind="bill" :value="row.status" :status-text="row.statusText" />
            </template>
            <template #operation="{ row }">
              <div class="flex items-center justify-center gap-1">
                <!-- 权限判断挂外层 t-tooltip：v-hasPermi 在 mounted 里删节点，会与 tooltip 的包裹结构打架 -->
                <t-tooltip v-if="checkPermi(['erp:finance:other-flow:query'])" content="查看" placement="top">
                  <t-button theme="primary" variant="text" shape="square" size="small" @click="handleView(row)">
                    <template #icon><BrowseIcon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.status === 'DRAFT' && checkPermi(['erp:finance:other-flow:edit'])" content="编辑" placement="top">
                  <t-button theme="primary" variant="text" shape="square" size="small" @click="handleEdit(row)">
                    <template #icon><Edit1Icon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.status === 'DRAFT' && checkPermi(['erp:finance:other-flow:confirm'])" content="确认" placement="top">
                  <t-button theme="success" variant="text" shape="square" size="small" @click="handleConfirm(row)">
                    <template #icon><CheckCircleIcon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.status === 'CONFIRMED' && checkPermi(['erp:finance:other-flow:withdraw'])" content="撤回" placement="top">
                  <t-button theme="warning" variant="text" shape="square" size="small" @click="handleWithdraw(row)">
                    <template #icon><RollbackIcon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.status === 'CONFIRMED' && checkPermi(['erp:finance:other-flow:void'])" content="作废" placement="top">
                  <t-button theme="danger" variant="text" shape="square" size="small" @click="handleVoid(row)">
                    <template #icon><CloseCircleIcon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.status === 'DRAFT' && checkPermi(['erp:finance:other-flow:remove'])" content="删除" placement="top">
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

    <!-- 新增 / 编辑 / 查看 其他收支 -->
    <t-dialog v-model:visible="dialog.visible" :header="dialog.title" width="520px" attach="body">
      <t-form ref="flowFormRef" :data="form" :rules="rules" label-align="right" label-width="90px" :disabled="readonly">
        <t-form-item label="收支类型" name="flowType">
          <t-select v-model="form.flowType" style="width: 100%" :disabled="true" :options="flowTypeOptions" />
        </t-form-item>
        <t-form-item label="分类" name="category">
          <t-input v-model="form.category" placeholder="如：办公费/运费/水电费" />
        </t-form-item>
        <t-form-item label="金额" name="amount">
          <t-input-number v-model="form.amount" :decimal-places="2" :min="0" theme="normal" align="right" style="width: 100%" />
        </t-form-item>
        <t-form-item label="业务日期" name="bizDate">
          <t-date-picker v-model="form.bizDate" value-type="YYYY-MM-DD" placeholder="请选择日期" style="width: 100%" />
        </t-form-item>
        <!-- 结算账户：新建必填（后端 saveHead 同口径）；存量单只有账户文本时不标必填 -->
        <t-form-item label="结算账户" name="accountId" :required-mark="!form.id">
          <t-input v-if="readonly && !form.accountId" :model-value="form.accountText" placeholder="存量单无结算账户引用" disabled />
          <t-select
            v-else
            v-model="form.accountId"
            filterable
            placeholder="请选择结算账户"
            :disabled="readonly"
            style="width: 100%"
            :options="accountSelectOptions"
          />
        </t-form-item>
        <t-form-item v-if="form.flowType === 'EXPENSE'" label="计入综合成本">
          <t-checkbox v-model="form.affectCost">该支出计入综合成本</t-checkbox>
        </t-form-item>
        <t-form-item label="备注" name="remark">
          <t-textarea v-model="form.remark" :autosize="{ minRows: 2, maxRows: 4 }" placeholder="请输入备注" />
        </t-form-item>
      </t-form>
      <template #footer>
        <div class="flex items-center justify-between">
          <span class="erp-text-secondary">
            {{ form.flowType === 'INCOME' ? '收入不影响综合成本' : '勾选后该支出将计入综合成本' }}
          </span>
          <div class="flex items-center gap-2">
            <t-button theme="default" variant="outline" @click="dialog.visible = false">{{ readonly ? '关 闭' : '取 消' }}</t-button>
            <t-button v-if="!readonly" theme="primary" variant="outline" @click="submitForm(false)">保存草稿</t-button>
            <t-button v-if="!readonly && checkPermi(['erp:finance:other-flow:confirm'])" theme="success" @click="submitForm(true)"
              >保存并确认</t-button
            >
          </div>
        </div>
      </template>
    </t-dialog>
  </div>
</template>

<script setup name="ErpFinanceOtherFlow" lang="ts">
import {
  addOtherFlow,
  confirmOtherFlow,
  delOtherFlow,
  getOtherFlow,
  listOtherFlow,
  updateOtherFlow,
  voidOtherFlow,
  withdrawOtherFlow
} from '@/api/erp/finance';
import { accountOptions as accountOptionsFn } from '@/api/erp/account';
import type { FinAccountOption } from '@/api/erp/account';
import { ERP_BILL_STATUS, ERP_FLOW_TYPE } from '@/api/erp/enums';
import type { ErpBillStatus, ErpFlowType, ErpId, FinOtherFlowForm, FinOtherFlowQuery, FinOtherFlowVO } from '@/api/erp/types';
import { formatMoney, roundMoney } from '@/utils/erp/calc';
import { checkPermi } from '@/utils/permission';
// 列宽记忆：拖拽后按表名存 localStorage，进页面还原（见 hooks/useTableColumnWidth）
import { mergeSavedWidths, saveColumnWidths } from '@/hooks/useTableColumnWidth';
import StatusTag from '@/components/erp/StatusTag.vue';
import { BrowseIcon, CheckCircleIcon, CloseCircleIcon, DeleteIcon, Edit1Icon, PlusIcon, RollbackIcon } from 'tdesign-icons-vue-next';

interface FlowFormState {
  id?: ErpId;
  flowType: ErpFlowType;
  category: string;
  amount: number;
  bizDate: string;
  /** 结算账户ID；存量单为空时只读展示 account 文本 */
  accountId?: ErpId;
  /** 存量账户文本（只读回显） */
  accountText: string;
  affectCost: boolean;
  remark: string;
}

interface FlowQueryState extends PageQuery {
  category: string;
  status: string;
}

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const statusOptions = Object.entries(ERP_BILL_STATUS).map(([value, item]) => ({ value: value as ErpBillStatus, label: item.label }));

/** 弹窗内收支类型下拉：固定两项，与 tab 取值一致 */
const flowTypeOptions = [
  { label: '其他收入', value: 'INCOME' },
  { label: '其他支出', value: 'EXPENSE' }
];

/** 启用账户下拉（DOC/25 §4.5，页面加载取一次） */
const accountOptions = ref<FinAccountOption[]>([]);
const loadAccountOptions = async () => {
  try {
    const res = await accountOptionsFn();
    accountOptions.value = res.data ?? [];
  } catch {
    accountOptions.value = [];
  }
};

/** 账户下拉选项：t-select 只认 {label,value} 数组，不能直接传 VO 列表 */
const accountSelectOptions = computed(() => accountOptions.value.map((acc) => ({ label: acc.name, value: acc.id })));

/** 当前收支类型 */
const flowType = ref<ErpFlowType>('INCOME');

const flowList = ref<FinOtherFlowVO[]>([]);
const loading = ref(true);
const showSearch = ref(true);
const total = ref(0);
/** 表格与表单 ref：已换 t-table/t-form，实例类型不再来自 Element Plus */
const flowFormRef = ref<any>();
const queryFormRef = ref<any>();

/** 主列表列配置：t-table 用 columns 数组，插槽名与 cell 字符串同名（DOC/35 §5） */
const listColumns: any[] = [
  { colKey: 'no', title: '单号', align: 'center', minWidth: 140, ellipsis: true },
  { colKey: 'flowType', title: '类型', align: 'center', width: 100, cell: 'flowType' },
  { colKey: 'category', title: '分类', align: 'center', minWidth: 130, ellipsis: true },
  { colKey: 'amount', title: '金额', align: 'right', width: 140, cell: 'amount' },
  { colKey: 'bizDate', title: '业务日期', align: 'center', width: 120, cell: 'bizDate' },
  { colKey: 'account', title: '结算账户', align: 'center', minWidth: 120, ellipsis: true },
  { colKey: 'affectCost', title: '计入成本', align: 'center', width: 100, cell: 'affectCost' },
  { colKey: 'status', title: '状态', align: 'center', width: 100, cell: 'status' },
  { colKey: 'remark', title: '备注', align: 'center', minWidth: 140, ellipsis: true },
  { colKey: 'operation', title: '操作', align: 'center', width: 160, cell: 'operation' }
];
mergeSavedWidths('finance-other-flow/list', listColumns);

const dialog = reactive<DialogOption>({
  visible: false,
  title: ''
});

/** 只读（查看已确认/已作废单据） */
const readonly = ref(false);

const createForm = (): FlowFormState => ({
  id: undefined,
  flowType: 'INCOME',
  category: '',
  amount: 0,
  bizDate: new Date().toISOString().slice(0, 10),
  accountId: undefined,
  accountText: '',
  affectCost: false,
  remark: ''
});

const form = reactive<FlowFormState>(createForm());

const queryParams = reactive<FlowQueryState>({
  pageNum: 1,
  pageSize: 10,
  category: '',
  status: ''
});

/** 表单校验规则：t-form 的 rules 形状与 EP 的 ElFormRules 不兼容，类型放宽为 Record */
const rules: Record<string, any> = {
  flowType: [{ required: true, message: '收支类型不能为空', trigger: 'change' }],
  bizDate: [{ required: true, message: '业务日期不能为空', trigger: 'change' }],
  amount: [{ required: true, message: '金额不能为空', trigger: 'blur' }]
};

const num = (value: number | string | undefined | null): number => {
  const n = Number(value ?? 0);
  return Number.isFinite(n) ? n : 0;
};

/** 日期只取 yyyy-MM-dd */
const toDate = (value: string | undefined): string => (value ? String(value).slice(0, 10) : '');

/** 查询其他收支列表 */
const getList = async () => {
  loading.value = true;
  try {
    const params = { ...queryParams, flowType: flowType.value } as FinOtherFlowQuery & { category?: string };
    const res = await listOtherFlow(params);
    flowList.value = res.rows ?? [];
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
  // t-form 的实例方法叫 reset()（EP 是 resetFields()）
  queryFormRef.value?.reset();
  queryParams.pageNum = 1;
  queryParams.category = '';
  queryParams.status = '';
  handleQuery();
};

/** 切换收支类型 */
const handleFlowTypeChange = async () => {
  queryParams.pageNum = 1;
  await getList();
};

/** 表单重置 */
const resetForm = () => {
  Object.assign(form, createForm());
  form.flowType = flowType.value;
  readonly.value = false;
  flowFormRef.value?.clearValidate();
};

/** 新增按钮操作 */
const handleAdd = () => {
  resetForm();
  dialog.visible = true;
  dialog.title = flowType.value === 'INCOME' ? '新增其他收入' : '新增其他支出';
};

/** 载入详情 */
const loadDetail = async (id: ErpId, isReadonly: boolean) => {
  const res = await getOtherFlow(id);
  const detail = res.data;
  Object.assign(form, {
    id: detail.id,
    flowType: detail.flowType,
    category: detail.category || '',
    amount: num(detail.amount),
    bizDate: toDate(detail.bizDate),
    accountId: detail.accountId ?? undefined,
    accountText: detail.account || '',
    affectCost: detail.affectCost === 1,
    remark: detail.remark || ''
  });
  readonly.value = isReadonly;
};

/** 编辑按钮操作 */
const handleEdit = async (row: FinOtherFlowVO) => {
  resetForm();
  dialog.visible = true;
  dialog.title = `编辑${row.flowType === 'INCOME' ? '其他收入' : '其他支出'} ${row.no ?? ''}`;
  await loadDetail(row.id, false);
};

/** 查看按钮操作 */
const handleView = async (row: FinOtherFlowVO) => {
  resetForm();
  dialog.visible = true;
  dialog.title = `查看${row.flowType === 'INCOME' ? '其他收入' : '其他支出'} ${row.no ?? ''}`;
  await loadDetail(row.id, true);
};

/** 组装提交数据 */
const buildPayload = (confirm: boolean): FinOtherFlowForm => ({
  id: form.id,
  flowType: form.flowType,
  category: form.category,
  amount: roundMoney(form.amount),
  bizDate: form.bizDate,
  accountId: form.accountId,
  account: form.accountText,
  affectCost: form.flowType === 'EXPENSE' && form.affectCost ? 1 : 0,
  remark: form.remark,
  confirm
});

/** 提交按钮 */
const submitForm = async (confirm: boolean) => {
  // t-form 的 validate() 失败是 resolve(false)（或错误对象），不是 reject
  const r = await flowFormRef.value?.validate().catch(() => false);
  const ok = r === true || r === undefined;
  if (!ok || readonly.value || (confirm && !checkPermi(['erp:finance:other-flow:confirm']))) return;
  if (!(form.amount > 0)) {
    proxy?.$modal.msgWarning('金额必须大于 0');
    return;
  }
  // 结算账户：新建必填；存量单沿用 account 文本（与后端 saveHead 口径一致）
  if ((!form.id && !form.accountId) || (form.id && !form.accountId && !form.accountText)) {
    proxy?.$modal.msgWarning('请选择结算账户');
    return;
  }
  const payload = buildPayload(confirm);
  if (form.id) {
    await updateOtherFlow(payload);
  } else {
    await addOtherFlow(payload);
  }
  proxy?.$modal.msgSuccess(confirm ? '已保存并确认' : '已保存草稿');
  dialog.visible = false;
  await getList();
};

/** 确认按钮操作 */
const handleConfirm = async (row: FinOtherFlowVO) => {
  try {
    await proxy?.$modal.confirm(`是否确认「${row.no ?? ''}」？确认后不可再修改。`);
  } catch {
    return;
  }
  await confirmOtherFlow(row.id);
  proxy?.$modal.msgSuccess('已确认');
  await getList();
};

/** 撤回按钮操作 */
const handleWithdraw = async (row: FinOtherFlowVO) => {
  try {
    await proxy?.$modal.confirm(`是否撤回「${row.no ?? ''}」？撤回后回到草稿可继续修改。`);
  } catch {
    return;
  }
  await withdrawOtherFlow(row.id);
  proxy?.$modal.msgSuccess('已撤回');
  await getList();
};

/** 作废按钮操作 */
const handleVoid = async (row: FinOtherFlowVO) => {
  try {
    await proxy?.$modal.confirm(`是否作废「${row.no ?? ''}」？作废为终态，不可再修改。`);
  } catch {
    return;
  }
  await voidOtherFlow(row.id);
  proxy?.$modal.msgSuccess('已作废');
  await getList();
};

/** 删除按钮操作（仅草稿可删） */
const handleDelete = async (row: FinOtherFlowVO) => {
  try {
    await proxy?.$modal.confirm(`是否删除「${row.no ?? ''}」？删除后不可恢复。`);
  } catch {
    return;
  }
  await delOtherFlow(row.id);
  proxy?.$modal.msgSuccess('已删除');
  await getList();
};

onMounted(() => {
  getList();
  loadAccountOptions();
});
</script>
