<template>
  <div class="p-2">
    <t-row :gutter="20">
      <t-col :span="12">
        <transition :enter-active-class="proxy?.animate.searchAnimate.enter" :leave-active-class="proxy?.animate.searchAnimate.leave">
          <div v-show="showSearch" class="erp-row-container">
            <t-card :bordered="false" class="erp-filter-card">
              <t-form ref="queryFormRef" :data="queryParams" class="erp-filter-row" label-align="right" :label-width="'auto'" @submit="handleQuery">
                <t-form-item label="关键字" name="keyword">
                  <t-input v-model="queryParams.keyword" placeholder="编码/名称/规格" clearable class="query-w-md" @enter="handleQuery" />
                </t-form-item>
                <t-form-item label="商品分类" name="categoryId">
                  <ErpPagedSelect
                    v-model="queryParams.categoryId"
                    placeholder="全部分类"
                    class="query-w-md"
                    :fetch-page="productCategoryOptions"
                    :fetch-by-ids="productCategoryOptionsByIds"
                    :label="nameLabel"
                  />
                </t-form-item>
                <t-form-item label="所属供应商" name="supplierId">
                  <ErpPagedSelect
                    v-model="queryParams.supplierId"
                    placeholder="全部供应商"
                    class="query-w-md"
                    :fetch-page="supplierOptions"
                    :fetch-by-ids="supplierOptionsByIds"
                    :label="nameLabel"
                  />
                </t-form-item>
                <t-form-item label="类型" name="type">
                  <t-select v-model="queryParams.type" placeholder="产品类型" clearable class="query-w-sm" :options="productTypeOptions" />
                </t-form-item>
                <t-form-item label="状态" name="status">
                  <t-select v-model="queryParams.status" placeholder="产品状态" clearable class="query-w-sm" :options="productStatusOptions" />
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
            <div class="flex flex-wrap items-center gap-2">
              <t-button v-hasPermi="['erp:master:product:add']" theme="primary" variant="outline" @click="handleAdd">
                <template #icon><PlusIcon /></template>
                新增
              </t-button>
              <t-button v-hasPermi="['erp:master:product:edit']" theme="success" variant="outline" :disabled="single" @click="handleUpdate()">
                <template #icon><Edit1Icon /></template>
                修改
              </t-button>
              <t-button v-hasPermi="['erp:master:product:remove']" theme="danger" variant="outline" :disabled="multiple" @click="handleDelete()">
                <template #icon><DeleteIcon /></template>
                删除
              </t-button>
              <t-button v-hasPermi="['erp:product-category:list']" theme="default" variant="outline" @click="openCategoryDialog">
                <template #icon><FolderIcon /></template>
                分类维护
              </t-button>
              <t-button v-if="listMode === 'grouped'" theme="default" variant="outline" @click="handleToggleExpandAll">
                <template #icon><ChevronDownDoubleIcon /></template>
                {{ expandAll ? '收起全部' : '展开全部' }}
              </t-button>
              <t-dropdown>
                <t-button theme="default" variant="outline">
                  导入/导出
                  <template #suffix><ChevronDownIcon /></template>
                </t-button>
                <template #dropdown>
                  <!-- t-dropdown-item 的权限判断：组件内容延迟渲染，v-hasPermi 不生效，需用 checkPermi -->
                  <t-dropdown-item v-if="checkPermi(['erp:master:product:import'])" @click="importTemplate">
                    <template #prefix-icon><DownloadIcon /></template>
                    下载模板
                  </t-dropdown-item>
                  <t-dropdown-item v-if="checkPermi(['erp:master:product:import'])" @click="handleImport">
                    <template #prefix-icon><UploadIcon /></template>
                    导入数据
                  </t-dropdown-item>
                  <t-dropdown-item v-if="checkPermi(['erp:master:product:export'])" @click="handleExport">
                    <template #prefix-icon><DownloadIcon /></template>
                    导出数据
                  </t-dropdown-item>
                </template>
              </t-dropdown>
            </div>
            <right-toolbar v-model:show-search="showSearch" @query-table="getList"></right-toolbar>
          </div>

          <t-alert
            v-if="listMode === 'paged'"
            class="mb-2"
            theme="info"
            :close="false"
            :message="`商品超过 ${GROUP_LIMIT} 条，已切换为普通分页列表（不分组）`"
          />

          <EnhancedTable
            class="erp-table"
            row-key="id"
            :data="tableData"
            :columns="listColumns"
            :loading="loading"
            :hover="true"
            :resizable="true"
            bordered
            size="small"
            :cell-empty-content="(_h: any, params: any) => (params?.row?.isGroup ? '' : '-')"
            :tree="{ childrenKey: 'children', checkStrictly: true }"
            :expanded-tree-nodes="expandedTreeKeys"
            :selected-row-keys="ids"
            @select-change="handleSelectionChange"
            @expanded-tree-nodes-change="(keys: Array<string | number>) => (expandedTreeKeys = keys)"
            @column-resize-change="(ctx: any) => saveColumnWidths('master-product/list', ctx.columnsWidth)"
          >
            <template #name="{ row }">
              <template v-if="row.isGroup">
                <span class="font-bold">{{ row.categoryName }}</span>
                <t-tag class="ml-2" size="small" theme="default">{{ row.productCount }}</t-tag>
              </template>
              <template v-else>{{ row.name }}</template>
            </template>
            <template #code="{ row }">{{ row.isGroup ? '' : row.code }}</template>
            <template #type="{ row }">
              <StatusTag v-if="!row.isGroup" kind="productType" :value="row.type" />
            </template>
            <template #categoryName="{ row }">{{ row.categoryName || '未分类' }}</template>
            <template #qtyScale="{ row }">{{ row.isGroup ? '' : qtyScaleText(row.qtyScale) }}</template>
            <template #costPrice="{ row }">{{ row.isGroup ? '' : formatMoney(row.costPrice) }}</template>
            <template #purchasePrice="{ row }">{{ row.isGroup ? '' : formatMoney(row.purchasePrice) }}</template>
            <template #freight="{ row }">{{ row.isGroup ? '' : formatMoney(row.freight) }}</template>
            <template #taxType="{ row }">
              <StatusTag v-if="!row.isGroup" kind="tax" :value="row.taxType" />
            </template>
            <template #status="{ row }">
              <t-tag v-if="!row.isGroup" :theme="row.status === 1 ? 'success' : 'default'" size="small">
                {{ row.status === 1 ? '启用' : '停用' }}
              </t-tag>
            </template>
            <template #operation="{ row }">
              <div v-if="!row.isGroup" class="flex items-center justify-center gap-1">
                <t-tooltip v-if="row.type === 'PRODUCT' && checkPermi(['erp:master:product:edit'])" content="配比" placement="top">
                  <t-button theme="primary" variant="text" shape="square" size="small" @click="handleRecipe(row)">
                    <template #icon><LayersIcon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="checkPermi(['erp:master:product:edit'])" content="修改" placement="top">
                  <t-button theme="primary" variant="text" shape="square" size="small" @click="handleUpdate(row)">
                    <template #icon><Edit1Icon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="checkPermi(['erp:master:product:remove'])" content="删除" placement="top">
                  <t-button theme="primary" variant="text" shape="square" size="small" @click="handleDelete(row)">
                    <template #icon><DeleteIcon /></template>
                  </t-button>
                </t-tooltip>
              </div>
            </template>
          </EnhancedTable>

          <pagination
            v-show="listMode === 'paged' && total > 0"
            v-model:page="queryParams.pageNum"
            v-model:limit="queryParams.pageSize"
            :total="total"
            @pagination="getList"
          />
        </div>

        <!-- 产品导入 -->
        <t-dialog v-model:visible="upload.open" :header="upload.title" width="420px" attach="body">
          <t-upload
            ref="uploadRef"
            v-model:files="upload.files"
            theme="file"
            draggable
            :max="1"
            accept=".xlsx, .xls"
            :headers="upload.headers"
            :action="upload.url"
            :data="{ updateSupport: upload.updateSupport }"
            :disabled="upload.isUploading"
            :auto-upload="false"
            :on-progress="handleFileUploadProgress"
            :on-success="handleFileSuccess"
            :on-fail="handleFileError"
          >
            <!-- 空文件态沿用原拖拽区文案：t-upload 默认走 locale 文案，这里用默认插槽还原 -->
            <template #default>
              <div class="flex flex-col items-center gap-1">
                <UploadIcon size="24px" />
                <span>将文件拖到此处，或<em>点击上传</em></span>
              </div>
            </template>
          </t-upload>
          <div class="mt-2 text-center">
            <div>
              <t-checkbox :checked="upload.updateSupport === 1" @change="(checked: boolean) => (upload.updateSupport = checked ? 1 : 0)">
                是否更新已存在的数据
              </t-checkbox>
            </div>
            <span class="erp-text-secondary">仅允许导入 xls、xlsx 格式文件，单文件最多 2000 行。</span>
            <div>
              <t-link theme="primary" :underline="false" class="text-xs" @click="importTemplate">下载模板</t-link>
            </div>
          </div>
          <template #footer>
            <div class="flex items-center justify-end gap-2">
              <t-button theme="primary" :loading="upload.isUploading" @click="submitFileForm">确 定</t-button>
              <t-button theme="default" variant="outline" @click="upload.open = false">取 消</t-button>
            </div>
          </template>
        </t-dialog>

        <!-- 导入结果 -->
        <t-dialog v-model:visible="importResult.visible" header="导入结果" width="620px" attach="body">
          <t-descriptions v-if="importResult.data" :column="4" bordered size="small">
            <t-descriptions-item label="总行数">{{ importResult.data.total }}</t-descriptions-item>
            <t-descriptions-item label="成功">{{ importResult.data.success }}</t-descriptions-item>
            <t-descriptions-item label="跳过">{{ importResult.data.skipped }}</t-descriptions-item>
            <t-descriptions-item label="失败">{{ importResult.data.failed }}</t-descriptions-item>
          </t-descriptions>
          <t-alert v-if="importResult.data && importResult.data.failed === 0" class="mt-3" theme="success" :close="false" title="全部数据处理完成" />
          <t-table
            v-else
            class="mt-3 erp-table"
            row-key="row"
            :data="importResult.data?.errors ?? []"
            :columns="importErrorColumns"
            :resizable="true"
            bordered
            size="small"
            max-height="320"
            cell-empty-content="-"
            :empty="'无错误行'"
            @column-resize-change="(ctx: any) => saveColumnWidths('master-product/import-errors', ctx.columnsWidth)"
          />
          <template #footer>
            <div class="flex items-center justify-end">
              <t-button theme="primary" @click="importResult.visible = false">关 闭</t-button>
            </div>
          </template>
        </t-dialog>

        <!-- 商品分类维护（内嵌商品页，DOC/26 §4.2） -->
        <t-dialog v-model:visible="categoryDialog.visible" header="商品分类维护" width="760px" attach="body">
          <t-form :data="categoryForm" layout="inline" class="mb-2">
            <t-form-item label="分类名称" name="name" required-mark>
              <t-input v-model="categoryForm.name" placeholder="请输入分类名称" style="width: 160px" />
            </t-form-item>
            <t-form-item label="排序" name="sort">
              <t-input-number v-model="categoryForm.sort" :min="0" theme="normal" align="right" style="width: 80px" />
            </t-form-item>
            <t-form-item label="状态" name="status">
              <t-select v-model="categoryForm.status" style="width: 100px" :options="productStatusOptions" />
            </t-form-item>
            <t-form-item>
              <div class="flex items-center gap-2">
                <t-button theme="primary" variant="outline" @click="submitCategory">
                  <template #icon><CheckIcon /></template>
                  {{ categoryForm.id ? '保存' : '新增' }}
                </t-button>
                <t-button theme="default" variant="outline" @click="resetCategoryForm">
                  <template #icon><RefreshIcon /></template>
                  取消编辑
                </t-button>
              </div>
            </t-form-item>
          </t-form>
          <t-table
            class="erp-table"
            row-key="id"
            :data="categoryList"
            :columns="categoryColumns"
            :loading="categoryLoading"
            :resizable="true"
            bordered
            size="small"
            max-height="360"
            cell-empty-content="-"
            @column-resize-change="(ctx: any) => saveColumnWidths('master-product/category', ctx.columnsWidth)"
          >
            <template #status="{ row }">
              <t-tag :theme="row.status === 1 ? 'success' : 'default'" size="small">
                {{ row.status === 1 ? '启用' : '停用' }}
              </t-tag>
            </template>
            <template #operation="{ row }">
              <div class="flex items-center justify-center gap-1">
                <t-button
                  v-hasPermi="['erp:product-category:edit']"
                  theme="primary"
                  variant="text"
                  shape="square"
                  size="small"
                  @click="editCategory(row)"
                >
                  <template #icon><Edit1Icon /></template>
                </t-button>
                <t-button
                  v-hasPermi="['erp:product-category:remove']"
                  theme="primary"
                  variant="text"
                  shape="square"
                  size="small"
                  @click="removeCategory(row)"
                >
                  <template #icon><DeleteIcon /></template>
                </t-button>
              </div>
            </template>
          </t-table>
          <template #footer>
            <div class="flex items-center justify-end">
              <t-button theme="default" variant="outline" @click="categoryDialog.visible = false">关 闭</t-button>
            </div>
          </template>
        </t-dialog>

        <t-drawer v-model:visible="dialog.visible" :header="dialog.title" size="720px" attach="body">
          <t-form ref="productFormRef" :data="form" :rules="productRules" label-align="top">
            <ProductFormFields v-model="form" active-category-only show-add-category @add-category="openCategoryDialog" />
          </t-form>
          <template #footer>
            <div class="flex items-center justify-end gap-2">
              <t-button theme="default" variant="outline" @click="cancel">取 消</t-button>
              <t-button theme="primary" @click="submitForm">确 定</t-button>
            </div>
          </template>
        </t-drawer>

        <t-dialog v-model:visible="recipeDialog.visible" :header="recipeDialog.title" width="780px" attach="body">
          <t-table
            class="erp-table"
            row-key="__key"
            :data="recipeRows"
            :columns="recipeColumns"
            :resizable="true"
            bordered
            size="small"
            cell-empty-content="-"
            :empty="'暂无配比，请点击新增行'"
            @column-resize-change="(ctx: any) => saveColumnWidths('master-product/recipe', ctx.columnsWidth)"
          >
            <template #materialId="{ row }">
              <ProductSelect v-model="row.materialId" type="MATERIAL" show-category-filter placeholder="请选择原料" dialog-title="选择原料" />
            </template>
            <template #qtyPerUnit="{ row }">
              <t-input-number v-model="row.qtyPerUnit" :decimal-places="3" :min="0" theme="normal" align="right" style="width: 100%" />
            </template>
            <template #stock="{ row }">{{ getStockText(row.materialId) }}</template>
            <template #rowOperation="{ rowIndex }">
              <t-button theme="danger" variant="text" shape="square" size="small" @click="removeRecipeRow(rowIndex)">
                <template #icon><DeleteIcon /></template>
              </t-button>
            </template>
          </t-table>
          <div class="mt-2">
            <t-button theme="primary" variant="outline" @click="addRecipeRow">
              <template #icon><PlusIcon /></template>
              新增行
            </t-button>
          </div>
          <template #footer>
            <div class="flex items-center justify-end gap-2">
              <t-button theme="primary" @click="submitRecipe">确 定</t-button>
              <t-button theme="default" variant="outline" @click="recipeDialog.visible = false">取 消</t-button>
            </div>
          </template>
        </t-dialog>
      </t-col>
    </t-row>
  </div>
