<script setup lang="ts">
/**
 * 商品选择器（弹窗式）
 *
 * 触发器是一个只读输入框，点击弹出商品选择窗：关键词搜索 + 分类筛选 + 明细表格。
 * 相比原先的 el-select 下拉，能同时呈现编码/名称/规格/单位/分类/数量精度/库存/参考价，
 * 避免「只能看到一行标签、选错商品」。
 *
 * 对外契约与旧的下拉版保持一致（v-model + change(product)），因此开单页无需改动：
 * - modelValue：商品 ID
 * - change：选中/清空后回传完整 ErpProductVO（qtyScale、purchasePrice、costPrice 等供开单页使用）
 */
import { computed, ref, watch } from 'vue';
import { listAllProductCategories, productOptions, productOptionsByIds } from '@/api/erp/master';
import { listStockQuantities } from '@/api/erp/stock';
import { ERP_PRODUCT_TYPE, ERP_QTY_SCALE_OPTIONS } from '@/api/erp/enums';
import type { ErpAmount, ErpId, ErpProductCategoryVO, ErpProductType, ErpProductVO } from '@/api/erp/types';
import { formatMoney, formatQtyTrim } from '@/utils/erp/calc';
import { checkPermi } from '@/utils/permission';
import useDialog from '@/hooks/useDialog';
import { mergeSavedWidths, saveColumnWidths } from '@/hooks/useTableColumnWidth';
import StatusTag from '@/components/erp/StatusTag.vue';
import { loadProductForRestore } from './productRestoreLoader';
import { CloseCircleFilledIcon, SearchIcon } from 'tdesign-icons-vue-next';

const props = withDefaults(
  defineProps<{
    /** 单选为商品 ID；多选（multiple）为 ID 数组 */
    modelValue?: ErpId | ErpId[] | null;
    /** 限定产品类型，PRODUCT=成品，MATERIAL=原料；不传=不限（弹窗内提供类型切换） */
    type?: ErpProductType;
    placeholder?: string;
    disabled?: boolean;
    /** 允许清空 */
    clearable?: boolean;
    /** 多选：弹窗显示勾选列，跨页保留勾选，确认后 modelValue 为 ID 数组 */
    multiple?: boolean;
    /** 弹窗展示库存列（无 erp:stock:list 权限时自动隐藏） */
    showStock?: boolean;
    /** 弹窗展示分类筛选 */
    showCategoryFilter?: boolean;
    /** 弹窗标题 */
    dialogTitle?: string;
  }>(),
  {
    modelValue: undefined,
    type: undefined,
    placeholder: '请选择产品',
    disabled: false,
    clearable: true,
    multiple: false,
    showStock: true,
    showCategoryFilter: false,
    dialogTitle: '选择商品'
  }
);

const emit = defineEmits<{
  (e: 'update:modelValue', value: ErpId | ErpId[] | undefined): void;
  /** 单选：选中/清空时回传商品对象（multiple 模式下不触发） */
  (e: 'change', product: ErpProductVO | undefined): void;
  /** 多选：确认/清空时回传商品数组，仅 multiple 模式触发 */
  (e: 'change-multiple', products: ErpProductVO[]): void;
}>();

const productDialog = useDialog({ title: props.dialogTitle });

const loading = ref(false);
/** 当前检索结果（关键词/分类由服务端过滤） */
const list = ref<ErpProductVO[]>([]);
/** 已选商品，用于触发器回显 */
const selected = ref<ErpProductVO>();
/** 弹窗内高亮行，点「确定」后落为选中 */
const currentRow = ref<ErpProductVO>();

const keyword = ref('');
const categoryId = ref<ErpId>();
/** 类型筛选：仅 props.type 未限定时生效 */
const typeFilter = ref<ErpProductType>();
/** 类型下拉选项：t-select 只认 {label,value} 数组，直接传 Record 会在其内部 .map 抛 TypeError */
const typeOptions = computed(() => Object.entries(ERP_PRODUCT_TYPE).map(([value, item]) => ({ value, label: item.label })));

