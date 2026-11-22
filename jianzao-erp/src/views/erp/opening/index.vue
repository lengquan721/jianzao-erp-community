<template>
  <div class="p-2">
    <t-tabs v-model="activeTab" class="!mb-[10px]">
      <t-tab-panel label="库存期初" value="stock" />
      <t-tab-panel label="往来期初" value="finance" />
    </t-tabs>

    <!-- ==================== 库存期初 ==================== -->
    <div v-show="activeTab === 'stock'" class="erp-surface erp-panel">
      <div class="erp-panel__head">
        <div class="flex flex-wrap items-center gap-2">
          <ErpPagedSelect
            v-model="stWarehouseId"
            placeholder="全部仓库"
            :fetch-page="warehouseOptions"
            :fetch-by-ids="warehouseOptionsByIds"
            style="width: 160px"
            @change="handleStQuery"
          />
          <t-button v-hasPermi="['erp:stock:opening']" theme="primary" variant="outline" @click="handleAdd">
            <template #icon><PlusIcon /></template>
            新增期初
          </t-button>
          <t-dropdown>
            <t-button theme="default" variant="outline">
              导入/导出
              <template #suffix><ChevronDownIcon /></template>
            </t-button>
            <template #dropdown>
              <t-dropdown-menu>
                <!-- t-dropdown-item 延迟渲染，v-hasPermi 不生效，需用 checkPermi -->
                <t-dropdown-item v-if="checkPermi(['erp:stock:opening:import'])" @click="handleStTemplate">
                  <template #prefix-icon><DownloadIcon /></template>
                  下载模板
                </t-dropdown-item>
                <t-dropdown-item v-if="checkPermi(['erp:stock:opening:import'])" @click="handleStImport">
                  <template #prefix-icon><UploadIcon /></template>
                  导入数据
                </t-dropdown-item>
                <t-dropdown-item v-if="checkPermi(['erp:stock:opening:export'])" @click="handleStExport">
                  <template #prefix-icon><DownloadIcon /></template>
                  导出数据
                </t-dropdown-item>
              </t-dropdown-menu>
            </template>
          </t-dropdown>
          <span class="erp-text-secondary">期初确认后锁定，需先反确认才能修改</span>
        </div>
        <right-toolbar :search="false" @query-table="getStList" />
      </div>

      <t-table
        class="erp-table"
        row-key="id"
        :data="stList"
        :columns="stColumns"
        :loading="stLoading"
        :hover="true"
        :resizable="true"
        size="small"
        cell-empty-content="-"
        :empty="stFailed ? '库存期初加载失败，请重试' : '暂无库存期初数据'"
        @column-resize-change="(ctx: any) => saveColumnWidths('opening/stock-list', ctx.columnsWidth)"
      >
        <template #warehouseId="{ row }">{{ warehouseName(row) }}</template>
        <template #qty="{ row }">{{ formatQtyByScale(row.qty, row.qtyScale) }}</template>
        <template #unitCost="{ row }">{{ formatMoney(row.unitCost) }}</template>
        <template #amount="{ row }">{{ formatMoney(rowAmount(row)) }}</template>
        <template #status="{ row }">
          <StatusTag kind="bill" :value="row.status" :status-text="row.statusText" />
        </template>
        <template #operation="{ row }">
          <div class="flex items-center justify-center gap-1">
            <t-tooltip v-if="row.status === 'DRAFT' && checkPermi(['erp:stock:opening'])" content="编辑" placement="top">
              <t-button theme="primary" variant="text" shape="square" size="small" @click="handleEdit(row)">
                <template #icon><Edit1Icon /></template>
              </t-button>
            </t-tooltip>
            <t-tooltip v-if="row.status === 'DRAFT' && checkPermi(['erp:stock:opening'])" content="确认" placement="top">
              <t-button theme="success" variant="text" shape="square" size="small" @click="handleStConfirm(row)">
                <template #icon><CheckCircleIcon /></template>
              </t-button>
            </t-tooltip>
            <t-tooltip v-if="row.status === 'CONFIRMED' && checkPermi(['erp:stock:opening'])" content="反确认" placement="top">
              <t-button theme="warning" variant="text" shape="square" size="small" @click="handleStUnconfirm(row)">
                <template #icon><RollbackIcon /></template>
              </t-button>
            </t-tooltip>
            <t-tooltip v-if="row.status === 'DRAFT' && checkPermi(['erp:stock:opening:remove'])" content="删除" placement="top">
              <t-button theme="danger" variant="text" shape="square" size="small" @click="handleStDelete(row)">
                <template #icon><DeleteIcon /></template>
              </t-button>
            </t-tooltip>
          </div>
        </template>
      </t-table>
      <pagination v-show="stTotal > 0" v-model:page="stPageNum" v-model:limit="stPageSize" :total="stTotal" @pagination="getStList" />
    </div>

    <!-- ==================== 往来期初 ==================== -->
    <div v-show="activeTab === 'finance'" class="erp-surface erp-panel">
      <div class="erp-panel__head">
        <div class="flex flex-wrap items-center gap-2">
          <t-select v-model="finPartyType" :options="partyTypeOptions" style="width: 160px" @change="handleFinQuery" />
          <t-button v-hasPermi="['erp:finance:opening']" theme="primary" variant="outline" @click="handleFinAdd">
            <template #icon><PlusIcon /></template>
            新增期初
          </t-button>
          <t-dropdown>
            <t-button theme="default" variant="outline">
              导入/导出
              <template #suffix><ChevronDownIcon /></template>
            </t-button>
            <template #dropdown>
              <t-dropdown-menu>
                <!-- t-dropdown-item 延迟渲染，v-hasPermi 不生效，需用 checkPermi -->
                <t-dropdown-item v-if="checkPermi(['erp:finance:opening:import'])" @click="handleFinTemplate">
                  <template #prefix-icon><DownloadIcon /></template>
                  下载模板
                </t-dropdown-item>
                <t-dropdown-item v-if="checkPermi(['erp:finance:opening:import'])" @click="handleFinImport">
                  <template #prefix-icon><UploadIcon /></template>
                  导入数据
                </t-dropdown-item>
                <t-dropdown-item v-if="checkPermi(['erp:finance:opening:export'])" @click="handleFinExport">
                  <template #prefix-icon><DownloadIcon /></template>
                  导出数据
                </t-dropdown-item>
              </t-dropdown-menu>
            </template>
          </t-dropdown>
          <span class="erp-text-secondary">期初确认后锁定，需先反确认才能修改</span>
        </div>
        <right-toolbar :search="false" @query-table="getFinList" />
      </div>

      <t-table
        class="erp-table"
        row-key="id"
        :data="finList"
        :columns="finColumns"
        :loading="finLoading"
        :hover="true"
        :resizable="true"
        size="small"
        cell-empty-content="-"
        :empty="finFailed ? '往来期初加载失败，请重试' : '暂无往来期初数据'"
        @column-resize-change="(ctx: any) => saveColumnWidths('opening/finance-list', ctx.columnsWidth)"
      >
        <template #partyType="{ row }">{{ partyTypeLabel(row.partyType) }}</template>
        <template #amount="{ row }">{{ formatMoney(row.amount) }}</template>
        <template #status="{ row }">
          <StatusTag kind="bill" :value="row.status" :status-text="row.statusText" />
        </template>
        <template #operation="{ row }">
          <div class="flex items-center justify-center gap-1">
            <t-tooltip v-if="row.status === 'DRAFT' && checkPermi(['erp:finance:opening'])" content="编辑" placement="top">
              <t-button theme="primary" variant="text" shape="square" size="small" @click="handleFinEdit(row)">
                <template #icon><Edit1Icon /></template>
              </t-button>
            </t-tooltip>
            <t-tooltip v-if="row.status === 'DRAFT' && checkPermi(['erp:finance:opening'])" content="确认" placement="top">
              <t-button theme="success" variant="text" shape="square" size="small" @click="handleFinConfirm(row)">
                <template #icon><CheckCircleIcon /></template>
              </t-button>
            </t-tooltip>
            <t-tooltip v-if="row.status === 'CONFIRMED' && checkPermi(['erp:finance:opening'])" content="反确认" placement="top">
              <t-button theme="warning" variant="text" shape="square" size="small" @click="handleFinUnconfirm(row)">
                <template #icon><RollbackIcon /></template>
              </t-button>
            </t-tooltip>
          </div>
        </template>
      </t-table>
      <pagination v-show="finTotal > 0" v-model:page="finPageNum" v-model:limit="finPageSize" :total="finTotal" @pagination="getFinList" />
    </div>

    <!-- 期初导入（库存 / 往来共用，打开时切换地址） -->
    <t-dialog v-model:visible="upload.open" :header="upload.title" width="420px" attach="body">
      <t-upload
        ref="uploadRef"
        v-model="uploadFiles"
        theme="file"
        draggable
        :max="1"
        accept=".xlsx, .xls"
        :headers="upload.headers"
        :action="upload.url"
        :disabled="upload.isUploading"
        :auto-upload="false"
        @progress="handleFileUploadProgress"
        @success="handleFileSuccess"
        @fail="handleFileError"
      >
        <div class="flex flex-col items-center justify-center py-3">
          <UploadIcon size="32px" />
          <div class="mt-2">将文件拖到此处，或<em>点击上传</em></div>
        </div>
        <template #tips>
          <div class="text-center">
            <span>仅允许导入 xls、xlsx 格式文件，单文件最多 2000 行；导入后仅保存为草稿。</span>
            <t-link theme="primary" :underline="false" style="font-size: 12px; vertical-align: baseline" @click="handleImportTemplate">
              下载模板
            </t-link>
          </div>
        </template>
      </t-upload>
      <template #footer>
        <div class="dialog-footer">
          <t-button theme="primary" :loading="upload.isUploading" @click="submitFileForm">确 定</t-button>
          <t-button theme="default" variant="outline" @click="upload.open = false">取 消</t-button>
        </div>
      </template>
    </t-dialog>

    <!-- 导入结果 -->
    <t-dialog v-model:visible="importResult.visible" header="导入结果" width="620px" attach="body" :destroy-on-close="true">
      <t-descriptions v-if="importResult.data" :column="4" bordered size="small">
        <t-descriptions-item label="总行数">{{ importResult.data.total }}</t-descriptions-item>
        <t-descriptions-item label="成功">{{ importResult.data.success }}</t-descriptions-item>
        <t-descriptions-item label="跳过">{{ importResult.data.skipped }}</t-descriptions-item>
        <t-descriptions-item label="失败">{{ importResult.data.failed }}</t-descriptions-item>
      </t-descriptions>
      <t-alert
        v-if="importResult.data && importResult.data.failed === 0"
        class="mt-3"
        theme="success"
        message="全部数据处理完成（仅保存为草稿，请在列表逐项确认）"
      />
      <t-table
        v-else
        class="erp-table mt-3"
        row-key="row"
        :data="importResult.data?.errors ?? []"
        :columns="importErrorColumns"
        :hover="true"
        :resizable="true"
        size="small"
        max-height="320"
        cell-empty-content="-"
        :empty="'无错误行'"
        @column-resize-change="(ctx: any) => saveColumnWidths('opening/import-errors', ctx.columnsWidth)"
      />
      <template #footer>
        <div class="dialog-footer">
          <t-button theme="primary" @click="importResult.visible = false">关 闭</t-button>
        </div>
      </template>
    </t-dialog>

    <!-- 库存期初表单 -->
    <t-dialog v-model:visible="stDialog.visible" :header="stDialog.title" width="520px" attach="body">
      <t-form ref="stFormRef" :data="stForm" :rules="stRules" label-width="90px">
        <t-form-item label="产品" name="productId">
          <ProductSelect v-model="stForm.productId" show-category-filter placeholder="请选择产品" @change="handleStProductChange" />
        </t-form-item>
        <t-form-item label="仓库" name="warehouseId">
          <ErpPagedSelect
            v-model="stForm.warehouseId"
            placeholder="请选择仓库"
            :fetch-page="warehouseOptions"
            :fetch-by-ids="warehouseOptionsByIds"
            style="width: 100%"
          />
        </t-form-item>
        <t-form-item label="数量" name="qty">
          <!-- 精度随商品切换时强制重建控件，避免沿用上一精度的显示值 -->
          <t-input-number
            :key="`qty-${qtyPrecision(stForm.qtyScale)}`"
            v-model="stForm.qty"
            :decimal-places="qtyPrecision(stForm.qtyScale)"
            :step="qtyStep(stForm.qtyScale)"
            :min="0"
            theme="normal"
            align="right"
            style="width: 100%"
          />
        </t-form-item>
        <t-form-item label="期初成本" name="unitCost">
          <t-input-number v-model="stForm.unitCost" :decimal-places="2" :min="0" theme="normal" align="right" style="width: 100%" />
        </t-form-item>
        <t-alert v-if="stIdentityChanged" theme="warning" class="mb-3" message="修改产品或仓库将新建一条期初记录，原记录保留不变" />
      </t-form>
      <template #footer>
        <div class="dialog-footer">
          <t-button theme="primary" @click="submitSt">确 定</t-button>
          <t-button theme="default" variant="outline" @click="stDialog.visible = false">取 消</t-button>
        </div>
      </template>
    </t-dialog>

    <!-- 往来期初表单 -->
    <t-dialog v-model:visible="finDialog.visible" :header="finDialog.title" width="520px" attach="body">
      <t-form ref="finFormRef" :data="finForm" :rules="finRules" label-width="90px">
        <t-form-item label="往来类型" name="partyType">
          <t-select v-model="finForm.partyType" :options="partyTypeOptions" style="width: 100%" @change="handlePartyTypeChange" />
        </t-form-item>
        <t-form-item label="往来单位" name="partyId">
          <PartnerSelect
            v-model="finForm.partyId"
            :kind="finForm.partyType === 'AP' ? 'supplier' : 'customer'"
            :placeholder="finForm.partyType === 'AP' ? '请选择供应商' : '请选择客户'"
          />
        </t-form-item>
        <t-form-item label="期初金额" name="amount">
          <t-input-number v-model="finForm.amount" large-number :decimal-places="2" :min="0" theme="normal" align="right" style="width: 100%" />
        </t-form-item>
        <t-form-item label="截止日" name="openingDate">
          <t-date-picker
            v-model="finForm.openingDate"
            value-type="YYYY-MM-DD"
            placeholder="期初余额截至哪一天"
            :disable-date="(d: any) => new Date(d).getTime() > Date.now()"
            style="width: 100%"
          />
        </t-form-item>
        <t-form-item label="备注" name="remark">
          <t-textarea v-model="finForm.remark" :autosize="{ minRows: 2 }" placeholder="请输入备注" />
        </t-form-item>
        <t-alert v-if="finIdentityChanged" theme="warning" class="mb-3" message="修改往来类型或单位将新建一条期初记录，原记录保留不变" />
      </t-form>
      <template #footer>
        <div class="dialog-footer">
          <t-button theme="primary" @click="submitFin">确 定</t-button>
          <t-button theme="default" variant="outline" @click="finDialog.visible = false">取 消</t-button>
        </div>
      </template>
    </t-dialog>
  </div>