</template>

<script setup name="ErpProduct" lang="ts">
import {
  listProduct,
  getProduct,
  addProduct,
  updateProduct,
  delProduct,
  getRecipe,
  saveRecipe,
  listAllProductCategories,
  productCategoryOptions,
  productCategoryOptionsByIds,
  addProductCategory,
  updateProductCategory,
  delProductCategory,
  supplierOptions,
  supplierOptionsByIds
} from '@/api/erp/master';
import type { ErpImportResult } from '@/api/erp/master';
import { listStockQuantities } from '@/api/erp/stock';
import { ERP_PRODUCT_TYPE, ERP_QTY_SCALE_OPTIONS } from '@/api/erp/enums';
import type { ErpId, ErpProductCategoryForm, ErpProductCategoryVO, ErpProductForm, ErpProductQuery, ErpProductVO } from '@/api/erp/types';
import ErpPagedSelect from '@/components/erp/ErpPagedSelect.vue';
import { formatMoney } from '@/utils/erp/calc';
import { checkPermi } from '@/utils/permission';
import { globalHeaders } from '@/utils/request';
// 列宽记忆：拖拽后按表名存 localStorage，进页面还原（见 hooks/useTableColumnWidth）
import { mergeSavedWidths, saveColumnWidths } from '@/hooks/useTableColumnWidth';
// 树形表格只有 EnhancedTable 消费 tree 属性（t-table/PrimaryTable 会静默忽略），故主列表单独用增强表
import { EnhancedTable } from 'tdesign-vue-next/es/table';
import ProductFormFields from '@/components/erp/ProductFormFields.vue';
import ProductSelect from '@/components/erp/ProductSelect.vue';
import StatusTag from '@/components/erp/StatusTag.vue';
import {
  CheckIcon,
  ChevronDownDoubleIcon,
  ChevronDownIcon,
  DeleteIcon,
  DownloadIcon,
  Edit1Icon,
  FolderIcon,
  LayersIcon,
  PlusIcon,
  RefreshIcon,
  SearchIcon,
  UploadIcon
} from 'tdesign-icons-vue-next';

