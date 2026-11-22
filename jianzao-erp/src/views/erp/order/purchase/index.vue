<template>
  <div class="p-2">
    <transition :enter-active-class="proxy?.animate.searchAnimate.enter" :leave-active-class="proxy?.animate.searchAnimate.leave">
      <div v-show="showSearch" class="erp-row-container">
        <t-card :bordered="false" class="erp-filter-card" title="筛选条件" subtitle="按订单号、供应商、状态与交期组合查询">
          <template #actions>
            <right-toolbar v-model:show-search="showSearch" @query-table="getList"></right-toolbar>
          </template>
          <t-form ref="queryFormRef" :data="queryParams" class="erp-filter-row" label-align="right" :label-width="'auto'" @submit="handleQuery">
            <t-form-item label="关键字" name="keyword">
              <t-tooltip :content="queryParams.keyword" :disabled="!queryParams.keyword" placement="top">
                <t-input v-model="queryParams.keyword" placeholder="订单号/供应商单号" clearable class="query-w-md" @enter="handleQuery" />
              </t-tooltip>
            </t-form-item>
            <t-form-item label="供应商" name="supplierId">
              <PartnerSelect v-model="queryParams.supplierId" kind="supplier" placeholder="全部供应商" clearable class="query-w-md" />
            </t-form-item>
            <t-form-item label="状态" name="status">
              <t-select v-model="queryParams.status" placeholder="单据状态" clearable class="query-w-sm" :options="statusOptions" />
            </t-form-item>
            <t-form-item label="执行状态" name="execStatus">
              <t-select v-model="queryParams.execStatus" placeholder="执行状态" clearable class="query-w-sm" :options="execStatusOptions" />
            </t-form-item>
            <t-form-item label="交期">
              <FieldTip content="按订单派生交期（最早未交行交期）过滤，不是承诺交期" />
              <t-date-range-picker
                v-model="dueDateRange"
                mode="date"
                value-type="YYYY-MM-DD"
                :placeholder="['交期起', '交期止']"
                class="query-w-lg"
              />
            </t-form-item>
            <t-form-item label="只看未完成">
              <t-switch v-model="queryParams.unfinished" />
            </t-form-item>
            <t-form-item label="只看已逾期">
              <t-switch v-model="queryParams.overdue" />
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
          <t-button v-hasPermi="['erp:purchase-order:add']" theme="primary" variant="outline" @click="handleAdd">新增采购订单</t-button>
        </div>
      </div>

      <t-table
        class="erp-table"
        row-key="id"
        :data="orderList"
        :columns="listColumns"
        :loading="loading"
        :hover="true"
        :resizable="true"
        size="small"
        :empty="'暂无采购订单'"
        @column-resize-change="(ctx: any) => saveColumnWidths('order/list', ctx.columnsWidth)"
      >
        <template #dueDateHeader>
          <span>交期</span>
          <FieldTip content="派生交期：取最早未交行的要求到货日；逾期标红一律按此日期 + 允许延迟天数判定" />
        </template>
        <template #no="{ row }">
          <t-button variant="text" theme="primary" size="small" @click="handleView(row)">{{ row.no || '-' }}</t-button>
        </template>
        <template #bizDate="{ row }">{{ toDate(row.bizDate) || '-' }}</template>
        <template #dueDate="{ row }">
          <span>{{ toDate(row.dueDate) || '-' }}</span>
          <span v-if="overdueDays(row) > 0" class="ml-1 text-danger font-medium">逾期 {{ overdueDays(row) }} 天</span>
        </template>
        <template #progress="{ row }">
          <div class="flex items-center gap-2">
            <t-progress :percentage="progressPercent(row)" :color="progressColor(row)" :stroke-width="8" :label="false" style="flex: 1" />
            <span class="text-xs erp-text-secondary whitespace-nowrap">{{ formatQtyTrim(row.executedQty) }}/{{ formatQtyTrim(row.totalQty) }}</span>
          </div>
        </template>
        <template #remainQty="{ row }">{{ remainText(row.remainQty) }}</template>
        <template #goodsAmount="{ row }">{{ formatMoney(row.goodsAmount) }}</template>
        <template #executedAmount="{ row }">{{ row.executedAmount == null ? '—' : formatMoney(row.executedAmount) }}</template>
        <template #status="{ row }">
          <div class="flex items-center justify-center gap-1">
            <StatusTag kind="bill" :value="row.status" :status-text="row.statusText" />
            <StatusTag kind="exec" :value="row.execStatus" :status-text="row.execStatusText" />
          </div>
        </template>
        <template #operation="{ row }">
          <div class="flex items-center justify-center gap-1">
            <t-tooltip content="查看" placement="top">
              <t-button v-hasPermi="['erp:purchase-order:query']" theme="primary" variant="text" shape="square" size="small" @click="handleView(row)">
                <template #icon><BrowseIcon /></template>
              </t-button>
            </t-tooltip>
            <t-tooltip v-if="canEdit(row)" content="编辑" placement="top">
              <t-button v-hasPermi="['erp:purchase-order:edit']" theme="primary" variant="text" shape="square" size="small" @click="handleEdit(row)">
                <template #icon><Edit1Icon /></template>
              </t-button>
            </t-tooltip>
            <t-tooltip v-if="canConfirm(row)" content="确认订单" placement="top">
              <t-button
                v-hasPermi="['erp:purchase-order:audit']"
                theme="success"
                variant="text"
                shape="square"
                size="small"
                @click="handleConfirm(row)"
              >
                <template #icon><CheckCircleIcon /></template>
              </t-button>
            </t-tooltip>
            <t-tooltip v-if="canTransfer(row)" content="转采购入库单" placement="top">
              <t-button theme="primary" variant="text" shape="square" size="small" @click="openTransfer(row)">
                <template #icon><TicketIcon /></template>
              </t-button>
            </t-tooltip>
            <t-tooltip v-if="canClose(row)" content="关闭剩余" placement="top">
              <t-button
                v-hasPermi="['erp:purchase-order:edit']"
                theme="warning"
                variant="text"
                shape="square"
                size="small"
                @click="handleCloseOrder(row)"
              >
                <template #icon><LockOnIcon /></template>
              </t-button>
            </t-tooltip>
            <t-tooltip v-if="canWithdraw(row)" content="撤回" placement="top">
              <t-button
                v-hasPermi="['erp:purchase-order:withdraw']"
                theme="warning"
                variant="text"
                shape="square"
                size="small"
                @click="handleWithdraw(row)"
              >
                <template #icon><RollbackIcon /></template>
              </t-button>
            </t-tooltip>
            <t-tooltip v-if="canVoid(row)" content="作废" placement="top">
              <t-button v-hasPermi="['erp:purchase-order:void']" theme="danger" variant="text" shape="square" size="small" @click="handleVoid(row)">
                <template #icon><CloseCircleIcon /></template>
              </t-button>
            </t-tooltip>
            <t-tooltip v-if="canDelete(row)" content="删除" placement="top">
              <t-button
                v-hasPermi="['erp:purchase-order:remove']"
                theme="danger"
                variant="text"
                shape="square"
                size="small"
                @click="handleDelete(row)"
              >
                <template #icon><DeleteIcon /></template>
              </t-button>
            </t-tooltip>
          </div>
        </template>
      </t-table>

      <pagination v-show="total > 0" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" :total="total" @pagination="getList" />
    </div>

    <!-- 订单详情 / 表单：页签 1 订单内容，页签 2 执行记录 -->
    <t-drawer v-model:visible="drawer.visible" size="92%" :destroy-on-close="true" attach="body">
      <template #header>
        <div class="flex items-center gap-2">
          <span class="text-base font-medium">{{ drawer.title }}</span>
          <StatusTag v-if="detailStatus" kind="bill" :value="detailStatus" :status-text="detailStatusText" />
          <StatusTag v-if="detailExecStatus" kind="exec" :value="detailExecStatus" :status-text="detailExecStatusText" />
        </div>
      </template>

      <div v-loading="detailLoading" class="px-2 pb-4">
        <t-tabs v-model="activeTab">
          <t-tab-panel label="订单内容" value="content">
            <t-form ref="orderFormRef" :data="form" :rules="rules" label-align="top" :disabled="readonly" @submit="submitForm">
              <t-row :gutter="16">
                <t-col :span="3">
                  <t-form-item label="供应商" name="supplierId">
                    <PartnerSelect v-model="form.supplierId" kind="supplier" placeholder="请选择供应商" :disabled="readonly || isConfirmedOrder" />
                  </t-form-item>
                </t-col>
                <t-col :span="3">
                  <t-form-item label="下单日期" name="bizDate">
                    <t-date-picker
                      v-model="form.bizDate"
                      value-type="YYYY-MM-DD"
                      placeholder="请选择日期"
                      :disabled="readonly || isConfirmedOrder"
                      style="width: 100%"
                    />
                  </t-form-item>
                </t-col>
                <t-col :span="3">
                  <t-form-item label="供应商单号">
                    <t-input v-model="form.supplierOrderNo" placeholder="对方单号" :title="form.supplierOrderNo" />
                  </t-form-item>
                </t-col>
                <t-col :span="3">
                  <t-form-item label="跟单人">
                    <t-input v-model="form.buyerName" placeholder="跟单人姓名" :title="form.buyerName" />
                  </t-form-item>
                </t-col>
              </t-row>
              <t-row :gutter="16">
                <t-col :span="3">
                  <t-form-item label="联系人">
                    <t-input v-model="form.contactName" placeholder="联系人" :title="form.contactName" />
                  </t-form-item>
                </t-col>
                <t-col :span="3">
                  <t-form-item label="联系电话">
                    <t-input v-model="form.contactPhone" placeholder="电话" :title="form.contactPhone" />
                  </t-form-item>
                </t-col>
                <t-col :span="3">
                  <t-form-item label="发票类型">
                    <t-select v-model="form.taxType" :options="taxTypeOptions" :disabled="readonly || isConfirmedOrder" style="width: 100%" />
                  </t-form-item>
                </t-col>
                <t-col :span="3">
                  <t-form-item label="税率(%)">
                    <t-input-number
                      v-model="form.taxRate"
                      :decimal-places="2"
                      :min="0"
                      :max="100"
                      :allow-input-over-limit="false"
                      theme="normal"
                      :disabled="readonly || isConfirmedOrder"
                      style="width: 100%"
                    />
                  </t-form-item>
                </t-col>
              </t-row>
              <t-row :gutter="16">
                <t-col :span="2">
                  <t-form-item label="允许延迟">
                    <template #label>
                      <span>允许延迟</span>
                      <FieldTip content="宽限天数加在交期上用于逾期判定，不影响承诺交期" />
                    </template>
                    <t-input-number v-model="form.allowDelayDays" :decimal-places="0" :min="0" theme="normal" style="width: 100%" />
                  </t-form-item>
                </t-col>
                <t-col :span="2">
                  <t-form-item label="超收容差(%)">
                    <t-input-number v-model="form.overTolPct" :decimal-places="2" :min="0" theme="normal" style="width: 100%" />
                  </t-form-item>
                </t-col>
                <t-col :span="8">
                  <t-form-item label="备注">
                    <t-input v-model="form.remark" placeholder="备注" :title="form.remark" />
                  </t-form-item>
                </t-col>
              </t-row>
              <t-row v-if="isConfirmedEdit" :gutter="16">
                <t-col :span="12">
                  <t-form-item label="改单原因" name="reason">
                    <t-input v-model="form.reason" placeholder="已确认订单改数量/交期必须填写原因" :title="form.reason" />
                  </t-form-item>
                </t-col>
              </t-row>
            </t-form>

            <!-- 明细工具条：与上方表头用分隔线 + 20px 间距隔开，避免「和输入框贴在一起」 -->
            <div class="erp-detail-bar">
              <div class="flex items-center gap-2">
                <span class="erp-detail-bar__title">订单明细</span>
                <t-button v-if="canAddRow" theme="primary" variant="outline" size="small" @click="addRow">
                  <template #icon><PlusIcon /></template>
                  添加明细
                </t-button>
                <t-button
                  v-if="!readonly && isConfirmedOrder && closedRows.length > 0"
                  theme="warning"
                  variant="outline"
                  size="small"
                  @click="handleReopenAll"
                >
                  <template #icon><LockOffIcon /></template>
                  全部重开
                </t-button>
              </div>
              <span v-if="isConfirmedOrder" class="text-xs erp-text-secondary">已确认订单不允许增删明细；已关闭的行请先重开再改数量</span>
            </div>

            <t-table
              class="erp-table"
              row-key="__key"
              :data="rows"
              :columns="rowColumns"
              :row-class-name="rowClassName"
              :hover="true"
              :resizable="true"
              size="small"
              cell-empty-content="-"
              :empty="'请添加订单明细'"
              @column-resize-change="(ctx: any) => saveColumnWidths('order/detail', ctx.columnsWidth)"
            >
              <template #rowIndex="{ rowIndex }">{{ rowIndex + 1 }}</template>
              <template #product="{ row, rowIndex }">
                <ProductSelect
                  v-model="row.productId"
                  :disabled="readonly || isConfirmedOrder"
                  show-category-filter
                  placeholder="请选择商品"
                  @change="(product) => onProductChange(rowIndex, product)"
                />
              </template>
              <template #qty="{ row, rowIndex }">
                <t-input-number
                  :key="`qty-${rowIndex}-${qtyPrecision(row.qtyScale)}`"
                  v-model="row.qty"
                  :decimal-places="qtyPrecision(row.qtyScale)"
                  :step="qtyStep(row.qtyScale)"
                  :min="qtyMin(row)"
                  theme="normal"
                  align="right"
                  :disabled="readonly || isRowClosed(row)"
                  style="width: 100%"
                />
                <t-tooltip v-if="isRowClosed(row)" content="该行已关闭，请先重开后再改数量" placement="top">
                  <div class="text-xs erp-text-secondary text-right cursor-help">已关闭</div>
                </t-tooltip>
              </template>
              <template #price="{ row }">
                <t-input-number
                  v-model="row.price"
                  :decimal-places="2"
                  :min="0"
                  theme="normal"
                  align="right"
                  :disabled="readonly || !priceEditable"
                  style="width: 100%"
                />
              </template>
              <template #amount="{ row }">{{ formatMoney(rowAmount(row)) }}</template>
              <template #dueDateHeader>
                <span>交期</span>
                <FieldTip content="我方向供应商要求的到货日，必填；逾期按此日期 + 允许延迟天数判定" />
              </template>
              <template #dueDate="{ row }">
                <t-date-picker v-model="row.dueDate" value-type="YYYY-MM-DD" placeholder="必填" :disabled="readonly" style="width: 100%" />
              </template>
              <template #promisedDateHeader>
                <span>承诺交期</span>
                <FieldTip content="供应商回签的承诺日，选填；不参与逾期判定，改交期不会自动改写" />
              </template>
              <template #promisedDate="{ row }">
                <t-date-picker v-model="row.promisedDate" value-type="YYYY-MM-DD" placeholder="选填" :disabled="readonly" style="width: 100%" />
              </template>
              <template #executedQty="{ row }">{{ formatQtyTrim(row.executedQty) }}</template>
              <template #remain="{ row }">{{ remainText(rowRemain(row)) }}</template>
              <template #rowOperation="{ row, rowIndex }">
                <div class="flex items-center justify-center gap-1">
                  <t-tooltip v-if="canCloseItem(row)" content="关闭剩余" placement="top">
                    <t-button
                      v-hasPermi="['erp:purchase-order:edit']"
                      theme="warning"
                      variant="text"
                      shape="square"
                      size="small"
                      @click="handleCloseItem(row)"
                    >
                      <template #icon><LockOnIcon /></template>
                    </t-button>
                  </t-tooltip>
                  <t-tooltip v-if="canReopenItem(row)" content="重开该行" placement="top">
                    <t-button
                      v-hasPermi="['erp:purchase-order:edit']"
                      theme="primary"
                      variant="text"
                      shape="square"
                      size="small"
                      @click="handleReopenItem(row)"
                    >
                      <template #icon><LockOffIcon /></template>
                    </t-button>
                  </t-tooltip>
                  <t-tooltip v-if="canDeleteRow" content="删除该行" placement="top">
                    <t-button theme="danger" variant="text" shape="square" size="small" @click="removeRow(rowIndex)">
                      <template #icon><DeleteIcon /></template>
                    </t-button>
                  </t-tooltip>
                </div>
              </template>
            </t-table>

            <!-- 页签 1 底部固定三行口径：与执行单/应收应付对账时看这三行 -->
            <div class="mt-3 flex flex-wrap items-center gap-6 border-t pt-2 text-sm">
              <span
                >订单金额（不含运费）：<b>{{ formatMoney(goodsAmount) }}</b></span
              >
              <span>
                已执行金额（含运费）：<b>{{ execAmountText }}</b>
              </span>
              <span
                >差异（含运费/超收/退货）：<b>{{ diffAmountText }}</b></span
              >
            </div>
          </t-tab-panel>

          <t-tab-panel v-if="form.id" label="执行记录" value="exec" :destroy-on-hide="true">
            <t-table
              class="erp-table"
              row-key="id"
              :data="execList"
              :columns="execColumns"
              :loading="execLoading"
              :hover="true"
              :resizable="true"
              size="small"
              cell-empty-content="-"
              :empty="'暂无执行记录'"
              @column-resize-change="(ctx: any) => saveColumnWidths('order/exec', ctx.columnsWidth)"
            >
              <template #execNo="{ row }">
                <t-button variant="text" theme="primary" size="small" @click="openExecBill(row)">{{ row.no || '-' }}</t-button>
              </template>
              <template #execType="{ row }">{{ row.typeText || (row.type === 'RETURN' ? '退货' : '入库') }}</template>
              <template #execDate="{ row }">{{ toDate(row.bizDate) || '-' }}</template>
              <template #execQty="{ row }">{{ formatQtyTrim(row.qty) }}</template>
              <template #execAmount="{ row }">{{ row.amount == null ? '—' : formatMoney(row.amount) }}</template>
              <template #execStatus="{ row }">
                <StatusTag
                  kind="bill"
                  :value="row.status"
                  :status-text="row.statusText"
                  :draft-label="row.type === 'RETURN' ? undefined : '待入库'"
                />
              </template>
            </t-table>
          </t-tab-panel>
        </t-tabs>
      </div>

      <template #footer>
        <div class="flex items-center justify-end gap-2">
          <t-button theme="default" variant="outline" @click="drawer.visible = false">{{ readonly ? '关 闭' : '取 消' }}</t-button>
          <t-button v-if="!readonly" theme="primary" :loading="saving" @click="submitForm">保 存</t-button>
        </div>
      </template>
    </t-drawer>

    <!-- 订单 → 采购入库单：部分下推，生成 DRAFT 入库单 -->
    <t-dialog v-model:visible="transfer.visible" header="转采购入库单" width="820px" attach="body">
      <t-alert theme="info" :close="false" class="mb-3" message="只带本次勾选的未交行；生成的采购入库单为草稿，确认后才影响库存与应付。" />
      <t-table
        class="erp-table"
        row-key="id"
        :data="transfer.items"
        :columns="transferColumns"
        :loading="transfer.loading"
        :hover="true"
        :resizable="true"
        size="small"
        max-height="380"
        cell-empty-content="-"
        :empty="'该订单没有可转单的未交行'"
        @column-resize-change="(ctx: any) => saveColumnWidths('order/transfer', ctx.columnsWidth)"
      >
        <template #checked="{ row }">
          <t-checkbox v-model="row.checked" />
        </template>
        <template #product="{ row }">{{ row.productCode ? `${row.productCode} ` : '' }}{{ row.productName || '-' }}</template>
        <template #dueDate="{ row }">{{ toDate(row.dueDate) || '-' }}</template>
        <template #remain="{ row }">{{ formatQtyTrim(row.remain) }}</template>
        <template #qty="{ row, rowIndex }">
          <t-input-number
            :key="`qty-${rowIndex}-${qtyPrecision(row.qtyScale)}`"
            v-model="row.qty"
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
      </t-table>
      <template #footer>
        <t-button theme="default" variant="outline" @click="transfer.visible = false">取 消</t-button>
        <t-button theme="primary" :loading="transfer.submitting" @click="submitTransfer">生成入库单</t-button>
      </template>
    </t-dialog>
  </div>