const categories = ref<ErpProductCategoryVO[]>([]);
const categoriesLoaded = ref(false);

/** 当前页的全仓数量；不把无快照/加载失败显示成 0。 */
const stockMap = ref(new Map<string, ErpAmount>());
const showStockColumn = computed(() => props.showStock && checkPermi(['erp:stock:list']));

const PAGE_SIZE = 10;
const pageNum = ref(1);
const nextPageNum = ref<number | null>(null);
const queryFailed = ref(false);
let pageRequest = 0;
let submittedKeyword = '';
let restoreRequest = 0;
const restoring = ref(false);
const restoreFailed = ref(false);
/** 跨页勾选以商品 ID 归一化为键，但对外仍回传原始 ID 与完整 VO。 */
const checked = ref(new Map<string, ErpProductVO>());
const checkedIds = computed(() => list.value.filter((row) => checked.value.has(String(row.id))).map((row) => row.id));
const pickingCount = computed(() => checked.value.size);

/** 多选已选 ID 集合，触发器文案与「确定」按钮可用性都基于它 */
const pickedIds = computed<ErpId[]>(() => (props.multiple && Array.isArray(props.modelValue) ? props.modelValue : []));

/** 触发器展示文案：多选显示数量，单选显示「编码 名称 / 规格」 */
const label = computed(() => {
  if (props.multiple) return pickedIds.value.length ? `已选 ${pickedIds.value.length} 项` : '';
  const p = selected.value;
  if (!p) return '';
  return `${p.code} ${p.name}${p.spec ? ' / ' + p.spec : ''}`;
});

const canConfirm = computed(() => (props.multiple ? !restoring.value && !restoreFailed.value && pickingCount.value > 0 : !!currentRow.value));

/** 单选当前高亮行：t-table 用 active-row-keys 受控（多选态不高亮） */
const activeRowKeys = computed<Array<string | number>>(() => (props.multiple || !currentRow.value ? [] : [currentRow.value.id]));

/**
 * 表格列配置
 *
 * t-table 用 columns 数组：勾选列必须写成 { type: 'multiple' } 的列项才会渲染，
 * 只传 selectedRowKeys 不建列（useRowSelect 只从 columns 里找 type）。
 * 弹窗表同样开列宽拖拽，宽度按 'product-select/list' 记忆。
 */
const columns = computed<any[]>(() => {
  const cols: any[] = [];
  if (props.multiple) cols.push({ colKey: 'row-select', type: 'multiple', width: 46, align: 'center' });
  cols.push(
    { colKey: 'code', title: '编码', align: 'center', width: 120, ellipsis: true },
    { colKey: 'name', title: '名称', align: 'left', minWidth: 160, ellipsis: true },
    { colKey: 'spec', title: '规格', align: 'center', width: 120, ellipsis: true },
    { colKey: 'unit', title: '单位', align: 'center', width: 70 },
    { colKey: 'categoryName', title: '分类', align: 'center', width: 110, ellipsis: true }
  );
  if (!props.type) cols.push({ colKey: 'type', title: '类型', align: 'center', width: 80 });
  cols.push(
    { colKey: 'supplierName', title: '供应商', align: 'center', width: 130, ellipsis: true },
    { colKey: 'qtyScale', title: '数量精度', align: 'center', width: 90 },
    { colKey: 'taxType', title: '含税', align: 'center', width: 100 }
  );
  if (showStockColumn.value) cols.push({ colKey: 'stock', title: '库存', align: 'right', width: 100 });
  cols.push(
    { colKey: 'purchasePrice', title: '采购价', align: 'right', width: 110 },
    { colKey: 'costPrice', title: '最近成本', align: 'right', width: 110 }
  );
  return mergeSavedWidths('product-select/list', cols);
});

/**
 * 数量精度文案
 *
 * @param scale 精度位数 0~3
 * @returns 中文文案，如「整数」「2位小数」
 */