/** 商品折叠阈值：超过即回退普通分页列表（DOC/26 §5.2，2026-11-07 由 2000 下调至 500） */
const GROUP_LIMIT = 500;

/** 分组行：分类行只承载分类名与商品数量，不可编辑/删除 */
interface ProductGroupRow {
  id: string;
  isGroup: true;
  categoryName: string;
  /** 分类排序值，未分类取最大排最后 */
  categorySort: number;
  productCount: number;
  children: ErpProductVO[];
}

type ProductTableRow = ErpProductVO | ProductGroupRow;

interface RecipeRow {
  /** t-table 行 key（前端生成，不提交后端） */
  __key: string;
  materialId?: ErpId;
  qtyPerUnit?: number;
}

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

// 分组数据整体替换、行字段由表格只读渲染，无需深度响应式；shallowRef 避免 500+ 行每个字段被 Vue 代理
const tableData = shallowRef<ProductTableRow[]>([]);
const loading = ref(true);
const categoryLoading = ref(false);
const showSearch = ref(true);
const ids = ref<Array<string | number>>([]);
const single = ref(true);
const multiple = ref(true);
const total = ref(0);
/** 列表模式：grouped=按分类折叠，paged=超阈值回退普通分页 */
const listMode = ref<'grouped' | 'paged'>('grouped');
const expandAll = ref(true);
/** 展开的分类行 key：TDesign 树表用受控数组，替代 EP 的 toggleRowExpansion */
const expandedTreeKeys = ref<Array<string | number>>([]);
/** t-form 实例提供 reset()/clearValidate()/validate()，与 EP 的 resetFields() 契约不同 */
const productFormRef = ref<any>();
const queryFormRef = ref<any>();
/** t-upload 实例只提供 uploadFiles()，没有 EP 的 submit()/clearFiles() */
const uploadRef = ref<any>();

