<template>
  <div class="p-2">
    <t-row :gutter="20">
      <t-col :span="12">
        <transition :enter-active-class="proxy?.animate.searchAnimate.enter" :leave-active-class="proxy?.animate.searchAnimate.leave">
          <div v-show="showSearch" class="mb-[10px]">
            <t-card :bordered="false" class="erp-filter-card">
              <t-form ref="queryFormRef" :data="queryParams" class="erp-filter-row" label-align="right" :label-width="'auto'" @submit="handleQuery">
                <t-form-item label="关键字" name="keyword">
                  <t-input v-model="queryParams.keyword" placeholder="编码/名称" clearable class="query-w-md" @enter="handleQuery" />
                </t-form-item>
                <t-form-item label="类型" name="type">
                  <t-select v-model="queryParams.type" placeholder="全部类型" clearable class="query-w-sm" :options="accountTypeOptions" />
                </t-form-item>
                <t-form-item label="状态" name="status">
                  <t-select v-model="queryParams.status" placeholder="全部状态" clearable class="query-w-sm" :options="statusOptions" />
                </t-form-item>
                <div class="erp-filter-actions">
                  <t-button theme="primary" @click="handleQuery">
                    <template #icon><SearchIcon /></template>
                    搜索
                  </t-button>
                  <t-button theme="default" variant="outline" @click="resetQuery">
                    <template #icon><RefreshIcon /></template>
                    重置
                  </t-button>
                </div>
              </t-form>
            </t-card>
          </div>
        </transition>

        <div class="erp-surface erp-panel">
          <div class="erp-panel__head">
            <t-button v-hasPermi="['erp:finance:account:add']" theme="primary" variant="outline" @click="handleAdd">
              <template #icon><PlusIcon /></template>
              新增账户
            </t-button>
            <right-toolbar v-model:show-search="showSearch" @query-table="getList"></right-toolbar>
          </div>

          <t-table
            class="erp-table"
            row-key="id"
            :data="accountList"
            :columns="listColumns"
            :loading="loading"
            :hover="true"
            :resizable="true"
            size="small"
            bordered
            cell-empty-content="-"
            :empty="'暂无结算账户'"
            @column-resize-change="(ctx: any) => saveColumnWidths('finance-account/list', ctx.columnsWidth)"
          >
            <template #type="{ row }">{{ ERP_ACCOUNT_TYPE[row.type as ErpAccountType]?.label ?? row.type }}</template>
            <template #openingBalance="{ row }">{{ formatMoney(row.openingBalance) }}</template>
            <template #balance="{ row }">
              <span :class="Number(row.balance ?? 0) < 0 ? 'amount-bad' : ''">{{ formatMoney(row.balance) }}</span>
            </template>
            <template #status="{ row }">
              <t-tag :theme="row.status === 1 ? 'success' : 'default'" size="small">{{ row.status === 1 ? '启用' : '停用' }}</t-tag>
            </template>
            <template #operation="{ row }">
              <div class="flex items-center justify-center gap-1">
                <!-- 权限判断挂外层 t-tooltip：v-hasPermi 在 mounted 里删节点，会与 tooltip 的包裹结构打架 -->
                <t-tooltip v-if="checkPermi(['erp:finance:account:query'])" content="流水" placement="top">
                  <t-button theme="primary" variant="text" shape="square" size="small" @click="handleFlows(row)">
                    <template #icon><ViewListIcon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="checkPermi(['erp:finance:account:edit'])" content="编辑" placement="top">
                  <t-button theme="primary" variant="text" shape="square" size="small" @click="handleEdit(row)">
                    <template #icon><Edit1Icon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="checkPermi(['erp:finance:account:remove'])" content="删除（仅未被引用的错档）" placement="top">
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

    <!-- 新增 / 编辑账户 -->
    <t-dialog v-model:visible="dialog.visible" :header="dialog.title" width="480px" attach="body">
      <t-form ref="accountFormRef" :data="form" :rules="rules" label-align="right" label-width="90px">
        <t-form-item label="编码" name="code">
          <t-input v-model="form.code" :disabled="!!form.id" placeholder="如 CASH01" :maxlength="32" />
        </t-form-item>
        <t-form-item label="名称" name="name">
          <t-input v-model="form.name" placeholder="如 现金 / 微信收款" :maxlength="50" />
        </t-form-item>
        <t-form-item label="类型" name="type">
          <t-select v-model="form.type" style="width: 100%" :options="accountTypeOptions" />
        </t-form-item>
        <t-form-item label="期初余额" name="openingBalance">
          <div class="w-full">
            <t-input-number
              v-model="form.openingBalance"
              :decimal-places="2"
              :disabled="hasMovement"
              theme="normal"
              align="right"
              style="width: 100%"
            />
            <div v-if="hasMovement" class="erp-text-secondary mt-1">该账户已发生收支动账，期初余额不可修改</div>
          </div>
        </t-form-item>
        <t-form-item label="状态" name="status">
          <t-radio-group v-model="form.status">
            <t-radio :value="1">启用</t-radio>
            <t-radio :value="0">停用</t-radio>
          </t-radio-group>
        </t-form-item>
        <t-form-item label="备注" name="remark">
          <t-textarea v-model="form.remark" :autosize="{ minRows: 2, maxRows: 2 }" :maxlength="500" />
        </t-form-item>
      </t-form>
      <template #footer>
        <t-button theme="default" variant="outline" @click="dialog.visible = false">取 消</t-button>
        <t-button theme="primary" :loading="submitLoading" @click="submitForm">确 定</t-button>
      </template>
    </t-dialog>

    <!-- 账户收支流水 -->
    <t-dialog v-model:visible="flowDialog.visible" :header="flowDialog.title" width="860px" attach="body">
      <t-table
        class="erp-table"
        row-key="__key"
        :data="flowList"
        :columns="flowColumns"
        :loading="flowLoading"
        :hover="true"
        :resizable="true"
        size="small"
        bordered
        cell-empty-content="-"
        :empty="'该账户暂无已确认收支流水'"
        @column-resize-change="(ctx: any) => saveColumnWidths('finance-account/flows', ctx.columnsWidth)"
      >
        <template #bizDate="{ row }">{{ toDate(row.bizDate) }}</template>
        <template #bizType="{ row }">{{ flowTypeLabel(row.bizType) }}</template>
        <template #direction="{ row }">
          <t-tag :theme="row.direction === 'IN' ? 'success' : 'danger'" size="small">
            {{ row.direction === 'IN' ? '收' : '支' }}
          </t-tag>
        </template>
        <template #amount="{ row }">{{ formatMoney(row.amount) }}</template>
      </t-table>
      <pagination
        v-show="flowTotal > 0"
        v-model:page="flowQuery.pageNum"
        v-model:limit="flowQuery.pageSize"
        :total="flowTotal"
        @pagination="loadFlows"
      />
    </t-dialog>
  </div>
