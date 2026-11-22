<template>
  <div class="p-2">
    <transition :enter-active-class="proxy?.animate.searchAnimate.enter" :leave-active-class="proxy?.animate.searchAnimate.leave">
      <div v-show="showSearch" class="mb-[10px]">
        <t-card hover-shadow>
          <t-form ref="queryFormRef" :data="queryParams" layout="inline" @submit="handleQuery">
            <t-form-item label="配置key" name="configKey">
              <t-input v-model="queryParams.configKey" placeholder="配置key" clearable class="query-w-md" @enter="handleQuery" />
            </t-form-item>
            <t-form-item label="桶名称" name="bucketName">
              <t-input v-model="queryParams.bucketName" placeholder="请输入桶名称" clearable class="query-w-md" @enter="handleQuery" />
            </t-form-item>
            <t-form-item label="是否默认" name="status">
              <t-select v-model="queryParams.status" placeholder="请选择状态" clearable class="query-w-sm" :options="statusOptions" />
            </t-form-item>
            <t-form-item>
              <t-button theme="primary" @click="handleQuery">
                <template #icon><SearchIcon /></template>
                搜索
              </t-button>
              <t-button theme="default" variant="outline" @click="resetQuery">
                <template #icon><RefreshIcon /></template>
                重置
              </t-button>
            </t-form-item>
          </t-form>
        </t-card>
      </div>
    </transition>

    <t-card hover-shadow>
      <template #header>
        <div class="flex items-center justify-between w-full">
          <div class="flex items-center gap-2">
            <t-button v-hasPermi="['system:ossConfig:add']" theme="primary" variant="outline" @click="handleAdd">
              <template #icon><PlusIcon /></template>
              新增
            </t-button>
            <t-button v-hasPermi="['system:ossConfig:edit']" theme="success" variant="outline" :disabled="single" @click="handleUpdate()">
              <template #icon><Edit1Icon /></template>
              修改
            </t-button>
            <t-button v-hasPermi="['system:ossConfig:remove']" theme="danger" variant="outline" :disabled="multiple" @click="handleDelete()">
              <template #icon><DeleteIcon /></template>
              删除
            </t-button>
          </div>
          <right-toolbar v-model:show-search="showSearch" @query-table="getList"></right-toolbar>
        </div>
      </template>

      <t-table
        class="erp-table"
        row-key="ossConfigId"
        :data="ossConfigList"
        :columns="listColumns"
        :loading="loading"
        :hover="true"
        :resizable="true"
        size="small"
        cell-empty-content="-"
        :selected-row-keys="ids"
        @select-change="handleSelectionChange"
        @column-resize-change="(ctx: any) => saveColumnWidths('system-oss-config/list', ctx.columnsWidth)"
      >
        <template #accessPolicy="{ row }">
          <t-tag v-if="row.accessPolicy === '0'" theme="warning" size="small">private</t-tag>
          <t-tag v-if="row.accessPolicy === '1'" theme="success" size="small">public</t-tag>
          <t-tag v-if="row.accessPolicy === '2'" theme="default" size="small">custom</t-tag>
        </template>
        <template #status="{ row }">
          <t-switch v-model="row.status" :custom-value="['0', '1']" size="small" @change="handleStatusChange(row)" />
        </template>
        <template #operation="{ row }">
          <div class="flex items-center justify-center gap-1">
            <!-- 权限判断改挂外层：v-hasPermi 在 mounted 里删节点，会与 t-tooltip 的包裹结构打架 -->
            <t-tooltip v-if="checkPermi(['system:ossConfig:edit'])" content="修改" placement="top">
              <t-button theme="primary" variant="text" shape="square" size="small" @click="handleUpdate(row)">
                <template #icon><Edit1Icon /></template>
              </t-button>
            </t-tooltip>
            <t-tooltip v-if="checkPermi(['system:ossConfig:remove'])" content="删除" placement="top">
              <t-button theme="danger" variant="text" shape="square" size="small" @click="handleDelete(row)">
                <template #icon><DeleteIcon /></template>
              </t-button>
            </t-tooltip>
          </div>
        </template>
      </t-table>

      <pagination v-show="total > 0" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" :total="total" @pagination="getList" />
    </t-card>
    <!-- 添加或修改对象存储配置对话框 -->
    <t-dialog v-model:visible="dialog.visible" :header="dialog.title" width="800px" attach="body" :destroy-on-close="true">
      <t-form ref="ossConfigFormRef" :data="form" :rules="rules" label-align="top">
        <t-form-item label="配置key" name="configKey">
          <t-input v-model="form.configKey" placeholder="请输入配置key" />
        </t-form-item>
        <t-form-item label="访问站点" name="endpoint">
          <t-input v-model="form.endpoint" placeholder="请输入访问站点">
            <template #label
              ><span style="color: #999">{{ protocol }}</span></template
            >
          </t-input>
        </t-form-item>
        <t-form-item label="自定义域名" name="domain">
          <t-input v-model="form.domain" placeholder="请输入自定义域名">
            <template #label
              ><span style="color: #999">{{ protocol }}</span></template
            >
          </t-input>
        </t-form-item>
        <t-form-item label="accessKey" name="accessKey">
          <t-input v-model="form.accessKey" placeholder="请输入accessKey" />
        </t-form-item>
        <t-form-item label="secretKey" name="secretKey">
          <t-input v-model="form.secretKey" placeholder="请输入秘钥" type="password" />
        </t-form-item>
        <t-form-item label="桶名称" name="bucketName">
          <t-input v-model="form.bucketName" placeholder="请输入桶名称" />
        </t-form-item>
        <t-form-item label="前缀" name="prefix">
          <t-input v-model="form.prefix" placeholder="请输入前缀" />
        </t-form-item>
        <t-form-item label="是否HTTPS">
          <t-radio-group v-model="form.isHttps">
            <t-radio v-for="dict in sys_yes_no" :key="dict.value" :value="dict.value">{{ dict.label }}</t-radio>
          </t-radio-group>
        </t-form-item>
        <t-form-item label="桶权限类型">
          <t-radio-group v-model="form.accessPolicy">
            <t-radio value="0">private</t-radio>
            <t-radio value="1">public</t-radio>
            <t-radio value="2">custom</t-radio>
          </t-radio-group>
        </t-form-item>
        <t-form-item label="域" name="region">
          <t-input v-model="form.region" placeholder="请输入域" />
        </t-form-item>
        <t-form-item label="备注" name="remark">
          <t-textarea v-model="form.remark" :autosize="{ minRows: 2, maxRows: 4 }" placeholder="请输入内容" />
        </t-form-item>
      </t-form>
      <template #footer>
        <t-button theme="primary" :loading="buttonLoading" @click="submitForm">确 定</t-button>
        <t-button theme="default" variant="outline" @click="cancel">取 消</t-button>
      </template>
    </t-dialog>
  </div>