</template>

<script setup name="ErpPurchaseOrder" lang="ts">
import {
  addPurchaseOrder,
  closePurchaseOrder,
  closePurchaseOrderItem,
  confirmPurchaseOrder,
  createPurchaseReceipt,
  delPurchaseOrder,
  getPurchaseOrder,
  listPurchaseOpenItems,
  listPurchaseOrder,
  listPurchaseOrderExecutions,
  reopenPurchaseOrderItem,
  updatePurchaseOrder,
  voidPurchaseOrder,
  withdrawPurchaseOrder
} from '@/api/erp/order';
import { ERP_BILL_STATUS, ERP_ORDER_EXEC_STATUS, ERP_TAX_TYPE } from '@/api/erp/enums';
import type { ErpBillStatus, ErpOrderExecStatus, ErpTaxType } from '@/api/erp/enums';
import type {
  ErpAmount,
  ErpId,
  ErpProductVO,
  ErpPurchaseOrderExecVO,
  ErpPurchaseOrderForm,
  ErpPurchaseOrderItemVO,
  ErpPurchaseOrderQuery,
  ErpPurchaseOrderVO
} from '@/api/erp/types';
import { formatMoney, formatQtyTrim, normalizeQty, qtyPrecision, qtyStep, roundMoney } from '@/utils/erp/calc';
import { readExecutedAmount } from '@/utils/erp/tradeContracts';
// 列宽记忆：拖拽后按表名存 localStorage，进页面还原（见 hooks/useTableColumnWidth）
import { mergeSavedWidths, saveColumnWidths } from '@/hooks/useTableColumnWidth';
import { checkPermi } from '@/utils/permission';
import PartnerSelect from '@/components/erp/PartnerSelect.vue';
import ProductSelect from '@/components/erp/ProductSelect.vue';
import StatusTag from '@/components/erp/StatusTag.vue';
import FieldTip from '@/components/erp/FieldTip.vue';
import {
  BrowseIcon,
  CheckCircleIcon,
  CloseCircleIcon,
  DeleteIcon,
  Edit1Icon,
  LockOffIcon,
  LockOnIcon,
  PlusIcon,
  RollbackIcon,
  TicketIcon
} from 'tdesign-icons-vue-next';

