<template>
  <div class="p-2">
    <t-row :gutter="20">
      <t-col :span="12">
        <transition :enter-active-class="proxy?.animate.searchAnimate.enter" :leave-active-class="proxy?.animate.searchAnimate.leave">
          <div v-show="showSearch">
            <div class="erp-surface erp-search-panel">
              <t-form ref="queryFormRef" :data="queryParams" class="erp-search-form" label-align="right" :label-width="'auto'">
                <t-form-item label="单号" name="keyword">
                  <t-input v-model="queryParams.keyword" placeholder="请输入销售单号" clearable class="query-w-md" @enter="handleQuery" />
                </t-form-item>
                <t-form-item label="客户" name="customerId">
                  <PartnerSelect v-model="queryParams.customerId" kind="customer" placeholder="全部客户" clearable class="query-w-md" />
                </t-form-item>
                <t-form-item label="状态" name="status">
                  <t-select v-model="queryParams.status" placeholder="单据状态" clearable class="query-w-sm" :options="statusOptions" />
                </t-form-item>
                <div class="erp-search-form__actions flex items-center gap-2">
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
            </div>
          </div>
        </transition>

        <div class="erp-surface erp-panel">
          <div class="erp-panel__head">
            <t-button v-hasPermi="['erp:sale:add']" theme="primary" variant="outline" @click="handleAdd">
              <template #icon><PlusIcon /></template>
              新增销售单
            </t-button>
            <right-toolbar v-model:show-search="showSearch" @query-table="getList"></right-toolbar>
          </div>

          <t-table
            class="erp-table"
            row-key="id"
            :data="saleList"
            :columns="listColumns"
            :loading="loading"
            :hover="true"
            :resizable="true"
            size="small"
            cell-empty-content="-"
            @column-resize-change="(ctx: any) => saveColumnWidths('sale/list', ctx.columnsWidth)"
          >
            <template #customer="{ row }">{{ row.customerName || '-' }}</template>
            <!-- 来源订单：直开单为空，转单生成的单据显示订单号并可跳订单详情（DOC/31 §10.4） -->
            <template #sourceOrder="{ row }">
              <t-button v-if="row.sourceOrderNo" variant="text" theme="primary" size="small" @click="goSourceOrder(row.sourceOrderId)">
                {{ row.sourceOrderNo }}
              </t-button>
            </template>
            <template #bizDate="{ row }">{{ toDate(row.bizDate) }}</template>
            <template #totalAmount="{ row }">{{ formatMoney(row.totalAmount) }}</template>
            <template #costAmount="{ row }">{{ snapshotText(row.costAmount) }}</template>
            <template #grossProfit="{ row }">{{ snapshotText(row.grossProfit) }}</template>
            <template #grossMargin="{ row }">{{ marginText(row.grossMargin) }}</template>
            <template #status="{ row }">
              <StatusTag kind="bill" :value="row.status" :status-text="row.statusText" draft-label="待出库" />
            </template>
            <template #operation="{ row }">
              <div class="flex items-center justify-center gap-1">
                <!-- v-hasPermi 会 removeChild，与 t-tooltip 包裹的 t-button 组合会留游离触发元素（DOC/36 P6），改外层 checkPermi -->
                <t-tooltip v-if="checkPermi(['erp:sale:query'])" content="查看" placement="top">
                  <t-button theme="primary" variant="text" shape="square" size="small" @click="handleView(row)">
                    <template #icon><BrowseIcon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.status === 'DRAFT' && checkPermi(['erp:sale:edit'])" content="编辑" placement="top">
                  <t-button theme="primary" variant="text" shape="square" size="small" @click="handleEdit(row)">
                    <template #icon><Edit1Icon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.status === 'DRAFT' && checkPermi(['erp:sale:audit'])" content="确认出库" placement="top">
                  <t-button theme="success" variant="text" shape="square" size="small" @click="handleConfirm(row)">
                    <template #icon><CheckCircleIcon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.status === 'CONFIRMED' && checkPermi(['erp:sale:withdraw'])" content="撤回" placement="top">
                  <t-button theme="warning" variant="text" shape="square" size="small" @click="handleWithdraw(row)">
                    <template #icon><RollbackIcon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.status === 'CONFIRMED' && checkPermi(['erp:sale:void'])" content="作废" placement="top">
                  <t-button theme="danger" variant="text" shape="square" size="small" @click="handleVoid(row)">
                    <template #icon><CloseCircleIcon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.status === 'DRAFT' && checkPermi(['erp:sale:remove'])" content="删除" placement="top">
                  <t-button theme="danger" variant="text" shape="square" size="small" @click="handleDelete(row)">
                    <template #icon><DeleteIcon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.status === 'CONFIRMED' && checkPermi(['erp:print:render'])" content="打印送货单" placement="top">
                  <t-button theme="primary" variant="text" shape="square" size="small" @click="handlePrint(row)">
                    <template #icon><PrintIcon /></template>
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

    <!-- 新增 / 编辑 / 查看 销售出库单 -->
    <t-drawer v-model:visible="dialog.visible" size="92%" :destroy-on-close="true" attach="body">
      <template #header>
        <div class="flex items-center gap-2">
          <span class="text-base font-medium">{{ dialog.title }}</span>
          <!-- 查看/编辑弹窗同步单据状态；DRAFT 按销售业务显示为「待出库」 -->
          <StatusTag v-if="dialogStatus" kind="bill" :value="dialogStatus" :status-text="dialogStatusText" draft-label="待出库" />
        </div>
      </template>
      <!-- 抽屉 body 自带纵向滚动，footer 固定；不再依赖无效的 body-style（普通 div 不识别） -->
      <div class="erp-drawer-body">
        <t-form ref="saleFormRef" :data="form" :rules="rules" label-align="top" :disabled="readonly">
          <!-- TDesign 为 12 栅格：原 6/4/4/3/4/3 无法整除，按同组字段拆两行，每行合计 12 -->
          <t-row :gutter="16">
            <t-col :span="3">
              <t-form-item label="客户" name="customerId">
                <PartnerSelect v-model="form.customerId" kind="customer" placeholder="请选择客户" />
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
                  align="right"
                  style="width: 100%"
                />
              </t-form-item>
            </t-col>
          </t-row>
          <t-row :gutter="16">
            <t-col :span="3">
              <t-form-item label="运杂费总额" name="freightAmount">
                <t-input-number v-model="form.freightAmount" :decimal-places="2" :min="0" theme="normal" align="right" style="width: 100%" />
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
        <div v-if="!form.id && !readonly" class="mb-2 flex flex-wrap items-center gap-2">
          <span class="shrink-0 erp-text-secondary">来源订单</span>
          <SourceOrderSelect v-if="dialog.visible" v-model="form.sourceOrderId" kind="sale" @change="onSourceOrderChange" />
          <t-button v-if="form.sourceOrderId" theme="success" variant="outline" size="small" @click="openSourceItems">
            <template #icon><DownloadIcon /></template>
            从订单带入
          </t-button>
        </div>

        <!-- 详情回显：编辑与查看态都显示来源订单，联系人/电话/送货地址取执行单头快照（DOC/31 R1） -->
        <div v-if="form.sourceOrderId" class="mb-2 flex flex-wrap items-center gap-3 rounded bg-blue-50 px-3 py-2 text-sm">
          <span>
            本单来自订单 <b>{{ form.sourceOrderNo || form.sourceOrderId }}</b>
          </span>
          <t-button variant="text" theme="primary" size="small" @click="goSourceOrder(form.sourceOrderId)">查看订单</t-button>
          <span v-if="form.contactName" class="erp-text-secondary">联系人：{{ form.contactName }}</span>
          <span v-if="form.contactPhone" class="erp-text-secondary">电话：{{ form.contactPhone }}</span>
          <span v-if="form.deliveryAddress" class="erp-text-secondary">送货地址：{{ form.deliveryAddress }}</span>
        </div>

        <div class="flex items-center justify-between mb-2">
          <div class="flex items-center gap-2">
            <!-- 挂订单时禁增行、禁现录现卖：空行/新商品会绕过订单量约束（后端整行集校验会拒，§6.7） -->
            <t-button v-if="!readonly && !form.sourceOrderId" theme="primary" variant="outline" @click="addRow">
              <template #icon><PlusIcon /></template>
              添加明细
            </t-button>
            <t-button v-if="!readonly && !form.sourceOrderId" theme="warning" variant="outline" @click="openNewProduct(-1)">
              <template #icon><PenFluorescenceIcon /></template>
              现录现卖
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
          bordered
          @row-click="(ctx: any) => onRowSelect(ctx.row)"
          @column-resize-change="(ctx: any) => saveColumnWidths('sale/detail', ctx.columnsWidth)"
        >
          <template #rowIndex="{ rowIndex }">{{ rowIndex + 1 }}</template>
          <template #product="{ row, rowIndex }">
            <!-- 现录现卖未建品：显示待建品卡片，避免空选择框被误读成「还没选产品」 -->
            <div v-if="row.newProduct && !row.productId" class="rounded border border-orange-200 bg-orange-50 px-2 py-1">
              <div class="flex items-center gap-1">
                <t-tag theme="warning" size="small">待建品</t-tag>
                <span class="truncate font-medium">{{ row.newProduct.name }}</span>
                <span v-if="row.newProduct.spec" class="shrink-0 erp-text-secondary">/ {{ row.newProduct.spec }}</span>
                <span class="shrink-0 erp-text-secondary">{{ row.newProduct.unit || '件' }}</span>
              </div>
              <div class="mt-1 erp-text-secondary">
                编码 {{ row.newProduct.code || '确认时按名称自动生成' }} · 入库
                {{ formatQtyByScale(row.newProduct.qty ?? row.qty, row.newProduct.qtyScale) }} · 成本
                {{ formatMoney(row.newProduct.costPrice) }}
              </div>
              <div v-if="!readonly" class="mt-1 flex items-center gap-1">
                <t-button variant="text" theme="primary" size="small" @click="openNewProduct(rowIndex)">编辑</t-button>
                <t-button variant="text" theme="danger" size="small" @click="clearNewProduct(rowIndex)">清除</t-button>
              </div>
            </div>
            <ProductSelect
              v-else
              v-model="row.productId"
              type="PRODUCT"
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
          <template #unitCost="{ row }">{{ snapshotText(row.unitCost) }}</template>
          <template #costAmount="{ row }">{{ snapshotText(row.costAmount) }}</template>
          <template #grossProfit="{ row }">{{ snapshotText(row.grossProfit) }}</template>
          <template #remark="{ row }">
            <t-input v-model="row.remark" :title="row.remark" :disabled="readonly" placeholder="备注" />
          </template>
          <template #rowOperation="{ rowIndex }">
            <div class="flex items-center justify-center gap-1">
              <t-button variant="text" theme="warning" shape="square" size="small" @click="openNewProduct(rowIndex)">
                <template #icon><PenFluorescenceIcon /></template>
              </t-button>
              <t-button variant="text" theme="danger" shape="square" size="small" @click="removeRow(rowIndex)">
                <template #icon><DeleteIcon /></template>
              </t-button>
            </div>
          </template>
        </t-table>

        <!-- 历史价格（DOC/26 §7）：按商品查询全市场成交记录，跟随当前选中行商品，倒序展示，点击回填单价 -->
        <div class="mt-3">
          <div class="flex items-center justify-between mb-1">
            <span class="text-sm font-medium">
              历史价格<template v-if="activeProductName">（{{ activeProductName }}）</template>
            </span>
            <span class="erp-text-secondary">最近 {{ PRICE_HISTORY_LIMIT }} 条{{ readonly ? '' : '，点击行可回填单价' }}</span>
          </div>
          <t-table
            class="erp-table erp-clickable-table"
            row-key="__key"
            :data="priceHistory.list"
            :columns="historyColumns"
            :loading="priceHistory.loading"
            :hover="!readonly"
            :resizable="true"
            size="small"
            max-height="200"
            cell-empty-content="-"
            bordered
            :empty="'暂无该商品的历史成交记录'"
            @column-resize-change="(ctx: any) => saveColumnWidths('sale/history', ctx.columnsWidth)"
            @row-click="(ctx: any) => applyHistoryPrice(ctx.row)"
          >
            <template #bizDate="{ row }">{{ toDate(row.bizDate) }}</template>
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
              >货品金额：<b class="text-primary">{{ formatMoney(totalAmount) }}</b></span
            >
            <span
              >运杂费：<b>{{ formatMoney(freightTotal) }}</b></span
            >
            <span v-if="form.costAmount !== undefined"
              >总成本：<b>{{ formatMoney(form.costAmount) }}</b></span
            >
            <span v-if="form.grossProfit !== undefined"
              >毛利：<b>{{ formatMoney(form.grossProfit) }}</b></span
            >
            <span v-if="form.grossMargin !== undefined"
              >毛利率：<b>{{ formatPercent(form.grossMargin) }}</b></span
            >
          </div>
          <div class="flex items-center gap-2">
            <t-button theme="default" variant="outline" @click="dialog.visible = false">{{ readonly ? '关 闭' : '取 消' }}</t-button>
            <t-button v-if="!readonly" theme="primary" variant="outline" :loading="saving" :disabled="saving" @click="submitForm(false)">
              保存为待出库
            </t-button>
            <t-button
              v-if="!readonly && checkPermi(['erp:sale:audit'])"
              theme="success"
              :loading="saving"
              :disabled="saving"
              @click="submitForm(true)"
            >
              保存并确认出库
            </t-button>
          </div>
        </div>
      </template>
    </t-drawer>

    <!-- 现录现卖：新产品信息 -->
    <t-dialog v-model:visible="newProduct.visible" :header="newProduct.title" width="520px" attach="body">
      <t-form ref="newProductFormRef" :data="newProductForm" :rules="newProductRules" label-align="top">
        <!-- 字段口径与「商品资料」完全一致：同组件渲染，避免两处表单漂移 -->
        <ProductFormFields v-model="newProductForm" :show-type="false" :show-status="false" :cost-readonly="false" />
        <t-row :gutter="16">
          <t-col :span="6">
            <t-form-item label="入库数量" name="qty">
              <t-input-number
                :key="`qty-new-${qtyPrecision(newProductForm.qtyScale)}`"
                v-model="newProductForm.qty"
                :decimal-places="qtyPrecision(newProductForm.qtyScale)"
                :step="qtyStep(newProductForm.qtyScale)"
                :min="0"
                theme="normal"
                align="right"
                placeholder="默认等于销售数量"
                style="width: 100%"
              />
            </t-form-item>
          </t-col>
          <t-col :span="6">
            <div class="pt-6 erp-text-secondary leading-5">默认等于销售数量，可改大；多出的数量留在库存</div>
          </t-col>
        </t-row>
      </t-form>
      <template #footer>
        <div class="flex items-center justify-end gap-2">
          <t-button theme="default" variant="outline" @click="newProduct.visible = false">取 消</t-button>
          <t-button theme="primary" @click="submitNewProduct">确 定</t-button>
        </div>
      </template>
    </t-dialog>

    <!-- 打印送货单预览：原弹窗无底部按钮，TDesign 默认会渲染「取消/确定」，显式关掉 -->
    <t-dialog v-model:visible="printDialog.visible" :header="printDialog.title" width="820px" top="4vh" :footer="false" attach="body">
      <div class="flex items-center gap-2 mb-2">
        <span>打印份数</span>
        <t-select v-model="printDialog.copies" style="width: 130px" :options="printCopyOptions" @change="loadPrintHtml" />
        <t-button theme="primary" :disabled="!printDialog.html" @click="doPrint">
          <template #icon><PrintIcon /></template>
          打印
        </t-button>
        <!-- 针打缩小问题多因打印对话框纸张与实际装纸不一致：提示用户核对纸张与缩放 -->
        <span class="print-dialog-hint">打印对话框请选择与实际装纸一致的纸张，缩放 100%</span>
      </div>
      <div v-loading="printDialog.loading" style="min-height: 200px">
        <iframe
          v-if="printDialog.html"
          ref="printFrameRef"
          :srcdoc="printDialog.html"
          sandbox="allow-same-origin allow-modals"
          title="打印预览"
          class="erp-print-frame"
          style="width: 100%; height: 62vh"
        ></iframe>
      </div>
    </t-dialog>

    <!-- 从销售订单带入：只列该单 CONFIRMED 且 remain>0 的未交行，勾选后回填明细并写行级来源ID -->
    <t-dialog v-model:visible="openItemState.visible" header="从销售订单带入明细" width="820px" top="6vh" attach="body">
      <t-alert theme="info" :close="false" class="mb-3" title="只带本次勾选的未交行；带入后仍可修改数量与单价。" />
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
        bordered
        :empty="'该订单没有可带入的未交行'"
        @column-resize-change="(ctx: any) => saveColumnWidths('sale/open-items', ctx.columnsWidth)"
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
        <div class="flex items-center justify-end gap-2">
          <t-button theme="default" variant="outline" @click="openItemState.visible = false">取 消</t-button>
          <t-button theme="primary" @click="confirmSourceItems">带 入</t-button>
        </div>
      </template>
    </t-dialog>
  </div>
