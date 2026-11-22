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
                  <t-tooltip :content="queryParams.keyword" :disabled="!queryParams.keyword" placement="top">
                    <t-input v-model="queryParams.keyword" placeholder="请输入采购单号" clearable class="query-w-md" @enter="handleQuery" />
                  </t-tooltip>
                </t-form-item>
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

        <div v-if="!showSearch" class="mb-[10px] flex justify-end">
          <right-toolbar v-model:show-search="showSearch" @query-table="getList"></right-toolbar>
        </div>

        <div class="erp-surface erp-panel">
          <div class="erp-panel__head">
            <div class="flex items-center justify-between w-full">
              <t-button v-hasPermi="['erp:purchase:add']" theme="primary" variant="outline" @click="handleAdd">
                <template #icon><PlusIcon /></template>
                新增采购单
              </t-button>
            </div>
          </div>

          <t-table
            class="erp-table"
            row-key="id"
            :data="purchaseList"
            :columns="listColumns"
            :loading="loading"
            :hover="true"
            :resizable="true"
            size="small"
            cell-empty-content="-"
            :empty="'暂无采购入库单'"
            @column-resize-change="(ctx: any) => saveColumnWidths('purchase/list', ctx.columnsWidth)"
          >
            <template #supplier="{ row }">{{ row.supplierName || '-' }}</template>
            <!-- 来源订单：直开单为空，转单生成的单据显示订单号并可跳订单详情（DOC/31 §10.4） -->
            <template #sourceOrder="{ row }">
              <t-button v-if="row.sourceOrderNo" variant="text" theme="primary" size="small" @click="goSourceOrder(row.sourceOrderId)">
                {{ row.sourceOrderNo }}
              </t-button>
            </template>
            <template #bizDate="{ row }">{{ toDate(row.bizDate) }}</template>
            <template #goodsAmount="{ row }">{{ formatMoney(row.goodsAmount) }}</template>
            <template #freightAmount="{ row }">{{ formatMoney(row.freightAmount) }}</template>
            <template #totalAmount="{ row }">{{ formatMoney(row.totalAmount) }}</template>
            <template #status="{ row }">
              <StatusTag kind="bill" :value="row.status" :status-text="row.statusText" draft-label="待入库" />
            </template>
            <template #operation="{ row }">
              <div class="flex items-center justify-center gap-1">
                <t-tooltip content="查看" placement="top">
                  <t-button v-hasPermi="['erp:purchase:query']" theme="primary" variant="text" shape="square" size="small" @click="handleView(row)">
                    <template #icon><BrowseIcon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.status === 'DRAFT'" content="编辑" placement="top">
                  <t-button v-hasPermi="['erp:purchase:edit']" theme="primary" variant="text" shape="square" size="small" @click="handleEdit(row)">
                    <template #icon><Edit1Icon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.status === 'DRAFT'" content="确认入库" placement="top">
                  <t-button
                    v-hasPermi="['erp:purchase:audit']"
                    theme="success"
                    variant="text"
                    shape="square"
                    size="small"
                    @click="handleConfirm(row)"
                  >
                    <template #icon><CheckCircleIcon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.status === 'CONFIRMED'" content="撤回" placement="top">
                  <t-button
                    v-hasPermi="['erp:purchase:withdraw']"
                    theme="warning"
                    variant="text"
                    shape="square"
                    size="small"
                    @click="handleWithdraw(row)"
                  >
                    <template #icon><RollbackIcon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.status === 'CONFIRMED'" content="作废" placement="top">
                  <t-button v-hasPermi="['erp:purchase:void']" theme="danger" variant="text" shape="square" size="small" @click="handleVoid(row)">
                    <template #icon><CloseCircleIcon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.status === 'DRAFT'" content="删除" placement="top">
                  <t-button v-hasPermi="['erp:purchase:remove']" theme="danger" variant="text" shape="square" size="small" @click="handleDelete(row)">
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

    <!-- 新增 / 编辑 / 查看 采购入库单 -->
    <t-drawer v-model:visible="dialog.visible" size="92%" :destroy-on-close="true" attach="body">
      <template #header>
        <div class="flex items-center gap-2">
          <span class="text-base font-medium">{{ dialog.title }}</span>
          <!-- 查看/编辑弹窗同步单据状态；DRAFT 按采购业务显示为「待入库」 -->
          <StatusTag v-if="dialogStatus" kind="bill" :value="dialogStatus" :status-text="dialogStatusText" draft-label="待入库" />
        </div>
      </template>
      <!-- 抽屉 body 自带纵向滚动，footer 固定；不再依赖无效的 body-style（普通 div 不识别） -->
      <div class="erp-drawer-body">
        <t-form ref="purchaseFormRef" :data="form" :rules="rules" label-align="top" :disabled="readonly">
          <t-row :gutter="16">
            <t-col :span="3">
              <t-form-item label="供应商" name="supplierId">
                <PartnerSelect v-model="form.supplierId" kind="supplier" placeholder="请选择供应商" />
              </t-form-item>
            </t-col>
            <t-col :span="3">
              <t-form-item label="业务日期" name="bizDate">
                <t-date-picker v-model="form.bizDate" value-type="YYYY-MM-DD" placeholder="请选择日期" style="width: 100%" />
              </t-form-item>
            </t-col>
            <t-col :span="3">
              <t-form-item label="发票类型" name="taxType">
                <t-select v-model="form.taxType" :options="taxTypeOptions" style="width: 100%" />
              </t-form-item>
            </t-col>
            <t-col :span="3">
              <t-form-item label="税率(%)" name="taxRate">
                <t-input-number
                  v-model="form.taxRate"
                  :decimal-places="2"
                  :min="0"
                  :max="100"
                  :allow-input-over-limit="false"
                  theme="normal"
                  style="width: 100%"
                />
              </t-form-item>
            </t-col>
          </t-row>
          <t-row :gutter="16">
            <t-col :span="3">
              <t-form-item label="运杂费总额" name="freightAmount">
                <t-input-number v-model="form.freightAmount" :decimal-places="2" :min="0" theme="normal" style="width: 100%" />
              </t-form-item>
            </t-col>
            <t-col :span="9">
              <t-form-item label="备注">
                <t-input v-model="form.remark" placeholder="备注" />
              </t-form-item>
            </t-col>
          </t-row>
        </t-form>

        <!-- 新建时先选来源订单，出现「从订单带入」；已挂单的单据改为只读来源信息回显 -->
        <div v-if="!form.id && !readonly" class="mb-3 mt-3 flex flex-wrap items-center gap-2">
          <span class="shrink-0 erp-text-secondary">来源订单</span>
          <SourceOrderSelect v-if="dialog.visible" v-model="form.sourceOrderId" kind="purchase" @change="onSourceOrderChange" />
          <t-button v-if="form.sourceOrderId" theme="success" variant="outline" size="small" @click="openSourceItems">
            <template #icon><DownloadIcon /></template>
            从订单带入
          </t-button>
        </div>

        <!-- 详情回显：编辑与查看态都显示来源订单，联系人/电话取执行单头快照（DOC/31 R1） -->
        <div v-if="form.sourceOrderId" class="mb-3 mt-3 flex flex-wrap items-center gap-3 rounded bg-blue-50 px-3 py-2 text-sm">
          <span>
            本单来自订单 <b>{{ form.sourceOrderNo || form.sourceOrderId }}</b>
          </span>
          <t-button variant="text" theme="primary" size="small" @click="goSourceOrder(form.sourceOrderId)">查看订单</t-button>
          <span v-if="form.contactName" class="erp-text-secondary">联系人：{{ form.contactName }}</span>
          <span v-if="form.contactPhone" class="erp-text-secondary">电话：{{ form.contactPhone }}</span>
        </div>

        <div class="erp-detail-bar">
          <div class="flex items-center gap-2">
            <span class="erp-detail-bar__title">入库明细</span>
            <!-- 挂订单时禁增行：空行会绕过订单量约束（后端整行集校验会拒，§6.7） -->
            <t-button v-if="!readonly && !form.sourceOrderId" theme="primary" variant="outline" size="small" @click="addRow">
              <template #icon><PlusIcon /></template>
              添加明细
            </t-button>
          </div>
          <span v-if="form.sourceOrderId" class="text-xs erp-text-secondary">已挂来源订单，明细由订单带入，不可增删行</span>
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
          :empty="'请添加明细'"
          @row-click="(ctx: any) => onRowSelect(ctx.row)"
          @column-resize-change="(ctx: any) => saveColumnWidths('purchase/detail', ctx.columnsWidth)"
        >
          <template #rowIndex="{ rowIndex }">{{ rowIndex + 1 }}</template>
          <template #product="{ row, rowIndex }">
            <ProductSelect
              v-model="row.productId"
              :disabled="readonly || !!form.sourceOrderId"
              show-category-filter
              placeholder="请选择产品"
              @change="(product) => onProductChange(rowIndex, product)"
            />
          </template>
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
          <template #price="{ row }">
            <t-input-number
              v-model="row.price"
              :decimal-places="2"
              :min="0"
              theme="normal"
              align="right"
              :disabled="readonly"
              style="width: 100%"
              @change="() => (row.priceTouched = true)"
            />
          </template>
          <template #amount="{ rowIndex }">{{ formatMoney(rowAmount(rowIndex)) }}</template>
          <template #costPrice="{ rowIndex }">
            <span class="font-medium text-primary">{{ formatCost(displayList[rowIndex]?.costPrice) }}</span>
          </template>
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

        <!-- 历史价格（DOC/26 §7）：按商品查询全部供应商成交记录，跟随当前选中行商品，倒序展示，点击回填单价 -->
        <div class="mt-4">
          <div class="flex items-center justify-between mb-1">
            <span class="erp-detail-bar__title">
              历史价格<template v-if="activeProductName">（{{ activeProductName }}）</template>
            </span>
            <span class="erp-text-secondary">最近 {{ PRICE_HISTORY_LIMIT }} 条{{ readonly ? '' : '，点击行可回填单价' }}</span>
          </div>
          <t-table
            class="erp-table erp-clickable-table"
            row-key="id"
            :data="priceHistory.list"
            :columns="historyColumns"
            :loading="priceHistory.loading"
            :hover="!readonly"
            :resizable="true"
            size="small"
            max-height="200"
            cell-empty-content="-"
            :empty="'暂无该商品的历史采购记录'"
            @column-resize-change="(ctx: any) => saveColumnWidths('purchase/history', ctx.columnsWidth)"
            @row-click="(ctx: any) => applyHistoryPrice(ctx.row)"
          >
            <template #bizDate="{ row }">{{ toDate(row.bizDate) }}</template>
            <template #partyName="{ row }">{{ row.partyName || '-' }}</template>
            <template #qty="{ row }">{{ formatQtyTrim(row.qty) }}</template>
            <template #price="{ row }">{{ formatMoney(row.price) }}</template>
            <template #amount="{ row }">{{ formatMoney(row.amount) }}</template>
          </t-table>
        </div>
      </div>

      <template #footer>
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-4 text-sm">
            <span
              >货品金额：<b>{{ formatMoney(goodsAmount) }}</b></span
            >
            <span
              >运杂费：<b>{{ formatMoney(freightTotal) }}</b></span
            >
            <span
              >合计：<b class="text-primary">{{ formatMoney(totalAmount) }}</b></span
            >
          </div>
          <div class="flex items-center gap-2">
            <t-button theme="default" variant="outline" @click="dialog.visible = false">{{ readonly ? '关 闭' : '取 消' }}</t-button>
            <t-button v-if="!readonly" theme="primary" variant="outline" @click="submitForm(false)">保存为待入库</t-button>
            <t-button v-if="!readonly" v-hasPermi="['erp:purchase:audit']" theme="success" @click="submitForm(true)">保存并确认入库</t-button>
          </div>
        </div>
      </template>
    </t-drawer>

    <!-- 从采购订单带入：只列该单 CONFIRMED 且 remain>0 的未交行，勾选后回填明细并写行级来源ID -->
    <t-dialog v-model:visible="openItemState.visible" header="从采购订单带入明细" width="820px" attach="body">
      <t-alert theme="info" :close="false" class="mb-3" message="只带本次勾选的未交行；带入后仍可修改数量与单价。" />
      <t-table
        class="erp-table"
        row-key="id"
        :data="openItemState.list"
        :columns="openItemColumns"
        :loading="openItemState.loading"
        :hover="true"
        :resizable="true"
        size="small"
        max-height="380"
        cell-empty-content="-"
        :empty="'该订单没有可带入的未交行'"
        @column-resize-change="(ctx: any) => saveColumnWidths('purchase/open-items', ctx.columnsWidth)"
      >
        <template #checked="{ row }">
          <t-checkbox v-model="row.checked" />
        </template>
        <template #product="{ row }">{{ row.productCode ? `${row.productCode} ` : '' }}{{ row.productName || '-' }}</template>
        <template #remain="{ row }">{{ formatQtyTrim(row.remain) }}</template>
        <template #takeQty="{ row, rowIndex }">
          <t-input-number
            :key="`qty-${rowIndex}-${qtyPrecision(row.qtyScale)}`"
            v-model="row.takeQty"
            :decimal-places="qtyPrecision(row.qtyScale)"
            :step="qtyStep(row.qtyScale)"
            :min="0"
            :max="Number(row.remain ?? 0)"
            theme="normal"
            align="right"
            :disabled="!row.checked"
            style="width: 100%"
          />
        </template>
        <template #price="{ row }">{{ formatMoney(row.price) }}</template>
      </t-table>
      <template #footer>
        <t-button theme="default" variant="outline" @click="openItemState.visible = false">取 消</t-button>
        <t-button theme="primary" @click="confirmSourceItems">带 入</t-button>
      </template>
    </t-dialog>
  </div>