/** 表单明细行：编辑态保留行 id，已确认订单改单时后端按 id 匹配 */
interface OrderRow {
  id?: ErpId;
  productId?: ErpId;
  productCode?: string;
  productName?: string;
  spec?: string;
  unit?: string;
  /** 数量精度 0~3（商品级，DOC/26 §10） */
  qtyScale?: number;
  qty: number;
  price: number;
  dueDate: string;
  promisedDate: string;
  executedQty?: number;
  closedQty?: number;
  /** 1=已关闭剩余 */
  closedFlag?: number;
  remark?: string;
  /** t-table 行 key（前端生成，不提交后端） */
  __key?: string;
}

interface OrderFormState {
  id?: ErpId;
  supplierId?: ErpId;
  bizDate: string;
  supplierOrderNo: string;
  buyerName: string;
  contactName: string;
  contactPhone: string;
  allowDelayDays: number;
  /** 超收容差 %：采购侧可放宽（D6），默认 0＝严格不超收 */
  overTolPct: number;
  taxType: ErpTaxType;
  taxRate: number;
  remark: string;
  /** 已确认订单改单原因（改数量/交期必填） */
  reason: string;
}

interface TransferLine extends ErpPurchaseOrderItemVO {
  checked: boolean;
  qty: number;
}

const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const router = useRouter();
const route = useRoute();

