<template>
  <div class="p-2">
    <t-row :gutter="20">
      <t-col :span="12">
        <transition :enter-active-class="proxy?.animate.searchAnimate.enter" :leave-active-class="proxy?.animate.searchAnimate.leave">
          <div v-show="showSearch" class="erp-row-container">
            <t-card :bordered="false" class="erp-filter-card" title="筛选条件">
              <template #actions>
                <right-toolbar v-model:show-search="showSearch" @query-table="getList"></right-toolbar>
              </template>
              <t-form ref="queryFormRef" :data="queryParams" class="erp-filter-row" label-align="right" :label-width="'auto'" @submit="handleQuery">
                <t-form-item label="单号" name="keyword">
                  <t-input v-model="queryParams.keyword" placeholder="请输入其他出库单号" clearable class="query-w-md" @enter="handleQuery" />
                </t-form-item>
                <t-form-item label="成本分类" name="costCategory">
                  <t-select v-model="queryParams.costCategory" placeholder="全部分类" clearable class="query-w-sm" :options="costCategoryOptions" />
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

        <div v-if="!showSearch" class="mb-[10px] flex justify-end">
          <right-toolbar v-model:show-search="showSearch" @query-table="getList"></right-toolbar>
        </div>

        <div class="erp-surface erp-panel">
          <div class="erp-panel__head">
            <div class="flex items-center gap-2">
              <t-button v-hasPermi="['erp:other-out:add']" theme="primary" variant="outline" @click="handleAdd">
                <template #icon><PlusIcon /></template>
                新增其他出库单
              </t-button>
            </div>
          </div>

          <t-table
            class="erp-table"
            row-key="id"
            :data="otherOutList"
            :columns="listColumns"
            :loading="loading"
            :hover="true"
            :resizable="true"
            size="small"
            cell-empty-content="-"
            @column-resize-change="(ctx: any) => saveColumnWidths('other-out/list', ctx.columnsWidth)"
          >
            <template #bizDate="{ row }">{{ toDate(row.bizDate) }}</template>
            <template #costCategory="{ row }">{{ categoryLabel(row.costCategory) }}</template>
            <template #costAmount="{ row }">{{ snapshotText(row.costAmount) }}</template>
            <template #status="{ row }">
              <StatusTag kind="bill" :value="row.status" :status-text="row.statusText" />
            </template>
            <template #operation="{ row }">
              <div class="flex items-center justify-center gap-1">
                <!-- v-hasPermi 会 removeChild，与 t-tooltip 包裹的 t-button 组合会留游离触发元素（DOC/36 P6），改外层 checkPermi -->
                <t-tooltip v-if="checkPermi(['erp:other-out:query'])" content="查看" placement="top">
                  <t-button theme="primary" variant="text" shape="square" size="small" @click="handleView(row)">
                    <template #icon><BrowseIcon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.status === 'DRAFT' && checkPermi(['erp:other-out:edit'])" content="编辑" placement="top">
                  <t-button theme="primary" variant="text" shape="square" size="small" @click="handleEdit(row)">
                    <template #icon><Edit1Icon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.status === 'DRAFT' && checkPermi(['erp:other-out:audit'])" content="确认" placement="top">
                  <t-button theme="success" variant="text" shape="square" size="small" @click="handleConfirm(row)">
                    <template #icon><CheckCircleIcon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.status === 'CONFIRMED' && checkPermi(['erp:other-out:withdraw'])" content="撤回" placement="top">
                  <t-button theme="warning" variant="text" shape="square" size="small" @click="handleWithdraw(row)">
                    <template #icon><RollbackIcon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.status === 'CONFIRMED' && checkPermi(['erp:other-out:void'])" content="作废" placement="top">
                  <t-button theme="danger" variant="text" shape="square" size="small" @click="handleVoid(row)">
                    <template #icon><CloseCircleIcon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.status === 'DRAFT' && checkPermi(['erp:other-out:remove'])" content="删除" placement="top">
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

    <!-- 新增 / 编辑 / 查看 其他出库单 -->
    <t-drawer v-model:visible="dialog.visible" size="92%" :destroy-on-close="true" attach="body">
      <template #header>
        <span class="text-base font-medium">{{ dialog.title }}</span>
      </template>
      <!-- 抽屉 body 自带纵向滚动，footer 固定；不再依赖无效的 body-style（普通 div 不识别） -->
      <div class="erp-drawer-body">
        <t-form ref="otherOutFormRef" :data="form" :rules="rules" label-align="top" :disabled="readonly">
          <!-- TDesign 为 12 栅格：原 6/6/12（24 栅格）减半为 3/3/6，单行合计 12 -->
          <t-row :gutter="16">
            <t-col :span="3">
              <t-form-item label="业务日期" name="bizDate">
                <t-date-picker v-model="form.bizDate" value-type="YYYY-MM-DD" placeholder="请选择日期" style="width: 100%" />
              </t-form-item>
            </t-col>
            <t-col :span="3">
              <t-form-item label="成本分类" name="costCategory">
                <t-select v-model="form.costCategory" placeholder="请选择成本分类" :options="costCategoryOptions" style="width: 100%" />
              </t-form-item>
            </t-col>
            <t-col :span="6">
              <t-form-item label="备注">
                <t-input v-model="form.remark" placeholder="备注" />
              </t-form-item>
            </t-col>
          </t-row>
        </t-form>

        <div class="flex items-center justify-between mb-2">
          <div class="flex items-center gap-2">
            <t-button v-if="!readonly" theme="primary" variant="outline" @click="addRow">
              <template #icon><PlusIcon /></template>
              添加明细
            </t-button>
            <t-tag v-if="readonly" theme="default" size="small">已确认/已作废单据不可修改</t-tag>
          </div>
        </div>

        <t-table
          class="erp-table"
          row-key="__key"
          :data="rows"
          :columns="rowColumns"
          :loading="detailLoading"
          :hover="true"
          :resizable="true"
          size="small"
          cell-empty-content="-"
          @column-resize-change="(ctx: any) => saveColumnWidths('other-out/detail', ctx.columnsWidth)"
        >
          <template #rowIndex="{ rowIndex }">{{ rowIndex + 1 }}</template>
          <template #product="{ row, rowIndex }">
            <ProductSelect
              v-model="row.productId"
              :disabled="readonly"
              show-category-filter
              placeholder="请选择产品（不限类型）"
              @change="(product) => onProductChange(rowIndex, product)"
            />
          </template>
          <template #productName="{ row }">{{ row.productName || '-' }}</template>
          <template #qty="{ row, rowIndex }">
            <t-input-number
              :key="`qty-${rowIndex}-${qtyPrecision(row.qtyScale)}`"
              v-model="row.qty"
              :decimal-places="qtyPrecision(row.qtyScale)"
              :step="qtyStep(row.qtyScale)"
              :min="0"
              theme="normal"
              align="right"
              :disabled="readonly"
              style="width: 100%"
            />
          </template>
          <template #unitCost="{ row }">{{ snapshotText(row.unitCost) }}</template>
          <template #amount="{ row }">{{ snapshotText(row.amount) }}</template>
          <template #remark="{ row }">
            <t-input v-model="row.remark" :title="row.remark" :disabled="readonly" placeholder="备注" />
          </template>
          <template #rowOperation="{ rowIndex }">
            <t-tooltip content="删除该行" placement="top">
              <t-button theme="danger" variant="text" shape="square" size="small" @click="removeRow(rowIndex)">
                <template #icon><DeleteIcon /></template>
              </t-button>
            </t-tooltip>
          </template>
        </t-table>
      </div>

      <template #footer>
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-4 text-sm">
            <span
              >明细行数：<b>{{ rows.length }}</b></span
            >
            <span
              >合计数量：<b>{{ formatQty(totalQty) }}</b></span
            >
            <span
              >成本合计：<b class="text-primary">{{ costTotalText }}</b></span
            >
          </div>
          <div class="flex items-center gap-2">
            <t-button theme="default" variant="outline" @click="dialog.visible = false">{{ readonly ? '关 闭' : '取 消' }}</t-button>
            <t-button v-if="!readonly" theme="primary" variant="outline" @click="submitForm(false)">保存草稿</t-button>
            <t-button v-if="!readonly && checkPermi(['erp:other-out:audit'])" theme="success" @click="submitForm(true)">保存并确认</t-button>
          </div>
        </div>
      </template>
    </t-drawer>
  </div>
