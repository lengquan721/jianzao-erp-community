<template>
  <div class="p-2">
    <t-card :bordered="false" class="erp-filter-card">
      <t-form :data="query" class="erp-filter-row" label-align="right" :label-width="'auto'">
        <t-form-item label="场景" name="scene">
          <t-select v-model="query.scene" placeholder="全部场景" clearable :options="sceneOptions" class="query-w-sm" />
        </t-form-item>
        <t-form-item label="关键字" name="keyword">
          <t-input v-model="query.keyword" placeholder="模板名称/编码" clearable class="query-w-md" @enter="handleQuery" />
        </t-form-item>
        <div class="erp-filter-actions">
          <t-button theme="primary" :loading="loading" @click="handleQuery">
            <template #icon><SearchIcon /></template>
            搜索
          </t-button>
          <t-button theme="default" variant="outline" @click="resetQuery">
            <template #icon><RefreshIcon /></template>
            重置
          </t-button>
          <t-button v-hasPermi="['erp:print:template:edit']" theme="success" variant="outline" @click="handleAdd">
            <template #icon><PlusIcon /></template>
            新增模板
          </t-button>
        </div>
      </t-form>
    </t-card>

    <div class="erp-surface erp-panel">
      <t-table
        class="erp-table"
        row-key="id"
        :data="templateList"
        :columns="listColumns"
        :loading="loading"
        :hover="true"
        :resizable="true"
        bordered
        size="small"
        cell-empty-content="-"
        @column-resize-change="(ctx: any) => saveColumnWidths('print-template/list', ctx.columnsWidth)"
      >
        <template #scene="{ row }">
          <t-tag theme="primary" size="small">{{ sceneLabel(row.scene) }}</t-tag>
        </template>
        <template #customerName="{ row }">{{ row.customerName || '通用' }}</template>
        <template #paper="{ row }">{{ paperLabel(row.paper) }}</template>
        <template #isDefault="{ row }">
          <t-tag v-if="row.isDefault === 1" theme="success" size="small">默认</t-tag>
          <span v-else class="erp-text-secondary">-</span>
        </template>
        <template #status="{ row }">
          <t-tag :theme="row.status === 1 ? 'success' : 'default'" size="small">
            {{ row.status === 1 ? '启用' : '停用' }}
          </t-tag>
        </template>
        <template #operation="{ row }">
          <div class="flex items-center justify-center gap-1">
            <!-- 权限判断挂外层 t-tooltip：v-hasPermi 在 mounted 里删节点，会与 tooltip 的包裹结构打架 -->
            <t-tooltip v-if="checkPermi(['erp:print:template:edit'])" content="编辑" placement="top">
              <t-button theme="primary" variant="text" shape="square" size="small" @click="handleUpdate(row)">
                <template #icon><Edit1Icon /></template>
              </t-button>
            </t-tooltip>
            <t-tooltip v-if="checkPermi(['erp:print:template:preview'])" content="预览" placement="top">
              <t-button theme="primary" variant="text" shape="square" size="small" @click="handlePreview(row)">
                <template #icon><BrowseIcon /></template>
              </t-button>
            </t-tooltip>
            <t-tooltip v-if="row.isDefault !== 1 && checkPermi(['erp:print:template:edit'])" content="设为默认" placement="top">
              <t-button theme="primary" variant="text" shape="square" size="small" @click="handleSetDefault(row)">
                <template #icon><StarIcon /></template>
              </t-button>
            </t-tooltip>
            <t-tooltip v-if="checkPermi(['erp:print:template:edit'])" content="停用" placement="top">
              <t-button theme="danger" variant="text" shape="square" size="small" @click="handleDisable(row)">
                <template #icon><DeleteIcon /></template>
              </t-button>
            </t-tooltip>
          </div>
        </template>
      </t-table>

      <pagination v-show="total > 0" v-model:page="query.pageNum" v-model:limit="query.pageSize" :total="total" @pagination="getList" />
    </div>

    <!-- 编辑弹窗 -->
    <t-dialog v-model:visible="dialogVisible" :header="form.id ? '编辑模板' : '新增模板'" width="86%" top="4vh" attach="body">
      <t-form ref="formRef" :data="form" :rules="rules" label-align="right" label-width="90px">
        <t-row :gutter="16">
          <t-col :span="4">
            <t-form-item label="模板编码">
              <t-input :model-value="autoCode" disabled placeholder="保存时自动生成" />
            </t-form-item>
          </t-col>
          <t-col :span="4">
            <t-form-item label="模板名称" name="name">
              <t-input v-model="form.name" placeholder="送货单模板" />
            </t-form-item>
          </t-col>
          <t-col :span="4">
            <t-form-item label="适用场景" name="scene">
              <t-select v-model="form.scene" placeholder="请选择场景" :disabled="!!form.id" :options="sceneOptions" />
            </t-form-item>
          </t-col>
          <t-col :span="4">
            <t-form-item label="纸张" :help="paperLocked ? '模板已自带 @page 声明，按模板纸张输出' : ''">
              <t-select v-model="selectedPaper" placeholder="A4" :options="paperOptions" :disabled="paperLocked" />
            </t-form-item>
          </t-col>
          <t-col :span="4">
            <t-form-item label="专属客户">
              <PartnerSelect v-model="form.customerId" kind="customer" placeholder="留空为通用模板" clearable />
            </t-form-item>
          </t-col>
          <t-col :span="4">
            <t-form-item label="设为默认">
              <t-switch v-model="isDefault" />
            </t-form-item>
          </t-col>
        </t-row>

        <t-form-item label="编辑方式">
          <t-radio-group v-model="editMode" variant="default-filled">
            <t-radio-button value="design">可视化设计器</t-radio-button>
            <t-radio-button value="source">HTML 源码</t-radio-button>
          </t-radio-group>
          <span class="mode-hint">
            {{ editMode === 'design' ? '拖拽组件排版，无需写代码' : '直接编辑 HTML + Thymeleaf，适合精细调整' }}
          </span>
        </t-form-item>

        <t-form-item v-if="editMode === 'source'" label="模板内容" name="content">
          <t-textarea v-model="form.content" :autosize="{ minRows: 16, maxRows: 16 }" placeholder="HTML 模板，支持 Thymeleaf 表达式" />
        </t-form-item>

        <t-form-item v-else label="模板设计" name="schemaJson">
          <PrintDesigner :schema="designSchema" :scene="form.scene" @update:schema="onSchemaUpdate" />
        </t-form-item>
        <t-alert
          theme="info"
          :close="false"
          class="mb-2"
          title="打印时请选择与实际装纸一致的纸张、缩放100%；241宽连续纸请在驱动中设置241x93或241x140。源码自带@page时按模板声明输出。"
        />
      </t-form>

      <template #footer>
        <t-button theme="default" variant="outline" @click="dialogVisible = false">取 消</t-button>
        <t-button theme="default" variant="outline" :loading="previewingDesign" @click="handlePreviewDesign">
          <template #icon><BrowseIcon /></template>
          预览当前设计
        </t-button>
        <t-button theme="primary" :loading="saving" @click="handleSave">保 存</t-button>
      </template>
    </t-dialog>

    <!-- 预览弹窗 -->
    <t-dialog v-model:visible="previewVisible" header="模板预览" width="70%" top="4vh" attach="body">
      <t-tabs v-model="previewTab">
        <t-tab-panel label="预览效果" value="preview">
          <t-empty v-if="!previewHtml" description="暂无预览内容" :image-style="{ fontSize: '100px' }" />
          <iframe v-else class="preview-frame" :srcdoc="previewHtml" sandbox="allow-same-origin allow-modals" title="模板预览"></iframe>
        </t-tab-panel>
        <t-tab-panel label="变量说明" value="vars">
          <t-table
            class="erp-table"
            row-key="name"
            :data="variableRows"
            :columns="variableColumns"
            :hover="true"
            :resizable="true"
            bordered
            size="small"
            cell-empty-content="-"
            @column-resize-change="(ctx: any) => saveColumnWidths('print-template/vars', ctx.columnsWidth)"
          >
            <template #example="{ row }">
              <span class="vars-example">{{ row.example }}</span>
            </template>
          </t-table>
        </t-tab-panel>
      </t-tabs>
      <template #footer>
        <t-button theme="default" variant="outline" @click="previewVisible = false">关 闭</t-button>
        <t-button theme="primary" :disabled="!previewHtml" @click="handlePrintPreview">
          <template #icon><PrintIcon /></template>
          打 印
        </t-button>
      </template>
    </t-dialog>
  </div>