const statusOptions = Object.entries(ERP_BILL_STATUS).map(([value, item]) => ({ value: value as ErpBillStatus, label: item.label }));
const execStatusOptions = Object.entries(ERP_ORDER_EXEC_STATUS).map(([value, item]) => ({ value: value as ErpOrderExecStatus, label: item.label }));
const taxTypeOptions = Object.entries(ERP_TAX_TYPE).map(([value, item]) => ({ value: value as ErpTaxType, label: item.label }));

/** 主列表列配置（TDesign t-table 用 columns 数组，替代 el-table-column 模板列；DOC/35 §5） */
const listColumns = computed<any[]>(() =>
  mergeSavedWidths('order/list', [
    { colKey: 'no', title: '订单号', align: 'center', minWidth: 140, ellipsis: true, cell: 'no' },
    { colKey: 'supplierName', title: '供应商', align: 'center', minWidth: 140, ellipsis: true },
    { colKey: 'bizDate', title: '下单日期', align: 'center', width: 110, ellipsis: true, cell: 'bizDate' },
    { colKey: 'dueDate', title: 'dueDateHeader', align: 'center', width: 160, ellipsis: true, cell: 'dueDate' },
    { colKey: 'progress', title: '执行进度', align: 'center', width: 180, cell: 'progress' },
    { colKey: 'remainQty', title: '未交量', align: 'right', width: 100, ellipsis: true, cell: 'remainQty' },
    { colKey: 'goodsAmount', title: '订单金额（不含运费）', align: 'right', width: 170, ellipsis: true, cell: 'goodsAmount' },
    { colKey: 'executedAmount', title: '已执行金额（含运费）', align: 'right', width: 170, cell: 'executedAmount' },
    { colKey: 'buyerName', title: '跟单人', align: 'center', width: 100, ellipsis: true },
    { colKey: 'status', title: '状态', align: 'center', width: 160, cell: 'status' },
    // 操作列改图标按钮（DOC/35 §13）：8 个动作按状态最多同时出现 6 个，24px 图标按钮 + 4px 间距约 164px
    { colKey: 'operation', title: '操作', align: 'center', width: 180, cell: 'operation' }
  ])
);

