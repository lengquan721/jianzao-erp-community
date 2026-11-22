<template>
  <div class="p-2">
    <transition :enter-active-class="proxy?.animate.searchAnimate.enter" :leave-active-class="proxy?.animate.searchAnimate.leave">
      <div v-show="showSearch" class="mb-[10px]">
        <t-card>
          <t-form ref="queryFormRef" :data="queryParams" class="erp-filter-row" layout="inline">
            <t-form-item label="文件名" name="fileName">
              <t-input v-model="queryParams.fileName" placeholder="请输入文件名" clearable @enter="handleQuery" />
            </t-form-item>
            <t-form-item label="原名" name="originalName">
              <t-input v-model="queryParams.originalName" placeholder="请输入原名" clearable @enter="handleQuery" />
            </t-form-item>
            <t-form-item label="文件后缀" name="fileSuffix">
              <t-input v-model="queryParams.fileSuffix" placeholder="请输入文件后缀" clearable @enter="handleQuery" />
            </t-form-item>
            <t-form-item label="创建时间">
              <!-- 值类型仍为 YYYY-MM-DD HH:mm:ss 字符串，addDateRange 写入的 beginCreateTime/endCreateTime 请求字段不变 -->
              <t-date-range-picker
                v-model="dateRangeCreateTime"
                value-type="YYYY-MM-DD HH:mm:ss"
                enable-time-picker
                :placeholder="['开始日期', '结束日期']"
                :default-time="['00:00:00', '23:59:59']"
                style="width: 308px"
              />
            </t-form-item>
            <t-form-item label="服务商" name="service">
              <t-input v-model="queryParams.service" placeholder="请输入服务商" clearable @enter="handleQuery" />
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

    <t-card>
      <template #header>
        <div class="oss-toolbar">
          <t-button v-hasPermi="['system:oss:upload']" theme="primary" variant="outline" @click="handleFile">
            <template #icon><UploadIcon /></template>
            上传文件
          </t-button>
          <t-button v-hasPermi="['system:oss:upload']" theme="primary" variant="outline" @click="handleImage">
            <template #icon><UploadIcon /></template>
            上传图片
          </t-button>
          <t-button v-hasPermi="['system:oss:remove']" theme="danger" variant="outline" :disabled="multiple" @click="handleDelete()">
            <template #icon><DeleteIcon /></template>
            删除
          </t-button>
          <t-button
            v-hasPermi="['system:oss:edit']"
            :theme="previewListResource ? 'danger' : 'warning'"
            variant="outline"
            @click="handlePreviewListResource(!previewListResource)"
            >预览开关 : {{ previewListResource ? '禁用' : '启用' }}</t-button
          >
          <t-button v-hasPermi="['system:ossConfig:list']" theme="default" variant="outline" @click="handleOssConfig">
            <template #icon><SettingIcon /></template>
            配置管理
          </t-button>
          <right-toolbar v-model:show-search="showSearch" @query-table="getList"></right-toolbar>
        </div>
      </template>

      <t-table
        v-if="showTable"
        class="erp-table"
        row-key="ossId"
        :data="ossList"
        :columns="ossColumns"
        :loading="loading"
        bordered
        size="small"
        cell-empty-content="-"
        :resizable="true"
        multiple-sort
        :sort="sortInfo"
        :selected-row-keys="ids"
        @sort-change="handleSortChange"
        @select-change="handleSelectionChange"
        @column-resize-change="(ctx: any) => saveColumnWidths('system/oss/list', ctx.columnsWidth)"
      >
        <template #url="{ row }">
          <ImagePreview v-if="previewListResource && checkFileSuffix(row.fileSuffix)" :width="100" :height="100" :src="row.url" />
          <span v-if="!checkFileSuffix(row.fileSuffix) || !previewListResource" v-text="row.url" />
        </template>
        <template #createTime="{ row }">
          <span>{{ proxy.parseTime(row.createTime, '{y}-{m}-{d}') }}</span>
        </template>
        <template #operation="{ row }">
          <!-- 权限判断挂外层 t-tooltip：v-hasPermi 在 mounted 里 removeChild，与 tooltip 包裹结构打架（DOC/36 P1） -->
          <t-tooltip v-if="checkPermi(['system:oss:download'])" content="下载" placement="top">
            <t-button theme="primary" variant="text" shape="square" size="small" @click="handleDownload(row)">
              <template #icon><DownloadIcon /></template>
            </t-button>
          </t-tooltip>
          <t-tooltip v-if="checkPermi(['system:oss:remove'])" content="删除" placement="top">
            <t-button theme="danger" variant="text" shape="square" size="small" @click="handleDelete(row)">
              <template #icon><DeleteIcon /></template>
            </t-button>
          </t-tooltip>
        </template>
      </t-table>

      <pagination v-show="total > 0" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" :total="total" @pagination="getList" />
    </t-card>
    <!-- 添加或修改OSS对象存储对话框 -->
    <t-dialog v-model:visible="dialog.visible" :header="dialog.title" width="500px" attach="body">
      <t-form ref="ossFormRef" :data="form" :rules="rules" label-width="80px">
        <t-form-item label="文件名" name="file">
          <fileUpload v-if="type === 0" v-model="form.file" />
          <imageUpload v-if="type === 1" v-model="form.file" />
        </t-form-item>
      </t-form>
      <template #footer>
        <div class="dialog-footer">
          <t-button :loading="buttonLoading" theme="primary" @click="submitForm">确 定</t-button>
          <t-button theme="default" variant="outline" @click="cancel">取 消</t-button>
        </div>
      </template>
    </t-dialog>
  </div>