const qtyScaleText = (scale?: number | null): string => ERP_QTY_SCALE_OPTIONS.find((item) => item.value === Number(scale ?? 0))?.label ?? '整数';

/** 只为分类标签加载完整字典。 */
const loadCategories = async () => {
  if (!props.showCategoryFilter || categoriesLoaded.value) return;
  categoriesLoaded.value = true;
  try {
    categories.value = await listAllProductCategories();
  } catch {
    categoriesLoaded.value = false;
  }
};

/** 翻页和筛选都发服务端查询，只有本页商品的 ID 会用于查全仓库存。 */
const loadPage = async (page: number) => {
  const seq = ++pageRequest;
  pageNum.value = page;
  loading.value = true;
  queryFailed.value = false;
  list.value = [];
  stockMap.value = new Map();
  nextPageNum.value = null;
  try {
    const res = await productOptions({
      pageNum: page,
      pageSize: PAGE_SIZE,
      keyword: submittedKeyword || undefined,
      categoryId: categoryId.value,
      type: props.type || typeFilter.value
    });
    if (seq !== pageRequest) return;
    list.value = res.rows;
    nextPageNum.value = res.hasMore ? (res.nextPageNum ?? null) : null;
    if (showStockColumn.value && res.rows.length) {
      void listStockQuantities(res.rows.map((item) => item.id)).then(
        (quantities) => {
          if (seq === pageRequest) stockMap.value = new Map(quantities.map((item) => [String(item.productId), item.qty]));
        },
        () => {
          /* 保留未知库存，不阻塞选品。 */
        }
      );
    }
  } catch {
    if (seq === pageRequest) queryFailed.value = true;
  } finally {
    if (seq === pageRequest) loading.value = false;
  }
};

const search = () => {
  submittedKeyword = keyword.value.trim();
  return loadPage(1);
};

/** 按 ID 恢复页外/停用已选商品；回显失败不允许静默覆盖原选择。 */
const restoreSelection = async () => {
  const seq = ++restoreRequest;
  checked.value = new Map();
  restoreFailed.value = false;
  restoring.value = pickedIds.value.length > 0;
  if (!restoring.value) return;
  try {
    const products = await productOptionsByIds(pickedIds.value);
    if (seq !== restoreRequest) return;
    checked.value = new Map(products.filter((item) => !props.type || item.type === props.type).map((item) => [String(item.id), item]));
  } catch {
    if (seq === restoreRequest) restoreFailed.value = true;
  } finally {
    if (seq === restoreRequest) restoring.value = false;
  }
};

/** 打开弹窗：重置检索条件并拉取数据 */
const open = async () => {
  if (props.disabled) return;
  keyword.value = '';
  categoryId.value = undefined;
  typeFilter.value = undefined;
  currentRow.value = selected.value;
  productDialog.openDialog();
  await Promise.all([loadCategories(), search(), restoreSelection()]);
};

/** 当前页的勾选改变只更新当前页，页外选择继续保留。 */
const handleSelectChange = (keys: ErpId[]) => {
  if (restoring.value || restoreFailed.value) return;
  const keySet = new Set(keys.map(String));
  const next = new Map(checked.value);
  for (const row of list.value) {
    const id = String(row.id);
    if (keySet.has(id)) next.set(id, row);
    else next.delete(id);
  }
  checked.value = next;
};

const selectCategory = (id?: ErpId) => {
  if (String(categoryId.value ?? '') === String(id ?? '')) return;
  categoryId.value = id;
  void search();
};

const selectType = (value: unknown) => {
  typeFilter.value = value === 'PRODUCT' || value === 'MATERIAL' ? value : undefined;
  void search();
};

watch(
  () => props.type,
  () => {
    if (productDialog.visible.value) void search();
  }
);