/** 分类字典仅供完整分组与维护表使用；供应商不再全量加载，由筛选/表单独立远程分页。 */
const categoryList = ref<ErpProductCategoryVO[]>([]);
const nameLabel = (item: { id: ErpId; name?: string }) => item.name ?? String(item.id);
/** 类型下拉：ERP_PRODUCT_TYPE 是 Record，t-select 内部按数组 map，必须转数组 */
const productTypeOptions = Object.entries(ERP_PRODUCT_TYPE).map(([value, item]) => ({ label: item.label, value }));
const productStatusOptions = [
  { label: '启用', value: 1 },
  { label: '停用', value: 0 }
];

/** 主列表列配置：t-table 用 columns 数组，cell 字符串与 #插槽同名 */
const listColumns = computed<any[]>(() => {
  const cols: any[] = [
    { colKey: 'row-select', type: 'multiple', width: 55, align: 'center', disabled: ({ row }: any) => !!row.isGroup },
    { colKey: 'name', title: '商品名称', minWidth: 200, ellipsis: true, cell: 'name' },
    { colKey: 'code', title: '商品编码', align: 'center', width: 130, ellipsis: true, cell: 'code' },
    { colKey: 'spec', title: '规格', align: 'center', width: 110, ellipsis: true },
    { colKey: 'unit', title: '单位', align: 'center', width: 70 },
    { colKey: 'type', title: '类型', align: 'center', width: 80, cell: 'type' },
    { colKey: 'categoryName', title: '商品分类', align: 'center', width: 130, ellipsis: true, cell: 'categoryName' },
    { colKey: 'supplierName', title: '所属供应商', align: 'center', width: 140, ellipsis: true },
    { colKey: 'qtyScale', title: '数量精度', align: 'center', width: 100, cell: 'qtyScale' },
    { colKey: 'costPrice', title: '最近入库成本', align: 'right', width: 120, cell: 'costPrice' },
    { colKey: 'purchasePrice', title: '采购价', align: 'right', width: 110, cell: 'purchasePrice' },
    { colKey: 'freight', title: '默认运杂费', align: 'right', width: 110, cell: 'freight' },
    { colKey: 'taxType', title: '发票类型', align: 'center', width: 110, cell: 'taxType' },
    { colKey: 'status', title: '状态', align: 'center', width: 80, cell: 'status' },
    { colKey: 'remark', title: '备注', align: 'center', minWidth: 120, ellipsis: true },
    { colKey: 'operation', title: '操作', align: 'center', width: 160, cell: 'operation' }
  ];
  // 分组模式下分类行自带分类名，不再单列；分页模式才需要独立分类列
  if (listMode.value !== 'paged') {
    cols.splice(
      cols.findIndex((col) => col.colKey === 'categoryName'),
      1
    );
  }
  return mergeSavedWidths('master-product/list', cols);
});