</template>

<script setup name="Oss" lang="ts">
import { listOss, delOss } from '@/api/system/oss';
import ImagePreview from '@/components/ImagePreview/index.vue';
import { OssForm, OssQuery, OssVO } from '@/api/system/oss/types';
import { checkPermi } from '@/utils/permission';
import { mergeSavedWidths, saveColumnWidths } from '@/hooks/useTableColumnWidth';
import { DeleteIcon, DownloadIcon, RefreshIcon, SearchIcon, SettingIcon, UploadIcon } from 'tdesign-icons-vue-next';

const router = useRouter();
const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const ossList = ref<OssVO[]>([]);
const showTable = ref(true);
const buttonLoading = ref(false);
const loading = ref(true);
const showSearch = ref(true);
const ids = ref<Array<string | number>>([]);
const single = ref(true);
const multiple = ref(true);
const total = ref(0);
const type = ref(0);
const previewListResource = ref(true);
const dateRangeCreateTime = ref<[DateModelType, DateModelType]>(['', '']);

const dialog = reactive<DialogOption>({
  visible: false,
  title: ''
});

// 默认排序
const defaultSort = ref({ prop: 'createTime', order: 'ascending' });

/** t-form 实例提供 reset()/clearValidate()/validate()，与 EP 表单实例契约不同 */
const ossFormRef = ref<any>();
const queryFormRef = ref<any>();

const initFormData = {
  file: undefined
};
const data = reactive<{ form: OssForm; queryParams: OssQuery }>({
  form: { ...initFormData },
  // 查询参数
  queryParams: {
    pageNum: 1,
    pageSize: 10,
    fileName: '',
    originalName: '',
    fileSuffix: '',
    createTime: '',
    service: '',
    orderByColumn: defaultSort.value.prop,
    isAsc: defaultSort.value.order
  }
});

const { queryParams, form } = toRefs(data);

/** 表单校验规则：t-form 的 rules 形状与 EP 的 ElFormRules 不兼容，单独用 Record 声明（DOC/35 §5） */
const rules: Record<string, any> = {
  file: [{ required: true, message: '文件不能为空', trigger: 'blur' }]
};

/**
 * t-table 多列排序受控值：数组顺序即 orderByColumn 的逗号拼接顺序，descending 映射回 ascending/descending。
 * 默认与 EP 的 defaultSort（createTime ascending）一致。
 */
const sortInfo = ref<any>([{ sortBy: defaultSort.value.prop, descending: defaultSort.value.order === 'descending' }]);

/** OSS 列表列配置：prop→colKey，模板列走同名 cell 插槽，可排序列保留 sorter */
const ossColumns: any[] = [
  { colKey: 'row-select', type: 'multiple', width: 55, align: 'center' },
  { colKey: 'fileName', title: '文件名', align: 'center', ellipsis: true },
  { colKey: 'originalName', title: '原名', align: 'center', ellipsis: true },
  { colKey: 'fileSuffix', title: '文件后缀', align: 'center', ellipsis: true },
  { colKey: 'url', title: '文件展示', align: 'center', cell: 'url' },
  { colKey: 'createTime', title: '创建时间', align: 'center', width: 180, sorter: true, cell: 'createTime' },
  { colKey: 'createByName', title: '上传人', align: 'center', ellipsis: true },
  { colKey: 'service', title: '服务商', align: 'center', sorter: true, ellipsis: true },
  { colKey: 'operation', title: '操作', align: 'center', width: 100, className: 'small-padding fixed-width', cell: 'operation' }
];
mergeSavedWidths('system/oss/list', ossColumns);

