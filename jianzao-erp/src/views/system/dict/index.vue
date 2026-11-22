<template>
  <div class="p-2 dict-page">
    <t-row :gutter="16" class="dict-grid">
      <!-- 字典类型 -->
      <t-col :xs="12" :lg="6">
        <t-card class="dict-card">
          <template #header>
            <div class="dict-card__header">
              <div class="dict-card__title">字典管理</div>
              <right-toolbar v-model:show-search="showTypeSearch" @query-table="getTypeList" />
            </div>
          </template>

          <div v-show="showTypeSearch" class="dict-form-scroll">
            <t-form ref="typeQueryFormRef" :data="typeQueryParams" class="erp-filter-row">
              <t-form-item label="字典名称" name="dictName">
                <t-input v-model="typeQueryParams.dictName" placeholder="请输入字典名称" clearable @enter="handleTypeQuery" />
              </t-form-item>
              <t-form-item label="字典类型" name="dictType">
                <t-input v-model="typeQueryParams.dictType" placeholder="请输入字典类型" clearable @enter="handleTypeQuery" />
              </t-form-item>
              <t-form-item label="创建时间" name="createTime">
                <!-- 值类型保持 YYYY-MM-DD HH:mm:ss 字符串，addDateRange 写入的 beginTime/endTime 请求字段格式不变 -->
                <t-date-range-picker
                  v-model="dateRange"
                  value-type="YYYY-MM-DD HH:mm:ss"
                  :placeholder="['开始日期', '结束日期']"
                  style="width: 308px"
                />
              </t-form-item>
              <div class="erp-filter-actions">
                <t-button theme="primary" @click="handleTypeQuery">
                  <template #icon><SearchIcon /></template>
                  搜索
                </t-button>
                <t-button theme="default" variant="outline" @click="handleTypeResetQuery">
                  <template #icon><RefreshIcon /></template>
                  重置
                </t-button>
              </div>
            </t-form>
          </div>

          <div class="dict-actions">
            <t-button v-hasPermi="['system:dict:add']" theme="primary" variant="outline" @click="handleTypeAdd">
              <template #icon><PlusIcon /></template>
              新增
            </t-button>
            <t-button v-hasPermi="['system:dict:edit']" theme="success" variant="outline" :disabled="typeSingle" @click="handleTypeUpdate()">
              <template #icon><Edit1Icon /></template>
              修改
            </t-button>
            <t-button v-hasPermi="['system:dict:remove']" theme="danger" variant="outline" :disabled="typeMultiple" @click="handleTypeDelete()">
              <template #icon><DeleteIcon /></template>
              删除
            </t-button>
            <t-button v-hasPermi="['system:dict:export']" theme="warning" variant="outline" @click="handleTypeExport">
              <template #icon><DownloadIcon /></template>
              导出
            </t-button>
            <t-button v-hasPermi="['system:dict:remove']" theme="danger" variant="outline" @click="handleRefreshCache">
              <template #icon><RefreshIcon /></template>
              刷新缓存
            </t-button>
          </div>

          <div class="dict-table-wrap">
            <t-table
              class="erp-table"
              row-key="dictId"
              :data="typeList"
              :columns="typeColumns"
              :loading="typeLoading"
              :active-row-keys="currentDict ? [currentDict.dictId] : []"
              bordered
              size="small"
              cell-empty-content="-"
              :resizable="true"
              :selected-row-keys="typeIds"
              @row-click="(ctx: any) => handleTypeRowClick(ctx.row)"
              @select-change="handleTypeSelectionChange"
              @column-resize-change="(ctx: any) => saveColumnWidths('system/dict/type', ctx.columnsWidth)"
            >
              <template #dictType="{ row }">
                <span class="link-type" @click.stop="handleTypeRowClick(row)">{{ row.dictType }}</span>
              </template>
              <template #createTime="{ row }">{{ proxy.parseTime(row.createTime) }}</template>
              <template #operation="{ row }">
                <!-- 权限判断挂外层 t-tooltip：v-hasPermi 在 mounted 里 removeChild，与 tooltip 包裹结构打架（DOC/36 P1） -->
                <t-tooltip v-if="checkPermi(['system:dict:edit'])" content="修改" placement="top">
                  <t-button theme="primary" variant="text" shape="square" size="small" @click="handleTypeUpdate(row)">
                    <template #icon><Edit1Icon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="checkPermi(['system:dict:remove'])" content="删除" placement="top">
                  <t-button theme="danger" variant="text" shape="square" size="small" @click="handleTypeDelete(row)">
                    <template #icon><DeleteIcon /></template>
                  </t-button>
                </t-tooltip>
              </template>
            </t-table>
          </div>

          <pagination
            v-show="typeTotal > 0"
            v-model:page="typeQueryParams.pageNum"
            v-model:limit="typeQueryParams.pageSize"
            :total="typeTotal"
            @pagination="getTypeList"
          />
        </t-card>
      </t-col>

      <!-- 字典数据 -->
      <t-col :xs="12" :lg="6">
        <t-card class="dict-card">
          <template #header>
            <div class="dict-card__header">
              <div class="dict-card__title">
                字典数据
                <span class="dict-card__subtitle">{{ currentDictLabel }}</span>
              </div>
              <right-toolbar v-model:show-search="showDataSearch" @query-table="getDataList" />
            </div>
          </template>

          <div v-show="showDataSearch" class="dict-form-scroll">
            <t-form ref="dataQueryFormRef" :data="dataQueryParams" class="erp-filter-row">
              <t-form-item label="字典标签" name="dictLabel">
                <t-input
                  v-model="dataQueryParams.dictLabel"
                  placeholder="请输入字典标签"
                  clearable
                  :disabled="!hasCurrentDict"
                  @enter="handleDataQuery"
                />
              </t-form-item>
              <div class="erp-filter-actions">
                <t-button theme="primary" :disabled="!hasCurrentDict" @click="handleDataQuery">
                  <template #icon><SearchIcon /></template>
                  搜索
                </t-button>
                <t-button theme="default" variant="outline" :disabled="!hasCurrentDict" @click="handleDataResetQuery">
                  <template #icon><RefreshIcon /></template>
                  重置
                </t-button>
              </div>
            </t-form>
          </div>

          <div class="dict-actions">
            <t-button v-hasPermi="['system:dict:add']" theme="primary" variant="outline" :disabled="!hasCurrentDict" @click="handleDataAdd">
              <template #icon><PlusIcon /></template>
              新增
            </t-button>
            <t-button
              v-hasPermi="['system:dict:edit']"
              theme="success"
              variant="outline"
              :disabled="dataSingle || !hasCurrentDict"
              @click="handleDataUpdate()"
            >
              <template #icon><Edit1Icon /></template>
              修改
            </t-button>
            <t-button
              v-hasPermi="['system:dict:remove']"
              theme="danger"
              variant="outline"
              :disabled="dataMultiple || !hasCurrentDict"
              @click="handleDataDelete()"
            >
              <template #icon><DeleteIcon /></template>
              删除
            </t-button>
            <t-button v-hasPermi="['system:dict:export']" theme="warning" variant="outline" :disabled="!hasCurrentDict" @click="handleDataExport">
              <template #icon><DownloadIcon /></template>
              导出
            </t-button>
          </div>

          <div class="dict-table-wrap">
            <t-table
              class="erp-table"
              row-key="dictCode"
              :data="dataList"
              :columns="dataColumns"
              :loading="dataLoading"
              bordered
              size="small"
              cell-empty-content="-"
              :resizable="true"
              :selected-row-keys="dataIds"
              @select-change="handleDataSelectionChange"
              @column-resize-change="(ctx: any) => saveColumnWidths('system/dict/data', ctx.columnsWidth)"
            >
              <template #dictLabel="{ row }">
                <span v-if="(row.listClass === '' || row.listClass === 'default') && (row.cssClass === '' || row.cssClass == null)">{{
                  row.dictLabel
                }}</span>
                <t-tag v-else :theme="listClassTheme(row.listClass)" :class="row.cssClass">{{ row.dictLabel }}</t-tag>
              </template>
              <template #createTime="{ row }">{{ proxy.parseTime(row.createTime) }}</template>
              <template #operation="{ row }">
                <!-- 权限判断挂外层 t-tooltip：v-hasPermi 在 mounted 里 removeChild，与 tooltip 包裹结构打架（DOC/36 P1） -->
                <t-tooltip v-if="checkPermi(['system:dict:edit'])" content="修改" placement="top">
                  <t-button theme="primary" variant="text" shape="square" size="small" @click="handleDataUpdate(row)">
                    <template #icon><Edit1Icon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="checkPermi(['system:dict:remove'])" content="删除" placement="top">
                  <t-button theme="danger" variant="text" shape="square" size="small" @click="handleDataDelete(row)">
                    <template #icon><DeleteIcon /></template>
                  </t-button>
                </t-tooltip>
              </template>
            </t-table>
          </div>

          <pagination
            v-show="dataTotal > 0"
            v-model:page="dataQueryParams.pageNum"
            v-model:limit="dataQueryParams.pageSize"
            :total="dataTotal"
            @pagination="getDataList"
          />
        </t-card>
      </t-col>
    </t-row>

    <!-- 字典类型对话框 -->
    <t-dialog v-model:visible="typeDialog.visible" :header="typeDialog.title" width="500px" attach="body">
      <t-form ref="typeFormRef" :data="typeForm" :rules="typeRules" label-width="100px">
        <t-form-item label="字典名称" name="dictName">
          <t-input v-model="typeForm.dictName" placeholder="请输入字典名称" />
        </t-form-item>
        <t-form-item name="dictType">
          <template #label>
            <span>
              <t-tooltip content="数据存储中的Key值，如：sys_user_sex" placement="top">
                <HelpCircleIcon />
              </t-tooltip>
              字典类型
            </span>
          </template>
          <t-input v-model="typeForm.dictType" placeholder="请输入字典类型" maxlength="100" />
        </t-form-item>
        <t-form-item label="备注" name="remark">
          <t-textarea v-model="typeForm.remark" placeholder="请输入内容"></t-textarea>
        </t-form-item>
      </t-form>
      <template #footer>
        <div class="dialog-footer">
          <t-button theme="primary" @click="submitTypeForm">确 定</t-button>
          <t-button theme="default" variant="outline" @click="cancelType">取 消</t-button>
        </div>
      </template>
    </t-dialog>

    <!-- 字典数据对话框 -->
    <t-dialog v-model:visible="dataDialog.visible" :header="dataDialog.title" width="500px" attach="body">
      <t-form ref="dataFormRef" :data="dataForm" :rules="dataRules" label-width="80px">
        <t-form-item label="字典类型" name="dictType">
          <t-input v-model="dataForm.dictType" :disabled="true" />
        </t-form-item>
        <t-form-item label="数据标签" name="dictLabel">
          <t-input v-model="dataForm.dictLabel" placeholder="请输入数据标签" />
        </t-form-item>
        <t-form-item label="数据键值" name="dictValue">
          <t-input v-model="dataForm.dictValue" placeholder="请输入数据键值" />
        </t-form-item>
        <t-form-item label="样式属性" name="cssClass">
          <t-input v-model="dataForm.cssClass" placeholder="请输入样式属性" />
        </t-form-item>
        <t-form-item label="显示排序" name="dictSort">
          <t-input-number v-model="dataForm.dictSort" theme="normal" :min="0" style="width: 100%" />
        </t-form-item>
        <t-form-item label="回显样式" name="listClass">
          <t-select v-model="dataForm.listClass" :options="listClassOptions" />
        </t-form-item>
        <t-form-item label="备注" name="remark">
          <t-textarea v-model="dataForm.remark" placeholder="请输入内容"></t-textarea>
        </t-form-item>
      </t-form>
      <template #footer>
        <div class="dialog-footer">
          <t-button theme="primary" @click="submitDataForm">确 定</t-button>
          <t-button theme="default" variant="outline" @click="cancelData">取 消</t-button>
        </div>
      </template>
    </t-dialog>
  </div>