</template>

<script setup name="OssConfig" lang="ts">
import { listOssConfig, getOssConfig, delOssConfig, addOssConfig, updateOssConfig, changeOssConfigStatus } from '@/api/system/ossConfig';
import { OssConfigForm, OssConfigQuery, OssConfigVO } from '@/api/system/ossConfig/types';
import { checkPermi } from '@/utils/permission';
// 列宽记忆：拖拽后按表名存 localStorage，进页面还原（见 hooks/useTableColumnWidth）
import { mergeSavedWidths, saveColumnWidths } from '@/hooks/useTableColumnWidth';
import { DeleteIcon, Edit1Icon, PlusIcon, RefreshIcon, SearchIcon } from 'tdesign-icons-vue-next';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const { sys_yes_no } = toRefs<any>(proxy?.useDict('sys_yes_no'));

const ossConfigList = ref<OssConfigVO[]>([]);
const buttonLoading = ref(false);
const loading = ref(true);
const showSearch = ref(true);
const ids = ref<Array<number | string>>([]);
/** 工具栏「修改」禁用条件：勾选行数不是 1；「删除」禁用条件：一行未勾选（原由 selection-change 手写同步，改由 ids 派生） */
const single = computed(() => ids.value.length !== 1);
const multiple = computed(() => ids.value.length === 0);
const total = ref(0);