</template>

<script setup name="ErpSale" lang="ts">
import { addSale, confirmSale, delSale, getSale, getSalePriceHistory, listSale, updateSale, voidSale, withdrawSale } from '@/api/erp/trade';
import { renderSaleDelivery } from '@/api/erp/print';
import { isPrintableHtml } from '@/utils/erp/printPreview';
import { ERP_BILL_STATUS, ERP_TAX_TYPE } from '@/api/erp/enums';
import type { ErpBillStatus, ErpTaxType } from '@/api/erp/enums';
import type {
  ErpAmount,
  ErpId,
  ErpPriceHistoryVO,
  ErpProductFormModel,
  ErpProductVO,
  ErpPurchaseOrderVO,
  ErpSaleForm,
  ErpSaleNewProduct,
  ErpSaleOrderItemVO,
  ErpSaleOrderVO,
  ErpSaleVO
} from '@/api/erp/types';
import { listSaleOpenItems } from '@/api/erp/order';
import SourceOrderSelect from '@/components/erp/SourceOrderSelect.vue';
import { formatMoney, formatPercent, formatQtyByScale, formatQtyTrim, normalizeQty, qtyPrecision, qtyStep, roundMoney } from '@/utils/erp/calc';
import { checkPermi } from '@/utils/permission';
// 列宽记忆：拖拽后按表名存 localStorage，进页面还原（见 hooks/useTableColumnWidth）
import { mergeSavedWidths, saveColumnWidths } from '@/hooks/useTableColumnWidth';
import PartnerSelect from '@/components/erp/PartnerSelect.vue';
import ProductFormFields from '@/components/erp/ProductFormFields.vue';
import ProductSelect from '@/components/erp/ProductSelect.vue';
import StatusTag from '@/components/erp/StatusTag.vue';
import {
  BrowseIcon,
  CheckCircleIcon,
  CloseCircleIcon,
  DeleteIcon,
  DownloadIcon,
  Edit1Icon,
  PenFluorescenceIcon,
  PlusIcon,
  PrintIcon,
  RefreshIcon,
  RollbackIcon,
  SearchIcon
} from 'tdesign-icons-vue-next';