/** 明细行列表配置：数量/单价/交期是行内编辑控件，操作列只在可编辑态出现 */
const rowColumns = computed<any[]>(() => {
  const cols: any[] = [
    { colKey: 'rowIndex', title: '#', width: 50, align: 'center', cell: 'rowIndex' },
    { colKey: 'productId', title: '商品', minWidth: 220, cell: 'product' },
    { colKey: 'spec', title: '规格', width: 100, align: 'center', ellipsis: true },
    { colKey: 'unit', title: '单位', width: 64, align: 'center', ellipsis: true },
    { colKey: 'qty', title: '数量', width: 140, align: 'right', cell: 'qty' },
    { colKey: 'price', title: '单价', width: 130, align: 'right', cell: 'price' },
    { colKey: 'amount', title: '金额', width: 110, align: 'right', ellipsis: true, cell: 'amount' },
    { colKey: 'dueDate', title: 'dueDateHeader', width: 165, align: 'center', cell: 'dueDate' },
    { colKey: 'promisedDate', title: 'promisedDateHeader', width: 165, align: 'center', cell: 'promisedDate' },
    { colKey: 'executedQty', title: '已执行', width: 90, align: 'right', ellipsis: true, cell: 'executedQty' },
    { colKey: 'remainQty', title: '未交', width: 90, align: 'right', ellipsis: true, cell: 'remain' }
  ];
  if (!readonly.value) cols.push({ colKey: 'operation', title: '操作', width: 110, align: 'center', cell: 'rowOperation' });
  return mergeSavedWidths('order/detail', cols);
});

/** 执行记录列表配置 */
const execColumns: any[] = [
  { colKey: 'no', title: '执行单号', align: 'center', minWidth: 150, cell: 'execNo' },
  { colKey: 'type', title: '类型', align: 'center', width: 100, cell: 'execType' },
  { colKey: 'bizDate', title: '日期', align: 'center', width: 120, cell: 'execDate' },
  { colKey: 'qty', title: '数量', align: 'right', width: 120, cell: 'execQty' },
  { colKey: 'amount', title: '金额（含运费）', align: 'right', width: 130, cell: 'execAmount' },
  { colKey: 'status', title: '状态', align: 'center', width: 110, cell: 'execStatus' }
];
mergeSavedWidths('order/exec', execColumns);

/** 转单列表配置：勾选列沿用行内 checked 字段，不改 TDesign 的 selectedRowKeys 契约 */
const transferColumns: any[] = [
  { colKey: 'checked', title: ' ', width: 46, align: 'center', cell: 'checked' },
  { colKey: 'productId', title: '商品', minWidth: 200, cell: 'product' },
  { colKey: 'spec', title: '规格', width: 100, align: 'center', ellipsis: true },
  { colKey: 'dueDate', title: '交期', width: 110, align: 'center', cell: 'dueDate' },
  { colKey: 'remain', title: '未交量', width: 110, align: 'right', ellipsis: true, cell: 'remain' },
  { colKey: 'qty', title: '本次数量', width: 140, align: 'right', cell: 'qty' }
];
mergeSavedWidths('order/transfer', transferColumns);

const orderList = ref<ErpPurchaseOrderVO[]>([]);
const loading = ref(true);
const detailLoading = ref(false);
const saving = ref(false);
const showSearch = ref(true);
const total = ref(0);
// 查询区与订单表单均已换 t-form（TDesign 的 ref 提供 reset()/validate()，与 EP 的 resetFields()/validate() 返回契约不同）
const queryFormRef = ref<any>();
const orderFormRef = ref<any>();
const dueDateRange = ref<[string, string]>(['', '']);

const drawer = reactive<DialogOption>({ visible: false, title: '' });
/** 表单模式：create=新增 / edit=编辑 / view=只读查看 */
const viewMode = ref<'create' | 'edit' | 'view'>('create');
const readonly = computed(() => viewMode.value === 'view');

const detailStatus = ref('');
const detailStatusText = ref('');
const detailExecStatus = ref('');
const detailExecStatusText = ref('');
/** 详情单号：跨页跳转只有订单ID时，加载完成后回填抽屉标题 */
const detailNo = ref('');
const activeTab = ref('content');

const rows = ref<OrderRow[]>([]);
const execList = ref<ErpPurchaseOrderExecVO[]>([]);
const execLoading = ref(false);
/** 已执行金额（按执行单含运费）；null=取不到执行单详情，不做估算 */
const execAmount = ref<number | null>(null);

const transfer = reactive({
  visible: false,
  loading: false,
  submitting: false,
  orderId: undefined as ErpId | undefined,
  items: [] as TransferLine[]
});

const createForm = (): OrderFormState => ({
  id: undefined,
  supplierId: undefined,
  bizDate: new Date().toISOString().slice(0, 10),
  supplierOrderNo: '',
  buyerName: '',
  contactName: '',
  contactPhone: '',
  allowDelayDays: 0,
  overTolPct: 0,
  taxType: 'VAT_INVOICE',
  taxRate: 13,
  remark: '',
  reason: ''
});

const form = reactive<OrderFormState>(createForm());

const queryParams = reactive<ErpPurchaseOrderQuery>({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  supplierId: undefined,
  status: '',
  execStatus: undefined,
  unfinished: false,
  overdue: false
});

const isConfirmedOrder = computed(() => detailStatus.value === 'CONFIRMED');
/** 已确认订单改单：供应商/税率锁定，改数量或交期必须填原因 */
const isConfirmedEdit = computed(() => viewMode.value === 'edit' && isConfirmedOrder.value);
const canAddRow = computed(() => !readonly.value && !isConfirmedOrder.value);
const canDeleteRow = computed(() => !readonly.value && !isConfirmedOrder.value);

// t-form 的 rules 形状是 { [field]: FormRule[] }，与 EP 的 ElFormRules 不兼容，这里用宽松类型
const rules = computed<Record<string, any>>(() => ({
  supplierId: [{ required: true, message: '供应商不能为空', trigger: 'change' }],
  bizDate: [{ required: true, message: '下单日期不能为空', trigger: 'change' }],
  ...(isConfirmedEdit.value ? { reason: [{ required: true, message: '改单原因不能为空', trigger: 'blur' }] } : {})
}));

/** 数值兜底：null/NaN 一律按 0 */
const num = (value: ErpAmount | number | undefined | null): number => {
  const n = Number(value ?? 0);
  return Number.isFinite(n) ? n : 0;
};

/** 日期只取 yyyy-MM-dd */
const toDate = (value: string | undefined): string => (value ? String(value).slice(0, 10) : '');

/** 解析 yyyy-MM-dd 为本地日期，避免 new Date('yyyy-MM-dd') 按 UTC 解析偏一天 */
const parseDate = (value?: string): Date | null => {
  if (!value) return null;
  const [y, m, d] = String(value).slice(0, 10).split('-').map(Number);
  if (!y || !m || !d) return null;
  return new Date(y, m - 1, d);
};