</template>

<script setup name="ErpPrintTemplate" lang="ts">
import {
  addPrintTemplate,
  disablePrintTemplate,
  getPrintTemplate,
  listPrintTemplate,
  previewPrintDesign,
  previewPrintTemplate,
  setDefaultPrintTemplate,
  updatePrintTemplate
} from '@/api/erp/print';
import type { ErpId, ErpPrintScene, PrintDesignSchema, PrintTemplateForm, PrintTemplateVO } from '@/api/erp/types';
import { checkPermi } from '@/utils/permission';
// 列宽记忆：拖拽后按表名存 localStorage，进页面还原（见 hooks/useTableColumnWidth）
import { mergeSavedWidths, saveColumnWidths } from '@/hooks/useTableColumnWidth';
import PartnerSelect from '@/components/erp/PartnerSelect.vue';
import PrintDesigner from './components/PrintDesigner.vue';
import { PAPER_SIZES, cloneSchema, emptySchema, parseSchema } from './components/designerOptions';
import { isPrintableHtml } from '@/utils/erp/printPreview';
import { BrowseIcon, DeleteIcon, Edit1Icon, PlusIcon, PrintIcon, RefreshIcon, SearchIcon, StarIcon } from 'tdesign-icons-vue-next';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const sceneOptions: { label: string; value: ErpPrintScene }[] = [
  { label: '送货单', value: 'SALE_DELIVERY' },
  { label: '对账单', value: 'STATEMENT' },
  { label: '对账单（区间）', value: 'STATEMENT_RANGE' },
  { label: '采购入库单', value: 'PURCHASE_IN' },
  { label: '收款单', value: 'RECEIPT' },
  { label: '付款单', value: 'PAYMENT' }
];