/** 查询OSS对象存储列表 */
const getList = async () => {
  loading.value = true;
  const res = await proxy?.getConfigKey('sys.oss.previewListResource');
  previewListResource.value = res?.data === undefined ? true : res.data === 'true';
  const response = await listOss(proxy?.addDateRange(queryParams.value, dateRangeCreateTime.value, 'CreateTime'));
  ossList.value = response.rows;
  total.value = response.total;
  loading.value = false;
  showTable.value = true;
};
function checkFileSuffix(fileSuffix: string | string[]) {
  const arr = ['.png', '.jpg', '.jpeg'];
  const suffixArray = Array.isArray(fileSuffix) ? fileSuffix : [fileSuffix];
  return suffixArray.some((suffix) => arr.includes(suffix.toLowerCase()));
}
/** 取消按钮 */
function cancel() {
  dialog.visible = false;
  reset();
}
/** 表单重置：t-form 只清校验态，表单值以 initFormData 为准 */
function reset() {
  form.value = { ...initFormData };
  ossFormRef.value?.clearValidate();
}
/** 搜索按钮操作 */
function handleQuery() {
  queryParams.value.pageNum = 1;
  getList();
}
/** 重置按钮操作：排序态回默认，保留 EP 里 showTable=false 触发表格重建的行为 */
function resetQuery() {
  showTable.value = false;
  dateRangeCreateTime.value = ['', ''];
  queryFormRef.value?.reset();
  queryParams.value.fileName = '';
  queryParams.value.originalName = '';
  queryParams.value.fileSuffix = '';
  queryParams.value.service = '';
  sortInfo.value = [{ sortBy: defaultSort.value.prop, descending: defaultSort.value.order === 'descending' }];
  queryParams.value.orderByColumn = defaultSort.value.prop;
  queryParams.value.isAsc = defaultSort.value.order;
  handleQuery();
}
/** 选择条数：t-table 的 select-change 只回传选中的 rowKey（rowKey=ossId） */
function handleSelectionChange(keys: Array<string | number>) {
  ids.value = keys;
  single.value = keys.length != 1;
  multiple.value = !keys.length;
}
/** 排序触发事件：t-table 的 multiple-sort 回传 [{ sortBy, descending }] 数组，合并成后端的多字段排序参数 */
const handleSortChange = (sort: any) => {
  const sorts: Array<{ sortBy: string; descending: boolean }> = Array.isArray(sort) ? sort : sort ? [sort] : [];
  sortInfo.value = sorts;
  queryParams.value.orderByColumn = sorts.map((item) => item.sortBy).join(',');
  queryParams.value.isAsc = sorts.map((item) => (item.descending ? 'descending' : 'ascending')).join(',');
  getList();
};
/** 任务日志列表查询 */
const handleOssConfig = () => {
  router.push('/system/oss-config/index');
};
/** 文件按钮操作 */
const handleFile = () => {
  reset();
  type.value = 0;
  dialog.visible = true;
  dialog.title = '上传文件';
};
/** 图片按钮操作 */
const handleImage = () => {
  reset();
  type.value = 1;
  dialog.visible = true;
  dialog.title = '上传图片';
};
/** 提交按钮 */
const submitForm = () => {
  dialog.visible = false;
  getList();
};
/** 下载按钮操作 */
const handleDownload = (row: OssVO) => {
  proxy?.$download.oss(row.ossId);
};
/** 预览开关按钮  */
const handlePreviewListResource = async (preview: boolean) => {
  const text = preview ? '启用' : '停用';
  try {
    await proxy?.$modal.confirm('确认要"' + text + '""预览列表图片"配置吗?');
    await proxy?.updateConfigByKey('sys.oss.previewListResource', preview);
    await getList();
    proxy?.$modal.msgSuccess(text + '成功');
  } catch {
    return;
  }
};
/** 删除按钮操作 */
const handleDelete = async (row?: OssVO) => {
  const ossIds = row?.ossId || ids.value;
  await proxy?.$modal.confirm('是否确认删除OSS对象存储编号为"' + ossIds + '"的数据项?');
  loading.value = true;
  await delOss(ossIds).finally(() => (loading.value = false));
  await getList();
  proxy?.$modal.msgSuccess('删除成功');
};

onMounted(() => {
  getList();
});
</script>

<style lang="scss" scoped>
.oss-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
</style>