/** 导入错误行列表列配置 */
const importErrorColumns: any[] = [
  { colKey: 'row', title: '行号', width: 90, align: 'center' },
  { colKey: 'message', title: '失败原因', minWidth: 380, ellipsis: true }
];
mergeSavedWidths('master-product/import-errors', importErrorColumns);

/** 分类维护列表列配置 */
const categoryColumns: any[] = [
  { colKey: 'code', title: '分类编码', align: 'center', width: 120, ellipsis: true },
  { colKey: 'name', title: '分类名称', align: 'center', minWidth: 140, ellipsis: true },
  { colKey: 'sort', title: '排序', align: 'center', width: 70 },
  { colKey: 'status', title: '状态', align: 'center', width: 80, cell: 'status' },
  { colKey: 'remark', title: '备注', align: 'center', minWidth: 140, ellipsis: true },
  { colKey: 'operation', title: '操作', align: 'center', width: 120, cell: 'operation' }
];
mergeSavedWidths('master-product/category', categoryColumns);

/** 配比列表列配置：原料与单件用量是行内编辑控件 */
const recipeColumns: any[] = [
  { colKey: 'materialId', title: '原料', minWidth: 260, cell: 'materialId' },
  { colKey: 'qtyPerUnit', title: '单件用量', align: 'right', width: 150, cell: 'qtyPerUnit' },
  { colKey: 'stock', title: '当前库存', align: 'right', width: 110, cell: 'stock' },
  { colKey: 'rowOperation', title: '操作', align: 'center', width: 70, cell: 'rowOperation' }
];
mergeSavedWidths('master-product/recipe', recipeColumns);

/** 导入参数 */
const upload = reactive<ImportOption>({
  open: false,
  title: '',
  isUploading: false,
  updateSupport: 0,
  /** t-upload 受控文件列表：上传成功后清空，替代 EP 的 clearFiles() */
  files: [],
  headers: globalHeaders(),
  url: import.meta.env.VITE_APP_BASE_API + '/erp/master/products/importData'
});