// 列表、编辑下拉和设计器画布共用尺寸来源，避免241x93只在一处出现。
const paperOptions = Object.entries(PAPER_SIZES).map(([value, size]) => ({ label: size.label, value }));
const paperLabel = (paper?: string) => paperOptions.find((item) => item.value === paper)?.label ?? paper ?? '-';

const variableRows = [
  { name: 'tenant', desc: '租户/公司信息（名称、地址、电话）', example: '<span th:text="${tenant.name}">公司名称</span>' },
  {
    name: 'doc',
    desc: '单据主信息（单号、业务日期、备注）',
    example: '单号 <span th:text="${doc.no}">XS001</span>；日期 <span th:text="${#dates.format(doc.date, \'yyyy-MM-dd\')}">2026-01-01</span>'
  },
  {
    name: 'partner',
    desc: '往来单位（客户/供应商名称、联系方式）',
    example: '<span th:text="${partner.name}">客户</span>，另有 partner.contact / phone / address'
  },
  {
    name: 'items',
    desc: '明细行集合（名称、规格、单位、数量、单价、金额）',
    example: '<tr th:each="i : ${items}"><td th:text="${i.name}"></td><td th:text="${i.qty}"></td></tr>，行字段 name/spec/unit/qty/price/amount'
  },
  {
    name: 'totals',
    desc: '合计信息（数量合计、金额合计）',
    example: '合计 <span th:text="${totals.amountTotal}">0.00</span>；大写 <span th:text="${totals.amountUpper}">零元整</span>'
  },
  {
    name: 'statement',
    desc: '对账数据（月度用 month；区间用 periodStart/periodEnd/periodLabel，区间模式 month 为空）',
    example:
      '<span th:text="${statement.periodLabel}"></span> / month / periodStart / periodEnd / opening / previousReceivable / openingEntryAmount / totalReceivable / salesAmount / returnAmount / netSalesAmount / receiptAmount / refundAmount / netReceiptAmount / monthlyUnpaid / cumulativeSales / cumulativeReceipts / cumulativeUnpaid / closing；明细 ${statement.items} 循环，行字段 date/no/content/productName/qtyText/price/amount/settled/unsettled'
  },
  {
    name: 'profit',
    desc: '成本与利润（成本金额、毛利、毛利率）',
    example: '<span th:text="${profit.costAmount}"></span> / profit.grossProfit / profit.grossMargin'
  },
  { name: 'showProfit', desc: '是否显示成本利润（true 时渲染内部利润区）', example: '<div th:if="${showProfit}">…</div>' },
  {
    name: 'signatures',
    desc: '签章区（制单人、仓管、客户签收）',
    example: '<span th:text="${signatures.maker}"></span>，另有 signatures.warehouse / customer'
  }
];