interface SaleRow {
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
  /** 现录现卖：随明细提交的新产品 */
  newProduct?: ErpSaleNewProduct;
  /** 确认后成本快照 */
  unitCost?: number;
  costAmount?: number;
  grossProfit?: number;
  /** 来源销售订单行ID：转单/带入时有值，直开单为空（DOC/31 §10.4） */
  sourceOrderItemId?: ErpId;
  /** t-table 行 key（前端生成，不提交后端） */
  __key?: string;
}

/** 历史价格行：接口返回无主键，t-table 的 rowKey 需前端补一个字符串字段 */
interface PriceHistoryRow extends ErpPriceHistoryVO {
  __key: string;
}

/** 从订单带入弹窗的行：未交行 + 勾选态 + 本次数量 */
interface OpenItemRow extends ErpSaleOrderItemVO {
  checked: boolean;
  takeQty: number;
}

interface SaleFormState {
  id?: ErpId;
  /** 来源销售订单ID（挂单时有值，直开单为空） */
  sourceOrderId?: ErpId;
  /** 来源销售订单号快照，仅回显 */
  sourceOrderNo?: string;
  /** 客户联系人快照（挂订单时由后端从订单带出） */
  contactName?: string;
  /** 客户联系电话快照 */
  contactPhone?: string;
  /** 送货地址快照 */
  deliveryAddress?: string;
  customerId?: ErpId;
  bizDate: string;
  /** 发票类型（整单统一） */
  taxType: ErpTaxType;
  /** 税率%（整单统一） */
  taxRate: number;
  /** 运杂费总额 */
  freightAmount: number;
  remark: string;
  /** 确认后的成本快照 */
  costAmount?: number;
  grossProfit?: number;
  grossMargin?: number;
}