// 查询区与配置表单均已换 t-form（TDesign 的 ref 提供 reset()/validate()，与 EP 的 resetFields()/validate() 返回契约不同）
const queryFormRef = ref<any>();
const ossConfigFormRef = ref<any>();

const dialog = reactive<DialogOption>({
  visible: false,
  title: ''
});

// 列显隐信息（RightToolbar 的 :columns 契约）
const columns = ref<FieldOption[]>([
  { key: 0, label: `主建`, visible: false },
  { key: 1, label: `配置key`, visible: true },
  { key: 2, label: `访问站点`, visible: true },
  { key: 3, label: `自定义域名`, visible: true },
  { key: 4, label: `桶名称`, visible: true },
  { key: 5, label: `前缀`, visible: true },
  { key: 6, label: `域`, visible: true },
  { key: 7, label: `桶权限类型`, visible: true },
  { key: 8, label: `状态`, visible: true }
]);

/** 状态筛选项：沿用原 el-option 的固定两项 */
const statusOptions = [
  { label: '是', value: '0' },
  { label: '否', value: '1' }
];

const initFormData: OssConfigForm = {
  ossConfigId: undefined,
  configKey: '',
  accessKey: '',
  secretKey: '',
  bucketName: '',
  prefix: '',
  endpoint: '',
  domain: '',
  isHttps: 'N',
  accessPolicy: '1',
  region: '',
  status: '1',
  remark: ''
};

/** t-form 的 rules 形状与 EP 的 ElFormRules 不兼容，用宽松类型声明 */
const rules: Record<string, any> = {
  configKey: [{ required: true, message: 'configKey不能为空', trigger: 'blur' }],
  accessKey: [
    { required: true, message: 'accessKey不能为空', trigger: 'blur' },
    { min: 2, max: 200, message: 'accessKey长度必须介于 2 和 100 之间', trigger: 'blur' }
  ],
  secretKey: [
    { required: true, message: 'secretKey不能为空', trigger: 'blur' },
    { min: 2, max: 100, message: 'secretKey长度必须介于 2 和 100 之间', trigger: 'blur' }
  ],
  bucketName: [
    { required: true, message: 'bucketName不能为空', trigger: 'blur' },
    { min: 2, max: 100, message: 'bucketName长度必须介于 2 和 100 之间', trigger: 'blur' }
  ],
  endpoint: [
    { required: true, message: 'endpoint不能为空', trigger: 'blur' },
    { min: 2, max: 100, message: 'endpoint名称长度必须介于 2 和 100 之间', trigger: 'blur' }
  ],
  accessPolicy: [{ required: true, message: 'accessPolicy不能为空', trigger: 'blur' }]
};

const data = reactive<{ form: OssConfigForm; queryParams: OssConfigQuery }>({
  form: { ...initFormData },
  // 查询参数
  queryParams: {
    pageNum: 1,
    pageSize: 10,
    configKey: '',
    bucketName: '',
    status: ''
  }
});

const { queryParams, form } = toRefs<{ form: OssConfigForm; queryParams: OssConfigQuery }>(data);

const protocol = computed(() => (form.value.isHttps === 'Y' ? 'https://' : 'http://'));

/** 主列表列配置：t-table 用 columns 数组，按 columns[i].visible 显隐（原 el-table-column 的 v-if） */
const listColumns = computed<any[]>(() =>
  mergeSavedWidths('system-oss-config/list', [
    { colKey: 'row-select', type: 'multiple', width: 46, align: 'center' },
    ...(columns.value[0].visible ? [{ colKey: 'ossConfigId', title: '主建', align: 'center', minWidth: 100, ellipsis: true }] : []),
    ...(columns.value[1].visible ? [{ colKey: 'configKey', title: '配置key', align: 'center', minWidth: 120, ellipsis: true }] : []),
    ...(columns.value[2].visible ? [{ colKey: 'endpoint', title: '访问站点', align: 'center', width: 200, ellipsis: true }] : []),
    ...(columns.value[3].visible ? [{ colKey: 'domain', title: '自定义域名', align: 'center', width: 200, ellipsis: true }] : []),
    ...(columns.value[4].visible ? [{ colKey: 'bucketName', title: '桶名称', align: 'center', minWidth: 120, ellipsis: true }] : []),
    ...(columns.value[5].visible ? [{ colKey: 'prefix', title: '前缀', align: 'center', minWidth: 100, ellipsis: true }] : []),
    ...(columns.value[6].visible ? [{ colKey: 'region', title: '域', align: 'center', minWidth: 100, ellipsis: true }] : []),
    ...(columns.value[7].visible ? [{ colKey: 'accessPolicy', title: '桶权限类型', align: 'center', width: 120, cell: 'accessPolicy' }] : []),
    ...(columns.value[8].visible ? [{ colKey: 'status', title: '是否默认', align: 'center', width: 100, cell: 'status' }] : []),
    { colKey: 'operation', title: '操作', align: 'center', width: 100, cell: 'operation' }
  ])
);