const sceneLabel = (scene?: string) => sceneOptions.find((item) => item.value === scene)?.label ?? scene ?? '-';

/** 主列表列配置：t-table 用 columns 数组，插槽名与 cell 字符串同名（DOC/35 §5） */
const listColumns: any[] = [
  { colKey: 'code', title: '模板编码', align: 'center', width: 160 },
  { colKey: 'name', title: '模板名称', align: 'center', minWidth: 140 },
  { colKey: 'scene', title: '适用场景', align: 'center', width: 120, cell: 'scene' },
  { colKey: 'customerName', title: '专属客户', align: 'center', width: 120, cell: 'customerName' },
  { colKey: 'paper', title: '纸张', align: 'center', width: 110, cell: 'paper' },
  { colKey: 'version', title: '版本', align: 'center', width: 70 },
  { colKey: 'isDefault', title: '默认', align: 'center', width: 70, cell: 'isDefault' },
  { colKey: 'status', title: '状态', align: 'center', width: 80, cell: 'status' },
  { colKey: 'operation', title: '操作', align: 'center', width: 120, cell: 'operation' }
];
mergeSavedWidths('print-template/list', listColumns);

/** 变量说明列配置：预览弹窗内的静态表，常用写作用等宽字号渲染 */
const variableColumns: any[] = [
  { colKey: 'name', title: '变量', align: 'center', width: 140 },
  { colKey: 'desc', title: '含义', align: 'left', minWidth: 180 },
  { colKey: 'example', title: '常用写法（Thymeleaf）', align: 'left', minWidth: 320, cell: 'example' }
];
mergeSavedWidths('print-template/vars', variableColumns);

const loading = ref(false);
const saving = ref(false);
const templateList = ref<PrintTemplateVO[]>([]);
const total = ref(0);

const query = reactive({
  scene: undefined as ErpPrintScene | undefined,
  keyword: '',
  pageNum: 1,
  pageSize: 10
});

const getList = async () => {
  loading.value = true;
  try {
    const res = await listPrintTemplate(query);
    templateList.value = res.rows ?? [];
    total.value = res.total ?? 0;
  } finally {
    loading.value = false;
  }
};

const handleQuery = () => {
  query.pageNum = 1;
  getList();
};

const resetQuery = () => {
  query.scene = undefined;
  query.keyword = '';
  query.pageNum = 1;
  getList();
};

/* ------------------------------ 编辑 ------------------------------ */

const dialogVisible = ref(false);
const formRef = ref();
const isDefault = ref(false);

/**
 * 编辑方式：design = 可视化设计器（存 schemaJson）; source = HTML 源码（存 content）
 *
 * 打开老模板时按 schemaJson 是否为空自动判定，客户可在两者间切换：
 * 切到源码模式会把当前组件树编译结果展示出来，便于精细微调。
 */