/** 逾期天数 = 今天 −（交期 + 允许延迟天数）；已完成/关闭/非确认态不算逾期 */
const overdueDays = (row: ErpPurchaseOrderVO): number => {
  if (row.status !== 'CONFIRMED' || row.execStatus === 'FULL' || row.execStatus === 'CLOSED') return 0;
  const due = parseDate(row.dueDate);
  if (!due) return 0;
  due.setDate(due.getDate() + num(row.allowDelayDays));
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const days = Math.floor((today.getTime() - due.getTime()) / 86400000);
  return days > 0 ? days : 0;
};

const progressPercent = (row: ErpPurchaseOrderVO): number => {
  const totalQty = num(row.totalQty);
  if (totalQty <= 0) return 0;
  return Math.min(100, Math.round((num(row.executedQty) / totalQty) * 100));
};

/**
 * 订单执行进度条颜色：按执行状态取 TDesign 语义色变量，亮/暗两套主题自动跟随。
 * @param row 采购订单列表行
 * @return CSS 颜色值（var(...) 表达式）
 */
const progressColor = (row: ErpPurchaseOrderVO): string => {
  if (row.execStatus === 'FULL') return 'var(--td-success-color)';
  if (row.execStatus === 'PARTIAL') return 'var(--td-warning-color)';
  if (row.execStatus === 'CLOSED') return 'var(--td-brand-color)';
  return 'var(--td-gray-color-6)';
};

/** 数量为 0 显示「—」 */
const remainText = (value: ErpAmount | number | undefined | null): string => (num(value) > 0 ? formatQtyTrim(value) : '—');

const isRowClosed = (row: OrderRow): boolean => num(row.closedFlag) === 1;

const rowAmount = (row: OrderRow): number => roundMoney(num(row.qty) * num(row.price));

/** 未交量 = max(0, 订单量 − 已执行 − 已关闭)，与后端同口径 */
const rowRemain = (row: OrderRow): number => Math.max(0, num(row.qty) - num(row.executedQty) - num(row.closedQty));

/** 已确认订单的订单量不得低于已执行 + 已关闭（DOC/31 §6.3、§10.3） */
const qtyMin = (row: OrderRow): number => (isConfirmedOrder.value && row.id ? num(row.executedQty) + num(row.closedQty) : 0);

/** 全单有执行量时单价锁定（后端按整单已执行量判断） */
const executedTotal = computed(() => rows.value.reduce((sum, row) => sum + num(row.executedQty), 0));
const priceEditable = computed(() => !isConfirmedOrder.value || executedTotal.value === 0);
const closedRows = computed(() => rows.value.filter((row) => isRowClosed(row)));
const goodsAmount = computed(() => roundMoney(rows.value.reduce((sum, row) => sum + rowAmount(row), 0)));
const execAmountText = computed(() => (execAmount.value === null ? '—' : formatMoney(execAmount.value)));
const diffAmountText = computed(() => (execAmount.value === null ? '—' : formatMoney(roundMoney(goodsAmount.value - execAmount.value))));

/* ------------------------------ 列表 ------------------------------ */