/** 导入结果 */
const importResult = reactive<{ visible: boolean; data?: ErpImportResult }>({
  visible: false,
  data: undefined
});

const dialog = reactive<DialogOption>({
  visible: false,
  title: ''
});

const categoryDialog = reactive<DialogOption>({
  visible: false,
  title: '商品分类维护'
});

const recipeDialog = reactive<DialogOption>({
  visible: false,
  title: ''
});

const emptyCategoryForm: ErpProductCategoryForm = { id: undefined, name: '', sort: 0, status: 1, remark: '' };
const categoryForm = ref<ErpProductCategoryForm>({ ...emptyCategoryForm });

const recipeProductId = ref<ErpId>();
const recipeRows = ref<RecipeRow[]>([]);
const stockMap = ref<Record<string, string>>({});

const initFormData: ErpProductForm = {
  id: undefined,
  code: '',
  name: '',
  spec: '',
  unit: '',
  type: 'PRODUCT',
  categoryId: undefined,
  supplierId: undefined,
  qtyScale: 0,
  costPrice: 0,
  purchasePrice: 0,
  taxType: 'NO_TAX',
  freight: 0,
  minStock: undefined,
  maxStock: undefined,
  status: 1,
  remark: ''
};

const form = ref<ErpProductForm>({ ...initFormData });

const queryParams = ref<ErpProductQuery>({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  type: '',
  status: '',
  categoryId: '',
  supplierId: ''
});

// t-form 的 rules 形状与 EP 的 ElFormRules 不兼容，用宽松类型
const productRules: Record<string, any> = {
  name: [{ required: true, message: '产品名称不能为空', trigger: 'blur' }],
  unit: [{ required: true, message: '单位不能为空', trigger: 'blur' }],
  type: [{ required: true, message: '类型不能为空', trigger: 'change' }]
};

/** 按分类分组：未分类（含分类已删除）固定排最后，组内保持后端 sort/code 顺序 */
const buildGroups = (rows: ErpProductVO[]): ProductTableRow[] => {
  const groups = new Map<string, ProductGroupRow>();
  rows.forEach((row) => {
    const hasCategory = row.categoryId != null && !!row.categoryName;
    const key = hasCategory ? `cat-${row.categoryId}` : 'cat-0';
    let group = groups.get(key);
    if (!group) {
      group = {
        id: key,
        isGroup: true,
        categoryName: hasCategory ? String(row.categoryName) : '未分类',
        categorySort: hasCategory ? Number(row.categorySort ?? 0) : Number.MAX_SAFE_INTEGER,
        productCount: 0,
        children: []
      };
      groups.set(key, group);
    }
    group.children.push(row);
    group.productCount += 1;
  });
  return Array.from(groups.values()).sort((a, b) => a.categorySort - b.categorySort);
};

/** 分类行 key 列表：树表展开态用 rowKey 数组表达 */
const groupKeys = (rows: ProductTableRow[]): Array<string | number> => rows.filter((row) => (row as ProductGroupRow).isGroup).map((row) => row.id);

/**
 * 数量精度文案：0/空值显示「整数」，1~3 显示「N位小数」
 *
 * @param qtyScale 商品数量精度位数
 */
const qtyScaleText = (qtyScale?: number | null): string => {
  const hit = ERP_QTY_SCALE_OPTIONS.find((item) => item.value === Number(qtyScale ?? 0));
  return hit ? hit.label : '整数';
};

/** 查询产品列表：先探测总数，超阈值回退普通分页，否则一次加载全部分组数据（DOC/26 §5.2） */
const getList = async () => {
  loading.value = true;
  // t-table 的勾选是受控的，数据重载后沿用 EP 的行为清空选择，避免批量操作命中旧 id
  ids.value = [];
  single.value = true;
  multiple.value = true;
  try {
    if (listMode.value === 'paged') {
      const res = await listProduct(queryParams.value);
      tableData.value = (res.rows ?? []).map((row) => markRaw(row));
      total.value = res.total ?? 0;
      expandedTreeKeys.value = [];
      return;
    }
    // 先按 pageSize=1 探测总数：超阈值时只回 1 行，不再白拉 GROUP_LIMIT+1 条大响应
    const probe = await listProduct({ ...queryParams.value, pageNum: 1, pageSize: 1 });
    total.value = probe.total ?? 0;
    if (total.value > GROUP_LIMIT) {
      listMode.value = 'paged';
      queryParams.value.pageNum = 1;
      await getList();
      return;
    }
    const res = await listProduct({ ...queryParams.value, pageNum: 1, pageSize: GROUP_LIMIT });
    // 行对象只被表格读取，markRaw 防止组件库内部 reactive 对 500 行逐字段代理
    const rows = (res.rows ?? []).map((row) => markRaw(row));
    const grouped = buildGroups(rows);
    // 先写展开态再换数据：增强表只在 data 变更时按当前 expandedTreeNodes 展平子行
    expandedTreeKeys.value = expandAll.value ? groupKeys(grouped) : [];
    tableData.value = grouped;
  } finally {
    loading.value = false;
  }
};