</template>

<script setup name="ErpOtherOut" lang="ts">
import {
  addOtherOut,
  confirmOtherOut,
  delOtherOut,
  getOtherOut,
  listOtherOut,
  updateOtherOut,
  voidOtherOut,
  withdrawOtherOut
} from '@/api/erp/trade';
import { ERP_BILL_STATUS, ERP_COST_CATEGORY } from '@/api/erp/enums';
import type { ErpBillStatus, ErpCostCategory } from '@/api/erp/enums';
import type { ErpAmount, ErpId, ErpOtherOutForm, ErpOtherOutVO, ErpProductVO } from '@/api/erp/types';
import { formatMoney, formatQty, normalizeQty, qtyPrecision, qtyStep, roundMoney } from '@/utils/erp/calc';
// 列宽记忆：拖拽后按表名存 localStorage，进页面还原（见 hooks/useTableColumnWidth）
import { mergeSavedWidths, saveColumnWidths } from '@/hooks/useTableColumnWidth';
// t-tooltip 包裹的 t-button 不能挂 v-hasPermi（指令会 removeChild），改外层 checkPermi 判断（DOC/36 P6）
import { checkPermi } from '@/utils/permission';
import ProductSelect from '@/components/erp/ProductSelect.vue';
import StatusTag from '@/components/erp/StatusTag.vue';
import { BrowseIcon, CheckCircleIcon, CloseCircleIcon, DeleteIcon, Edit1Icon, PlusIcon, RollbackIcon } from 'tdesign-icons-vue-next';

