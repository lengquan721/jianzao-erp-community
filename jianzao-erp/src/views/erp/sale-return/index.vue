<template>
  <div class="p-2">
    <t-row :gutter="20">
      <t-col :span="12">
        <transition :enter-active-class="proxy?.animate.searchAnimate.enter" :leave-active-class="proxy?.animate.searchAnimate.leave">
          <div v-show="showSearch" class="mb-[10px]">
            <t-card :bordered="false" class="erp-filter-card">
              <t-form ref="queryFormRef" :data="queryParams" class="erp-filter-row" label-align="right" :label-width="'auto'">
                <t-form-item label="单号" name="keyword">
                  <t-input v-model="queryParams.keyword" placeholder="退货单号/原单号" clearable class="query-w-md" @enter="handleQuery" />
                </t-form-item>
                <t-form-item label="客户" name="customerId">
                  <PartnerSelect v-model="queryParams.customerId" kind="customer" placeholder="全部客户" clearable class="query-w-md" />
                </t-form-item>
                <t-form-item label="状态" name="status">
                  <t-select v-model="queryParams.status" placeholder="单据状态" clearable class="query-w-sm" :options="statusOptions" />
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
            <t-button v-hasPermi="['erp:sale-return:add']" theme="primary" variant="outline" @click="handleAdd">
              <template #icon><PlusIcon /></template>
              新建退货
            </t-button>
            <right-toolbar v-model:show-search="showSearch" @query-table="getList"></right-toolbar>
          </div>

          <t-table
            class="erp-table"
            row-key="id"
            :data="returnList"
            :columns="listColumns"
            :loading="loading"
            :hover="true"
            :resizable="true"
            size="small"
            bordered
            cell-empty-content="-"
            :empty="'暂无销售退货单'"
            @column-resize-change="(ctx: any) => saveColumnWidths('sale-return/list', ctx.columnsWidth)"
          >
            <template #customer="{ row }">{{ row.customerName || '-' }}</template>
            <template #totalAmount="{ row }">{{ formatMoney(row.totalAmount) }}</template>
            <template #returnCostAmount="{ row }">{{ formatMoney(row.returnCostAmount) }}</template>
            <template #refund="{ row }">
              <t-tag v-if="row.needRefund === 1" theme="danger" size="small">退 {{ formatMoney(row.refundAmount) }}</t-tag>
              <span v-else>-</span>
            </template>
            <template #bizDate="{ row }">{{ toDate(row.bizDate) }}</template>
            <template #status="{ row }">
              <StatusTag kind="bill" :value="row.status" :status-text="row.statusText" />
            </template>
            <template #operation="{ row }">
              <div class="flex items-center justify-center gap-1">
                <!-- v-hasPermi 会 removeChild，与 t-tooltip 包裹的 t-button 组合会留游离触发元素（DOC/36 P6），权限判断改外层 checkPermi -->
                <t-tooltip v-if="checkPermi(['erp:sale-return:query'])" content="查看" placement="top">
                  <t-button theme="primary" variant="text" shape="square" size="small" @click="handleView(row)">
                    <template #icon><BrowseIcon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.status === 'DRAFT' && checkPermi(['erp:sale-return:edit'])" content="编辑" placement="top">
                  <t-button theme="primary" variant="text" shape="square" size="small" @click="handleEdit(row)">
                    <template #icon><Edit1Icon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.status === 'DRAFT' && checkPermi(['erp:sale-return:audit'])" content="确认" placement="top">
                  <t-button theme="success" variant="text" shape="square" size="small" @click="handleConfirm(row)">
                    <template #icon><CheckCircleIcon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.status === 'CONFIRMED' && checkPermi(['erp:sale-return:withdraw'])" content="撤回" placement="top">
                  <t-button theme="warning" variant="text" shape="square" size="small" @click="handleWithdraw(row)">
                    <template #icon><RollbackIcon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.status === 'CONFIRMED' && checkPermi(['erp:sale-return:void'])" content="作废" placement="top">
                  <t-button theme="danger" variant="text" shape="square" size="small" @click="handleVoid(row)">
                    <template #icon><CloseCircleIcon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.status === 'DRAFT' && checkPermi(['erp:sale-return:remove'])" content="删除" placement="top">
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

    <!-- 新建 / 编辑 / 查看销售退货 -->
    <t-drawer v-model:visible="dialog.visible" size="92%" :destroy-on-close="true" attach="body">
      <template #header>
        <span class="text-base font-medium">{{ dialog.title }}</span>
      </template>
      <div class="erp-drawer-body">
        <t-form ref="returnFormRef" :data="form" :rules="rules" label-align="top" :disabled="readonly">
          <!-- TDesign 为 12 栅格：原 6/8/5/5 折算为 3/4/3/2，合计 12 -->
          <t-row :gutter="16">
            <t-col :span="3">
              <t-form-item label="客户" name="customerId">
                <PartnerSelect
                  v-model="form.customerId"
                  kind="customer"
                  placeholder="请选择客户"
                  :disabled="!!form.id"
                  @change="() => handleCustomerChange()"
                />
              </t-form-item>
            </t-col>
            <t-col :span="4">
              <t-form-item label="原销售单" name="saleId">
                <!-- 原 EP 的 #label 插槽在 t-select 里是前置内容而非替换选中文案，改为 tooltip 包住选择器，悬停看完整单号 -->
                <t-tooltip :content="selectedSaleLabel" :disabled="!selectedSaleLabel" placement="top">
                  <t-select
                    v-model="form.saleId"
                    filterable
                    placeholder="请选择原单"
                    style="width: 100%"
                    :disabled="!!form.id"
                    :options="returnableOrderOptions"
                    @change="(value: any) => handleSaleChange(value)"
                  />
                </t-tooltip>
              </t-form-item>
            </t-col>
            <t-col :span="3">
              <t-form-item label="业务日期" name="bizDate">
                <t-date-picker v-model="form.bizDate" value-type="YYYY-MM-DD" placeholder="请选择日期" style="width: 100%" />
              </t-form-item>
            </t-col>
            <t-col :span="2">
              <t-form-item label="备注" name="remark">
                <t-input v-model="form.remark" placeholder="备注" maxlength="500" />
              </t-form-item>
            </t-col>
          </t-row>
        </t-form>

        <t-table
          class="erp-table"
          row-key="__key"
          :data="form.items"
          :columns="itemColumns"
          :loading="detailLoading"
          :hover="true"
          :resizable="true"
          size="small"
          bordered
          max-height="40vh"
          cell-empty-content="-"
          :empty="'请先选择原销售单带出明细'"
          @column-resize-change="(ctx: any) => saveColumnWidths('sale-return/detail', ctx.columnsWidth)"
        >
          <template #rowIndex="{ rowIndex }">{{ rowIndex + 1 }}</template>
          <template #originalQty="{ row }">{{ formatQtyByScale(row.originalQty, row.qtyScale) }}</template>
          <template #returnedQty="{ row }">{{ formatQtyByScale(row.returnedQty, row.qtyScale) }}</template>
          <template #returnableQty="{ row }">{{ formatQtyByScale(row.returnableQty, row.qtyScale) }}</template>
          <template #qty="{ row, rowIndex }">
            <!-- 只读（查看）直接展示数值：数字输入控件在只读态仍会按 :max 钳制 model，导致数量被清零 -->
            <span v-if="readonly">{{ formatQtyByScale(row.qty, row.qtyScale) }}</span>
            <t-input-number
              v-else
              :key="`qty-${rowIndex}-${qtyPrecision(row.qtyScale)}`"
              v-model="row.qty"
              :decimal-places="qtyPrecision(row.qtyScale)"
              :step="qtyStep(row.qtyScale)"
              :min="0"
              :max="qtyMax(row)"
              theme="normal"
              align="right"
              style="width: 100%"
            />
          </template>
          <template #price="{ row }">
            <t-input-number v-model="row.price" :decimal-places="2" :min="0" theme="normal" align="right" :disabled="readonly" style="width: 100%" />
            <div class="erp-text-secondary">原价 {{ formatMoney(row.originalPrice) }}</div>
          </template>
          <template #amount="{ row }">{{ formatMoney((Number(row.qty) || 0) * (Number(row.price) || 0)) }}</template>
          <template #rowOperation="{ rowIndex }">
            <t-button theme="danger" variant="text" shape="square" size="small" @click="removeItem(rowIndex)">
              <template #icon><DeleteIcon /></template>
            </t-button>
          </template>
        </t-table>

        <!-- 明细表放在 t-form 之外：t-button 默认不带 type，落在 form 内会触发 submit；退款行单独包一层表单统一 label-align -->
        <t-form v-if="!readonly" :data="form" label-align="top" class="mt-3">
          <t-row :gutter="16">
            <t-col :span="3">
              <t-form-item label="退款给客户">
                <t-switch v-model="form.needRefund" />
              </t-form-item>
            </t-col>
            <template v-if="form.needRefund">
              <t-col :span="3">
                <t-form-item label="退款金额" name="refundAmount">
                  <t-input-number
                    v-model="form.refundAmount"
                    :decimal-places="2"
                    :min="0"
                    :max="totalAmount"
                    theme="normal"
                    align="right"
                    style="width: 100%"
                  />
                </t-form-item>
              </t-col>
              <t-col :span="3">
                <t-form-item label="退款账户" name="refundAccountId">
                  <t-select v-model="form.refundAccountId" filterable placeholder="请选择账户" style="width: 100%" :options="refundAccountOptions" />
                </t-form-item>
              </t-col>
              <t-col :span="3">
                <div class="erp-text-secondary leading-6">退款额不可超过原单已收款；确认后生成红字收款单并同步确认</div>
              </t-col>
            </template>
          </t-row>
        </t-form>
      </div>

      <template #footer>
        <div class="flex items-center justify-between w-full">
          <span class="text-sm">
            共 {{ form.items.length }} 行 · 合计 <b>{{ formatMoney(totalAmount) }}</b>
          </span>
          <div class="flex items-center gap-2">
            <t-button theme="default" variant="outline" @click="dialog.visible = false">取 消</t-button>
            <t-button v-if="!readonly" theme="primary" variant="outline" @click="submitForm(false)">保存草稿</t-button>
            <t-button v-if="!readonly && checkPermi(['erp:sale-return:audit'])" theme="success" :loading="submitLoading" @click="submitForm(true)"
              >保存并确认</t-button
            >
          </div>
        </div>
      </template>
    </t-drawer>
  </div>