/** 确认选择：单选回传商品对象，多选回传商品数组，开单页据此带出单位/精度/默认价 */
const confirmPick = () => {
  if (props.multiple) {
    if (!canConfirm.value) return;
    const rows = [...checked.value.values()];
    emit(
      'update:modelValue',
      rows.map((row) => row.id)
    );
    emit('change-multiple', rows);
    productDialog.closeDialog();
    return;
  }
  const product = currentRow.value;
  if (!product) return;
  selected.value = product;
  emit('update:modelValue', product.id);
  emit('change', product);
  productDialog.closeDialog();
};

/** 单击行：多选切换勾选；单选只高亮，避免误触直接改单（改单会触发开单页重算默认价） */
const handleRowClick = ({ row }: { row: ErpProductVO }) => {
  if (props.multiple) {
    if (restoring.value || restoreFailed.value) return;
    // 行点击不触发 select-change，受控勾选在这里按 id 做 Map toggle
    const next = new Map(checked.value);
    const key = String(row.id);
    if (next.has(key)) next.delete(key);
    else next.set(key, row);
    checked.value = next;
    return;
  }
  currentRow.value = row;
};

/** 双击行直接确认 */
const handleRowDblclick = ({ row }: { row: ErpProductVO }) => {
  if (props.multiple) return;
  currentRow.value = row;
  confirmPick();
};

/** 清空选择 */
const clear = () => {
  if (props.multiple) {
    checked.value = new Map();
    restoring.value = false;
    restoreFailed.value = false;
    emit('update:modelValue', []);
    emit('change-multiple', []);
    return;
  }
  selected.value = undefined;
  emit('update:modelValue', undefined);
  emit('change', undefined);
};

// 编辑回显不依赖当前页或状态过滤；忽略上一个商品的迟到结果。
watch(
  () => props.modelValue,
  async (value, _old, onCleanup) => {
    if (props.multiple) return;
    if (selected.value && String(selected.value.id) === String(value)) return;
    selected.value = list.value.find((item) => String(item.id) === String(value));
    if (selected.value || value == null || value === '' || Array.isArray(value)) return;
    let current = true;
    onCleanup(() => (current = false));
    try {
      const product = await loadProductForRestore(value);
      if (current) selected.value = product;
    } catch {
      // 保留原 ID，不擅自触发 change、重算开单价格等字段。
    }
  },
  { immediate: true }
);

defineExpose({ open });
</script>