</template>

<script setup name="ErpPurchase" lang="ts">
import {
  addPurchase,
  confirmPurchase,
  delPurchase,
  getPurchase,
  getPurchasePriceHistory,
  listPurchase,
  previewAllocation,
  updatePurchase,
  voidPurchase,
  withdrawPurchase
} from '@/api/erp/trade';
import { ERP_BILL_STATUS, ERP_TAX_TYPE } from '@/api/erp/enums';
import type { ErpBillStatus, ErpTaxType } from '@/api/erp/enums';
import type {
  ErpAmount,
  ErpId,
  ErpPriceHistoryVO,
  ErpProductVO,
  ErpPurchaseForm,
  ErpPurchaseItemVO,
  ErpPurchaseOrderItemVO,
  ErpPurchaseOrderVO,
  ErpPurchaseVO,
  ErpSaleOrderVO
} from '@/api/erp/types';
import { listPurchaseOpenItems } from '@/api/erp/order';
import SourceOrderSelect from '@/components/erp/SourceOrderSelect.vue';
import { formatMoney, formatQtyTrim, normalizeQty, qtyPrecision, qtyStep, roundMoney } from '@/utils/erp/calc';
// 列宽记忆：拖拽后按表名存 localStorage，进页面还原（见 hooks/useTableColumnWidth）
import { mergeSavedWidths, saveColumnWidths } from '@/hooks/useTableColumnWidth';
import PartnerSelect from '@/components/erp/PartnerSelect.vue';
import ProductSelect from '@/components/erp/ProductSelect.vue';
import StatusTag from '@/components/erp/StatusTag.vue';
import { BrowseIcon, CheckCircleIcon, CloseCircleIcon, DeleteIcon, DownloadIcon, Edit1Icon, PlusIcon, RollbackIcon } from 'tdesign-icons-vue-next';