</template>

<script setup name="ErpSaleReturn" lang="ts">
import {
  addSaleReturn,
  confirmSaleReturn,
  delSaleReturn,
  getSaleReturn,
  listSaleReturn,
  returnableSaleOrders,
  saleReturnOriginalItems,
  updateSaleReturn,
  voidSaleReturn,
  withdrawSaleReturn
} from '@/api/erp/trade';
import { accountOptions as accountOptionsFn } from '@/api/erp/account';
import { ERP_BILL_STATUS } from '@/api/erp/enums';
import type { ErpAmount, ErpBillStatus, ErpId, FinAccountVO, SaleReturnItemVO, SaleReturnQuery, SaleReturnVO } from '@/api/erp/types';
import { formatMoney, formatQtyByScale, qtyPrecision, qtyStep, roundMoney } from '@/utils/erp/calc';
import { checkPermi } from '@/utils/permission';
// 列宽记忆：拖拽后按表名存 localStorage，进页面还原（见 hooks/useTableColumnWidth）
import { mergeSavedWidths, saveColumnWidths } from '@/hooks/useTableColumnWidth';
import PartnerSelect from '@/components/erp/PartnerSelect.vue';
import StatusTag from '@/components/erp/StatusTag.vue';
import {
  BrowseIcon,
  CheckCircleIcon,
  CloseCircleIcon,
  DeleteIcon,
  Edit1Icon,
  PlusIcon,
  RefreshIcon,
  RollbackIcon,
  SearchIcon
} from 'tdesign-icons-vue-next';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const route = useRoute();