</template>

<script setup name="Dict" lang="ts">
import { useDictStore } from '@/store/modules/dict';
import { listType, getType, delType, addType, updateType, refreshCache } from '@/api/system/dict/type';
import { listData, getData, delData, addData, updateData } from '@/api/system/dict/data';
import { DictTypeForm, DictTypeQuery, DictTypeVO } from '@/api/system/dict/type/types';
import { DictDataForm, DictDataQuery, DictDataVO } from '@/api/system/dict/data/types';
import { checkPermi } from '@/utils/permission';
import { mergeSavedWidths, saveColumnWidths } from '@/hooks/useTableColumnWidth';
import { DeleteIcon, DownloadIcon, Edit1Icon, HelpCircleIcon, PlusIcon, RefreshIcon, SearchIcon } from 'tdesign-icons-vue-next';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const typeList = ref<DictTypeVO[]>([]);
const typeLoading = ref(true);
const showTypeSearch = ref(true);
const typeIds = ref<Array<number | string>>([]);
const typeSingle = ref(true);
const typeMultiple = ref(true);
const typeTotal = ref(0);
/** 创建时间区间：t-date-range-picker 输出 YYYY-MM-DD HH:mm:ss 字符串数组，addDateRange 读取 [0]/[1] 不变 */
const dateRange = ref<[string, string]>(['', '']);

