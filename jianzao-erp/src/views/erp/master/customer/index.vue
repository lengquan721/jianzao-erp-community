<template>
  <div class="p-2">
    <t-row :gutter="20">
      <t-col :span="12">
        <transition :enter-active-class="proxy?.animate.searchAnimate.enter" :leave-active-class="proxy?.animate.searchAnimate.leave">
          <div v-show="showSearch" class="erp-row-container">
            <t-card :bordered="false" class="erp-filter-card" title="筛选条件">
              <t-form ref="queryFormRef" :data="queryParams" class="erp-filter-row" label-align="right" :label-width="'auto'" @submit="handleQuery">
                <t-form-item label="关键字" name="keyword">
                  <t-input v-model="queryParams.keyword" placeholder="编码/名称" clearable class="query-w-md" @enter="handleQuery" />
                </t-form-item>
                <t-form-item label="状态" name="status">
                  <t-select v-model="queryParams.status" placeholder="客户状态" clearable class="query-w-sm" :options="statusOptions" />
                </t-form-item>
                <div class="erp-filter-actions">
                  <t-button theme="primary" @click="handleQuery">搜索</t-button>
                  <t-button theme="default" variant="outline" @click="resetQuery">重置</t-button>
                </div>
              </t-form>
            </t-card>
          </div>
        </transition>

        <div class="erp-surface erp-panel">
          <div class="erp-panel__head">
            <div class="flex items-center gap-2">
              <t-button v-hasPermi="['erp:master:customer:add']" theme="primary" variant="outline" @click="handleAdd">
                <template #icon><PlusIcon /></template>
                新增
              </t-button>
              <t-button v-hasPermi="['erp:master:customer:edit']" theme="success" variant="outline" :disabled="single" @click="handleUpdate()">
                <template #icon><Edit1Icon /></template>
                修改
              </t-button>
              <t-button v-hasPermi="['erp:master:customer:remove']" theme="danger" variant="outline" :disabled="multiple" @click="handleDelete()">
                <template #icon><DeleteIcon /></template>
                删除
              </t-button>
              <t-dropdown>
                <t-button theme="default" variant="outline">
                  导入/导出
                  <template #suffix><ChevronDownIcon /></template>
                </t-button>
                <template #dropdown>
                  <t-dropdown-menu>
                    <!-- 下拉项点击才会渲染，v-hasPermi 不生效，仍用 checkPermi 判断 -->
                    <t-dropdown-item v-if="checkPermi(['erp:master:customer:import'])" @click="importTemplate">
                      <template #prefix-icon><DownloadIcon /></template>
                      下载模板
                    </t-dropdown-item>
                    <t-dropdown-item v-if="checkPermi(['erp:master:customer:import'])" @click="handleImport">
                      <template #prefix-icon><UploadIcon /></template>
                      导入数据
                    </t-dropdown-item>
                    <t-dropdown-item v-if="checkPermi(['erp:master:customer:export'])" @click="handleExport">
                      <template #prefix-icon><DownloadIcon /></template>
                      导出数据
                    </t-dropdown-item>
                  </t-dropdown-menu>
                </template>
              </t-dropdown>
            </div>
            <right-toolbar v-model:show-search="showSearch" @query-table="getList"></right-toolbar>
          </div>

          <t-table
            class="erp-table"
            row-key="id"
            :data="customerList"
            :columns="listColumns"
            :loading="loading"
            :hover="true"
            :resizable="true"
            size="small"
            cell-empty-content="-"
            :selected-row-keys="ids"
            @select-change="handleSelectionChange"
            @column-resize-change="(ctx: any) => saveColumnWidths('master-customer/list', ctx.columnsWidth)"
          >
            <template #openingDebt="{ row }">{{ openingAmountText(row.openingDebt) }}</template>
            <template #taxRate="{ row }">{{ formatPercent(row.taxRate) }}</template>
            <template #status="{ row }">
              <t-tag :theme="row.status === 1 ? 'success' : 'default'" size="small">{{ row.status === 1 ? '启用' : '停用' }}</t-tag>
            </template>
            <template #operation="{ row }">
              <div class="flex items-center justify-center gap-1">
                <!-- 权限判断改挂外层 t-tooltip：v-hasPermi 在 mounted 里删节点，会与 tooltip 的包裹结构打架 -->
                <t-tooltip v-if="checkPermi(['erp:master:customer:edit'])" content="修改" placement="top">
                  <t-button theme="primary" variant="text" shape="square" size="small" @click="handleUpdate(row)">
                    <template #icon><Edit1Icon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="checkPermi(['erp:master:customer:remove'])" content="删除" placement="top">
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

        <!-- 客户导入 -->
        <t-dialog v-model:visible="upload.open" :header="upload.title" width="420px" attach="body">
          <t-upload
            ref="uploadRef"
            v-model="uploadFileList"
            theme="file"
            draggable
            :max="1"
            accept=".xlsx,.xls"
            :headers="upload.headers"
            :action="upload.url"
            :data="{ updateSupport: upload.updateSupport }"
            :disabled="upload.isUploading"
            :auto-upload="false"
            @progress="handleFileUploadProgress"
            @success="handleFileSuccess"
            @fail="handleFileError"
          >
            <div class="flex flex-col items-center gap-2 py-2">
              <CloudUploadIcon size="32px" />
              <div>将文件拖到此处，或<em>点击上传</em></div>
            </div>
          </t-upload>
          <div class="mt-2 flex flex-col items-center gap-1 text-center">
            <t-checkbox :checked="upload.updateSupport === 1" @change="onUpdateSupportChange">是否更新已存在的数据</t-checkbox>
            <span class="erp-text-secondary">仅允许导入 xls、xlsx 格式文件，单文件最多 2000 行。</span>
            <t-link theme="primary" :underline="false" class="text-xs" @click="importTemplate">下载模板</t-link>
          </div>
          <template #footer>
            <t-button theme="default" variant="outline" @click="upload.open = false">取 消</t-button>
            <t-button theme="primary" :loading="upload.isUploading" @click="submitFileForm">确 定</t-button>
          </template>
        </t-dialog>

        <!-- 导入结果 -->
        <t-dialog v-model:visible="importResult.visible" header="导入结果" width="620px" attach="body" :destroy-on-close="true">
          <t-descriptions v-if="importResult.data" :column="4" bordered size="small" :items="importSummaryItems" />
          <t-alert
            v-if="importResult.data && importResult.data.failed === 0"
            class="mt-3"
            theme="success"
            :close="false"
            message="全部数据处理完成"
          />
          <t-table
            v-else
            class="erp-table mt-3"
            row-key="row"
            :data="importResult.data?.errors ?? []"
            :columns="importErrorColumns"
            :resizable="true"
            size="small"
            max-height="320"
            :empty="'无错误行'"
            @column-resize-change="(ctx: any) => saveColumnWidths('master-customer/import-errors', ctx.columnsWidth)"
          />
          <template #footer>
            <t-button theme="primary" @click="importResult.visible = false">关 闭</t-button>
          </template>
        </t-dialog>

        <t-dialog v-model:visible="dialog.visible" :header="dialog.title" width="640px" attach="body">
          <t-form ref="customerFormRef" :data="form" :rules="rules" label-align="top">
            <t-row :gutter="16">
              <t-col :span="6">
                <t-form-item label="编码" name="code">
                  <t-input v-model="form.code" placeholder="留空自动生成（按名称）" />
                </t-form-item>
              </t-col>
              <t-col :span="6">
                <t-form-item label="名称" name="name">
                  <t-input v-model="form.name" placeholder="请输入客户名称" />
                </t-form-item>
              </t-col>
              <t-col :span="6">
                <t-form-item label="联系人" name="contact">
                  <t-input v-model="form.contact" placeholder="请输入联系人" />
                </t-form-item>
              </t-col>
              <t-col :span="6">
                <t-form-item label="电话" name="phone">
                  <t-input v-model="form.phone" placeholder="请输入联系电话" />
                </t-form-item>
              </t-col>
              <t-col :span="6">
                <t-form-item label="期初欠款" help="以财务→期初往来确认的期初为准，此处不可编辑">
                  <t-input :value="openingAmountText(openingDebtDisplay)" disabled />
                </t-form-item>
              </t-col>
              <t-col :span="6">
                <t-form-item label="税率" name="taxRate">
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
              <t-col :span="6">
                <t-form-item label="税号" name="taxNo">
                  <t-input v-model="form.taxNo" placeholder="请输入税号" />
                </t-form-item>
              </t-col>
              <t-col :span="6">
                <t-form-item label="状态" name="status">
                  <t-radio-group v-model="form.status">
                    <t-radio :value="1">启用</t-radio>
                    <t-radio :value="0">停用</t-radio>
                  </t-radio-group>
                </t-form-item>
              </t-col>
              <t-col :span="12">
                <t-form-item label="地址" name="address">
                  <t-input v-model="form.address" placeholder="请输入地址" />
                </t-form-item>
              </t-col>
            </t-row>
            <t-form-item label="备注" name="remark">
              <t-textarea v-model="form.remark" :autosize="{ minRows: 2, maxRows: 4 }" placeholder="请输入内容" />
            </t-form-item>
          </t-form>
          <template #footer>
            <t-button theme="default" variant="outline" @click="cancel">取 消</t-button>
            <t-button theme="primary" @click="submitForm">确 定</t-button>
          </template>
        </t-dialog>
      </t-col>
    </t-row>
  </div>