const returnList = ref<SaleReturnVO[]>([]);
const returnableOrders = ref<SaleReturnVO[]>([]);
const accounts = ref<Array<{ id: ErpId; name: string }>>([]);
const loading = ref(true);
const detailLoading = ref(false);
const submitLoading = ref(false);
const showSearch = ref(true);
const total = ref(0);
const returnFormRef = ref<any>();
/** 查询表单：已换 t-form，ref 提供 reset()（EP 是 resetFields()） */
const queryFormRef = ref<any>();

const statusOptions = Object.entries(ERP_BILL_STATUS).map(([value, item]) => ({ value: value as ErpBillStatus, label: item.label }));

const dialog = reactive<DialogOption>({ visible: false, title: '' });
const readonly = ref(false);

interface ReturnRow extends SaleReturnItemVO {
  originalPrice?: ErpAmount;
  /** t-table 行 key：后端明细无稳定主键，前端生成；buildPayload 按字段映射，不会外发 */
  __key?: string;
}

interface ReturnFormState {
  id?: ErpId;
  customerId?: ErpId;
  saleId?: ErpId;
  bizDate: string;
  needRefund: boolean;
  refundAmount: number;
  refundAccountId?: ErpId;
  remark: string;
  items: ReturnRow[];
}

const createForm = (): ReturnFormState => ({
  id: undefined,
  customerId: undefined,
  saleId: undefined,
  bizDate: new Date().toISOString().slice(0, 10),
  needRefund: false,
  refundAmount: 0,
  refundAccountId: undefined,
  remark: '',
  items: []
});