/** 搜索：重新从分组模式探测（可能在筛选后回到分组视图） */
const handleQuery = () => {
  queryParams.value.pageNum = 1;
  listMode.value = 'grouped';
  getList();
};

/** 重置按钮操作 */
const resetQuery = () => {
  queryFormRef.value?.reset();
  queryParams.value.pageNum = 1;
  queryParams.value.keyword = '';
  queryParams.value.categoryId = '';
  queryParams.value.supplierId = '';
  handleQuery();
};

/** 展开全部 / 收起全部 */
const handleToggleExpandAll = () => {
  expandAll.value = !expandAll.value;
  expandedTreeKeys.value = expandAll.value ? groupKeys(tableData.value) : [];
};

/** 多选框选中数据：t-table 的 select-change 直接给选中行 key */
const handleSelectionChange = (keys: Array<string | number>) => {
  ids.value = keys;
  single.value = keys.length != 1;
  multiple.value = !keys.length;
};

/** 表单重置 */
const reset = () => {
  form.value = { ...initFormData };
  productFormRef.value?.clearValidate();
};

/** 新增按钮操作 */
const handleAdd = () => {
  reset();
  dialog.visible = true;
  dialog.title = '添加产品';
};

/** 修改按钮操作 */
const handleUpdate = async (row?: ErpProductVO) => {
  reset();
  const id = row?.id ?? ids.value[0];
  const res = await getProduct(id);
  const detail = res.data;
  Object.assign(form.value, detail);
  form.value.categoryId = detail.categoryId ?? undefined;
  form.value.supplierId = detail.supplierId ?? undefined;
  form.value.qtyScale = detail.qtyScale ?? 0;
  form.value.costPrice = Number(detail.costPrice ?? 0);
  form.value.purchasePrice = Number(detail.purchasePrice ?? 0);
  form.value.freight = Number(detail.freight ?? 0);
  form.value.minStock = detail.minStock == null ? undefined : Number(detail.minStock);
  form.value.maxStock = detail.maxStock == null ? undefined : Number(detail.maxStock);
  form.value.status = detail.status ?? 1;
  dialog.visible = true;
  dialog.title = '修改产品';
};

/** 取消按钮 */
const cancel = () => {
  reset();
  dialog.visible = false;
};

/** 提交按钮 */
const submitForm = async () => {
  // TDesign t-form.validate() 失败 resolve(false)，不 reject
  const r = await productFormRef.value?.validate().catch(() => false);
  const ok = r === true || r === undefined;
  if (!ok) return;
  // 最近入库成本只由入库单回写，编辑时不回传，避免旧值覆盖并发回写结果
  form.value.id ? await updateProduct({ ...form.value, costPrice: undefined }) : await addProduct(form.value);
  proxy?.$modal.msgSuccess('操作成功');
  dialog.visible = false;
  await getList();
};

/** 删除按钮操作 */
const handleDelete = async (row?: ErpProductVO) => {
  const productIds = row?.id ?? ids.value;
  try {
    await proxy?.$modal.confirm('是否确认删除产品编号为"' + productIds + '"的数据项？');
  } catch {
    return;
  }
  await delProduct(productIds);
  await getList();
  proxy?.$modal.msgSuccess('删除成功');
};

/* ------------------------------ 商品分类维护 ------------------------------ */

/** 打开分类维护弹窗并加载分类 */
const openCategoryDialog = async () => {
  categoryDialog.visible = true;
  resetCategoryForm();
  await loadCategoryList();
};

const loadCategoryList = async () => {
  categoryLoading.value = true;
  try {
    categoryList.value = await listAllProductCategories();
  } finally {
    categoryLoading.value = false;
  }
};

const resetCategoryForm = () => {
  categoryForm.value = { ...emptyCategoryForm };
};

/** 点击分类行的编辑按钮：填充上方表单，实现弹窗内就地编辑 */
const editCategory = (row: ErpProductCategoryVO) => {
  categoryForm.value = { id: row.id, code: row.code, name: row.name, sort: row.sort ?? 0, status: row.status ?? 1, remark: row.remark ?? '' };
};

/** 新增/保存分类 */
const submitCategory = async () => {
  if (!categoryForm.value.name || !categoryForm.value.name.trim()) {
    proxy?.$modal.msgWarning('分类名称不能为空');
    return;
  }
  categoryForm.value.id ? await updateProductCategory(categoryForm.value) : await addProductCategory(categoryForm.value);
  proxy?.$modal.msgSuccess('操作成功');
  resetCategoryForm();
  await loadCategoryList();
  await getList();
};