<template>
  <div class="product-picker">
    <!-- 触发器：只读输入框，点击弹出商品选择窗；DOC/35 T2 起改用 TDesign t-input，与开单页其余控件同款 -->
    <div class="product-picker__trigger" :title="label || ''" @click="open">
      <t-input :value="label" :placeholder="placeholder" :disabled="disabled" readonly>
        <template #suffix>
          <CloseCircleFilledIcon v-if="clearable && label && !disabled" class="product-picker__clear" @click.stop="clear" />
          <SearchIcon v-else class="product-picker__icon" />
        </template>
      </t-input>
    </div>

    <t-dialog
      v-model:visible="productDialog.visible.value"
      :header="productDialog.title.value"
      width="88%"
      top="6vh"
      attach="body"
      :destroy-on-close="true"
    >
      <div class="flex flex-wrap items-center gap-3 mb-3">
        <t-input v-model="keyword" placeholder="编码 / 名称 / 规格" clearable style="width: 260px" @enter="search" @clear="search">
          <template #prefix-icon><SearchIcon /></template>
        </t-input>
        <t-select
          v-if="!type"
          :model-value="typeFilter"
          placeholder="全部类型"
          clearable
          style="width: 120px"
          :options="typeOptions"
          @change="selectType"
        />
        <t-button theme="primary" @click="search">
          <template #icon><SearchIcon /></template>
          搜索
        </t-button>
        <span class="erp-text-secondary"
          >第 {{ pageNum }} 页{{ nextPageNum != null ? '，可继续翻页' : ''
          }}{{ multiple ? `，已选 ${pickingCount} 个，点击行勾选` : '，点击行选中、双击直接确定' }}</span
        >
      </div>

      <!-- 分类筛选：与商品资料同一套分类，快速缩小范围 -->
      <div v-if="showCategoryFilter && categories.length > 0" class="flex flex-wrap gap-1 mb-2">
        <t-check-tag :checked="categoryId == null" @change="selectCategory(undefined)">全部</t-check-tag>
        <t-check-tag v-for="item in categories" :key="item.id" :checked="String(categoryId) === String(item.id)" @change="selectCategory(item.id)">
          {{ item.name }}{{ item.status === 1 ? '' : '（停用）' }}
        </t-check-tag>
      </div>

      <t-table
        class="erp-table"
        row-key="id"
        :data="list"
        :columns="columns"
        :loading="loading"
        bordered
        :hover="true"
        :resizable="true"
        size="small"
        max-height="52vh"
        active-row-type="single"
        :active-row-keys="activeRowKeys"
        :selected-row-keys="checkedIds"
        @row-click="(ctx: any) => handleRowClick(ctx)"
        @row-dblclick="(ctx: any) => handleRowDblclick(ctx)"
        @select-change="handleSelectChange"
        @column-resize-change="(ctx: any) => saveColumnWidths('product-select/list', ctx.columnsWidth)"
      >
        <template #spec="{ row }">{{ row.spec || '-' }}</template>
        <template #unit="{ row }">{{ row.unit || '-' }}</template>
        <template #categoryName="{ row }">{{ row.categoryName || '未分类' }}</template>
        <template #type="{ row }">{{ ERP_PRODUCT_TYPE[row.type]?.label || '-' }}</template>
        <template #supplierName="{ row }">{{ row.supplierName || '-' }}</template>
        <template #qtyScale="{ row }">{{ qtyScaleText(row.qtyScale) }}</template>
        <template #taxType="{ row }">
          <!-- 复用商品资料的发票类型映射：不含税即不含税价，专票/普票为含税价 -->
          <StatusTag kind="tax" :value="row.taxType" />
        </template>
        <template #stock="{ row }">{{ stockMap.has(String(row.id)) ? formatQtyTrim(stockMap.get(String(row.id))) : '—' }}</template>
        <template #purchasePrice="{ row }">{{ formatMoney(row.purchasePrice) }}</template>
        <template #costPrice="{ row }">{{ formatMoney(row.costPrice) }}</template>
        <template #empty>
          <span class="erp-text-secondary">{{ queryFailed ? '加载失败，请重试' : '没有匹配的商品' }}</span>
        </template>
      </t-table>
      <div class="mt-2 flex justify-end gap-2">
        <t-button size="small" variant="outline" :disabled="loading || pageNum <= 1" @click="loadPage(pageNum - 1)">上一页</t-button>
        <t-button size="small" variant="outline" :disabled="loading || nextPageNum == null" @click="loadPage(nextPageNum ?? pageNum)"
          >下一页</t-button
        >
      </div>

      <template #footer>
        <div class="flex items-center justify-between">
          <div v-if="multiple" class="erp-text-secondary">
            已选 <b>{{ pickingCount }}</b> 项
          </div>
          <div v-else class="erp-text-secondary">
            当前选中：<b>{{ currentRow ? `${currentRow.code} ${currentRow.name}` : '未选择' }}</b>
          </div>
          <div>
            <t-button theme="default" variant="outline" @click="productDialog.closeDialog()">取 消</t-button>
            <t-button theme="primary" :disabled="!canConfirm" @click="confirmPick">确 定</t-button>
          </div>
        </div>
      </template>
    </t-dialog>
  </div>
</template>

<style scoped lang="scss">
.product-picker__trigger {
  cursor: pointer;

  :deep(.t-input__inner) {
    cursor: pointer;
  }
}

.product-picker__icon,
.product-picker__clear {
  color: var(--td-text-color-placeholder, #999);
}

.product-picker__clear {
  cursor: pointer;

  &:hover {
    color: var(--td-brand-color, #0052d9);
  }
}
</style>