const form = reactive<ReturnFormState>(createForm());

const queryParams = reactive<SaleReturnQuery>({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  customerId: undefined,
  status: ''
});

// t-form 的 rules 形状与 EP 的 ElFormRules 不兼容
const rules: Record<string, any> = {
  customerId: [{ required: true, message: '请选择客户', trigger: 'change' }],
  saleId: [{ required: true, message: '请选择原销售单', trigger: 'change' }],
  bizDate: [{ required: true, message: '业务日期不能为空', trigger: 'change' }]
};

const toDate = (value: string | undefined): string => (value ? String(value).slice(0, 10) : '-');

const totalAmount = computed(() => form.items.reduce((n, r) => n + (Number(r.qty) || 0) * (Number(r.price) || 0), 0));

/** 主列表列配置：t-table 用 columns 数组，cell 名与 #插槽同名（DOC/35 §5） */
const listColumns: any[] = [
  { colKey: 'no', title: '退货单号', align: 'center', minWidth: 145, ellipsis: true },
  { colKey: 'saleNo', title: '原单号', align: 'center', minWidth: 145, ellipsis: true },
  { colKey: 'customerName', title: '客户', align: 'center', minWidth: 130, ellipsis: true, cell: 'customer' },
  { colKey: 'totalAmount', title: '退货金额', align: 'right', width: 110, cell: 'totalAmount' },
  { colKey: 'returnCostAmount', title: '成本回冲', align: 'right', width: 110, cell: 'returnCostAmount' },
  { colKey: 'refund', title: '退款', align: 'center', width: 110, cell: 'refund' },
  { colKey: 'bizDate', title: '业务日期', align: 'center', width: 105, cell: 'bizDate' },
  { colKey: 'status', title: '状态', align: 'center', width: 90, cell: 'status' },
  { colKey: 'operation', title: '操作', align: 'center', width: 190, cell: 'operation' }
];
mergeSavedWidths('sale-return/list', listColumns);

/** 明细行列表配置：数量/单价是行内编辑控件；只读态不出现操作列 */
const itemColumns = computed<any[]>(() => {
  const cols: any[] = [
    { colKey: 'rowIndex', title: '#', width: 50, align: 'center', cell: 'rowIndex' },
    { colKey: 'productCode', title: '产品编码', align: 'center', width: 120, ellipsis: true },
    { colKey: 'productName', title: '产品名称', align: 'center', minWidth: 140, ellipsis: true },
    { colKey: 'originalQty', title: '原行数量', align: 'right', width: 95, cell: 'originalQty' },
    { colKey: 'returnedQty', title: '已退', align: 'right', width: 90, cell: 'returnedQty' },
    { colKey: 'returnableQty', title: '可退', align: 'right', width: 90, cell: 'returnableQty' },
    { colKey: 'qty', title: '退货数量', align: 'right', width: 140, cell: 'qty' },
    { colKey: 'price', title: '退货单价', align: 'right', width: 150, cell: 'price' },
    { colKey: 'amount', title: '行金额', align: 'right', width: 110, cell: 'amount' }
  ];
  if (!readonly.value) {
    cols.push({ colKey: 'rowOperation', title: '操作', width: 70, align: 'center', cell: 'rowOperation' });
  }
  return mergeSavedWidths('sale-return/detail', cols);
});

/** 可退原单下拉：t-select 需要 { label, value }，标签沿用「单号（日期，金额）」 */
const returnableOrderOptions = computed(() =>
  returnableOrders.value.map((o) => ({ value: o.saleId, label: `${o.saleNo}（${toDate(o.bizDate)}，${formatMoney(o.totalAmount)}）` }))
);

/** 已选原单的完整标签：选择器外层 tooltip 用，长单号被截断时仍能看全 */
const selectedSaleLabel = computed(() => returnableOrderOptions.value.find((o) => String(o.value) === String(form.saleId ?? ''))?.label ?? '');