/** t-form 实例提供 reset()/clearValidate()/validate()，与 EP 表单实例契约不同 */
const typeFormRef = ref<any>();
const typeQueryFormRef = ref<any>();

const typeDialog = reactive<DialogOption>({
  visible: false,
  title: ''
});

const typeInitFormData: DictTypeForm = {
  dictId: undefined,
  dictName: '',
  dictType: '',
  remark: ''
};

const typeState = reactive<{ form: DictTypeForm; queryParams: DictTypeQuery }>({
  form: { ...typeInitFormData },
  queryParams: {
    pageNum: 1,
    pageSize: 10,
    dictName: '',
    dictType: ''
  }
});

const { queryParams: typeQueryParams, form: typeForm } = toRefs<{ form: DictTypeForm; queryParams: DictTypeQuery }>(typeState);

/** 表单校验规则：t-form 的 rules 形状与 EP 的 ElFormRules 不兼容，单独用 Record 声明（DOC/35 §5） */
const typeRules: Record<string, any> = {
  dictName: [{ required: true, message: '字典名称不能为空', trigger: 'blur' }],
  dictType: [{ required: true, message: '字典类型不能为空', trigger: 'blur' }]
};

/** 字典类型列表列配置：t-table 用 columns 数组，cell 字符串与 #插槽同名（DOC/35 §5） */
const typeColumns: any[] = [
  { colKey: 'row-select', type: 'multiple', width: 55, align: 'center' },
  { colKey: 'dictName', title: '字典名称', align: 'center', width: 120 },
  { colKey: 'dictType', title: '字典类型', align: 'center', width: 160, cell: 'dictType' },
  { colKey: 'remark', title: '备注', align: 'center', width: 160, ellipsis: true },
  { colKey: 'createTime', title: '创建时间', align: 'center', width: 180, cell: 'createTime' },
  { colKey: 'operation', title: '操作', align: 'center', fixed: 'right', width: 120, cell: 'operation' }
];
mergeSavedWidths('system/dict/type', typeColumns);