interface PurchaseRow {
  productId?: ErpId;
  productCode?: string;
  productName?: string;
  spec?: string;
  unit?: string;
  qty: number;
  /** 数量精度 0~3（商品级，DOC/26 §10） */
  qtyScale?: number;
  price: number;
  /** 用户已手动改过单价：为 true 后不再用最近成交价自动带价 */
  priceTouched?: boolean;
  remark?: string;
  /** 来源采购订单行ID：转单/带入时有值，直开单为空（DOC/31 §10.4） */
  sourceOrderItemId?: ErpId;
  /** t-table 行 key（前端生成，不提交后端） */
  __key?: string;
}

/** 从订单带入弹窗的行：未交行 + 勾选态 + 本次数量 */
interface OpenItemRow extends ErpPurchaseOrderItemVO {
  checked: boolean;
  takeQty: number;
}

interface PurchaseFormState {
  id?: ErpId;
  /** 来源采购订单ID（挂单时有值，直开单为空） */
  sourceOrderId?: ErpId;
  /** 来源采购订单号快照，仅回显 */
  sourceOrderNo?: string;
  /** 供应商联系人快照（挂订单时由后端从订单带出） */
  contactName?: string;
  /** 供应商联系电话快照 */
  contactPhone?: string;
  supplierId?: ErpId;
  bizDate: string;
  /** 发票类型（整单统一） */
  taxType: ErpTaxType;
  /** 税率%（整单统一） */
  taxRate: number;
  freightAmount: number;
  remark: string;
}