</template>

<script setup name="ErpFinanceAccount" lang="ts">
import { addAccount, deleteAccount, getAccount, listAccount, listAccountFlows, updateAccount } from '@/api/erp/account';
import { ERP_ACCOUNT_TYPE } from '@/api/erp/enums';
import type { ErpAccountType } from '@/api/erp/enums';
import type { ErpAmount, FinAccountFlowVO, FinAccountForm, FinAccountQuery, FinAccountVO } from '@/api/erp/types';
import { formatMoney } from '@/utils/erp/calc';
import { checkPermi } from '@/utils/permission';
// 列宽记忆：拖拽后按表名存 localStorage，进页面还原（见 hooks/useTableColumnWidth）
import { mergeSavedWidths, saveColumnWidths } from '@/hooks/useTableColumnWidth';
import { DeleteIcon, Edit1Icon, PlusIcon, RefreshIcon, SearchIcon, ViewListIcon } from 'tdesign-icons-vue-next';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const accountList = ref<FinAccountVO[]>([]);
const loading = ref(true);
const showSearch = ref(true);
const total = ref(0);
const submitLoading = ref(false);
/** 表格与表单 ref：已换 t-table/t-form，实例类型不再来自 Element Plus */
const accountFormRef = ref<any>();
const queryFormRef = ref<any>();