const editMode = ref<'design' | 'source'>('design');
const designSchema = ref<PrintDesignSchema>(emptySchema());

const emptyForm = (): PrintTemplateForm => ({
  id: undefined,
  code: '',
  name: '',
  scene: 'SALE_DELIVERY',
  content: '',
  schemaJson: '',
  paper: 'A4',
  isDefault: 0,
  customerId: undefined,
  status: 1
});

const form = reactive<PrintTemplateForm>(emptyForm());
// 设计器只以schema.paper为准；顶层选择与内部切换同步，不存在两个纸张来源。
const selectedPaper = computed({
  get: () => (editMode.value === 'design' ? designSchema.value.paper : (form.paper ?? 'A4')),
  set: (paper: string) => {
    form.paper = paper;
    const next = cloneSchema(designSchema.value);
    next.paper = paper;
    designSchema.value = next;
  }
});
const paperLocked = computed(
  () =>
    editMode.value === 'source' &&
    /@page\s*(?::[a-z-]+\s*)?(?:[a-z_-][\w-]*\s*)?\{/i.test((form.content ?? '').replace(/\/\*[\s\S]*?\*\//g, '').replace(/<!--[\s\S]*?-->/g, ''))
);

const codeSeed = ref('');

/** 编码自动生成：场景前缀 + 打开弹窗时刻（HHmmss），编辑时保持原编码 */
const autoCode = computed(() => {
  if (form.id) {
    return form.code;
  }
  return `${form.scene || 'SALE_DELIVERY'}_${codeSeed.value}`;
});

/**
 * 模板内容校验：必须含 HTML 标签与 Thymeleaf 指令，且无重复引号导致的属性提前闭合。
 * 从渲染页面复制可见文字、或粘贴时引号被插入重复字符，都会让整份模板退化为纯文本，
 * 后端 Jsoup 清洗会把纯文本原样存库，故在保存前拦截。
 * TDesign 自定义校验器失败返回 { result: false, message }，通过返回 true。
 */
const validateContent = (value: string) => {
  if (!value || !value.trim()) {
    return { result: false, message: '模板内容不能为空' };
  }
  if (!/<[a-z!][^>]*>/i.test(value)) {
    return { result: false, message: '模板内容不含 HTML 标签，请粘贴模板源码，不要从预览页面复制可见文字' };
  }
  if (/th:\w+=""\s*\$\{/i.test(value)) {
    return { result: false, message: '模板内容存在重复引号（th:属性=""${...}），会破坏 HTML 结构，请检查粘贴内容' };
  }
  return true;
};

/** 表单校验规则：t-form 的 rules 形状与 EP 的 ElFormRules 不兼容，单独用 Record 声明 */
const rules: Record<string, any> = {
  name: [{ required: true, message: '模板名称不能为空', trigger: 'blur' }],
  scene: [{ required: true, message: '适用场景不能为空', trigger: 'change' }]
};

const handleAdd = () => {
  ++detailRequest;
  ++modeRequest;
  Object.assign(form, emptyForm());
  const d = new Date();
  codeSeed.value = [d.getHours(), d.getMinutes(), d.getSeconds()].map((n) => String(n).padStart(2, '0')).join('');
  isDefault.value = false;
  // 新模板默认进设计器，从预置版式起步比空白画布友好
  editMode.value = 'design';
  designSchema.value = emptySchema();
  dialogVisible.value = true;
};

const handleUpdate = async (row: PrintTemplateVO) => {
  const current = ++detailRequest;
  ++modeRequest;
  dialogVisible.value = true;
  const res = await getPrintTemplate(row.id);
  if (current !== detailRequest || !dialogVisible.value) return;
  const detail = res.data;
  Object.assign(form, {
    id: detail.id,
    code: detail.code ?? '',
    name: detail.name ?? '',
    scene: detail.scene,
    content: detail.content ?? '',
    paper: detail.paper ?? 'A4',
    isDefault: detail.isDefault ?? 0,
    customerId: detail.customerId,
    status: detail.status ?? 1
  });
  isDefault.value = detail.isDefault === 1;
  // 有组件树进设计器，没有则沿用源码模式（老模板不强制迁移）
  const parsed = parseSchema(detail.schemaJson);
  designSchema.value = parsed ?? emptySchema();
  form.paper = parsed?.paper ?? detail.paper ?? 'A4';
  editMode.value = parsed ? 'design' : 'source';
  dialogVisible.value = true;
};

const handleSave = async () => {
  // t-form 的 validate() 失败是 resolve(false)，不是 reject
  const r = await formRef.value?.validate().catch(() => false);
  const ok = r === true || r === undefined;
  if (!ok) return;

  if (editMode.value === 'source') {
    // 源码模式沿用原有内容校验，避免粘贴残缺 HTML 落库
    const check = validateContent(form.content ?? '');
    if (check !== true) {
      proxy?.$modal.msgWarning((check as any).message);
      return;
    }
  } else if (!designSchema.value.blocks.length) {
    proxy?.$modal.msgWarning('请至少添加一个区块，或切到源码模式填写 HTML');
    return;
  }

  saving.value = true;
  try {
    const payload: PrintTemplateForm = {
      ...form,
      paper: selectedPaper.value,
      code: autoCode.value,
      isDefault: isDefault.value ? 1 : 0,
      // 两种模式互斥：设计器模式提交组件树，源码模式提交 HTML
      schemaJson: editMode.value === 'design' ? JSON.stringify(designSchema.value) : '',
      content: editMode.value === 'source' ? form.content : ''
    };
    if (form.id) {
      await updatePrintTemplate(payload);
    } else {
      await addPrintTemplate(payload);
    }
    proxy?.$modal.msgSuccess(form.id ? '修改成功' : '新增成功');
    dialogVisible.value = false;
    getList();
  } finally {
    saving.value = false;
  }
};

/**
 * 切换编辑方式。
 *
 * 设计器 → 源码：调用后端预览接口把组件树渲染成 HTML 填进编辑器，
 * 让客户看到「设计器到底生成了什么」，可在此基础上手改；
 * 但保存时若仍停留在源码模式，落库的是源码、schemaJson 被清空。
 */
let modeRequest = 0;
const onModeChange = async (mode: string | number | boolean) => {
  const current = ++modeRequest;
  if (mode !== 'source' || !dialogVisible.value || !designSchema.value.blocks.length) return;
  const contentBefore = form.content;
  try {
    const html: unknown = await previewPrintDesign({ scene: form.scene, schemaJson: JSON.stringify(designSchema.value) });
    // 切换/关闭/重开/用户继续编辑之后，编译结果不再属于当前编辑器。
    if (current === modeRequest && dialogVisible.value && editMode.value === 'source' && form.content === contentBefore && isPrintableHtml(html)) {
      form.content = html;
    }
  } catch {
    // 编译失败不阻断切换，客户仍可在空白编辑器里手写
  }
};

watch(editMode, (mode) => onModeChange(mode), { flush: 'sync' });

const handleSetDefault = async (row: PrintTemplateVO) => {
  await proxy?.$modal.confirm(`确认将「${row.name}」设为该场景的默认模板？`);
  await setDefaultPrintTemplate(row.id);
  proxy?.$modal.msgSuccess('设置成功');
  getList();
};

const handleDisable = async (row: PrintTemplateVO) => {
  await proxy?.$modal.confirm(`确认停用模板「${row.name}」？停用后保留审计记录。`);
  await disablePrintTemplate(row.id);
  proxy?.$modal.msgSuccess('停用成功');
  getList();
};

/** 设计器 v-model:schema 回写：深拷贝一份以触发响应式更新 */
const onSchemaUpdate = (next: PrintDesignSchema) => {
  designSchema.value = cloneSchema(next);
  form.paper = next.paper;
};

/* ------------------------------ 预览 ------------------------------ */

const previewVisible = ref(false);
const previewTab = ref('preview');
const previewHtml = ref('');
const previewingDesign = ref(false);
let previewRequest = 0;
let detailRequest = 0;
const invalidatePreview = () => {
  ++previewRequest;
  previewHtml.value = '';
  previewingDesign.value = false;
};
watch(
  previewVisible,
  (visible) => {
    if (!visible) invalidatePreview();
  },
  { flush: 'sync' }
);
watch(
  dialogVisible,
  (visible) => {
    if (!visible) {
      ++modeRequest;
      ++detailRequest;
      invalidatePreview();
    }
  },
  { flush: 'sync' }
);
watch([() => form.scene, () => form.customerId, () => form.content, () => form.paper, editMode, designSchema], invalidatePreview, {
  deep: true,
  flush: 'sync'
});
watch(
  [() => form.scene, () => form.customerId, () => form.paper, designSchema],
  () => {
    ++modeRequest;
  },
  { deep: true, flush: 'sync' }
);
onBeforeUnmount(() => {
  ++modeRequest;
  ++detailRequest;
  invalidatePreview();
});
onDeactivated(() => {
  ++modeRequest;
  ++detailRequest;
  invalidatePreview();
});

const handlePreview = async (row: PrintTemplateVO) => {
  const current = ++previewRequest;
  previewHtml.value = '';
  previewTab.value = 'preview';
  previewVisible.value = true;
  previewingDesign.value = true;
  try {
    const detail = row.content || row.schemaJson ? { ...row } : (await getPrintTemplate(row.id)).data;
    if (current !== previewRequest || !previewVisible.value) return;
    const parsed = parseSchema(detail.schemaJson);
    const html: unknown = parsed
      ? await previewPrintDesign({ scene: detail.scene, schemaJson: detail.schemaJson! })
      : detail.content
        ? await previewPrintTemplate({ scene: detail.scene, content: detail.content, customerId: detail.customerId, paper: detail.paper ?? 'A4' })
        : '';
    if (current !== previewRequest || !previewVisible.value) return;
    if (!isPrintableHtml(html)) {
      proxy?.$modal.msgWarning('模板渲染失败，请检查模板');
      return;
    }
    previewHtml.value = html;
  } catch {
    if (current === previewRequest) proxy?.$modal.msgWarning('预览失败，请稍后重试');
  } finally {
    if (current === previewRequest) previewingDesign.value = false;
  }
};

/**
 * 编辑弹窗内预览当前设计：不落库直接用组件树渲染，方便边改边看
 */
const handlePreviewDesign = async () => {
  if (editMode.value === 'design' && !designSchema.value.blocks.length) {
    proxy?.$modal.msgWarning('画布为空，请先添加区块');
    return;
  }
  await handlePreview({
    ...form,
    paper: selectedPaper.value,
    content: editMode.value === 'source' ? form.content : '',
    schemaJson: editMode.value === 'design' ? JSON.stringify(designSchema.value) : ''
  } as PrintTemplateVO);
};

const handlePrintPreview = () => {
  if (!previewVisible.value || !previewHtml.value || previewingDesign.value) return;
  const frame = document.querySelector('.preview-frame') as HTMLIFrameElement | null;
  if (!frame?.contentWindow) {
    proxy?.$modal.msgWarning('预览内容尚未加载');
    return;
  }
  try {
    frame.contentWindow.focus();
    frame.contentWindow.print();
  } catch {
    proxy?.$modal.msgWarning('打印被浏览器拦截，请允许弹出窗口');
  }
};

getList();
</script>

<style scoped lang="scss">
.preview-frame {
  width: 100%;
  height: 62vh;
  border: 1px solid var(--td-border-level-1-color);
  border-radius: 4px;
  background: #fff;
}

.vars-example {
  font-family: Menlo, Consolas, monospace;
  font-size: 12px;
  word-break: break-all;
}

.mode-hint {
  margin-left: 10px;
  font-size: 12px;
  color: var(--td-text-color-placeholder);
}
</style>