interface OtherOutRow {
  productId?: ErpId;
  productCode?: string;
  productName?: string;
  qty: number;
  /** 数量精度 0~3（商品级，DOC/26 §10） */
  qtyScale?: number;
  remark?: string;
  /** 确认后成本快照 */
  unitCost?: number;
  amount?: number;
  /** t-table 行 key（前端生成，不提交后端） */
  __key?: string;
}

interface OtherOutFormState {
  id?: ErpId;
  bizDate: string;
  costCategory?: ErpCostCategory;
  remark: string;
  /** 确认后的成本合计快照 */
  costAmount?: number;
}

interface OtherOutQueryState extends PageQuery {
  keyword: string;
  costCategory: string;
  status: string;
}

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const statusOptions = Object.entries(ERP_BILL_STATUS).map(([value, item]) => ({ value: value as ErpBillStatus, label: item.label }));
const costCategoryOptions = Object.entries(ERP_COST_CATEGORY).map(([value, item]) => ({ value: value as ErpCostCategory, label: item.label }));

/** 主列表列配置：t-table 用 columns 数组，插槽名与 cell 字符串同名（DOC/35 §5） */
const listColumns: any[] = [
  { colKey: 'no', title: '单号', align: 'center', minWidth: 130, ellipsis: true },
  { colKey: 'bizDate', title: '业务日期', align: 'center', width: 110, cell: 'bizDate' },
  { colKey: 'costCategory', title: '成本分类', align: 'center', width: 110, cell: 'costCategory' },
  { colKey: 'costAmount', title: '成本金额', align: 'right', width: 130, cell: 'costAmount' },
  { colKey: 'status', title: '状态', align: 'center', width: 90, cell: 'status' },
  { colKey: 'remark', title: '备注', align: 'center', minWidth: 140, ellipsis: true },
  { colKey: 'operation', title: '操作', align: 'center', width: 190, cell: 'operation' }
];
mergeSavedWidths('other-out/list', listColumns);

const otherOutList = ref<ErpOtherOutVO[]>([]);
const loading = ref(true);
const detailLoading = ref(false);
const showSearch = ref(true);
const total = ref(0);
/** 开单表单：已换 t-form，ref 只用到 clearValidate */
const otherOutFormRef = ref<any>();
/** 查询表单：已换 t-form，ref 提供 reset()（EP 是 resetFields()） */
const queryFormRef = ref<any>();

const dialog = reactive<DialogOption>({
  visible: false,
  title: ''
});

/** 只读（查看已确认/已作废单据） */
const readonly = ref(false);

const rows = ref<OtherOutRow[]>([]);

/** 明细行列表配置：数量是行内编辑控件；只读态不出现操作列 */
const rowColumns = computed<any[]>(() => {
  const cols: any[] = [
    { colKey: 'rowIndex', title: '#', width: 50, align: 'center', cell: 'rowIndex' },
    { colKey: 'productId', title: '产品', minWidth: 260, cell: 'product' },
    { colKey: 'productName', title: '产品名称', align: 'center', minWidth: 150, ellipsis: true, cell: 'productName' },
    { colKey: 'qty', title: '数量', width: 140, align: 'right', cell: 'qty' },
    { colKey: 'unitCost', title: '单位成本', width: 110, align: 'right', cell: 'unitCost' },
    { colKey: 'amount', title: '成本金额', width: 120, align: 'right', cell: 'amount' },
    { colKey: 'remark', title: '备注', minWidth: 150, cell: 'remark' }
  ];
  if (!readonly.value) {
    cols.push({ colKey: 'operation', title: '操作', width: 70, align: 'center', cell: 'rowOperation' });
  }
  return mergeSavedWidths('other-out/detail', cols);
});