const currentDict = ref<DictTypeVO | null>(null);
const hasCurrentDict = computed(() => !!currentDict.value);
const currentDictLabel = computed(() => {
  if (!currentDict.value) return '请先选择字典';
  return `${currentDict.value.dictName} / ${currentDict.value.dictType}`;
});

const dataList = ref<DictDataVO[]>([]);
const dataLoading = ref(false);
const showDataSearch = ref(true);
const dataIds = ref<Array<string | number>>([]);
const dataSingle = ref(true);
const dataMultiple = ref(true);
const dataTotal = ref(0);

/** t-form 实例提供 reset()/clearValidate()/validate()，与 EP 表单实例契约不同 */
const dataFormRef = ref<any>();
const dataQueryFormRef = ref<any>();

const dataDialog = reactive<DialogOption>({
  visible: false,
  title: ''
});

/** 回显样式下拉：t-select 需要 { label, value } 数组 */
const listClassOptions = ref<Array<{ value: string; label: string }>>([
  { value: 'default', label: '默认(default)' },
  { value: 'primary', label: '主要(primary)' },
  { value: 'success', label: '成功(success)' },
  { value: 'info', label: '信息(info)' },
  { value: 'warning', label: '警告(warning)' },
  { value: 'danger', label: '危险(danger)' }
]);