interface PurchaseQueryState extends PageQuery {
  keyword: string;
  supplierId?: ErpId;
  status: string;
}

const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const router = useRouter();
const route = useRoute();

/** DRAFT 在采购单业务上叫「待入库」，其余状态沿用全局文案 */
const statusOptions = Object.entries(ERP_BILL_STATUS).map(([value, item]) => ({
  value: value as ErpBillStatus,
  label: value === 'DRAFT' ? '待入库' : item.label
}));
const taxTypeOptions = Object.entries(ERP_TAX_TYPE).map(([value, item]) => ({ value: value as ErpTaxType, label: item.label }));

/** 主列表列配置：t-table 用 columns 数组，插槽名与 cell 字符串同名（DOC/35 §5） */
const listColumns: any[] = [
  { colKey: 'no', title: '单号', align: 'center', minWidth: 130, ellipsis: true },
  { colKey: 'supplierName', title: '供应商', align: 'center', minWidth: 140, ellipsis: true, cell: 'supplier' },
  { colKey: 'sourceOrderNo', title: '来源订单', align: 'center', minWidth: 150, ellipsis: true, cell: 'sourceOrder' },
  { colKey: 'bizDate', title: '业务日期', align: 'center', width: 110, cell: 'bizDate' },
  { colKey: 'goodsAmount', title: '货品金额', align: 'right', width: 120, cell: 'goodsAmount' },
  { colKey: 'freightAmount', title: '运杂费', align: 'right', width: 110, cell: 'freightAmount' },
  { colKey: 'totalAmount', title: '合计', align: 'right', width: 130, cell: 'totalAmount' },
  { colKey: 'status', title: '状态', align: 'center', width: 90, cell: 'status' },
  { colKey: 'remark', title: '备注', align: 'center', minWidth: 120, ellipsis: true },
  { colKey: 'operation', title: '操作', align: 'center', width: 160, cell: 'operation' }
];
mergeSavedWidths('purchase/list', listColumns);

/** 明细行列表配置：数量/单价/备注是行内编辑控件；挂订单或只读时不出现操作列 */
const rowColumns = computed<any[]>(() => {
  const cols: any[] = [
    { colKey: 'rowIndex', title: '#', width: 50, align: 'center', cell: 'rowIndex' },
    { colKey: 'productId', title: '产品', minWidth: 220, cell: 'product' },
    { colKey: 'spec', title: '规格', width: 110, align: 'center', ellipsis: true },
    { colKey: 'unit', title: '单位', width: 70, align: 'center', ellipsis: true },
    { colKey: 'qty', title: '数量', width: 130, align: 'right', cell: 'qty' },
    { colKey: 'price', title: '单价', width: 130, align: 'right', cell: 'price' },
    { colKey: 'amount', title: '合计', width: 120, align: 'right', cell: 'amount' },
    { colKey: 'costPrice', title: '成本单价', width: 120, align: 'right', cell: 'costPrice' },
    { colKey: 'remark', title: '备注', minWidth: 140, cell: 'remark' }
  ];
  if (!readonly.value && !form.sourceOrderId) {
    cols.push({ colKey: 'operation', title: '操作', width: 70, align: 'center', cell: 'rowOperation' });
  }
  return mergeSavedWidths('purchase/detail', cols);
});

/** 历史价格列表配置：整表可点，点击行回填单价 */
const historyColumns: any[] = [
  { colKey: 'bizDate', title: '业务日期', width: 110, align: 'center', cell: 'bizDate' },
  { colKey: 'partyName', title: '供应商', minWidth: 120, ellipsis: true, cell: 'partyName' },
  { colKey: 'orderNo', title: '单据号', minWidth: 140, ellipsis: true },
  { colKey: 'qty', title: '数量', width: 110, align: 'right', cell: 'qty' },
  { colKey: 'price', title: '单价', width: 120, align: 'right', cell: 'price' },
  { colKey: 'amount', title: '金额', width: 130, align: 'right', cell: 'amount' }
];
mergeSavedWidths('purchase/history', historyColumns);