const createForm = (): OtherOutFormState => ({
  id: undefined,
  bizDate: new Date().toISOString().slice(0, 10),
  costCategory: undefined,
  remark: '',
  costAmount: undefined
});

const form = reactive<OtherOutFormState>(createForm());

const queryParams = reactive<OtherOutQueryState>({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  costCategory: '',
  status: ''
});

// t-form 的 rules 形状与 EP 的 ElFormRules 不兼容（本页仅用于必填星号，提交前另有 validateRows 手工校验）
const rules: Record<string, any> = {
  bizDate: [{ required: true, message: '业务日期不能为空', trigger: 'change' }],
  costCategory: [{ required: true, message: '成本分类不能为空', trigger: 'change' }]
};

const num = (value: ErpAmount | undefined | null): number => {
  const n = Number(value ?? 0);
  return Number.isFinite(n) ? n : 0;
};

/** 日期只取 yyyy-MM-dd */
const toDate = (value: string | undefined): string => (value ? String(value).slice(0, 10) : '');

/** 成本快照为空时显示 '-'（草稿状态无成本快照） */
const snapshotText = (value: number | undefined | null): string => (value === undefined || value === null ? '-' : formatMoney(value));

const categoryLabel = (value: string | undefined): string => {
  if (!value) return '-';
  return ERP_COST_CATEGORY[value as ErpCostCategory]?.label ?? value;
};

const totalQty = computed(() => roundMoney(rows.value.reduce((sum, row) => sum + (row.qty || 0), 0)));

/** 成本合计：草稿状态后端未计算成本快照，显示 '-' */
const costTotalText = computed(() => {
  if (form.costAmount !== undefined && form.costAmount !== null) {
    return formatMoney(form.costAmount);
  }
  const sum = rows.value.reduce((total, row) => total + (row.amount === undefined ? 0 : num(row.amount)), 0);
  return sum > 0 ? formatMoney(sum) : '-';
});