/** 删除分类：逻辑删除，商品自动归入「未分类」 */
const removeCategory = async (row: ErpProductCategoryVO) => {
  try {
    await proxy?.$modal.confirm('删除后该分类下的商品将归入「未分类」，是否确认删除分类"' + row.name + '"？');
  } catch {
    return;
  }
  await delProductCategory(row.id);
  proxy?.$modal.msgSuccess('删除成功');
  if (String(categoryForm.value.id) === String(row.id)) {
    resetCategoryForm();
  }
  await loadCategoryList();
  await getList();
};

/** 配比只查当前原料 ID 的全仓数量（含新增/替换的原料）；空结果是未知，不是 0。 */
watch(
  () => (recipeDialog.visible ? recipeRows.value.flatMap(({ materialId }) => (materialId == null ? [] : [materialId])) : []),
  async (materialIds, _previous, onCleanup) => {
    stockMap.value = {};
    if (!materialIds.length || !checkPermi(['erp:stock:list'])) return;
    let current = true;
    onCleanup(() => (current = false));
    try {
      const quantities = await listStockQuantities(materialIds);
      if (current) stockMap.value = Object.fromEntries(quantities.map(({ productId, qty }) => [String(productId), Number(qty).toFixed(3)]));
    } catch {
      // 库存读取失败不影响编辑配比。
    }
  }
);

const getStockText = (materialId?: ErpId) => {
  if (materialId == null) return '-';
  const value = stockMap.value[String(materialId)];
  return value == null ? '-' : value;
};

/** 配比行没有业务主键，t-table 行 key 用前端自增序号 */
let recipeSeq = 0;
const nextRecipeKey = (): string => `recipe-${++recipeSeq}`;

/** 配比弹窗 */
const handleRecipe = async (row: ErpProductVO) => {
  recipeProductId.value = row.id;
  recipeDialog.title = `配比 - ${row.name}`;
  recipeDialog.visible = true;
  recipeRows.value = [];
  const res = await getRecipe(row.id);
  if (String(recipeProductId.value) !== String(row.id) || !recipeDialog.visible) return;
  recipeRows.value = (res.data ?? []).map((item) => ({
    __key: nextRecipeKey(),
    materialId: item.materialId,
    qtyPerUnit: Number(item.qtyPerUnit ?? 0)
  }));
};

const addRecipeRow = () => {
  recipeRows.value.push({ __key: nextRecipeKey(), materialId: undefined, qtyPerUnit: 0 });
};

const removeRecipeRow = (index: number) => {
  recipeRows.value.splice(index, 1);
};

/** 保存配比 */
const submitRecipe = async () => {
  if (recipeProductId.value == null) return;
  const items = recipeRows.value
    .filter((item) => item.materialId != null && Number(item.qtyPerUnit) > 0)
    .map((item) => ({ materialId: item.materialId as ErpId, qtyPerUnit: Number(item.qtyPerUnit) }));
  if (items.length !== recipeRows.value.length) {
    proxy?.$modal.msgWarning('请完整选择原料并填写单件用量');
    return;
  }
  await saveRecipe(recipeProductId.value, items);
  proxy?.$modal.msgSuccess('配比保存成功');
  recipeDialog.visible = false;
};

/** 导入上传响应（后端 R<ImportResult>） */
interface ImportUploadResponse {
  code: number;
  msg: string;
  data?: ErpImportResult;
}

/** 打开导入弹窗 */
const handleImport = () => {
  upload.title = '产品导入';
  upload.open = true;
};

/** 导出产品 */
const handleExport = () => {
  proxy?.download('/erp/master/products/export', { ...queryParams.value }, `products_${Date.now()}.xlsx`);
};

/** 下载导入模板 */
const importTemplate = () => {
  proxy?.download('/erp/master/products/importTemplate', {}, `product_template_${Date.now()}.xlsx`);
};

/** 文件上传中 */
const handleFileUploadProgress = () => {
  upload.isUploading = true;
};

/** 文件上传成功：t-upload 回传 { file, response, ... }，业务响应体在 response 上 */
const handleFileSuccess = (context: { response?: ImportUploadResponse }) => {
  upload.isUploading = false;
  upload.open = false;
  upload.files = [];
  const response = context?.response;
  if (!response || response.code !== 200 || !response.data) {
    proxy?.$modal.msgError(response?.msg || '导入失败，请检查文件内容');
    return;
  }
  importResult.data = response.data;
  importResult.visible = true;
  getList();
};

/** 文件上传失败：t-upload 的 on-fail 给 { file, response, failedFiles }，没有 Error 对象 */
const handleFileError = (context: { response?: ImportUploadResponse }) => {
  upload.isUploading = false;
  proxy?.$modal.msgError(context?.response?.msg || '导入失败，请检查网络或文件');
};

/** 提交上传文件：t-upload 没有 submit()，手动上传用 uploadFiles() */
const submitFileForm = () => {
  uploadRef.value?.uploadFiles();
};

onMounted(getList);
</script>