/** 编辑时：账户已有动账则期初余额禁改 */
const hasMovement = ref(false);

const dialog = reactive<DialogOption>({ visible: false, title: '' });

const createForm = (): FinAccountForm => ({
  id: undefined,
  code: '',
  name: '',
  type: 'CASH',
  openingBalance: 0,
  status: 1,
  remark: ''
});

const form = ref<FinAccountForm>(createForm());

const queryParams = reactive<FinAccountQuery>({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  type: undefined,
  status: undefined
});

/** 表单校验规则：t-form 的 rules 形状与 EP 的 ElFormRules 不兼容，类型放宽为 Record */
const rules: Record<string, any> = {
  code: [{ required: true, message: '账户编码不能为空', trigger: 'blur' }],
  name: [{ required: true, message: '账户名称不能为空', trigger: 'blur' }],
  type: [{ required: true, message: '请选择账户类型', trigger: 'change' }]
};

/** 账户类型下拉：ERP_ACCOUNT_TYPE 是 Record，t-select 只认 {label,value} 数组，不能直接传 */
const accountTypeOptions = Object.entries(ERP_ACCOUNT_TYPE).map(([value, item]) => ({ value: value as ErpAccountType, label: item.label }));

/** 状态下拉：沿用原 el-option 的固定两项 */
const statusOptions = [
  { label: '启用', value: 1 },
  { label: '停用', value: 0 }
];

/** 主列表列配置：t-table 用 columns 数组，插槽名与 cell 字符串同名（DOC/35 §5） */
const listColumns: any[] = [
  { colKey: 'code', title: '编码', align: 'center', width: 120, ellipsis: true },
  { colKey: 'name', title: '名称', align: 'center', minWidth: 140, ellipsis: true },
  { colKey: 'type', title: '类型', align: 'center', width: 90, cell: 'type' },
  { colKey: 'openingBalance', title: '期初余额', align: 'right', width: 120, cell: 'openingBalance' },
  { colKey: 'balance', title: '当前余额', align: 'right', width: 130, cell: 'balance' },
  { colKey: 'status', title: '状态', align: 'center', width: 80, cell: 'status' },
  { colKey: 'remark', title: '备注', align: 'center', minWidth: 120, ellipsis: true },
  { colKey: 'operation', title: '操作', align: 'center', width: 100, cell: 'operation' }
];
mergeSavedWidths('finance-account/list', listColumns);

/** 流水弹窗列配置：行 key 用前端生成的 __key（rowId 来自三张源表主键，可能重号） */
const flowColumns: any[] = [
  { colKey: 'bizDate', title: '日期', align: 'center', width: 110, cell: 'bizDate' },
  { colKey: 'bizType', title: '类型', align: 'center', width: 100, cell: 'bizType' },
  { colKey: 'bizNo', title: '单号', align: 'center', minWidth: 140, ellipsis: true },
  { colKey: 'counterparty', title: '客户/供应商/摘要', align: 'center', minWidth: 160, ellipsis: true },
  { colKey: 'direction', title: '方向', align: 'center', width: 80, cell: 'direction' },
  { colKey: 'amount', title: '金额', align: 'right', width: 130, cell: 'amount' }
];
mergeSavedWidths('finance-account/flows', flowColumns);

const toDate = (value: string | undefined): string => (value ? String(value).slice(0, 10) : '-');

const flowTypeLabel = (value: string): string => (value === 'RECEIPT' ? '收款单' : value === 'PAYMENT' ? '付款单' : '其他收支');