interface SaleQueryState extends PageQuery {
  keyword: string;
  customerId?: ErpId;
  status: string;
}

/** 现录现卖表单：字段与「商品资料」共用一份契约（ErpProductFormModel），另加入库数量 */
interface NewProductFormState extends ErpProductFormModel {
  /** 入库数量（默认等于销售数量，可改大，DOC/26 §8.3） */
  qty?: number;
}

const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const router = useRouter();
const route = useRoute();

/** DRAFT 在销售单业务上叫「待出库」，其余状态沿用全局文案 */
const statusOptions = Object.entries(ERP_BILL_STATUS).map(([value, item]) => ({
  value: value as ErpBillStatus,
  label: value === 'DRAFT' ? '待出库' : item.label
}));
const taxTypeOptions = Object.entries(ERP_TAX_TYPE).map(([value, item]) => ({ value: value as ErpTaxType, label: item.label }));

/** 主列表列配置：t-table 用 columns 数组，插槽名与 cell 字符串同名（DOC/35 §5） */
const listColumns: any[] = [
  { colKey: 'no', title: '单号', align: 'center', minWidth: 130, ellipsis: true },
  { colKey: 'customerName', title: '客户', align: 'center', minWidth: 140, ellipsis: true, cell: 'customer' },
  { colKey: 'sourceOrderNo', title: '来源订单', align: 'center', minWidth: 150, ellipsis: true, cell: 'sourceOrder' },
  { colKey: 'bizDate', title: '业务日期', align: 'center', width: 110, cell: 'bizDate' },
  { colKey: 'totalAmount', title: '销售金额', align: 'right', width: 120, cell: 'totalAmount' },
  { colKey: 'costAmount', title: '销售成本', align: 'right', width: 120, cell: 'costAmount' },
  { colKey: 'grossProfit', title: '毛利', align: 'right', width: 120, cell: 'grossProfit' },
  { colKey: 'grossMargin', title: '毛利率', align: 'right', width: 100, cell: 'grossMargin' },
  { colKey: 'status', title: '状态', align: 'center', width: 90, cell: 'status' },
  { colKey: 'remark', title: '备注', align: 'center', minWidth: 120, ellipsis: true },
  { colKey: 'operation', title: '操作', align: 'center', width: 160, cell: 'operation' }
];
mergeSavedWidths('sale/list', listColumns);

/** 明细行列表配置：数量/单价/备注是行内编辑控件；挂订单或只读时不出现操作列 */
const rowColumns = computed<any[]>(() => {
  const cols: any[] = [
    { colKey: 'rowIndex', title: '#', width: 50, align: 'center', cell: 'rowIndex' },
    { colKey: 'productId', title: '产品', minWidth: 220, cell: 'product' },
    { colKey: 'spec', title: '规格', width: 110, align: 'center', ellipsis: true },
    { colKey: 'unit', title: '单位', width: 70, align: 'center', ellipsis: true },
    { colKey: 'qty', title: '数量', width: 130, align: 'right', cell: 'qty' },
    { colKey: 'price', title: '销售单价', width: 150, align: 'right', cell: 'price' },
    { colKey: 'amount', title: '合计', width: 120, align: 'right', cell: 'amount' },
    { colKey: 'unitCost', title: '成本单价', width: 110, align: 'right', cell: 'unitCost' },
    { colKey: 'costAmount', title: '成本金额', width: 110, align: 'right', cell: 'costAmount' },
    { colKey: 'grossProfit', title: '毛利', width: 110, align: 'right', cell: 'grossProfit' },
    { colKey: 'remark', title: '备注', minWidth: 130, cell: 'remark' }
  ];
  // 挂订单时禁删行：删掉订单行后无法再带回，行级关联会被冲成 null（DOC/31 D13）
  if (!readonly.value && !form.sourceOrderId) {
    cols.push({ colKey: 'operation', title: '操作', width: 90, align: 'center', cell: 'rowOperation' });
  }
  return mergeSavedWidths('sale/detail', cols);
});