/** 从订单带入弹窗列配置：勾选列沿用行内 checked 字段，不改 TDesign 的 selectedRowKeys 契约 */
const openItemColumns: any[] = [
  { colKey: 'checked', title: ' ', width: 46, align: 'center', cell: 'checked' },
  { colKey: 'productId', title: '商品', minWidth: 200, cell: 'product' },
  { colKey: 'spec', title: '规格', width: 100, align: 'center', ellipsis: true },
  { colKey: 'remain', title: '未交量', width: 110, align: 'right', ellipsis: true, cell: 'remain' },
  { colKey: 'takeQty', title: '本次数量', width: 140, align: 'right', cell: 'takeQty' },
  { colKey: 'price', title: '单价', width: 110, align: 'right', cell: 'price' }
];
mergeSavedWidths('purchase/open-items', openItemColumns);

const purchaseList = ref<ErpPurchaseVO[]>([]);
const loading = ref(true);
const detailLoading = ref(false);
const showSearch = ref(true);
const total = ref(0);
const purchaseFormRef = ref<any>();
/** 查询表单：已换 t-form，ref 提供 reset()（EP 是 resetFields()） */
const queryFormRef = ref<any>();

const dialog = reactive<DialogOption>({
  visible: false,
  title: ''
});

/** 只读（查看已确认/已作废单据） */
const readonly = ref(false);
/** 弹窗内当前单据状态；新增未保存时为空，不显示状态标签 */
const dialogStatus = ref('');
/** 弹窗内当前单据状态中文文案（后端回填） */
const dialogStatusText = ref('');

const rows = ref<PurchaseRow[]>([]);

/** 从订单带入弹窗 */
const openItemState = reactive({ visible: false, loading: false, list: [] as OpenItemRow[] });

/** 历史价格区当前跟随的明细行（DOC/26 §7） */
const activeRowIndex = ref(0);
const activeProductName = computed(() => rows.value[activeRowIndex.value]?.productName || '');
/** 采购历史价格固定条数（后端另有 1~50 兜底） */
const PRICE_HISTORY_LIMIT = 10;
const priceHistory = reactive({ loading: false, list: [] as ErpPriceHistoryVO[] });
/** 请求序号：切换行/商品后丢弃过期响应 */
let priceHistorySeq = 0;

/**
 * 后端返回的行数据（编辑态来自预览接口、查看态来自详情接口），前端不自行分摊
 */
const serverRows = ref<ErpPurchaseItemVO[]>([]);
/** 后端数据是否与当前行数匹配 */
const serverReady = computed(() => serverRows.value.length > 0 && serverRows.value.length === rows.value.length);

const createForm = (): PurchaseFormState => ({
  id: undefined,
  sourceOrderId: undefined,
  sourceOrderNo: undefined,
  contactName: undefined,
  contactPhone: undefined,
  supplierId: undefined,
  bizDate: new Date().toISOString().slice(0, 10),
  taxType: 'VAT_INVOICE',
  taxRate: 13,
  freightAmount: 0,
  remark: ''
});

const form = reactive<PurchaseFormState>(createForm());

const queryParams = reactive<PurchaseQueryState>({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  supplierId: undefined,
  status: ''
});

// t-form 的 rules 形状与 EP 的 ElFormRules 不兼容（本页仅用于必填星号，提交前另有 validateRows 手工校验）
const rules: Record<string, any> = {
  supplierId: [{ required: true, message: '供应商不能为空', trigger: 'change' }],
  bizDate: [{ required: true, message: '业务日期不能为空', trigger: 'change' }]
};

const num = (value: ErpAmount | undefined | null): number => {
  const n = Number(value ?? 0);
  return Number.isFinite(n) ? n : 0;
};

/** 日期只取 yyyy-MM-dd */
const toDate = (value: string | undefined): string => (value ? String(value).slice(0, 10) : '');

/** 成本类数值四位小数展示 */
const formatCost = (value: number | undefined | null): string => {
  const n = Number(value ?? 0);
  return Number.isFinite(n) ? n.toFixed(4) : '0.0000';
};

/**
 * 行金额：数量 × 单价，两位小数 HALF_UP（纯展示反馈，不与后端口径冲突）
 */
const rowAmount = (index: number): number => {
  const row = rows.value[index];
  if (!row) return 0;
  return roundMoney((row.qty || 0) * (row.price || 0));
};

/**
 * 成本单价取自后端（保存与预览共用同一套分摊/还原口径）
 * 前端不再自行分摊，避免出现第二份实现
 */
const displayList = computed(() => {
  return rows.value.map((_row, index) => {
    const source = serverReady.value ? serverRows.value[index] : undefined;
    return {
      costPrice: source ? num(source.costPrice) : 0
    };
  });
});

const goodsAmount = computed(() => roundMoney(rows.value.reduce((sum, _row, index) => sum + rowAmount(index), 0)));
const freightTotal = computed(() => roundMoney(num(form.freightAmount)));
const totalAmount = computed(() => roundMoney(goodsAmount.value + freightTotal.value));

/** 程序化赋值（加载详情/重置表单）时抑制监听器，避免把后端数据清掉 */
let suppressWatch = false;