/** 表单校验规则：t-form 的 rules 形状与 EP 的 ElFormRules 不兼容，单独用 Record 声明（DOC/35 §5） */
const dataRules: Record<string, any> = {
  dictLabel: [{ required: true, message: '数据标签不能为空', trigger: 'blur' }],
  dictValue: [{ required: true, message: '数据键值不能为空', trigger: 'blur' }],
  dictSort: [{ required: true, message: '数据顺序不能为空', trigger: 'blur' }]
};

/** 字典数据列表列配置：t-table 用 columns 数组，cell 字符串与 #插槽同名（DOC/35 §5） */
const dataColumns: any[] = [
  { colKey: 'row-select', type: 'multiple', width: 55, align: 'center' },
  { colKey: 'dictLabel', title: '字典标签', align: 'center', width: 80, cell: 'dictLabel' },
  { colKey: 'dictValue', title: '字典键值', align: 'center', width: 80 },
  { colKey: 'dictSort', title: '字典排序', align: 'center', width: 80 },
  { colKey: 'remark', title: '备注', align: 'center', width: 100, ellipsis: true },
  { colKey: 'createTime', title: '创建时间', align: 'center', width: 180, cell: 'createTime' },
  { colKey: 'operation', title: '操作', align: 'center', fixed: 'right', width: 120, cell: 'operation' }
];
mergeSavedWidths('system/dict/data', dataColumns);

const dataInitFormData: DictDataForm = {
  dictCode: undefined,
  dictLabel: '',
  dictValue: '',
  cssClass: '',
  listClass: 'primary',
  dictSort: 0,
  remark: ''
};

const dataState = reactive<{ form: DictDataForm; queryParams: DictDataQuery }>({
  form: { ...dataInitFormData },
  queryParams: {
    pageNum: 1,
    pageSize: 10,
    dictName: '',
    dictType: '',
    dictLabel: ''
  }
});

const { queryParams: dataQueryParams, form: dataForm } = toRefs<{ form: DictDataForm; queryParams: DictDataQuery }>(dataState);

/** 字典数据回显样式 → t-tag theme：EP type 的 info/primary 映射为 TDesign 合法取值 */
const listClassTheme = (listClass?: string): 'default' | 'primary' | 'success' | 'warning' | 'danger' => {
  if (listClass === 'info') return 'default';
  if (listClass === 'primary' || listClass === 'default') return 'primary';
  if (listClass === 'success' || listClass === 'warning' || listClass === 'danger') return listClass;
  return 'default';
};