</template>

<script setup name="ErpCustomer" lang="ts">
import { listCustomer, getCustomer, addCustomer, updateCustomer, delCustomer } from '@/api/erp/master';
import type { ErpImportResult } from '@/api/erp/master';
import type { ErpAmount, ErpCustomerForm, ErpCustomerVO } from '@/api/erp/types';
import { confirmedOpeningByPartyIds } from '@/api/erp/finance';
import { formatPercent } from '@/utils/erp/calc';
import { openingAmountText } from '@/utils/erp/openingDisplay';
import { checkPermi } from '@/utils/permission';
import { globalHeaders } from '@/utils/request';
import { sanitizeImportMessage } from '@/utils/sanitizeImportMessage';
// 列宽记忆：拖拽后按表名存 localStorage，进页面还原（见 hooks/useTableColumnWidth）
import { mergeSavedWidths, saveColumnWidths } from '@/hooks/useTableColumnWidth';
import { ChevronDownIcon, CloudUploadIcon, DeleteIcon, DownloadIcon, Edit1Icon, PlusIcon, UploadIcon } from 'tdesign-icons-vue-next';

interface CustomerQuery extends PageQuery {
  keyword?: string;
  status?: number | '';
}

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const customerList = ref<ErpCustomerVO[]>([]);
/** 仅当前页有权读取的已确认期初；缺失不是0，不随表单提交。 */
const openingDebtDisplay = ref<ErpAmount>();
const loading = ref(true);
const showSearch = ref(true);
const ids = ref<Array<string | number>>([]);
/** 工具栏「修改」禁用条件：勾选行数不是 1（原 ref 手写同步，改由 ids 派生） */
const single = computed(() => ids.value.length !== 1);
/** 工具栏「删除」禁用条件：一行未勾选 */
const multiple = computed(() => ids.value.length === 0);
const total = ref(0);
/** 查询表单：已换 t-form，ref 提供 reset()（EP 是 resetFields()） */
const queryFormRef = ref<any>();
const customerFormRef = ref<any>();
const uploadRef = ref<any>();
/** 导入弹窗内已选文件：t-upload 受控列表，替代 EP 实例的 clearFiles() */
const uploadFileList = ref<any[]>([]);