/** 查询采购单列表 */
const getList = async () => {
  loading.value = true;
  try {
    const res = await listPurchase(queryParams);
    purchaseList.value = res.rows ?? [];
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

/** 明细行 t-table 行 key：新增行没有业务主键，用前端自增序号 */
let rowSeq = 0;
const nextRowKey = (): string => `row-${++rowSeq}`;

const createRow = (): PurchaseRow => ({
  __key: nextRowKey(),
  productId: undefined,
  qty: 1,
  price: 0,
  remark: '',
  sourceOrderItemId: undefined
});

/** 表单重置 */
const resetForm = async () => {
  suppressWatch = true;
  Object.assign(form, createForm());
  rows.value = [createRow()];
  serverRows.value = [];
  openItemState.visible = false;
  openItemState.list = [];
  activeRowIndex.value = 0;
  readonly.value = false;
  dialogStatus.value = '';
  dialogStatusText.value = '';
  await nextTick();
  suppressWatch = false;
};

/** 新增按钮操作 */
const handleAdd = async () => {
  await resetForm();
  dialog.visible = true;
  dialog.title = '新增采购入库单';
};

/** 选中一条服务端过滤/分页订单时，沿用其供应商和单号快照。 */
const onSourceOrderChange = (order?: ErpPurchaseOrderVO | ErpSaleOrderVO) => {
  if (order && !('supplierId' in order)) return;
  form.sourceOrderNo = order?.no;
  if (order && 'supplierId' in order) form.supplierId = order.supplierId;
  openItemState.visible = false;
  openItemState.list = [];
  rows.value = [createRow()];
  serverRows.value = [];
  activeRowIndex.value = 0;
  schedulePriceHistory();
};

/** 打开「从订单带入」：拉取该单未交行，默认勾选且数量＝未交量 */
const openSourceItems = async () => {
  if (!form.sourceOrderId) return;
  openItemState.visible = true;
  openItemState.loading = true;
  openItemState.list = [];
  try {
    const res = await listPurchaseOpenItems(form.sourceOrderId);
    openItemState.list = (res.data ?? []).map((item) => ({ ...item, checked: true, takeQty: num(item.remain) }));
    if (openItemState.list.length === 0) {
      openItemState.visible = false;
      proxy?.$modal.msgWarning('该订单没有可带入的未交行');
    }
  } finally {
    openItemState.loading = false;
  }
};

/** 带入所选未交行：整体替换明细并写行级来源ID，订单价视为已定价 */
const confirmSourceItems = () => {
  const selected = openItemState.list.filter((item) => item.checked && item.takeQty > 0);
  if (selected.length === 0) {
    proxy?.$modal.msgWarning('请勾选至少一行并填写本次数量');
    return;
  }
  if (selected.some((item) => item.takeQty > num(item.remain))) {
    proxy?.$modal.msgWarning('本次数量不能超过未交量');
    return;
  }
  rows.value = selected.map((item) => ({
    __key: nextRowKey(),
    productId: item.productId,
    productCode: item.productCode,
    productName: item.productName,
    spec: item.spec,
    unit: item.unit,
    qty: item.takeQty,
    qtyScale: item.qtyScale,
    price: num(item.price),
    priceTouched: true,
    remark: item.remark,
    sourceOrderItemId: item.id
  }));
  serverRows.value = [];
  activeRowIndex.value = 0;
  openItemState.visible = false;
  schedulePriceHistory();
};

/** 跳转来源订单详情 */
const goSourceOrder = (orderId?: ErpId) => {
  if (!orderId) return;
  router.push({ path: '/erp-purchase/purchase-order', query: { id: String(orderId) } });
};

const loadDetail = async (id: ErpId, isReadonly: boolean) => {
  detailLoading.value = true;
  try {
    const res = await getPurchase(id);
    const detail = res.data;
    dialogStatus.value = detail.status ?? '';
    dialogStatusText.value = detail.statusText ?? '';
    suppressWatch = true;
    Object.assign(form, {
      id: detail.id,
      sourceOrderId: detail.sourceOrderId,
      sourceOrderNo: detail.sourceOrderNo,
      contactName: detail.contactName,
      contactPhone: detail.contactPhone,
      supplierId: detail.supplierId,
      bizDate: toDate(detail.bizDate),
      taxType: (detail.taxType || 'VAT_INVOICE') as ErpTaxType,
      taxRate: num(detail.taxRate),
      freightAmount: num(detail.freightAmount),
      remark: detail.remark || ''
    });
    rows.value = (detail.items ?? []).map((item) => ({
      __key: nextRowKey(),
      productId: item.productId,
      productCode: item.productCode,
      productName: item.productName,
      spec: item.spec,
      unit: item.unit,
      qty: num(item.qty),
      qtyScale: item.qtyScale,
      price: num(item.price),
      // 回显的单价视为已定价，不再被最近成交价自动覆盖
      priceTouched: true,
      remark: item.remark,
      // 行级来源必须回显，否则挂订单草稿「编辑→保存」会把关联冲成 null（DOC/31 D13）
      sourceOrderItemId: item.sourceOrderItemId
    }));
    serverRows.value = detail.items ?? [];
    readonly.value = isReadonly;
    activeRowIndex.value = 0;
    schedulePriceHistory();
    await nextTick();
    suppressWatch = false;
    return detail;
  } finally {
    detailLoading.value = false;
  }
};

/** 编辑按钮操作 */
const handleEdit = async (row: ErpPurchaseVO) => {
  await resetForm();
  dialog.visible = true;
  dialog.title = `编辑采购入库单 ${row.no ?? ''}`;
  await loadDetail(row.id, false);
};

/** 查看按钮操作 */
const handleView = async (row: ErpPurchaseVO) => {
  await resetForm();
  dialog.visible = true;
  dialog.title = `查看采购入库单 ${row.no ?? ''}`;
  await loadDetail(row.id, true);
};

/** 跨页跳转：按单据ID直接打开详情，不依赖列表当前页（DOC/31 §10.4） */
const openDetailById = async (id: ErpId) => {
  await resetForm();
  dialog.visible = true;
  dialog.title = '查看采购入库单';
  const detail = await loadDetail(id, true);
  dialog.title = `查看采购入库单 ${detail?.no ?? ''}`;
};

/** 添加明细行 */
const addRow = () => {
  // 挂订单禁加行：空行会绕过订单量约束（DOC/31 §6.7）
  if (form.sourceOrderId) return;
  rows.value.push(createRow());
};

/** 删除明细行 */
const removeRow = (index: number) => {
  // 挂订单禁删行：删掉后行级关联无法恢复（DOC/31 D13）
  if (form.sourceOrderId) return;
  rows.value.splice(index, 1);
  if (rows.value.length === 0) {
    rows.value.push(createRow());
  }
  if (activeRowIndex.value >= rows.value.length) {
    activeRowIndex.value = rows.value.length - 1;
  }
  schedulePriceHistory();
};

/** 选择产品后带出采购价 */
const onProductChange = (index: number, product?: ErpProductVO) => {
  const row = rows.value[index];
  if (!row) return;
  if (!product) {
    row.productCode = undefined;
    row.productName = undefined;
    row.spec = undefined;
    row.unit = undefined;
    row.qtyScale = undefined;
    return;
  }
  row.productCode = product.code;
  row.productName = product.name;
  row.spec = product.spec;
  row.unit = product.unit;
  row.qtyScale = product.qtyScale;
  // 精度随商品切换时把已录数量归一化，避免小数商品的数量挂到整数商品行上（el-input-number 只改显示不回写 model）
  row.qty = normalizeQty(row.qty, product.qtyScale);
  row.price = num(product.purchasePrice ?? product.costPrice);
  activeRowIndex.value = index;
  schedulePriceHistory();
};

/** 拉取当前选中行的历史采购价；无商品时清空（不限供应商，全市场采购记录）。
 * 加载成功后若当前行单价未手改过，自动带入最近一条采购价，无历史价回落商品资料采购价。 */
const fetchPriceHistory = async () => {
  const productId = rows.value[activeRowIndex.value]?.productId;
  if (!productId) {
    priceHistory.list = [];
    priceHistory.loading = false;
    return;
  }
  const seq = ++priceHistorySeq;
  priceHistory.list = [];
  priceHistory.loading = true;
  try {
    const res = await getPurchasePriceHistory(productId, PRICE_HISTORY_LIMIT);
    if (seq !== priceHistorySeq) return;
    priceHistory.list = res.data ?? [];
    const latest = priceHistory.list[0];
    const target = rows.value[activeRowIndex.value];
    if (latest && target && target.productId === productId && !readonly.value && !target.priceTouched) {
      target.price = num(latest.price);
    }
  } catch {
    /* 历史价加载失败按空态展示 */
    if (seq === priceHistorySeq) priceHistory.list = [];
  } finally {
    if (seq === priceHistorySeq) priceHistory.loading = false;
  }
};
const schedulePriceHistory = useDebounceFn(fetchPriceHistory, 300);

/** 明细行点击：切换历史价跟随行 */
const onRowSelect = (row: PurchaseRow) => {
  const index = rows.value.indexOf(row);
  if (index < 0 || index === activeRowIndex.value) return;
  activeRowIndex.value = index;
  schedulePriceHistory();
};

/** 行商品变化时重查历史价（历史价按商品查询，供应商变化不影响结果） */
watch(
  () => rows.value.map((row) => row.productId ?? '').join('|'),
  () => schedulePriceHistory()
);

/** 点击历史价行回填当前行单价（只读态不生效），视为用户主动定价 */
const applyHistoryPrice = (row: ErpPriceHistoryVO) => {
  if (readonly.value) return;
  const target = rows.value[activeRowIndex.value];
  if (!target) return;
  target.price = num(row.price);
  target.priceTouched = true;
};

/**
 * 拉取后端预览分摊（唯一口径来源）
 *
 * 预览接口要求供应商/日期/明细齐备，未填齐时静默跳过，避免无效请求与错误提示。
 */
let previewSeq = 0;
/** 请求失败后的重试延迟（规避请求拦截器的 500ms 重复提交拦截） */
const PREVIEW_RETRY_DELAY = 600;
const sleep = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms));