const getTypeList = () => {
  typeLoading.value = true;
  listType(proxy?.addDateRange(typeQueryParams.value, dateRange.value)).then((res) => {
    typeList.value = res.rows;
    typeTotal.value = res.total;
    typeLoading.value = false;
    ensureCurrentType();
  });
};

const ensureCurrentType = () => {
  if (!typeList.value.length) {
    currentDict.value = null;
    dataQueryParams.value.dictType = '';
    dataList.value = [];
    dataTotal.value = 0;
    return;
  }

  const current = currentDict.value && typeList.value.find((item) => item.dictId === currentDict.value?.dictId);
  const nextRow = current || typeList.value[0];
  setCurrentType(nextRow);
};

const setCurrentType = (row: DictTypeVO) => {
  currentDict.value = row;
  dataQueryParams.value.dictType = row.dictType;
  dataQueryParams.value.pageNum = 1;
  dataQueryParams.value.dictLabel = '';
  getDataList();
};

/** t-table 的 row-click 回传上下文对象 { row, index, e }，不是 row 本身 */
const handleTypeRowClick = (row: DictTypeVO) => {
  setCurrentType(row);
};

const cancelType = () => {
  resetTypeForm();
  typeDialog.visible = false;
};

/** 表单重置：t-form 只清校验态，表单值以 typeInitFormData 为准 */
const resetTypeForm = () => {
  typeForm.value = { ...typeInitFormData };
  typeFormRef.value?.clearValidate();
};

const handleTypeQuery = () => {
  typeQueryParams.value.pageNum = 1;
  getTypeList();
};

const handleTypeResetQuery = () => {
  dateRange.value = ['', ''];
  typeQueryFormRef.value?.reset();
  typeQueryParams.value.dictName = '';
  typeQueryParams.value.dictType = '';
  handleTypeQuery();
};

const handleTypeAdd = () => {
  resetTypeForm();
  typeDialog.visible = true;
  typeDialog.title = '添加字典类型';
};

/** 多选框选中数据：t-table 的 select-change 只回传选中的 rowKey（rowKey=dictId） */
const handleTypeSelectionChange = (keys: Array<string | number>) => {
  typeIds.value = keys;
  typeSingle.value = keys.length != 1;
  typeMultiple.value = !keys.length;
};

const handleTypeUpdate = async (row?: DictTypeVO) => {
  resetTypeForm();
  const dictId = row?.dictId || typeIds.value[0];
  const res = await getType(dictId);
  Object.assign(typeForm.value, res.data);
  typeDialog.visible = true;
  typeDialog.title = '修改字典类型';
};

/** 提交按钮：t-form.validate() 失败 resolve(false)，不 reject（DOC/35 §5） */
const submitTypeForm = async () => {
  const result = await typeFormRef.value?.validate().catch(() => false);
  const valid = result === true || result === undefined;
  if (!valid) return;
  typeForm.value.dictId ? await updateType(typeForm.value) : await addType(typeForm.value);
  proxy?.$modal.msgSuccess('操作成功');
  typeDialog.visible = false;
  getTypeList();
};

const handleTypeDelete = async (row?: DictTypeVO) => {
  const dictIds = row?.dictId || typeIds.value;
  await proxy?.$modal.confirm('是否确认删除字典编号为"' + dictIds + '"的数据项？');
  await delType(dictIds);
  getTypeList();
  proxy?.$modal.msgSuccess('删除成功');
};

const handleTypeExport = () => {
  proxy?.download(
    'system/dict/type/export',
    {
      ...typeQueryParams.value
    },
    `dict_${new Date().getTime()}.xlsx`
  );
};

const handleRefreshCache = async () => {
  await refreshCache();
  proxy?.$modal.msgSuccess('刷新成功');
  useDictStore().cleanDict();
};

const getDataList = async () => {
  if (!currentDict.value) {
    dataList.value = [];
    dataTotal.value = 0;
    dataLoading.value = false;
    return;
  }
  dataLoading.value = true;
  const res = await listData(dataQueryParams.value);
  dataList.value = res.rows;
  dataTotal.value = res.total;
  dataLoading.value = false;
};