/** 默认排序（交期升序、同交期 id 倒序）由后端固定，前端不传排序参数，避免只排当前页 */
const getList = async () => {
  loading.value = true;
  try {
    const res = await listPurchaseOrder({
      ...queryParams,
      dueDateStart: dueDateRange.value?.[0] || undefined,
      dueDateEnd: dueDateRange.value?.[1] || undefined
    });
    orderList.value = res.rows ?? [];
    total.value = res.total ?? 0;
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
  queryParams.supplierId = undefined;
  queryParams.status = '';
  queryParams.execStatus = undefined;
  queryParams.unfinished = false;
  queryParams.overdue = false;
  queryParams.pageNum = 1;
  dueDateRange.value = ['', ''];
  getList();
};

/* ------------------------------ 行内按钮：集中判定 ------------------------------ */

const canEdit = (row: ErpPurchaseOrderVO): boolean => row.status === 'DRAFT' || row.status === 'CONFIRMED';
const canConfirm = (row: ErpPurchaseOrderVO): boolean => row.status === 'DRAFT';
/** 无执行量才可撤回/作废（后端还会检查未处理执行单） */
const canWithdraw = (row: ErpPurchaseOrderVO): boolean => row.status === 'CONFIRMED' && num(row.executedQty) === 0;
const canVoid = (row: ErpPurchaseOrderVO): boolean => row.status === 'CONFIRMED' && num(row.executedQty) === 0;
const canClose = (row: ErpPurchaseOrderVO): boolean => row.status === 'CONFIRMED' && num(row.remainQty) > 0;
/** 转单额外要求执行单新增权限（后端 erp:purchase-order:add + erp:purchase:add） */
const canTransfer = (row: ErpPurchaseOrderVO): boolean =>
  row.status === 'CONFIRMED' && num(row.remainQty) > 0 && checkPermi(['erp:purchase-order:add']) && checkPermi(['erp:purchase:add']);
const canDelete = (row: ErpPurchaseOrderVO): boolean => row.status === 'DRAFT';

const canCloseItem = (row: OrderRow): boolean => isConfirmedOrder.value && !!row.id && !isRowClosed(row) && rowRemain(row) > 0;
const canReopenItem = (row: OrderRow): boolean => isConfirmedOrder.value && !!row.id && isRowClosed(row);

/* ------------------------------ 表单 ------------------------------ */

const rowClassName = ({ row }: { row: OrderRow }): string => (isRowClosed(row) ? 'row-closed' : '');

/** 明细行 t-table 行 key：新增行没有业务主键，用前端自增序号，避免增删行时表格错位 */
let rowSeq = 0;
const nextRowKey = (): string => `row-${++rowSeq}`;

const createRow = (): OrderRow => ({ __key: nextRowKey(), productId: undefined, qty: 1, price: 0, dueDate: '', promisedDate: '', remark: '' });

const resetForm = () => {
  Object.assign(form, createForm());
  rows.value = [createRow()];
  detailStatus.value = '';
  detailStatusText.value = '';
  detailExecStatus.value = '';
  detailExecStatusText.value = '';
  execList.value = [];
  execAmount.value = 0;
  activeTab.value = 'content';
};

const handleAdd = () => {
  resetForm();
  viewMode.value = 'create';
  drawer.title = '新增采购订单';
  drawer.visible = true;
};

/** 加载订单详情 + 执行记录；viewMode 由调用方预先设置 */
const loadDetail = async (id: ErpId) => {
  detailLoading.value = true;
  try {
    const res = await getPurchaseOrder(id);
    const detail = res.data;
    detailNo.value = detail.no ?? '';
    detailStatus.value = detail.status ?? '';
    detailStatusText.value = detail.statusText ?? '';
    detailExecStatus.value = detail.execStatus ?? '';
    detailExecStatusText.value = detail.execStatusText ?? '';
    execAmount.value = readExecutedAmount(detail);
    Object.assign(form, {
      id: detail.id,
      supplierId: detail.supplierId,
      bizDate: toDate(detail.bizDate),
      supplierOrderNo: detail.supplierOrderNo ?? '',
      buyerName: detail.buyerName ?? '',
      contactName: detail.contactName ?? '',
      contactPhone: detail.contactPhone ?? '',
      allowDelayDays: num(detail.allowDelayDays),
      overTolPct: num(detail.overTolPct),
      taxType: (detail.taxType || 'VAT_INVOICE') as ErpTaxType,
      taxRate: num(detail.taxRate),
      remark: detail.remark ?? '',
      reason: ''
    });
    rows.value = (detail.items ?? []).map((item) => ({
      __key: nextRowKey(),
      id: item.id,
      productId: item.productId,
      productCode: item.productCode,
      productName: item.productName,
      spec: item.spec,
      unit: item.unit,
      qtyScale: item.qtyScale,
      qty: num(item.qty),
      price: num(item.price),
      dueDate: toDate(item.dueDate),
      promisedDate: toDate(item.promisedDate),
      executedQty: num(item.executedQty),
      closedQty: num(item.closedQty),
      closedFlag: item.closedFlag,
      remark: item.remark
    }));
    await loadExecutions(id);
  } finally {
    detailLoading.value = false;
  }
};

const handleView = async (row: ErpPurchaseOrderVO) => {
  resetForm();
  viewMode.value = 'view';
  drawer.title = `查看采购订单 ${row.no ?? ''}`;
  drawer.visible = true;
  await loadDetail(row.id);
};

const handleEdit = async (row: ErpPurchaseOrderVO) => {
  resetForm();
  viewMode.value = 'edit';
  drawer.title = `编辑采购订单 ${row.no ?? ''}`;
  drawer.visible = true;
  await loadDetail(row.id);
};

/** 跨页跳转：按订单ID直接打开详情，不依赖列表当前页（DOC/31 §10.4） */
const openDetailById = async (id: ErpId) => {
  resetForm();
  viewMode.value = 'view';
  drawer.visible = true;
  drawer.title = '查看采购订单';
  await loadDetail(id);
  drawer.title = `查看采购订单 ${detailNo.value}`;
};

const addRow = () => {
  rows.value.push(createRow());
};

const removeRow = (index: number) => {
  rows.value.splice(index, 1);
  if (rows.value.length === 0) rows.value.push(createRow());
};

/** 选商品只带资料（编码/规格/精度），不带价：订单价由人谈，与执行单「带最近成交价」不同 */
const onProductChange = (index: number, product?: ErpProductVO) => {
  const row = rows.value[index];
  if (!row) return;
  if (!product) {
    Object.assign(row, { productCode: undefined, productName: undefined, spec: undefined, unit: undefined, qtyScale: undefined });
    return;
  }
  row.productCode = product.code;
  row.productName = product.name;
  row.spec = product.spec;
  row.unit = product.unit;
  row.qtyScale = product.qtyScale;
  // 精度随商品切换时归一化已录数量（el-input-number 只改显示不回写 model）
  row.qty = normalizeQty(row.qty, product.qtyScale);
};

/** 明细提交前校验：必填 + 同一商品同一交期禁止重复 */
const validateRows = (): boolean => {
  if (rows.value.length === 0) {
    proxy?.$modal.msgWarning('请至少添加一行明细');
    return false;
  }
  const seen = new Set<string>();
  for (const row of rows.value) {
    if (!row.productId) {
      proxy?.$modal.msgWarning('明细中存在未选择商品的行');
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
    if (!row.dueDate) {
      proxy?.$modal.msgWarning('明细交期不能为空');
      return false;
    }
    const key = `${row.productId}-${row.dueDate}`;
    if (seen.has(key)) {
      proxy?.$modal.msgWarning('同一商品同一交期已存在，请合并数量或改交期');
      return false;
    }
    seen.add(key);
  }
  return true;
};

const buildPayload = (): ErpPurchaseOrderForm => ({
  id: form.id,
  supplierId: form.supplierId as ErpId,
  bizDate: form.bizDate,
  supplierOrderNo: form.supplierOrderNo,
  buyerName: form.buyerName,
  contactName: form.contactName,
  contactPhone: form.contactPhone,
  allowDelayDays: num(form.allowDelayDays),
  overTolPct: num(form.overTolPct),
  taxType: form.taxType,
  taxRate: num(form.taxRate),
  remark: form.remark,
  reason: isConfirmedEdit.value ? form.reason : undefined,
  items: rows.value.map((row) => ({
    id: row.id,
    productId: row.productId as ErpId,
    qty: row.qty,
    price: row.price,
    dueDate: row.dueDate,
    promisedDate: row.promisedDate || undefined,
    remark: row.remark
  }))
});

const submitForm = async () => {
  // TDesign t-form.validate() 成功 resolve(true)，失败 resolve(false)（不 reject）；兼容两种返回
  const result = await orderFormRef.value?.validate().catch(() => false);
  const valid = result === true || result === undefined;
  if (!valid || !validateRows()) return;
  saving.value = true;
  try {
    const payload = buildPayload();
    if (form.id) {
      await updatePurchaseOrder(payload);
    } else {
      await addPurchaseOrder(payload);
    }
    proxy?.$modal.msgSuccess('保存成功');
    drawer.visible = false;
    await getList();
  } finally {
    saving.value = false;
  }
};

/* ------------------------------ 执行记录 ------------------------------ */

const loadExecutions = async (id: ErpId) => {
  execLoading.value = true;
  execList.value = [];
  try {
    const res = await listPurchaseOrderExecutions(id);
    // amount 已由后端按入库正值、退货负值返回；无需逐单请求明细。
    execList.value = res.data ?? [];
  } catch {
    // 执行记录失败不阻塞订单内容；执行金额仍取订单 VO 的批量聚合值。
    execList.value = [];
  } finally {
    execLoading.value = false;
  }
};

/** 跳执行单：入库与退货分属不同页面，统一按单号过滤（DOC/31 §10.3） */
const openExecBill = (record: ErpPurchaseOrderExecVO) => {
  const path = record.type === 'RETURN' ? '/erp-purchase/purchase-return' : '/erp-purchase/purchase';
  // 抽屉挂在 body 上，直接跳转会在路由切换时留下未卸载的遮罩：先关抽屉再跳
  drawer.visible = false;
  router.push({ path, query: { keyword: record.no } });
};

/* ------------------------------ 动作 ------------------------------ */

const promptReason = async (message: string): Promise<string | undefined> => {
  try {
    // $modal.prompt 确定 resolve { value }、取消/关闭 reject；原 inputValidator 的「原因必填」由调用方 if (!reason) 兜底
    const { value } = await proxy?.$modal.prompt(message);
    return value.trim();
  } catch {
    return undefined;
  }
};

const handleConfirm = async (row: ErpPurchaseOrderVO) => {
  try {
    await proxy?.$modal.confirm(`是否确认采购订单「${row.no ?? ''}」？确认后可按未交量分批转采购入库单。`);
  } catch {
    return;
  }
  await confirmPurchaseOrder(row.id);
  proxy?.$modal.msgSuccess('已确认');
  await getList();
};

const handleWithdraw = async (row: ErpPurchaseOrderVO) => {
  try {
    await proxy?.$modal.confirm(`是否撤回采购订单「${row.no ?? ''}」？撤回后回到草稿可继续修改。`);
  } catch {
    return;
  }
  await withdrawPurchaseOrder(row.id);
  proxy?.$modal.msgSuccess('已撤回');
  await getList();
};

const handleVoid = async (row: ErpPurchaseOrderVO) => {
  try {
    await proxy?.$modal.confirm(`是否作废采购订单「${row.no ?? ''}」？作废为终态，不可再修改。`);
  } catch {
    return;
  }
  await voidPurchaseOrder(row.id);
  proxy?.$modal.msgSuccess('已作废');
  await getList();
};

const handleDelete = async (row: ErpPurchaseOrderVO) => {
  try {
    await proxy?.$modal.confirm(`是否删除采购订单「${row.no ?? ''}」？删除后不可恢复，单号不会回收重用。`);
  } catch {
    return;
  }
  await delPurchaseOrder(row.id);
  proxy?.$modal.msgSuccess('删除成功');
  await getList();
};

/** 整单关闭剩余：原因必填 */
const handleCloseOrder = async (row: ErpPurchaseOrderVO) => {
  const reason = await promptReason(`请输入关闭采购订单「${row.no ?? ''}」剩余量的原因`);
  if (!reason) return;
  await closePurchaseOrder(row.id, reason);
  proxy?.$modal.msgSuccess('已关闭剩余');
  await getList();
};

/** 行级关闭剩余：原因必填 */
const handleCloseItem = async (row: OrderRow) => {
  if (!form.id || !row.id) return;
  const reason = await promptReason(`请输入关闭「${row.productName ?? ''}」剩余 ${formatQtyTrim(rowRemain(row))} 的原因`);
  if (!reason) return;
  await closePurchaseOrderItem(form.id, row.id, reason);
  proxy?.$modal.msgSuccess('已关闭该行剩余');
  await Promise.all([loadDetail(form.id), getList()]);
};

/** 行级重开（唯一重开入口） */
const handleReopenItem = async (row: OrderRow) => {
  if (!form.id || !row.id) return;
  try {
    await proxy?.$modal.confirm(`是否重开「${row.productName ?? ''}」该行？重开后可继续改数量与交期。`);
  } catch {
    return;
  }
  await reopenPurchaseOrderItem(form.id, row.id);
  proxy?.$modal.msgSuccess('已重开');
  await Promise.all([loadDetail(form.id), getList()]);
};

/** 全部重开＝逐行调用行级重开接口（不另设整单重开） */
const handleReopenAll = async () => {
  if (!form.id || closedRows.value.length === 0) return;
  try {
    await proxy?.$modal.confirm(`是否重开全部 ${closedRows.value.length} 行已关闭明细？`);
  } catch {
    return;
  }
  for (const row of closedRows.value) {
    if (row.id) await reopenPurchaseOrderItem(form.id, row.id);
  }
  proxy?.$modal.msgSuccess('已全部重开');
  await Promise.all([loadDetail(form.id), getList()]);
};

/* ------------------------------ 转单 ------------------------------ */

const openTransfer = async (row: ErpPurchaseOrderVO) => {
  transfer.orderId = row.id;
  transfer.items = [];
  transfer.visible = true;
  transfer.loading = true;
  try {
    const res = await listPurchaseOpenItems(row.id);
    transfer.items = (res.data ?? []).map((item) => ({ ...item, checked: true, qty: num(item.remain) }));
    if (transfer.items.length === 0) {
      transfer.visible = false;
      proxy?.$modal.msgWarning('该订单没有可转单的未交行');
    }
  } finally {
    transfer.loading = false;
  }
};

const submitTransfer = async () => {
  if (!transfer.orderId) return;
  const selected = transfer.items.filter((item) => item.checked && item.qty > 0);
  if (selected.length === 0) {
    proxy?.$modal.msgWarning('请勾选至少一行并填写本次数量');
    return;
  }
  if (selected.some((item) => item.qty > num(item.remain))) {
    proxy?.$modal.msgWarning('本次数量不能超过未交量');
    return;
  }
  transfer.submitting = true;
  try {
    const res = await createPurchaseReceipt(transfer.orderId, {
      items: selected.map((item) => ({ orderItemId: item.id, qty: item.qty }))
    });
    transfer.visible = false;
    proxy?.$modal.msgSuccess('已生成采购入库单（草稿）');
    await getList();
    if (drawer.visible && String(form.id) === String(transfer.orderId)) await loadDetail(form.id);
    try {
      await proxy?.$modal.confirm('已生成采购入库单，是否前往采购入库页查看？');
      router.push({ path: '/erp-purchase/purchase', query: { id: String(res.data ?? '') } });
    } catch {
      // 用户选择留在当前页
    }
  } finally {
    transfer.submitting = false;
  }
};

/** 已消费的跨页参数签名，避免 keep-alive 首次进入时 onMounted/onActivated 重复执行 */
let handledRouteQuery = '';

/** 消费跨页参数：执行页「查看订单」带订单ID，直达详情而不是停在列表首页 */
const consumeRouteQuery = async () => {
  const id = typeof route.query.id === 'string' ? route.query.id : '';
  if (!id || id === handledRouteQuery) return;
  handledRouteQuery = id;
  try {
    await openDetailById(id);
  } catch {
    // 失败提示由请求拦截器统一给出
  }
};

onMounted(async () => {
  await getList();
  await consumeRouteQuery();
});
// 页面被 keep-alive 缓存后再次跳入时 onMounted 不再触发，由 onActivated 消费新参数
onActivated(consumeRouteQuery);
</script>

<style scoped lang="scss">
/* 已关闭行整行置灰，配合数量框禁用提示「先重开」 */
:deep(.row-closed) {
  color: #a8abb2;
}
</style>