/** 查询对象存储配置列表 */
const getList = async () => {
  loading.value = true;
  const res = await listOssConfig(queryParams.value);
  ossConfigList.value = res.rows;
  total.value = res.total;
  // 受控选中：换数据后清空勾选，保持 EP 的 el-table「重取即丢选中」行为
  ids.value = [];
  loading.value = false;
};
/** 取消按钮 */
const cancel = () => {
  dialog.visible = false;
  reset();
};
/** 表单重置：t-form 的 reset() 默认按 empty 清值，这里以 initFormData 为准，只清校验态 */
const reset = () => {
  form.value = { ...initFormData };
  ossConfigFormRef.value?.clearValidate();
};
/** 搜索按钮操作 */
const handleQuery = () => {
  queryParams.value.pageNum = 1;
  getList();
};
/** 重置按钮操作 */
const resetQuery = () => {
  queryFormRef.value?.reset();
  handleQuery();
};
/** 多选框选中数据：t-table 的 select-change 只回传选中的 rowKey（rowKey=ossConfigId） */
const handleSelectionChange = (keys: Array<string | number>) => {
  ids.value = keys;
};
/** 新增按钮操作 */
const handleAdd = () => {
  reset();
  dialog.visible = true;
  dialog.title = '添加对象存储配置';
};
/** 修改按钮操作 */
const handleUpdate = async (row?: OssConfigVO) => {
  reset();
  const ossConfigId = row?.ossConfigId || ids.value[0];
  const res = await getOssConfig(ossConfigId);
  Object.assign(form.value, res.data);
  dialog.visible = true;
  dialog.title = '修改对象存储配置';
};
/** 提交按钮 */
const submitForm = async () => {
  // TDesign t-form.validate() 成功 resolve(true)，失败 resolve(false)（不 reject）；兼容两种返回
  const result = await ossConfigFormRef.value?.validate().catch(() => false);
  const valid = result === true || result === undefined;
  if (!valid) return;
  buttonLoading.value = true;
  if (form.value.ossConfigId) {
    await updateOssConfig(form.value).finally(() => (buttonLoading.value = false));
  } else {
    await addOssConfig(form.value).finally(() => (buttonLoading.value = false));
  }
  proxy?.$modal.msgSuccess('新增成功');
  dialog.visible = false;
  await getList();
};
/** 状态修改  */
const handleStatusChange = async (row: OssConfigVO) => {
  const text = row.status === '0' ? '启用' : '停用';
  try {
    await proxy?.$modal.confirm('确认要"' + text + '""' + row.configKey + '"配置吗?');
    await changeOssConfigStatus(row.ossConfigId, row.status, row.configKey);
    await getList();
    proxy?.$modal.msgSuccess(text + '成功');
  } catch {
    return;
  } finally {
    row.status = row.status === '0' ? '1' : '0';
  }
};
/** 删除按钮操作 */
const handleDelete = async (row?: OssConfigVO) => {
  const ossConfigIds = row?.ossConfigId || ids.value;
  await proxy?.$modal.confirm('是否确认删除OSS配置编号为"' + ossConfigIds + '"的数据项?');
  loading.value = true;
  await delOssConfig(ossConfigIds).finally(() => (loading.value = false));
  await getList();
  proxy?.$modal.msgSuccess('删除成功');
};

onMounted(() => {
  getList();
});
</script>