const cancelData = () => {
  dataDialog.visible = false;
  resetDataForm();
};

/** 表单重置：t-form 只清校验态，表单值以 dataInitFormData 为准 */
const resetDataForm = () => {
  dataForm.value = { ...dataInitFormData };
  dataFormRef.value?.clearValidate();
};

const handleDataQuery = () => {
  if (!currentDict.value) return;
  dataQueryParams.value.pageNum = 1;
  getDataList();
};

const handleDataResetQuery = () => {
  dataQueryFormRef.value?.reset();
  dataQueryParams.value.dictLabel = '';
  handleDataQuery();
};

const handleDataAdd = () => {
  if (!currentDict.value) {
    proxy?.$modal.msgWarning('请先选择字典');
    return;
  }
  resetDataForm();
  dataForm.value.dictType = currentDict.value.dictType;
  dataDialog.visible = true;
  dataDialog.title = '添加字典数据';
};

/** 多选框选中数据：t-table 的 select-change 只回传选中的 rowKey（rowKey=dictCode） */
const handleDataSelectionChange = (keys: Array<string | number>) => {
  dataIds.value = keys;
  dataSingle.value = keys.length != 1;
  dataMultiple.value = !keys.length;
};

const handleDataUpdate = async (row?: DictDataVO) => {
  if (!currentDict.value) {
    proxy?.$modal.msgWarning('请先选择字典');
    return;
  }
  resetDataForm();
  const dictCode = row?.dictCode || dataIds.value[0];
  const res = await getData(dictCode);
  Object.assign(dataForm.value, res.data);
  dataDialog.visible = true;
  dataDialog.title = '修改字典数据';
};

/** 提交按钮：t-form.validate() 失败 resolve(false)，不 reject（DOC/35 §5） */
const submitDataForm = async () => {
  const result = await dataFormRef.value?.validate().catch(() => false);
  const valid = result === true || result === undefined;
  if (!valid) return;
  dataForm.value.dictCode ? await updateData(dataForm.value) : await addData(dataForm.value);
  useDictStore().removeDict(dataQueryParams.value.dictType);
  proxy?.$modal.msgSuccess('操作成功');
  dataDialog.visible = false;
  await getDataList();
};

const handleDataDelete = async (row?: DictDataVO) => {
  if (!currentDict.value) {
    proxy?.$modal.msgWarning('请先选择字典');
    return;
  }
  const dictCodes = row?.dictCode || dataIds.value;
  await proxy?.$modal.confirm('是否确认删除字典编码为"' + dictCodes + '"的数据项？');
  await delData(dictCodes);
  await getDataList();
  proxy?.$modal.msgSuccess('删除成功');
  useDictStore().removeDict(dataQueryParams.value.dictType);
};

const handleDataExport = () => {
  if (!currentDict.value) {
    proxy?.$modal.msgWarning('请先选择字典');
    return;
  }
  proxy?.download(
    'system/dict/data/export',
    {
      ...dataQueryParams.value
    },
    `dict_data_${new Date().getTime()}.xlsx`
  );
};

onMounted(() => {
  getTypeList();
});
</script>

<style lang="scss" scoped>
.dict-grid {
  row-gap: 16px;
}

.dict-card__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.dict-card__title {
  display: inline-flex;
  align-items: baseline;
  gap: 8px;
  font-weight: 600;
}

.dict-card__subtitle {
  font-size: 12px;
  color: var(--td-text-color-secondary);
}

.dict-form-scroll {
  max-height: 200px;
  overflow: auto;
  margin-bottom: 12px;
  padding-right: 6px;
  padding-bottom: 4px;
}

.dict-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin: 8px 0 12px;
}

.dict-actions :deep(.t-button) {
  height: 32px;
  padding: 0 14px;
}

.dict-table-wrap {
  overflow-x: auto;
}
</style>