const getList = async () => {
  loading.value = true;
  try {
    const res = await listAccount({
      pageNum: queryParams.pageNum,
      pageSize: queryParams.pageSize,
      keyword: queryParams.keyword?.trim() || undefined,
      type: queryParams.type || undefined,
      status: queryParams.status
    });
    accountList.value = res.rows ?? [];
    total.value = Number(res.total ?? 0);
  } finally {
    loading.value = false;
  }
};

const handleQuery = () => {
  queryParams.pageNum = 1;
  getList();
};

const resetQuery = () => {
  // t-form 的实例方法叫 reset()（EP 是 resetFields()）
  queryFormRef.value?.reset();
  queryParams.keyword = '';
  handleQuery();
};

const handleAdd = () => {
  form.value = createForm();
  hasMovement.value = false;
  dialog.visible = true;
  dialog.title = '新增结算账户';
};

const handleEdit = async (row: FinAccountVO) => {
  const res = await getAccount(row.id);
  const detail = res.data;
  form.value = {
    id: detail.id,
    code: detail.code,
    name: detail.name,
    type: detail.type,
    openingBalance: Number(detail.openingBalance ?? 0),
    status: detail.status,
    remark: detail.remark ?? ''
  };
  // 有动账（当前余额 ≠ 期初）则期初禁改；与后端校验同口径的前端提示
  hasMovement.value = Number(detail.balance ?? 0) !== Number(detail.openingBalance ?? 0);
  dialog.visible = true;
  dialog.title = `编辑账户 ${detail.code}`;
};

const submitForm = async () => {
  // t-form 的 validate() 失败是 resolve(false)，不是 reject
  const r = await accountFormRef.value?.validate().catch(() => false);
  const ok = r === true || r === undefined;
  if (!ok) return;
  submitLoading.value = true;
  try {
    if (form.value.id) {
      await updateAccount(form.value);
      proxy?.$modal.msgSuccess('修改成功');
    } else {
      await addAccount(form.value);
      proxy?.$modal.msgSuccess('新增成功');
    }
    dialog.visible = false;
    await getList();
  } finally {
    submitLoading.value = false;
  }
};

const handleDelete = async (row: FinAccountVO) => {
  await proxy?.$modal.confirm(`确认删除账户「${row.name}」？仅未被单据引用的账户可删除`);
  await deleteAccount(row.id);
  proxy?.$modal.msgSuccess('删除成功');
  await getList();
};

/* ------------------------------ 流水 ------------------------------ */

/** 流水行：后端 rowId 分别是收款单/付款单/其他收支三张表的主键，跨表可能重号，t-table 行 key 另生成前端字段 */
interface FlowRow extends FinAccountFlowVO {
  __key: string;
}

const flowList = ref<FlowRow[]>([]);
const flowLoading = ref(false);
const flowTotal = ref(0);
const flowQuery = reactive({ pageNum: 1, pageSize: 10 });
const flowDialog = reactive<DialogOption & { accountId?: ErpAmount }>({ visible: false, title: '' });
const flowAccountId = ref<string | number>('');

const handleFlows = (row: FinAccountVO) => {
  flowAccountId.value = row.id;
  flowQuery.pageNum = 1;
  flowDialog.visible = true;
  flowDialog.title = `账户流水 - ${row.name}`;
  loadFlows();
};

const loadFlows = async () => {
  flowLoading.value = true;
  try {
    const res = await listAccountFlows(flowAccountId.value, { pageNum: flowQuery.pageNum, pageSize: flowQuery.pageSize });
    // 流水只读展示、不提交后端，__key 仅用于 t-table 行标识
    flowList.value = (res.rows ?? []).map((row, index) => ({ ...row, __key: `flow-${index}` }));
    flowTotal.value = Number(res.total ?? 0);
  } finally {
    flowLoading.value = false;
  }
};

onMounted(() => {
  getList();
});
</script>

<style scoped lang="scss">
/* 功能色当文字用：走 --app-text-* 固定两档（实测 ≥4.5:1），不用 EP 的 dark-2 色阶 */
.amount-bad {
  color: var(--app-text-danger);
}
</style>