const fetchPreview = async (): Promise<void> => {
  if (readonly.value || !dialog.visible) return;
  if (!form.supplierId || !form.bizDate) return;
  // 后端要求每行都带 productId / qty，否则预览会直接报错
  const ready = rows.value.length > 0 && rows.value.every((row) => row.productId && row.qty > 0);
  if (!ready) return;
  const seq = ++previewSeq;
  const payload = buildPayload(false);
  const applyResult = (list: ErpPurchaseItemVO[]) => {
    serverRows.value = list.length === rows.value.length ? list : [];
  };
  try {
    const res = await previewAllocation(payload);
    // 已有更新的请求发出，丢弃过期响应
    if (seq !== previewSeq) return;
    applyResult(res.data ?? []);
  } catch {
    if (seq !== previewSeq) return;
    // 连续两次相同载荷的 POST 会被前端请求拦截器判为重复提交，稍后重试
    await sleep(PREVIEW_RETRY_DELAY);
    if (seq !== previewSeq) return;
    try {
      const res = await previewAllocation(payload);
      if (seq !== previewSeq) return;
      applyResult(res.data ?? []);
    } catch {
      if (seq !== previewSeq) return;
      serverRows.value = [];
    }
  }
};

/** 防抖 300ms 自动预览：供应商/发票类型/税率/运杂费总额/明细行变化均触发 */
const schedulePreview = useDebounceFn(fetchPreview, 300);