/** 历史价格列表配置：整表可点，点击行回填单价 */
const historyColumns: any[] = [
  { colKey: 'bizDate', title: '业务日期', width: 110, align: 'center', cell: 'bizDate' },
  { colKey: 'partyName', title: '客户', minWidth: 120, ellipsis: true },
  { colKey: 'orderNo', title: '单据号', minWidth: 140, ellipsis: true },
  { colKey: 'qty', title: '数量', width: 110, align: 'right', cell: 'qty' },
  { colKey: 'price', title: '单价', width: 120, align: 'right', cell: 'price' },
  { colKey: 'amount', title: '金额', width: 130, align: 'right', cell: 'amount' }
];
mergeSavedWidths('sale/history', historyColumns);

/** 从订单带入弹窗列配置：勾选列沿用行内 checked 字段，不改 TDesign 的 selectedRowKeys 契约 */
const openItemColumns: any[] = [
  { colKey: 'checked', title: ' ', width: 46, align: 'center', cell: 'checked' },
  { colKey: 'productId', title: '商品', minWidth: 200, cell: 'product' },
  { colKey: 'spec', title: '规格', width: 100, align: 'center', ellipsis: true },
  { colKey: 'remain', title: '未交量', width: 110, align: 'right', cell: 'remain' },
  { colKey: 'takeQty', title: '本次数量', width: 140, align: 'right', cell: 'takeQty' },
  { colKey: 'price', title: '单价', width: 110, align: 'right', cell: 'price' }
];
mergeSavedWidths('sale/open-items', openItemColumns);

/** 打印份数选项：原 el-option 字面量，迁 TDesign 后走 options 数组 */
const printCopyOptions = [
  { label: '1 份', value: 1 },
  { label: '2 份', value: 2 },
  { label: '3 份（三联）', value: 3 }
];

const saleList = ref<ErpSaleVO[]>([]);
const loading = ref(true);
const detailLoading = ref(false);
/** 两种保存方式共用锁，覆盖提交到列表刷新完成的整个异步流程。 */
const saving = ref(false);
const showSearch = ref(true);
const total = ref(0);
/** 表单实例：TDesign 的校验/重置契约与 EP 不同，统一按 any 持有（与采购入库页同写法） */
const saleFormRef = ref<any>();
/** 查询表单：已换 t-form，ref 提供 reset()（EP 是 resetFields()） */
const queryFormRef = ref<any>();
const newProductFormRef = ref<any>();

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

const rows = ref<SaleRow[]>([]);

/** 从订单带入弹窗 */
const openItemState = reactive({ visible: false, loading: false, list: [] as OpenItemRow[] });

/** 历史价格区当前跟随的明细行（DOC/26 §7） */
const activeRowIndex = ref(0);
const activeProductName = computed(() => {
  const row = rows.value[activeRowIndex.value];
  return row?.productName || row?.newProduct?.name || '';
});
/** 销售历史价格固定条数（后端另有 1~50 兜底） */
const PRICE_HISTORY_LIMIT = 10;
const priceHistory = reactive({ loading: false, list: [] as PriceHistoryRow[] });
/** 请求序号：切换行/商品后丢弃过期响应 */
let priceHistorySeq = 0;

const newProduct = reactive({
  visible: false,
  title: '',
  /** 目标行索引，-1 表示新增一行 */
  index: -1
});

const newProductForm = reactive<NewProductFormState>({
  code: '',
  name: '',
  spec: '',
  unit: '',
  type: 'PRODUCT',
  categoryId: undefined,
  supplierId: undefined,
  costPrice: undefined,
  purchasePrice: 0,
  taxType: 'NO_TAX',
  freight: 0,
  minStock: undefined,
  maxStock: undefined,
  status: 1,
  remark: '',
  qtyScale: 0,
  qty: undefined
});

// t-form 的 rules 形状与 EP 的 ElFormRules 不兼容（本页仅用于必填星号与现录现卖名称必填）
const newProductRules: Record<string, any> = {
  name: [{ required: true, message: '产品名称不能为空', trigger: 'blur' }]
};

const createForm = (): SaleFormState => ({
  id: undefined,
  sourceOrderId: undefined,
  sourceOrderNo: undefined,
  contactName: undefined,
  contactPhone: undefined,
  deliveryAddress: undefined,
  customerId: undefined,
  bizDate: new Date().toISOString().slice(0, 10),
  taxType: 'VAT_INVOICE',
  taxRate: 13,
  freightAmount: 0,
  remark: '',
  costAmount: undefined,
  grossProfit: undefined,
  grossMargin: undefined
});

const form = reactive<SaleFormState>(createForm());

const queryParams = reactive<SaleQueryState>({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  customerId: undefined,
  status: ''
});

// t-form 的 rules 形状与 EP 的 ElFormRules 不兼容（提交前另有 validateRows 手工校验）
const rules: Record<string, any> = {
  customerId: [{ required: true, message: '客户不能为空', trigger: 'change' }],
  bizDate: [{ required: true, message: '业务日期不能为空', trigger: 'change' }]
};

const num = (value: ErpAmount | undefined | null): number => {
  const n = Number(value ?? 0);
  return Number.isFinite(n) ? n : 0;
};

/** 可空数值：空值保持 undefined，避免把「留空」写成 0（库存上下限、成本单价等可空字段用） */
const numOrUndefined = (value: ErpAmount | undefined | null): number | undefined => (value === undefined || value === null ? undefined : num(value));