/** 退款账户下拉 */
const refundAccountOptions = computed(() => {
  const selectedId = form.refundAccountId;
  const options = accounts.value.map(({ id, name }) => ({
    value: selectedId != null && String(id) === String(selectedId) ? selectedId : id,
    label: name
  }));
  if (selectedId != null && !options.some(({ value }) => String(value) === String(selectedId))) {
    return [...options, { value: selectedId, label: `原退款账户（${selectedId}）`, disabled: true }];
  }
  return options;
});

/**
 * 退货数量输入上限
 *
 * 数字输入控件会按 :max 钳制 model 值：若保存后期间有别的退货单确认，可退量会变小，
 * 直接拿可退量当上限会把已保存的退货数量清零。这里保证上限不低于当前值，
 * 真正的超退拦截由后端确认时的累计可退校验兜底。
 *
 * @param row 退货明细行
 * @returns 输入上限；无可退量信息时返回 undefined（不限制）
 */
const qtyMax = (row: ReturnRow): number | undefined => {
  if (row.returnableQty === undefined || row.returnableQty === null) return undefined;
  return Math.max(Number(row.returnableQty), Number(row.qty) || 0);
};

const getList = async () => {
  loading.value = true;
  try {
    const res = await listSaleReturn({
      pageNum: queryParams.pageNum,
      pageSize: queryParams.pageSize,
      keyword: queryParams.keyword?.trim() || undefined,
      customerId: queryParams.customerId,
      status: queryParams.status || undefined
    });
    returnList.value = res.rows ?? [];
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
  queryFormRef.value?.reset();
  queryParams.keyword = '';
  handleQuery();
};

/** 明细行 t-table 行 key：后端明细行无稳定主键，前端自增生成 */
let itemSeq = 0;
const nextItemKey = (): string => `item-${++itemSeq}`;

/**
 * 客户变更：重拉可退原单列表
 *
 * @param resetItems 是否清空明细；编辑回显时为 false，否则会把已加载的退货明细清空
 */
const handleCustomerChange = async (resetItems = true) => {
  if (resetItems) {
    form.saleId = undefined;
    form.items = [];
  }
  if (!form.customerId) {
    returnableOrders.value = [];
    return;
  }
  const res = await returnableSaleOrders(form.customerId);
  returnableOrders.value = res.data ?? [];
};

const handleSaleChange = async (saleId: ErpId) => {
  if (!saleId) return;
  detailLoading.value = true;
  try {
    const res = await saleReturnOriginalItems(saleId);
    // 默认带出全部可退行，数量=可退量、单价=原行价（可改）
    form.items = (res.data ?? [])
      .filter((r) => Number(r.returnableQty ?? 0) > 0)
      .map((r) => ({
        ...r,
        __key: nextItemKey(),
        originalPrice: r.price,
        qty: Number(r.returnableQty ?? 0),
        price: Number(r.price ?? 0)
      }));
  } finally {
    detailLoading.value = false;
  }
};

const removeItem = (index: number) => {
  form.items.splice(index, 1);
};

const loadAccounts = async () => {
  try {
    const res = await accountOptionsFn();
    accounts.value = res.data ?? [];
  } catch {
    accounts.value = [];
  }
};

const loadDetail = async (id: ErpId, isReadonly: boolean) => {
  detailLoading.value = true;
  try {
    const res = await getSaleReturn(id);
    const detail = res.data;
    Object.assign(form, {
      id: detail.id,
      customerId: detail.customerId,
      saleId: detail.saleId,
      bizDate: toDate(detail.bizDate),
      needRefund: detail.needRefund === 1,
      refundAmount: Number(detail.refundAmount ?? 0),
      refundAccountId: detail.refundAccountId ?? undefined,
      remark: detail.remark || '',
      items: (detail.items ?? []).map((r) => ({
        ...r,
        __key: nextItemKey(),
        originalPrice: r.price,
        qty: Number(r.qty ?? 0),
        price: Number(r.price ?? 0)
      }))
    });
    readonly.value = isReadonly;
    // 查看态同样要加载可退原单：否则 t-select 匹配不到选项会回退显示原始 ID 而非单号
    // 编辑态也走同一分支且不清空已回显明细（resetItems=false）
    if (detail.customerId) {
      await handleCustomerChange(false);
    }
  } finally {
    detailLoading.value = false;
  }
};

const handleAdd = () => {
  Object.assign(form, createForm());
  readonly.value = false;
  dialog.visible = true;
  dialog.title = '新建销售退货';
};

const handleEdit = async (row: SaleReturnVO) => {
  Object.assign(form, createForm());
  dialog.visible = true;
  dialog.title = `编辑销售退货 ${row.no ?? ''}`;
  await loadDetail(row.id, false);
};

const handleView = async (row: SaleReturnVO) => {
  Object.assign(form, createForm());
  dialog.visible = true;
  dialog.title = `查看销售退货 ${row.no ?? ''}`;
  await loadDetail(row.id, true);
};

const buildPayload = (confirm: boolean) => ({
  id: form.id,
  saleId: form.saleId,
  bizDate: form.bizDate,
  needRefund: form.needRefund,
  refundAmount: form.needRefund ? roundMoney(form.refundAmount) : 0,
  refundAccountId: form.needRefund ? form.refundAccountId : undefined,
  remark: form.remark,
  confirm,
  items: form.items
    .filter((r) => Number(r.qty) > 0)
    .map((r) => ({ saleItemId: r.saleItemId, qty: Number(r.qty), price: Number(r.price), remark: r.remark }))
});

const submitForm = async (confirm: boolean) => {
  // t-form 的 validate() 失败是 resolve(false)，不是 reject
  const r = await returnFormRef.value?.validate().catch(() => false);
  const ok = r === true || r === undefined;
  if (!ok) return;
  if (!form.saleId) {
    proxy?.$modal.msgWarning('请选择原销售单');
    return;
  }
  if (!form.items.length) {
    proxy?.$modal.msgWarning('请至少保留一行退货明细');
    return;
  }
  if (form.needRefund && Number(form.refundAmount) > 0 && !form.refundAccountId) {
    proxy?.$modal.msgWarning('退款必须选择结算账户');
    return;
  }
  if (confirm) {
    await proxy?.$modal.confirm(`确认退货？库存回增、应收红冲${form.needRefund && Number(form.refundAmount) > 0 ? '，退款单将同步确认' : ''}`);
  }
  submitLoading.value = true;
  try {
    if (form.id) {
      await updateSaleReturn({ ...buildPayload(confirm), id: form.id } as any);
    } else {
      await addSaleReturn(buildPayload(confirm) as any);
    }
    dialog.visible = false;
    await getList();
  } finally {
    submitLoading.value = false;
  }
};

const handleConfirm = async (row: SaleReturnVO) => {
  await proxy?.$modal.confirm(`确认退货单 ${row.no}？库存回增、应收红冲`);
  await confirmSaleReturn(row.id);
  proxy?.$modal.msgSuccess('确认成功');
  await getList();
};

const handleWithdraw = async (row: SaleReturnVO) => {
  await proxy?.$modal.confirm(`撤回退货单 ${row.no}？将反核销退款并回减库存，若库存已售出会失败`);
  await withdrawSaleReturn(row.id);
  proxy?.$modal.msgSuccess('已撤回');
  await getList();
};

const handleVoid = async (row: SaleReturnVO) => {
  await proxy?.$modal.confirm(`作废退货单 ${row.no}？已确认单据会先撤回再作废`);
  await voidSaleReturn(row.id);
  proxy?.$modal.msgSuccess('已作废');
  await getList();
};

/** 删除按钮操作：仅草稿单据可删除，后端逻辑删除，单号不回收 */
const handleDelete = async (row: SaleReturnVO) => {
  try {
    await proxy?.$modal.confirm(`是否删除退货单 ${row.no}？删除后不可恢复，单号不会回收重用。`);
  } catch {
    return;
  }
  await delSaleReturn(row.id);
  proxy?.$modal.msgSuccess('删除成功');
  await getList();
};

/** 已消费的跨页参数签名，避免 keep-alive 首次进入时 onMounted/onActivated 重复执行 */
let handledRouteQuery = '';

/** 消费跨页参数：订单「执行记录」跳退货单时带单号，落到本页直接过滤 */
const consumeRouteQuery = async () => {
  const keyword = typeof route.query.keyword === 'string' ? route.query.keyword : '';
  if (!keyword || keyword === handledRouteQuery) return;
  handledRouteQuery = keyword;
  queryParams.keyword = keyword;
  queryParams.pageNum = 1;
  await getList();
};

onMounted(async () => {
  if (route.query.keyword) {
    await consumeRouteQuery();
  } else {
    await getList();
  }
  await loadAccounts();
});
// 页面被 keep-alive 缓存后再次跳入时 onMounted 不再触发，由 onActivated 消费新参数
onActivated(consumeRouteQuery);
</script>