/** 查询其他出库单列表 */
const getList = async () => {
  loading.value = true;
  try {
    const res = await listOtherOut(queryParams);
    otherOutList.value = res.rows ?? [];
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
  queryParams.costCategory = '';
  queryParams.status = '';
  handleQuery();
};

/** 明细行 t-table 行 key：新增行没有业务主键，用前端自增序号 */
let rowSeq = 0;
const nextRowKey = (): string => `row-${++rowSeq}`;

const createRow = (): OtherOutRow => ({
  __key: nextRowKey(),
  productId: undefined,
  qty: 1,
  remark: '',
  unitCost: undefined,
  amount: undefined
});

/** 表单重置 */
const resetForm = async () => {
  Object.assign(form, createForm());
  rows.value = [createRow()];
  readonly.value = false;
  await nextTick();
  otherOutFormRef.value?.clearValidate();
};

/** 新增按钮操作 */
const handleAdd = async () => {
  await resetForm();
  dialog.visible = true;
  dialog.title = '新增其他出库单';
};

const loadDetail = async (id: ErpId, isReadonly: boolean) => {
  detailLoading.value = true;
  try {
    const res = await getOtherOut(id);
    const detail = res.data;
    Object.assign(form, {
      id: detail.id,
      bizDate: toDate(detail.bizDate),
      costCategory: detail.costCategory,
      remark: detail.remark || '',
      costAmount: detail.costAmount === undefined || detail.costAmount === null ? undefined : num(detail.costAmount)
    });
    rows.value = (detail.items ?? []).map((item) => ({
      __key: nextRowKey(),
      productId: item.productId,
      productCode: item.productCode,
      productName: item.productName,
      qty: num(item.qty),
      qtyScale: item.qtyScale,
      remark: item.remark,
      unitCost: item.unitCost === undefined || item.unitCost === null ? undefined : num(item.unitCost),
      amount: item.amount === undefined || item.amount === null ? undefined : num(item.amount)
    }));
    readonly.value = isReadonly;
    await nextTick();
    otherOutFormRef.value?.clearValidate();
  } finally {
    detailLoading.value = false;
  }
};

/** 编辑按钮操作 */
const handleEdit = async (row: ErpOtherOutVO) => {
  await resetForm();
  dialog.visible = true;
  dialog.title = `编辑其他出库单 ${row.no ?? ''}`;
  await loadDetail(row.id, false);
};

/** 查看按钮操作 */
const handleView = async (row: ErpOtherOutVO) => {
  await resetForm();
  dialog.visible = true;
  dialog.title = `查看其他出库单 ${row.no ?? ''}`;
  await loadDetail(row.id, true);
};

/** 添加明细行 */
const addRow = () => {
  rows.value.push(createRow());
};

/** 删除明细行 */
const removeRow = (index: number) => {
  rows.value.splice(index, 1);
  if (rows.value.length === 0) {
    rows.value.push(createRow());
  }
};

/** 选择产品后带出名称与单位 */
const onProductChange = (index: number, product?: ErpProductVO) => {
  const row = rows.value[index];
  if (!row) return;
  if (!product) {
    row.productCode = undefined;
    row.productName = undefined;
    row.qtyScale = undefined;
    return;
  }
  row.productCode = product.code;
  row.productName = product.name;
  row.qtyScale = product.qtyScale;
  // 精度随商品切换时把已录数量归一化，避免小数商品的数量挂到整数商品行上（数量控件只改显示不回写 model）
  row.qty = normalizeQty(row.qty, product.qtyScale);
};

/** 组装提交数据 */
const buildPayload = (confirm: boolean): ErpOtherOutForm => ({
  id: form.id,
  bizDate: form.bizDate,
  costCategory: form.costCategory,
  remark: form.remark,
  confirm,
  items: rows.value.map((row) => ({
    productId: row.productId,
    qty: row.qty,
    remark: row.remark
  }))
});

/** 提交前校验 */
const validateRows = (): boolean => {
  if (!form.bizDate) {
    proxy?.$modal.msgWarning('请选择业务日期');
    return false;
  }
  if (!form.costCategory) {
    proxy?.$modal.msgWarning('请选择成本分类');
    return false;
  }
  if (rows.value.length === 0) {
    proxy?.$modal.msgWarning('请至少添加一行明细');
    return false;
  }
  for (const row of rows.value) {
    if (!row.productId) {
      proxy?.$modal.msgWarning('明细中存在未选择产品的行');
      return false;
    }
    if (!(row.qty > 0)) {
      proxy?.$modal.msgWarning('数量必须大于 0');
      return false;
    }
  }
  return true;
};

/** 提交按钮 */
const submitForm = async (confirm: boolean) => {
  if (!validateRows()) return;
  const payload = buildPayload(confirm);
  if (form.id) {
    await updateOtherOut(payload);
  } else {
    await addOtherOut(payload);
  }
  proxy?.$modal.msgSuccess(confirm ? '已保存并确认' : '已保存草稿');
  dialog.visible = false;
  await getList();
};

/** 确认按钮操作 */
const handleConfirm = async (row: ErpOtherOutVO) => {
  try {
    await proxy?.$modal.confirm(`是否确认其他出库单「${row.no ?? ''}」？确认后扣减库存并写入成本台账。`);
  } catch {
    return;
  }
  await confirmOtherOut(row.id);
  proxy?.$modal.msgSuccess('已确认');
  await getList();
};

/** 撤回按钮操作 */
const handleWithdraw = async (row: ErpOtherOutVO) => {
  try {
    await proxy?.$modal.confirm(`是否撤回其他出库单「${row.no ?? ''}」？撤回后回到草稿可继续修改。`);
  } catch {
    return;
  }
  await withdrawOtherOut(row.id);
  proxy?.$modal.msgSuccess('已撤回');
  await getList();
};

/** 作废按钮操作 */
const handleVoid = async (row: ErpOtherOutVO) => {
  try {
    await proxy?.$modal.confirm(`是否作废其他出库单「${row.no ?? ''}」？作废为终态，不可再修改。`);
  } catch {
    return;
  }
  await voidOtherOut(row.id);
  proxy?.$modal.msgSuccess('已作废');
  await getList();
};

/** 删除按钮操作：仅草稿单据可删除，后端逻辑删除，单号不回收 */
const handleDelete = async (row: ErpOtherOutVO) => {
  try {
    await proxy?.$modal.confirm(`是否删除其他出库单「${row.no ?? ''}」？删除后不可恢复，单号不会回收重用。`);
  } catch {
    return;
  }
  await delOtherOut(row.id);
  proxy?.$modal.msgSuccess('删除成功');
  await getList();
};

onMounted(() => {
  getList();
});
</script>