/** 日期只取 yyyy-MM-dd */
const toDate = (value: string | undefined): string => (value ? String(value).slice(0, 10) : '');

/** 成本快照为空时显示 '-'（草稿状态无成本快照） */
const snapshotText = (value: number | undefined | null): string => (value === undefined || value === null ? '-' : formatMoney(value));

/** 毛利率为空时显示 '-'（草稿状态无成本快照） */
const marginText = (value: number | undefined | null): string => (value === undefined || value === null ? '-' : formatPercent(value));

/** 行金额 = 数量 × 销售单价 */
const rowAmount = (index: number): number => {
  const row = rows.value[index];
  if (!row) return 0;
  return roundMoney((row.qty || 0) * (row.price || 0));
};

const totalAmount = computed(() => roundMoney(rows.value.reduce((sum, _row, index) => sum + rowAmount(index), 0)));
/** 运杂费总额（表头录入，确认时按数量分摊计入成本） */
const freightTotal = computed(() => roundMoney(num(form.freightAmount)));

/** 查询销售单列表 */
const getList = async () => {
  loading.value = true;
  try {
    const res = await listSale(queryParams);
    saleList.value = res.rows ?? [];
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
  queryParams.customerId = undefined;
  queryParams.status = '';
  handleQuery();
};

/* ------------------------------ 打印送货单 ------------------------------ */

const printDialog = reactive({
  visible: false,
  title: '',
  saleId: undefined as ErpId | undefined,
  copies: 1,
  loading: false,
  html: ''
});
const printFrameRef = ref<HTMLIFrameElement>();
let printRequest = 0;
const invalidatePrint = () => {
  ++printRequest;
  printDialog.html = '';
  printDialog.loading = false;
};
watch(
  () => printDialog.visible,
  (visible) => {
    if (!visible) invalidatePrint();
  },
  { flush: 'sync' }
);
onBeforeUnmount(invalidatePrint);
const loadPrintHtml = async () => {
  if (!printDialog.saleId || !printDialog.visible) return;
  const current = ++printRequest;
  printDialog.html = '';
  printDialog.loading = true;
  try {
    const html: unknown = await renderSaleDelivery(printDialog.saleId, { copies: printDialog.copies });
    if (current !== printRequest || !printDialog.visible) return;
    if (!isPrintableHtml(html)) {
      proxy?.$modal.msgWarning('打印渲染失败，请检查模板');
      return;
    }
    printDialog.html = html;
  } catch {
    if (current === printRequest) proxy?.$modal.msgWarning('打印预览加载失败，请稍后重试');
  } finally {
    if (current === printRequest) printDialog.loading = false;
  }
};

const handlePrint = (row: ErpSaleVO) => {
  printDialog.saleId = row.id;
  printDialog.copies = 1;
  printDialog.html = '';
  printDialog.title = `打印送货单 - ${row.no}`;
  printDialog.visible = true;
  loadPrintHtml();
};

const doPrint = () => {
  const frame = printFrameRef.value;
  if (!frame?.contentWindow || !printDialog.visible || !printDialog.html || printDialog.loading) return;
  frame.contentWindow.focus();
  frame.contentWindow.print();
};

/** 明细行 t-table 行 key：新增行没有业务主键，用前端自增序号 */
let rowSeq = 0;
const nextRowKey = (): string => `row-${++rowSeq}`;

const createRow = (): SaleRow => ({
  __key: nextRowKey(),
  productId: undefined,
  qty: 1,
  price: 0,
  remark: '',
  newProduct: undefined,
  unitCost: undefined,
  costAmount: undefined,
  grossProfit: undefined,
  sourceOrderItemId: undefined
});

/** 表单重置 */
const resetForm = async () => {
  Object.assign(form, createForm());
  rows.value = [createRow()];
  openItemState.visible = false;
  openItemState.list = [];
  activeRowIndex.value = 0;
  readonly.value = false;
  dialogStatus.value = '';
  dialogStatusText.value = '';
  await nextTick();
  saleFormRef.value?.clearValidate();
};

/** 新增按钮操作 */
const handleAdd = async () => {
  await resetForm();
  dialog.visible = true;
  dialog.title = '新增销售出库单';
};

/** 选中一条服务端过滤/分页订单时，沿用其客户和单号快照。 */
const onSourceOrderChange = (order?: ErpSaleOrderVO | ErpPurchaseOrderVO) => {
  if (order && !('customerId' in order)) return;
  form.sourceOrderNo = order?.no;
  if (order && 'customerId' in order) form.customerId = order.customerId;
  openItemState.visible = false;
  openItemState.list = [];
  rows.value = [createRow()];
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
    const res = await listSaleOpenItems(form.sourceOrderId);
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
  activeRowIndex.value = 0;
  openItemState.visible = false;
  schedulePriceHistory();
};

/** 跳转来源订单详情 */
const goSourceOrder = (orderId?: ErpId) => {
  if (!orderId) return;
  router.push({ path: '/erp-biz/sale-order', query: { id: String(orderId) } });
};

const loadDetail = async (id: ErpId, isReadonly: boolean) => {
  detailLoading.value = true;
  try {
    const res = await getSale(id);
    const detail = res.data;
    dialogStatus.value = detail.status ?? '';
    dialogStatusText.value = detail.statusText ?? '';
    Object.assign(form, {
      id: detail.id,
      sourceOrderId: detail.sourceOrderId,
      sourceOrderNo: detail.sourceOrderNo,
      contactName: detail.contactName,
      contactPhone: detail.contactPhone,
      deliveryAddress: detail.deliveryAddress,
      customerId: detail.customerId,
      bizDate: toDate(detail.bizDate),
      taxType: (detail.taxType || 'VAT_INVOICE') as ErpTaxType,
      taxRate: num(detail.taxRate),
      freightAmount: num(detail.freightAmount),
      remark: detail.remark || '',
      costAmount: detail.costAmount === undefined || detail.costAmount === null ? undefined : num(detail.costAmount),
      grossProfit: detail.grossProfit === undefined || detail.grossProfit === null ? undefined : num(detail.grossProfit),
      grossMargin: detail.grossMargin === undefined || detail.grossMargin === null ? undefined : num(detail.grossMargin)
    });
    rows.value = (detail.items ?? []).map((item) => ({
      __key: nextRowKey(),
      // 待建品（草稿）product_id=0，按未选产品处理，已确认单据商品已回填
      productId: item.productId ? item.productId : undefined,
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
      newProduct: item.newProduct,
      unitCost: item.unitCost === undefined || item.unitCost === null ? undefined : num(item.unitCost),
      costAmount: item.costAmount === undefined || item.costAmount === null ? undefined : num(item.costAmount),
      grossProfit: item.grossProfit === undefined || item.grossProfit === null ? undefined : num(item.grossProfit),
      // 行级来源必须回显，否则挂订单草稿「编辑→保存」会把关联冲成 null（DOC/31 D13）
      sourceOrderItemId: item.sourceOrderItemId
    }));
    readonly.value = isReadonly;
    activeRowIndex.value = 0;
    schedulePriceHistory();
    await nextTick();
    saleFormRef.value?.clearValidate();
    return detail;
  } finally {
    detailLoading.value = false;
  }
};

/** 编辑按钮操作 */
const handleEdit = async (row: ErpSaleVO) => {
  await resetForm();
  dialog.visible = true;
  dialog.title = `编辑销售出库单 ${row.no ?? ''}`;
  await loadDetail(row.id, false);
};

/** 查看按钮操作 */
const handleView = async (row: ErpSaleVO) => {
  await resetForm();
  dialog.visible = true;
  dialog.title = `查看销售出库单 ${row.no ?? ''}`;
  await loadDetail(row.id, true);
};

/** 跨页跳转：按单据ID直接打开详情，不依赖列表当前页（DOC/31 §10.4） */
const openDetailById = async (id: ErpId) => {
  await resetForm();
  dialog.visible = true;
  dialog.title = '查看销售出库单';
  const detail = await loadDetail(id, true);
  dialog.title = `查看销售出库单 ${detail?.no ?? ''}`;
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

/** 选择产品后带出名称、规格、单位（单价一单一价，由人工填写） */
const onProductChange = (index: number, product?: ErpProductVO) => {
  const row = rows.value[index];
  if (!row) return;
  if (!product) {
    row.productCode = undefined;
    row.productName = undefined;
    row.spec = undefined;
    row.unit = undefined;
    row.qtyScale = undefined;
    row.newProduct = undefined;
    return;
  }
  row.productCode = product.code;
  row.productName = product.name;
  row.spec = product.spec;
  row.unit = product.unit;
  row.qtyScale = product.qtyScale;
  // 精度随商品切换时把已录数量归一化，避免小数商品的数量挂到整数商品行上（数量控件只改显示不回写 model）
  row.qty = normalizeQty(row.qty, product.qtyScale);
  row.newProduct = undefined;
  activeRowIndex.value = index;
  schedulePriceHistory();
};

/** 拉取当前选中行的历史价格；无商品时清空（不限客户，全市场成交记录）。
 * 加载成功后若当前行单价未手改过，自动带入最近一条成交价（优化：不再默认 0.00）。 */
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
    const res = await getSalePriceHistory(productId, PRICE_HISTORY_LIMIT);
    if (seq !== priceHistorySeq) return;
    // 历史价格接口返回无主键，补一个前端行 key 供 t-table 定位行
    priceHistory.list = (res.data ?? []).map((item, index) => ({ ...item, __key: `ph-${index}` }));
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
const onRowSelect = (row: SaleRow) => {
  const index = rows.value.indexOf(row);
  if (index < 0 || index === activeRowIndex.value) return;
  activeRowIndex.value = index;
  schedulePriceHistory();
};

/** 行商品或客户变化时重查历史价（历史价按商品查询，客户变化时客户名列表不变，仍随商品刷新） */
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

/** 打开现录现卖表单；index 为 -1 表示新增一行 */
const openNewProduct = (index: number) => {
  // 挂订单禁现录现卖：新商品无法对应订单行，会绕过订单量约束（DOC/31 §6.7）
  if (form.sourceOrderId) return;
  newProduct.index = index;
  newProduct.title = index >= 0 ? '编辑现录现卖产品' : '新增现录现卖产品';
  const source = index >= 0 ? rows.value[index]?.newProduct : undefined;
  Object.assign(newProductForm, {
    code: source?.code ?? '',
    name: source?.name ?? '',
    spec: source?.spec ?? '',
    unit: source?.unit ?? '',
    type: 'PRODUCT',
    categoryId: source?.categoryId ?? undefined,
    supplierId: source?.supplierId ?? undefined,
    costPrice: numOrUndefined(source?.costPrice),
    purchasePrice: num(source?.purchasePrice),
    taxType: source?.taxType ?? 'NO_TAX',
    freight: num(source?.freight),
    minStock: numOrUndefined(source?.minStock),
    maxStock: numOrUndefined(source?.maxStock),
    status: 1,
    remark: source?.remark ?? '',
    qtyScale: source?.qtyScale ?? 0,
    // 入库数量默认等于销售数量（新行留空，由后端按销售数量兜底）
    qty: source?.qty === undefined ? (index >= 0 ? num(rows.value[index]?.qty) : undefined) : num(source.qty)
  });
  newProduct.visible = true;
  nextTick(() => newProductFormRef.value?.clearValidate());
};

/** 提交现录现卖表单 */
const submitNewProduct = async () => {
  // TDesign 校验失败时 resolve 的是错误对象（不是 false），必须显式判等 true
  const r = await newProductFormRef.value?.validate().catch(() => false);
  const ok = r === true || r === undefined;
  if (!ok) return;
  const payload: ErpSaleNewProduct = {
    code: (newProductForm.code ?? '').trim() || undefined,
    name: newProductForm.name.trim(),
    spec: (newProductForm.spec ?? '').trim() || undefined,
    unit: (newProductForm.unit ?? '').trim() || undefined,
    categoryId: newProductForm.categoryId ?? undefined,
    supplierId: newProductForm.supplierId ?? undefined,
    costPrice: numOrUndefined(newProductForm.costPrice),
    purchasePrice: numOrUndefined(newProductForm.purchasePrice),
    taxType: newProductForm.taxType,
    freight: numOrUndefined(newProductForm.freight),
    minStock: numOrUndefined(newProductForm.minStock),
    maxStock: numOrUndefined(newProductForm.maxStock),
    remark: (newProductForm.remark ?? '').trim() || undefined,
    qtyScale: newProductForm.qtyScale ?? 0,
    qty: numOrUndefined(newProductForm.qty)
  };
  // 入库数量必须覆盖销售数量，否则确认时会因库存不足失败（不允许负库存）
  const saleQty = newProduct.index >= 0 ? num(rows.value[newProduct.index]?.qty) : 1;
  if (payload.qty !== undefined && payload.qty < saleQty) {
    proxy?.$modal.msgWarning('入库数量不能小于销售数量');
    return;
  }
  let target = newProduct.index;
  if (target < 0) {
    rows.value.push(createRow());
    target = rows.value.length - 1;
  }
  const row = rows.value[target];
  row.productId = undefined;
  row.productCode = undefined;
  row.productName = payload.name;
  row.spec = payload.spec;
  row.unit = payload.unit;
  row.qtyScale = payload.qtyScale;
  // 现录现卖改精度后同样归一化已录数量，保持提交值与输入框精度一致
  row.qty = normalizeQty(row.qty, payload.qtyScale);
  row.newProduct = payload;
  newProduct.visible = false;
};

/** 清除现录现卖信息 */
const clearNewProduct = (index: number) => {
  const row = rows.value[index];
  if (!row) return;
  row.newProduct = undefined;
  row.productCode = undefined;
  row.productName = undefined;
  row.spec = undefined;
  row.unit = undefined;
  row.qtyScale = undefined;
};

/** 组装提交数据：税值/运杂费在表头整单统一，明细不再提交 */
const buildPayload = (confirm: boolean): ErpSaleForm => ({
  id: form.id,
  sourceOrderId: form.sourceOrderId,
  customerId: form.customerId,
  bizDate: form.bizDate,
  taxType: form.taxType,
  taxRate: num(form.taxRate),
  freightAmount: freightTotal.value,
  remark: form.remark,
  confirm,
  items: rows.value.map((row) => ({
    productId: row.productId,
    // 现录现卖行撤回后商品已回填，快照仍需回传，确认时据此重新入库
    newProduct: row.newProduct,
    sourceOrderItemId: row.sourceOrderItemId,
    qty: row.qty,
    price: row.price,
    remark: row.remark
  }))
});

/** 提交前校验 */
const validateRows = (): boolean => {
  if (!form.customerId) {
    proxy?.$modal.msgWarning('请选择客户');
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
      if (!row.newProduct || !row.newProduct.name) {
        proxy?.$modal.msgWarning('明细中存在未选择产品的行');
        return false;
      }
    }
    if (row.newProduct?.qty !== undefined && row.newProduct.qty < row.qty) {
      proxy?.$modal.msgWarning(`「${row.newProduct.name}」入库数量不能小于销售数量`);
      return false;
    }
    if (!(row.qty > 0)) {
      proxy?.$modal.msgWarning('数量必须大于 0');
      return false;
    }
    if (!(row.price >= 0)) {
      proxy?.$modal.msgWarning('销售单价不能为负数');
      return false;
    }
  }
  return true;
};

/** 成本缺失软提示：成本为 0 的商品按 100% 毛利计，允许继续确认（DOC/26 §2.4） */
const showCostWarnings = (warnings?: string[]) => {
  if (!warnings || warnings.length === 0) return;
  proxy?.$modal.msgWarning(`商品「${warnings.join('、')}」无成本价（库存成本为 0），本次毛利将按 100% 计`);
};

/** 提交按钮 */
const submitForm = async (confirm: boolean) => {
  if (saving.value || readonly.value) return;
  saving.value = true;
  try {
    if (!validateRows()) return;
    const payload = buildPayload(confirm);
    const res = form.id ? await updateSale(payload) : await addSale(payload);
    proxy?.$modal.msgSuccess(confirm ? '已保存并确认出库' : '已保存为待出库');
    if (confirm) showCostWarnings(res.data?.costWarnings);
    dialog.visible = false;
    await getList();
  } finally {
    saving.value = false;
  }
};

/** 确认按钮操作 */
const handleConfirm = async (row: ErpSaleVO) => {
  try {
    await proxy?.$modal.confirm(`是否确认销售单「${row.no ?? ''}」？确认出库后扣减库存并生成应收。`);
  } catch {
    return;
  }
  const res = await confirmSale(row.id);
  proxy?.$modal.msgSuccess('已确认出库');
  showCostWarnings(res.data?.costWarnings);
  await getList();
};

/** 撤回按钮操作 */
const handleWithdraw = async (row: ErpSaleVO) => {
  try {
    await proxy?.$modal.confirm(`是否撤回销售单「${row.no ?? ''}」？撤回后回到待出库可继续修改。`);
  } catch {
    return;
  }
  await withdrawSale(row.id);
  proxy?.$modal.msgSuccess('已撤回');
  await getList();
};

/** 作废按钮操作 */
const handleVoid = async (row: ErpSaleVO) => {
  try {
    await proxy?.$modal.confirm(`是否作废销售单「${row.no ?? ''}」？作废为终态，不可再修改。`);
  } catch {
    return;
  }
  await voidSale(row.id);
  proxy?.$modal.msgSuccess('已作废');
  await getList();
};

/** 删除按钮操作：仅草稿单据可删除，后端逻辑删除，单号不回收 */
const handleDelete = async (row: ErpSaleVO) => {
  try {
    await proxy?.$modal.confirm(`是否删除销售单「${row.no ?? ''}」？删除后不可恢复，单号不会回收重用。`);
  } catch {
    return;
  }
  await delSale(row.id);
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
/* 历史价格表整行可点回填单价：hover 时给手型（文档「点击行可回填单价」的可见反馈）
   注意 .erp-clickable-table 与 .t-table 落在同一个根元素上，选择器里不能再写一代 .t-table */
.erp-clickable-table :deep(tbody tr) {
  cursor: pointer;
}

.print-dialog-hint {
  font-size: 12px;
  color: var(--td-text-color-secondary);
}
</style>