/** 表头（供应商 / 发票类型 / 税率 / 运杂费总额）变化 */
watch(
  () => [form.supplierId, form.taxType, form.taxRate, form.freightAmount],
  () => {
    if (readonly.value || suppressWatch) return;
    serverRows.value = [];
    schedulePreview();
  }
);

/** 明细行（产品、数量、单价）变化 */
watch(
  () => rows.value.map((row) => `${row.productId}-${row.qty}-${row.price}`).join('|'),
  () => {
    if (readonly.value || suppressWatch) return;
    serverRows.value = [];
    schedulePreview();
  }
);

/** 组装提交数据：税值在表头整单统一，明细不再提交 */
const buildPayload = (confirm: boolean): ErpPurchaseForm => ({
  id: form.id,
  sourceOrderId: form.sourceOrderId,
  supplierId: form.supplierId,
  bizDate: form.bizDate,
  taxType: form.taxType,
  taxRate: num(form.taxRate),
  freightAmount: freightTotal.value,
  remark: form.remark,
  confirm,
  items: rows.value.map((row) => ({
    productId: row.productId,
    sourceOrderItemId: row.sourceOrderItemId,
    qty: row.qty,
    price: row.price,
    remark: row.remark
  }))
});

/** 提交前校验 */
const validateRows = (): boolean => {
  if (!form.supplierId) {
    proxy?.$modal.msgWarning('请选择供应商');
    return false;
  }
  if (!form.bizDate) {
    proxy?.$modal.msgWarning('请选择业务日期');
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
    if (!(row.price >= 0)) {
      proxy?.$modal.msgWarning('单价不能为负数');
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
    await updatePurchase(payload);
  } else {
    await addPurchase(payload);
  }
  proxy?.$modal.msgSuccess(confirm ? '已保存并确认入库' : '已保存为待入库');
  dialog.visible = false;
  await getList();
};

/** 确认按钮操作 */
const handleConfirm = async (row: ErpPurchaseVO) => {
  try {
    await proxy?.$modal.confirm(`是否确认采购单「${row.no ?? ''}」？确认入库后库存增加并生成应付。`);
  } catch {
    return;
  }
  await confirmPurchase(row.id);
  proxy?.$modal.msgSuccess('已确认入库');
  await getList();
};

/** 撤回按钮操作 */
const handleWithdraw = async (row: ErpPurchaseVO) => {
  try {
    await proxy?.$modal.confirm(`是否撤回采购单「${row.no ?? ''}」？撤回后回到待入库可继续修改。`);
  } catch {
    return;
  }
  await withdrawPurchase(row.id);
  proxy?.$modal.msgSuccess('已撤回');
  await getList();
};

/** 作废按钮操作 */
const handleVoid = async (row: ErpPurchaseVO) => {
  try {
    await proxy?.$modal.confirm(`是否作废采购单「${row.no ?? ''}」？作废为终态，不可再修改。`);
  } catch {
    return;
  }
  await voidPurchase(row.id);
  proxy?.$modal.msgSuccess('已作废');
  await getList();
};

/** 删除按钮操作：仅草稿单据可删除，后端逻辑删除，单号不回收 */
const handleDelete = async (row: ErpPurchaseVO) => {
  try {
    await proxy?.$modal.confirm(`是否删除采购单「${row.no ?? ''}」？删除后不可恢复，单号不会回收重用。`);
  } catch {
    return;
  }
  await delPurchase(row.id);
  proxy?.$modal.msgSuccess('删除成功');
  await getList();
};

/** 已消费的跨页参数签名，避免 keep-alive 首次进入时 onMounted/onActivated 重复执行 */
let handledRouteQuery = '';

/** 消费跨页参数：订单页带单号过滤，转单后带单据ID直达详情（DOC/31 §10.4） */
const consumeRouteQuery = async () => {
  const keyword = typeof route.query.keyword === 'string' ? route.query.keyword : '';
  const id = typeof route.query.id === 'string' ? route.query.id : '';
  const queryKey = keyword || id ? `${keyword}|${id}` : '';
  if (!queryKey || queryKey === handledRouteQuery) return;
  handledRouteQuery = queryKey;
  if (keyword) {
    queryParams.keyword = keyword;
    queryParams.pageNum = 1;
  }
  await getList();
  if (id) {
    try {
      await openDetailById(id);
    } catch {
      // 失败提示由请求拦截器统一给出
    }
  }
};

onMounted(async () => {
  if (route.query.keyword || route.query.id) {
    await consumeRouteQuery();
  } else {
    await getList();
  }
});
// 页面被 keep-alive 缓存后再次跳入时 onMounted 不再触发，由 onActivated 消费新参数
onActivated(consumeRouteQuery);
</script>

<style scoped lang="scss">
/* 历史价格表整行可点回填单价：hover 时给手型（文档「点击行可回填单价」的可见反馈） */
.erp-clickable-table :deep(.t-table tbody tr) {
  cursor: pointer;
}
</style>