/** 导入参数 */
const upload = reactive<ImportOption>({
  open: false,
  title: '',
  isUploading: false,
  updateSupport: 0,
  headers: globalHeaders(),
  url: import.meta.env.VITE_APP_BASE_API + '/erp/master/customers/importData'
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

const initFormData: ErpCustomerForm = {
  id: undefined,
  code: '',
  name: '',
  contact: '',
  phone: '',
  address: '',
  taxNo: '',
  taxRate: 0,
  status: 1,
  remark: ''
};

const data = reactive<{ form: ErpCustomerForm; queryParams: CustomerQuery }>({
  form: { ...initFormData },
  queryParams: {
    pageNum: 1,
    pageSize: 10,
    keyword: '',
    status: ''
  }
});

const { queryParams, form } = toRefs<{ form: ErpCustomerForm; queryParams: CustomerQuery }>(data);

/** 表单校验规则：t-form 的 rules 形状与 EP 的 ElFormRules 不兼容，单独用 Record 声明 */
const rules: Record<string, any> = {
  name: [{ required: true, message: '客户名称不能为空', trigger: 'blur' }]
};

/** 状态筛选项：沿用原 el-option 的固定两项 */
const statusOptions = [
  { label: '启用', value: 1 },
  { label: '停用', value: 0 }
];

/** 主列表列配置：t-table 用 columns 数组，插槽名与 cell 字符串同名（DOC/35 §5） */
const listColumns: any[] = [
  { colKey: 'row-select', type: 'multiple', width: 46, align: 'center' },
  { colKey: 'code', title: '客户编码', align: 'center', width: 130, ellipsis: true },
  { colKey: 'name', title: '客户名称', align: 'center', minWidth: 140, ellipsis: true },
  { colKey: 'contact', title: '联系人', align: 'center', width: 100, ellipsis: true },
  { colKey: 'phone', title: '电话', align: 'center', width: 130, ellipsis: true },
  { colKey: 'address', title: '地址', align: 'center', minWidth: 140, ellipsis: true },
  { colKey: 'openingDebt', title: '期初欠款', align: 'right', width: 120, cell: 'openingDebt' },
  { colKey: 'taxNo', title: '税号', align: 'center', width: 130, ellipsis: true },
  { colKey: 'taxRate', title: '税率', align: 'right', width: 90, cell: 'taxRate' },
  { colKey: 'status', title: '状态', align: 'center', width: 80, cell: 'status' },
  { colKey: 'remark', title: '备注', align: 'center', minWidth: 120, ellipsis: true },
  { colKey: 'operation', title: '操作', align: 'center', width: 80, cell: 'operation' }
];
mergeSavedWidths('master-customer/list', listColumns);

/** 导入失败明细列配置 */
const importErrorColumns: any[] = [
  { colKey: 'row', title: '行号', align: 'center', width: 90 },
  { colKey: 'message', title: '失败原因', minWidth: 380, ellipsis: true }
];
mergeSavedWidths('master-customer/import-errors', importErrorColumns);

/** 导入结果概要：t-descriptions 用 items 数组渲染（替代 el-descriptions-item） */
const importSummaryItems = computed<any[]>(() => [
  { label: '总行数', content: String(importResult.data?.total ?? 0) },
  { label: '成功', content: String(importResult.data?.success ?? 0) },
  { label: '跳过', content: String(importResult.data?.skipped ?? 0) },
  { label: '失败', content: String(importResult.data?.failed ?? 0) }
]);

let listRequest = 0;
onBeforeUnmount(() => {
  ++listRequest;
});
/** 主数据保持旧分页；期初仅请求此页ID，无权、失败、遗漏一律未知。 */
const getList = async () => {
  const current = ++listRequest;
  loading.value = true;
  try {
    const res = await listCustomer({ ...queryParams.value });
    if (current !== listRequest) return;
    const rows: ErpCustomerVO[] = (res.rows ?? []).map((row) => ({ ...row, openingDebt: undefined }));
    customerList.value = rows;
    total.value = res.total ?? 0;
    ids.value = [];
    if (rows.length && checkPermi(['erp:finance:list'])) {
      try {
        const result = await confirmedOpeningByPartyIds(
          'AR',
          rows.map((row) => row.id)
        );
        if (current !== listRequest || result.code !== 200 || !Array.isArray(result.data)) return;
        const openings = new Map(result.data.map((p) => [String(p.partyId), p.amount]));
        customerList.value = rows.map((row) => ({ ...row, openingDebt: openings.get(String(row.id)) }));
      } catch {
        // 不回退全量summary；未知不冒充0，主数据仍可使用。
      }
    }
  } finally {
    if (current === listRequest) loading.value = false;
  }
};

/** 搜索按钮操作 */
const handleQuery = () => {
  queryParams.value.pageNum = 1;
  getList();
};

/** 重置按钮操作 */
const resetQuery = () => {
  queryFormRef.value?.reset();
  queryParams.value.keyword = '';
  queryParams.value.status = '';
  queryParams.value.pageNum = 1;
  handleQuery();
};

/** 多选框选中数据：t-table 的 select-change 只回传选中的 rowKey（rowKey=id） */
const handleSelectionChange = (keys: Array<string | number>) => {
  ids.value = keys;
};

/** 表单重置：t-form 的 reset() 默认 resetType='empty'，会把数值/单选清成 undefined，这里以 initFormData 为准，只清校验态 */
const reset = () => {
  form.value = { ...initFormData };
  openingDebtDisplay.value = undefined;
  customerFormRef.value?.clearValidate();
};

/** 新增按钮操作 */
const handleAdd = () => {
  reset();
  dialog.visible = true;
  dialog.title = '添加客户';
};

/** 修改按钮操作 */
const handleUpdate = async (row?: ErpCustomerVO) => {
  reset();
  const id = row?.id ?? ids.value[0];
  const res = await getCustomer(id);
  const detail = res.data;
  // 仅复制可编辑字段，详情中的历史openingDebt及其它只读字段不得混入提交。
  Object.assign(form.value, Object.fromEntries((Object.keys(initFormData) as Array<keyof ErpCustomerForm>).map((key) => [key, detail[key]])));
  const listed = customerList.value.find((row) => String(row.id) === String(id));
  openingDebtDisplay.value = listed?.openingDebt;
  form.value.taxRate = Number(detail.taxRate ?? 0);
  form.value.status = detail.status ?? 1;
  dialog.visible = true;
  dialog.title = '修改客户';
};

/** 取消按钮 */
const cancel = () => {
  reset();
  dialog.visible = false;
};

/** 提交按钮 */
const submitForm = async () => {
  // TDesign 的 t-form.validate() 成功 resolve(true)，失败 resolve(false) 或错误对象（不 reject）
  const result = await customerFormRef.value?.validate().catch(() => false);
  const valid = result === true || result === undefined;
  if (!valid) return;
  form.value.id ? await updateCustomer(form.value) : await addCustomer(form.value);
  proxy?.$modal.msgSuccess('操作成功');
  dialog.visible = false;
  await getList();
};

/** 删除按钮操作 */
const handleDelete = async (row?: ErpCustomerVO) => {
  const customerIds = row?.id ?? ids.value;
  try {
    await proxy?.$modal.confirm('是否确认删除客户编号为"' + customerIds + '"的数据项？');
  } catch {
    return;
  }
  await delCustomer(customerIds);
  await getList();
  proxy?.$modal.msgSuccess('删除成功');
};

/** 导入上传响应（后端 R<ImportResult>） */
interface ImportUploadResponse {
  code: number;
  msg: string;
  data?: ErpImportResult;
}

/** 打开导入弹窗 */
const handleImport = () => {
  upload.title = '客户导入';
  upload.open = true;
};

/** 导出客户 */
const handleExport = () => {
  proxy?.download('/erp/master/customers/export', { ...queryParams.value }, `customers_${Date.now()}.xlsx`);
};

/** 下载导入模板 */
const importTemplate = () => {
  proxy?.download('/erp/master/customers/importTemplate', {}, `customer_template_${Date.now()}.xlsx`);
};

/** 文件上传中 */
const handleFileUploadProgress = () => {
  upload.isUploading = true;
};

/** 「是否更新已存在的数据」：t-checkbox 只给 boolean，写回导入参数时映射成 1/0 */
const onUpdateSupportChange = (checked: boolean) => {
  upload.updateSupport = checked ? 1 : 0;
};

/** 文件上传成功：t-upload 回调给上下文对象，取 response 判断后端业务码（仍按 EP 时代逻辑） */
const handleFileSuccess = (ctx: any) => {
  const response = ctx?.response as ImportUploadResponse | undefined;
  upload.isUploading = false;
  upload.open = false;
  uploadFileList.value = [];
  if (!response || response.code !== 200 || !response.data) {
    proxy?.$modal.msgError(sanitizeImportMessage(response?.msg) || '导入失败，请检查文件内容');
    return;
  }
  importResult.data = {
    ...response.data,
    errors: response.data.errors?.map((error) => ({ ...error, message: sanitizeImportMessage(error.message) })) ?? []
  };
  importResult.visible = true;
  getList();
};

/** 文件上传失败：t-upload 回调给 { file, response }，优先取后端 msg */
const handleFileError = (ctx: any) => {
  upload.isUploading = false;
  proxy?.$modal.msgError(sanitizeImportMessage(ctx?.response?.msg) || '导入失败，请检查网络或文件');
};

/** 提交上传文件：t-upload 关闭自动上传后由按钮触发，无参即提交全部待上传文件 */
const submitFileForm = () => {
  uploadRef.value?.uploadFiles();
};

onMounted(() => {
  getList();
});
</script>