</template>

<script setup name="ErpOpening" lang="ts">
import { computed, getCurrentInstance, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { confirmFinOpening, listFinOpeningPage, saveFinOpening, unconfirmFinOpening } from '@/api/erp/finance';
import { confirmStOpening, delStOpening, listStOpening, saveStOpening, unconfirmStOpening } from '@/api/erp/stock';
import { warehouseOptions, warehouseOptionsByIds } from '@/api/erp/master';
import type { ErpImportResult } from '@/api/erp/master';
import { ERP_PARTY_TYPE } from '@/api/erp/enums';
import type { ErpPartyType } from '@/api/erp/enums';
import type { ErpAmount, ErpId, ErpProductVO, FinOpeningVO, StOpeningVO } from '@/api/erp/types';
import { formatMoney, formatQtyByScale, normalizeQty, qtyPrecision, qtyStep, roundMoney } from '@/utils/erp/calc';
import { checkPermi } from '@/utils/permission';
import { globalHeaders } from '@/utils/request';
import { sanitizeImportMessage } from '@/utils/sanitizeImportMessage';
// 列宽记忆：拖拽后按表名存 localStorage，进页面还原（见 hooks/useTableColumnWidth）
import { mergeSavedWidths, saveColumnWidths } from '@/hooks/useTableColumnWidth';
import PartnerSelect from '@/components/erp/PartnerSelect.vue';
import ProductSelect from '@/components/erp/ProductSelect.vue';
import StatusTag from '@/components/erp/StatusTag.vue';
import type { SuccessContext, UploadFailContext, UploadFile, UploadInstanceFunctions } from 'tdesign-vue-next';
import ErpPagedSelect from '@/components/erp/ErpPagedSelect.vue';
import { CheckCircleIcon, ChevronDownIcon, DeleteIcon, DownloadIcon, Edit1Icon, PlusIcon, RollbackIcon, UploadIcon } from 'tdesign-icons-vue-next';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

/** 主页签：库存期初 / 往来期初 */
const activeTab = ref<'stock' | 'finance'>('stock');

/** 库存期初 */
const stList = ref<StOpeningVO[]>([]);
const stLoading = ref(false);
const stFailed = ref(false);
const stTotal = ref(0);
const stPageNum = ref(1);
const stPageSize = ref(20);
/** 已换 t-form：ref 提供 clearValidate()/validate() */
const stFormRef = ref<any>();
/** 库存期初仓库筛选（同时作为导出参数） */
const stWarehouseId = ref<ErpId>();
/** 往来类型下拉：应收（客户）/ 应付（供应商） */
const partyTypeOptions = [
  { label: '应收（客户）', value: 'AR' },
  { label: '应付（供应商）', value: 'AP' }
];

/** 库存期初列表列配置：t-table 用 columns 数组，插槽名与 cell 字符串同名（DOC/35 §5） */
const stColumns: any[] = [
  { colKey: 'productCode', title: '产品编码', align: 'center', width: 130, ellipsis: true },
  { colKey: 'productName', title: '产品名称', align: 'center', minWidth: 160, ellipsis: true },
  { colKey: 'warehouseId', title: '仓库', align: 'center', minWidth: 120, ellipsis: true, cell: 'warehouseId' },
  { colKey: 'qty', title: '数量', align: 'right', width: 110, cell: 'qty' },
  { colKey: 'unitCost', title: '期初成本', align: 'right', width: 110, cell: 'unitCost' },
  { colKey: 'amount', title: '金额', align: 'right', width: 120, cell: 'amount' },
  { colKey: 'status', title: '状态', align: 'center', width: 90, cell: 'status' },
  { colKey: 'operation', title: '操作', align: 'center', width: 170, cell: 'operation' }
];
mergeSavedWidths('opening/stock-list', stColumns);

/** 往来期初列表列配置 */
const finColumns: any[] = [
  { colKey: 'partyType', title: '往来类型', align: 'center', width: 100, cell: 'partyType' },
  { colKey: 'partyName', title: '往来单位', align: 'center', minWidth: 180, ellipsis: true },
  { colKey: 'amount', title: '期初金额', align: 'right', width: 140, cell: 'amount' },
  { colKey: 'openingDate', title: '截止日', align: 'center', width: 120 },
  { colKey: 'status', title: '状态', align: 'center', width: 90, cell: 'status' },
  { colKey: 'remark', title: '备注', align: 'center', minWidth: 160, ellipsis: true },
  { colKey: 'operation', title: '操作', align: 'center', width: 170, cell: 'operation' }
];
mergeSavedWidths('opening/finance-list', finColumns);

/** 导入失败行列表（只读展示） */
const importErrorColumns: any[] = [
  { colKey: 'row', title: '行号', align: 'center', width: 90 },
  { colKey: 'message', title: '失败原因', minWidth: 380, ellipsis: true }
];
mergeSavedWidths('opening/import-errors', importErrorColumns);

const stDialog = reactive<DialogOption>({
  visible: false,
  title: ''
});

interface StFormState {
  /** 编辑时的原产品（upsert 唯一键：产品 + 仓库） */
  originProductId?: ErpId;
  originWarehouseId?: ErpId;
  productId?: ErpId;
  warehouseId?: ErpId;
  qty: number;
  /** 数量精度 0~3（商品级，DOC/26 §10） */
  qtyScale?: number;
  unitCost: number;
}

const createStForm = (): StFormState => ({
  originProductId: undefined,
  originWarehouseId: undefined,
  productId: undefined,
  warehouseId: undefined,
  qty: 0,
  qtyScale: undefined,
  unitCost: 0
});

const stForm = reactive<StFormState>(createStForm());

// t-form 的 rules 形状与 EP 的 ElFormRules 不兼容（DOC/35 §5）
const stRules: Record<string, any> = {
  productId: [{ required: true, message: '产品不能为空', trigger: 'change' }],
  warehouseId: [{ required: true, message: '仓库不能为空', trigger: 'change' }],
  qty: [{ required: true, message: '数量不能为空', trigger: 'blur' }],
  unitCost: [{ required: true, message: '期初成本不能为空', trigger: 'blur' }]
};

/** 产品或仓库变更将产生新记录 */
const stIdentityChanged = computed(
  () =>
    stForm.originProductId !== undefined &&
    (String(stForm.productId ?? '') !== String(stForm.originProductId) || String(stForm.warehouseId ?? '') !== String(stForm.originWarehouseId))
);

/** 往来期初 */
const finList = ref<FinOpeningVO[]>([]);
const finLoading = ref(false);
const finFailed = ref(false);
const finTotal = ref(0);
const finPageNum = ref(1);
const finPageSize = ref(20);
let finRequest = 0;
/** 已换 t-form：ref 提供 clearValidate()/validate() */
const finFormRef = ref<any>();
/** 往来类型筛选 */
const finPartyType = ref<ErpPartyType>('AR');

const finDialog = reactive<DialogOption>({
  visible: false,
  title: ''
});

interface FinFormState {
  /** 编辑时的原往来（upsert 唯一键：类型 + 单位） */
  originPartyType?: ErpPartyType;
  originPartyId?: ErpId;
  partyType: ErpPartyType;
  partyId?: ErpId;
  amount: ErpAmount;
  /** 期初截止日 yyyy-MM-dd（含当日）；必须由用户显式选择。 */
  openingDate: string;
  remark: string;
}

const createFinForm = (): FinFormState => ({
  originPartyType: undefined,
  originPartyId: undefined,
  partyType: 'AR',
  partyId: undefined,
  amount: 0,
  openingDate: '',
  remark: ''
});

const finForm = reactive<FinFormState>(createFinForm());

const finRules: Record<string, any> = {
  partyType: [{ required: true, message: '往来类型不能为空', trigger: 'change' }],
  partyId: [{ required: true, message: '往来单位不能为空', trigger: 'change' }],
  amount: [{ required: true, message: '期初金额不能为空', trigger: 'blur' }],
  openingDate: [{ required: true, message: '期初截止日不能为空', trigger: 'change' }]
};

/** 往来类型或单位变更将产生新记录 */
const finIdentityChanged = computed(
  () =>
    finForm.originPartyType !== undefined &&
    (finForm.partyType !== finForm.originPartyType || String(finForm.partyId ?? '') !== String(finForm.originPartyId))
);

const num = (value: ErpAmount | undefined | null): number => {
  const n = Number(value ?? 0);
  return Number.isFinite(n) ? n : 0;
};

/** 新列表行直接由后端联查仓库名称，不用首屏 options 猜测。 */
const warehouseName = (row: StOpeningVO): string => row.warehouseName || row.warehouseCode || String(row.warehouseId);

/** 往来类型中文 */
const partyTypeLabel = (partyType?: string): string => {
  if (!partyType) return '-';
  return ERP_PARTY_TYPE[partyType as ErpPartyType]?.label ?? partyType;
};

/** 期初金额 = 数量 × 单位成本 */
const rowAmount = (row: StOpeningVO): number => roundMoney(num(row.qty) * num(row.unitCost));

/** 业务失败兜底提示（拦截器已弹出后端 msg，这里补充同义文案确保用户可见） */
const showBizError = (fallback: string) => {
  proxy?.$modal.msgWarning(fallback);
};

/* ------------------------------ 库存期初 ------------------------------ */

/** 库存期初列表按后端真实总数翻页，筛选改变回第一页。 */
let stRequest = 0;
const getStList = async () => {
  const seq = ++stRequest;
  stLoading.value = true;
  stFailed.value = false;
  try {
    const res = await listStOpening({ pageNum: stPageNum.value, pageSize: stPageSize.value, warehouseId: stWarehouseId.value || undefined });
    if (seq !== stRequest) return;
    stList.value = res.rows;
    stTotal.value = res.total;
    // 删除了末页唯一一条记录时回到最后一个有效页。
    const lastPage = Math.max(1, Math.ceil(res.total / stPageSize.value));
    if (stPageNum.value > lastPage) {
      stPageNum.value = lastPage;
      await getStList();
    }
  } catch {
    if (seq !== stRequest) return;
    stList.value = [];
    stTotal.value = 0;
    stFailed.value = true;
  } finally {
    if (seq === stRequest) stLoading.value = false;
  }
};

const handleStQuery = () => {
  stPageNum.value = 1;
  void getStList();
};

/** 新增期初 */
const handleAdd = async () => {
  Object.assign(stForm, createStForm());
  stFormRef.value?.clearValidate();
  stDialog.visible = true;
  stDialog.title = '新增库存期初';
};

/** 选品后带出默认成本：优先采购价（>0 才算维护过），否则回退最近入库成本 */
const handleStProductChange = (product?: ErpProductVO) => {
  if (!product) {
    stForm.qtyScale = undefined;
    return;
  }
  // purchase_price 是非空列（默认 0），不能用 null 判断；采购价为 0 时回退到最近入库成本
  const purchase = num(product.purchasePrice);
  stForm.unitCost = purchase > 0 ? purchase : num(product.costPrice);
  stForm.qtyScale = product.qtyScale;
  // 精度随商品切换时把已录数量归一化，避免小数商品的数量挂到整数商品行上（t-input-number 只改显示不回写 model）
  stForm.qty = normalizeQty(stForm.qty, product.qtyScale);
};

/** 编辑期初 */
const handleEdit = async (row: StOpeningVO) => {
  Object.assign(stForm, createStForm());
  Object.assign(stForm, {
    originProductId: row.productId,
    originWarehouseId: row.warehouseId,
    productId: row.productId,
    warehouseId: row.warehouseId,
    qty: num(row.qty),
    qtyScale: row.qtyScale,
    unitCost: num(row.unitCost)
  });
  stFormRef.value?.clearValidate();
  stDialog.visible = true;
  stDialog.title = '编辑库存期初';
};

/** 提交库存期初 */
const submitSt = async () => {
  // t-form 校验失败 resolve(false) 而非 reject，ref 未挂载时为 undefined
  const result = await stFormRef.value?.validate().catch(() => false);
  const valid = result === true || result === undefined;
  if (!valid) return;
  if (stIdentityChanged.value) {
    try {
      await proxy?.$modal.confirm('修改产品或仓库将新建一条期初记录，原记录保留不变，是否继续？');
    } catch {
      return;
    }
  }
  try {
    await saveStOpening({
      productId: stForm.productId,
      warehouseId: stForm.warehouseId,
      qty: num(stForm.qty),
      unitCost: num(stForm.unitCost)
    });
  } catch {
    showBizError('保存失败：期初已确认锁定，请先反确认');
    return;
  }
  proxy?.$modal.msgSuccess('已保存');
  stDialog.visible = false;
  await getStList();
};

/** 确认库存期初 */
const handleStConfirm = async (row: StOpeningVO) => {
  try {
    await proxy?.$modal.confirm(
      `是否确认产品「${row.productName || row.productCode || ''}」在「${warehouseName(row)}」的库存期初？确认后写入期初入库流水并锁定。`
    );
  } catch {
    return;
  }
  try {
    await confirmStOpening(row.id);
    proxy?.$modal.msgSuccess('已确认');
  } catch {
    showBizError('确认失败：期初已确认锁定，请先反确认');
    return;
  }
  await getStList();
};

/** 反确认库存期初 */
const handleStUnconfirm = async (row: StOpeningVO) => {
  try {
    await proxy?.$modal.confirm(
      `是否反确认产品「${row.productName || row.productCode || ''}」在「${warehouseName(row)}」的库存期初？反确认后回退库存并解锁。`
    );
  } catch {
    return;
  }
  try {
    await unconfirmStOpening(row.id);
    proxy?.$modal.msgSuccess('已反确认');
  } catch {
    showBizError('反确认失败：期初已确认锁定，请先反确认');
    return;
  }
  await getStList();
};

/** 删除草稿库存期初：仅草稿可删，后端逻辑删除，不影响库存与成本 */
const handleStDelete = async (row: StOpeningVO) => {
  try {
    await proxy?.$modal.confirm(
      `是否删除产品「${row.productName || row.productCode || ''}」在「${warehouseName(row)}」的库存期初草稿？删除后不可恢复。`
    );
  } catch {
    return;
  }
  await delStOpening(row.id);
  proxy?.$modal.msgSuccess('删除成功');
  await getStList();
};

/* ------------------------------ 往来期初 ------------------------------ */

/** 加载数据库往来期初页；真实 total 不从当页长度推导。 */
const getFinList = async () => {
  const requestId = ++finRequest;
  finLoading.value = true;
  finFailed.value = false;
  finList.value = [];
  try {
    const res = await listFinOpeningPage({ partyType: finPartyType.value, pageNum: finPageNum.value, pageSize: finPageSize.value });
    if (requestId !== finRequest) return;
    finList.value = res.rows;
    finTotal.value = Number(res.total);
  } catch {
    if (requestId !== finRequest) return;
    finList.value = [];
    finTotal.value = 0;
    finFailed.value = true;
  } finally {
    if (requestId === finRequest) finLoading.value = false;
  }
};

/** 类型筛选复位财务页码，不改变库存期初的筛选或页码。 */
const handleFinQuery = () => {
  finPageNum.value = 1;
  getFinList();
};

/** 切换往来类型时清空已选单位 */
const handlePartyTypeChange = () => {
  finForm.partyId = undefined;
};

/** 新增往来期初 */
const handleFinAdd = async () => {
  Object.assign(finForm, createFinForm());
  finForm.partyType = finPartyType.value;
  finFormRef.value?.clearValidate();
  finDialog.visible = true;
  finDialog.title = '新增往来期初';
};

/** 编辑往来期初 */
const handleFinEdit = async (row: FinOpeningVO) => {
  Object.assign(finForm, createFinForm());
  Object.assign(finForm, {
    originPartyType: row.partyType,
    originPartyId: row.partyId,
    partyType: row.partyType,
    partyId: row.partyId,
    amount: row.amount,
    openingDate: row.openingDate || '',
    remark: row.remark || ''
  });
  finFormRef.value?.clearValidate();
  finDialog.visible = true;
  finDialog.title = '编辑往来期初';
};

/** 提交往来期初 */
const submitFin = async () => {
  // t-form 校验失败 resolve(false) 而非 reject，ref 未挂载时为 undefined
  const result = await finFormRef.value?.validate().catch(() => false);
  const valid = result === true || result === undefined;
  if (!valid) return;
  if (!/^\d{4}-\d{2}-\d{2}$/.test(finForm.openingDate)) {
    proxy?.$modal.msgWarning('请明确选择期初截止日；历史无日期记录不会自动回填');
    return;
  }
  if (finIdentityChanged.value) {
    try {
      await proxy?.$modal.confirm('修改往来类型或单位将新建一条期初记录，原记录保留不变，是否继续？');
    } catch {
      return;
    }
  }
  let saved: FinOpeningVO | undefined;
  try {
    const res = await saveFinOpening({
      partyType: finForm.partyType,
      partyId: finForm.partyId!,
      amount: finForm.amount,
      openingDate: finForm.openingDate,
      remark: finForm.remark
    });
    saved = res.data;
  } catch {
    showBizError('保存失败：期初已确认锁定，请先反确认');
    return;
  }
  // 同一往来单位只有一条期初：仅在新记录撞到已有草稿时提示覆盖，编辑原记录不提示
  const writesNewRecord = finForm.originPartyId === undefined || finIdentityChanged.value;
  proxy?.$modal.msgSuccess(writesNewRecord && saved?.overwritten ? '已保存，并覆盖了该单位原有期初' : '已保存');
  finDialog.visible = false;
  await getFinList();
};

/** 确认往来期初 */
const handleFinConfirm = async (row: FinOpeningVO) => {
  if (!row.openingDate) {
    proxy?.$modal.msgWarning('请先编辑并明确选择期初截止日，再确认；历史无日期不会自动回填');
    return;
  }
  try {
    await proxy?.$modal.confirm(
      `是否确认「${partyTypeLabel(row.partyType)} - ${row.partyName ?? ''}」截至 ${row.openingDate ?? '—'} 的期初金额 ${formatMoney(row.amount)}？确认后写入应收应付台账并锁定。`
    );
  } catch {
    return;
  }
  try {
    await confirmFinOpening(row.id);
    proxy?.$modal.msgSuccess('已确认');
  } catch {
    showBizError('确认失败：期初已确认锁定，请先反确认');
    return;
  }
  await getFinList();
};

/** 反确认往来期初 */
const handleFinUnconfirm = async (row: FinOpeningVO) => {
  try {
    await proxy?.$modal.confirm(`是否反确认「${partyTypeLabel(row.partyType)} - ${row.partyName ?? ''}」的期初？反确认后冲销台账并解锁。`);
  } catch {
    return;
  }
  try {
    await unconfirmFinOpening(row.id);
    proxy?.$modal.msgSuccess('已反确认');
  } catch {
    showBizError('已核销的应收应付需先反核销');
    return;
  }
  await getFinList();
};

/** 导入上传响应（后端 R<ImportResult>） */
interface ImportUploadResponse {
  code: number;
  msg: string;
  data?: ErpImportResult;
}

type OpeningImportTarget = 'stock' | 'finance';

const uploadRef = ref<UploadInstanceFunctions>();
/** t-upload 受控文件列表：成功后清空，保持「一次只导一个文件」的既有行为 */
const uploadFiles = ref<UploadFile[]>([]);
const uploadTarget = ref<OpeningImportTarget>('stock');
const upload = reactive<ImportOption>({
  open: false,
  title: '',
  isUploading: false,
  updateSupport: 0,
  headers: globalHeaders(),
  url: ''
});
const importResult = reactive<{ visible: boolean; data?: ErpImportResult }>({
  visible: false,
  data: undefined
});

/** 打开导入弹窗（按目标切换接口地址） */
const openImport = (target: OpeningImportTarget) => {
  uploadTarget.value = target;
  upload.title = target === 'stock' ? '库存期初导入' : '往来期初导入';
  upload.url = import.meta.env.VITE_APP_BASE_API + (target === 'stock' ? '/erp/stock/opening/importData' : '/erp/finance/openings/importData');
  upload.isUploading = false;
  upload.open = true;
};

const handleStImport = () => openImport('stock');
const handleFinImport = () => openImport('finance');

/** 库存期初导出（带 warehouseId） */
const handleStExport = () => {
  proxy?.download('/erp/stock/opening/export', { warehouseId: stWarehouseId.value || undefined }, `stock_opening_${Date.now()}.xlsx`);
};

/** 往来期初导出（带 partyType） */
const handleFinExport = () => {
  proxy?.download('/erp/finance/openings/export', { partyType: finPartyType.value }, `fin_opening_${finPartyType.value}_${Date.now()}.xlsx`);
};

/** 工具栏下载模板 */
const handleStTemplate = () => {
  proxy?.download('/erp/stock/opening/importTemplate', {}, `stock_opening_template_${Date.now()}.xlsx`);
};

const handleFinTemplate = () => {
  proxy?.download('/erp/finance/openings/importTemplate', {}, `fin_opening_template_${Date.now()}.xlsx`);
};

/** 导入弹窗内的下载模板，按当前导入目标选择 */
const handleImportTemplate = () => {
  if (uploadTarget.value === 'stock') {
    handleStTemplate();
  } else {
    handleFinTemplate();
  }
};

const handleFileUploadProgress = () => {
  upload.isUploading = true;
};

/** 上传成功：TDesign 从 context.response 取后端响应体（原 EP 直接回传 response） */
const handleFileSuccess = (context: SuccessContext) => {
  const target = uploadTarget.value;
  const response = (context.response ?? {}) as ImportUploadResponse;
  upload.isUploading = false;
  upload.open = false;
  uploadFiles.value = [];
  if (response.code !== 200 || !response.data) {
    proxy?.$modal.msgError(sanitizeImportMessage(response.msg) || '导入失败，请检查文件内容');
    return;
  }
  importResult.data = {
    ...response.data,
    errors: response.data.errors?.map((error) => ({ ...error, message: sanitizeImportMessage(error.message) })) ?? []
  };
  importResult.visible = true;
  if (target === 'stock') {
    getStList();
  } else {
    getFinList();
  }
};

/** 上传失败：TDesign 从 context.response 取后端响应体，取不到时用网络失败兜底文案 */
const handleFileError = (context: UploadFailContext) => {
  upload.isUploading = false;
  const response = (context?.response ?? {}) as ImportUploadResponse;
  proxy?.$modal.msgError(sanitizeImportMessage(response.msg) || '导入失败，请检查网络或文件');
};

const submitFileForm = () => {
  uploadRef.value?.uploadFiles();
};

onMounted(() => {
  getStList();
  getFinList();
});
onBeforeUnmount(() => {
  ++finRequest;
});
</script>
