<template>
  <div class="p-2">
    <t-row :gutter="20">
      <!-- 流程分类树：TDesign 12 栅格，span 由原 24 栅格减半（4→2，20→10） -->
      <t-col :span="2">
        <t-card hover-shadow>
          <t-input v-model="categoryName" placeholder="请输入流程分类名" clearable>
            <template #prefix-icon><SearchIcon /></template>
          </t-input>
          <!-- t-tree 的 filter 是响应式 prop，未录入时返回 undefined 显示全部节点；click 上下文取 node.data（DOC/35 §4） -->
          <t-tree
            ref="categoryTreeRef"
            class="mt-2"
            :data="categoryOptions"
            :keys="{ value: 'id', label: 'label', children: 'children' }"
            :activable="true"
            :actived="categoryActived"
            :expand-on-click-node="false"
            :filter="categoryFilter"
            :hover="true"
            expand-all
            @click="handleNodeClick"
          />
        </t-card>
      </t-col>
      <t-col :span="10">
        <transition :enter-active-class="proxy?.animate.searchAnimate.enter" :leave-active-class="proxy?.animate.searchAnimate.leave">
          <div v-show="showSearch" class="mb-[10px]">
            <t-card hover-shadow>
              <t-form ref="queryFormRef" :data="queryParams" layout="inline" label-width="120px" class="erp-filter-row">
                <t-form-item label="流程定义名称" name="flowName">
                  <t-input v-model="queryParams.flowName" placeholder="请输入流程定义名称" clearable @enter="handleQuery" />
                </t-form-item>
                <t-form-item label="流程定义编码" name="flowCode">
                  <t-input v-model="queryParams.flowCode" placeholder="请输入流程定义编码" clearable @enter="handleQuery" />
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
        <t-card hover-shadow>
          <template #header>
            <div class="definition-toolbar">
              <t-button v-hasPermi="['workflow:definition:add']" theme="primary" @click="handleAdd()">
                <template #icon><PlusIcon /></template>
                添加
              </t-button>
              <t-button v-hasPermi="['workflow:definition:edit']" theme="success" :disabled="single" @click="handleUpdate()">
                <template #icon><Edit1Icon /></template>
                修改
              </t-button>
              <t-button v-hasPermi="['workflow:definition:remove']" theme="danger" :disabled="multiple" @click="handleDelete()">
                <template #icon><DeleteIcon /></template>
                删除
              </t-button>
              <t-button v-hasPermi="['workflow:definition:import']" theme="primary" @click="uploadDialog.visible = true">
                <template #icon><UploadIcon /></template>
                部署流程文件
              </t-button>
              <t-button v-hasPermi="['workflow:definition:export']" theme="warning" :disabled="single" @click="handleExportDef">
                <template #icon><DownloadIcon /></template>
                导出
              </t-button>
              <right-toolbar v-model:show-search="showSearch" @query-table="handleQuery"></right-toolbar>
            </div>
          </template>
          <!-- t-tabs 只渲染 TTabPanel 子组件，表格/分页放在 tabs 外层，避免被 getSlotPanels 过滤掉 -->
          <t-tabs :value="activeName" @change="handleTabChange">
            <t-tab-panel label="已发布" value="0"></t-tab-panel>
            <t-tab-panel label="未发布" value="1"></t-tab-panel>
          </t-tabs>
          <t-table
            class="erp-table"
            row-key="id"
            :data="processDefinitionList"
            :columns="definitionColumns"
            :loading="loading"
            :hover="true"
            :resizable="true"
            size="small"
            bordered
            cell-empty-content="-"
            :selected-row-keys="ids"
            @select-change="handleSelectionChange"
          >
            <template #version="{ row }"> v{{ row.version }}.0</template>
            <template #activityStatus="{ row }">
              <t-switch
                v-hasPermi="['workflow:definition:active']"
                v-model="row.activityStatus"
                :custom-value="[1, 0]"
                @change="(status) => handleProcessDefState(row, status)"
              />
            </template>
            <template #isPublish="{ row }">
              <t-tag v-if="row.isPublish == 0" theme="danger">未发布</t-tag>
              <t-tag v-else-if="row.isPublish == 1" theme="success">已发布</t-tag>
              <t-tag v-else theme="danger">失效</t-tag>
            </template>
            <template #operation="{ row }">
              <div class="definition-operations">
                <t-button v-if="checkPermi(['workflow:definition:remove'])" theme="primary" variant="text" size="small" @click="handleDelete(row)">
                  <template #icon><DeleteIcon /></template>
                  删除流程
                </t-button>
                <t-button v-if="checkPermi(['workflow:definition:copy'])" theme="primary" variant="text" size="small" @click="handleCopyDef(row)">
                  <template #icon><FileCopyIcon /></template>
                  复制流程
                </t-button>
                <t-button
                  v-if="row.isPublish === 0 && checkPermi(['workflow:definition:query'])"
                  theme="primary"
                  variant="text"
                  size="small"
                  @click="design(row)"
                >
                  <template #icon><ViewListIcon /></template>
                  流程设计
                </t-button>
                <t-button
                  v-if="row.isPublish !== 0 && checkPermi(['workflow:definition:query'])"
                  theme="primary"
                  variant="text"
                  size="small"
                  @click="designView(row)"
                >
                  <template #icon><BrowseIcon /></template>
                  查看流程
                </t-button>
                <t-button
                  v-if="row.isPublish !== 1 && checkPermi(['workflow:definition:publish'])"
                  theme="primary"
                  variant="text"
                  size="small"
                  @click="handlePublish(row)"
                >
                  <template #icon><CheckCircleIcon /></template>
                  发布流程
                </t-button>
              </div>
            </template>
          </t-table>
          <pagination
            v-show="total > 0"
            v-model:page="queryParams.pageNum"
            v-model:limit="queryParams.pageSize"
            :total="total"
            @pagination="getPageList"
          />
        </t-card>
      </t-col>
    </t-row>

    <!-- 部署文件 -->
    <t-dialog v-model:visible="uploadDialog.visible" :header="uploadDialog.title" width="30%" attach="body" :footer="false" destroy-on-close>
      <t-loading :loading="uploadDialogLoading">
        <div class="mb5">
          <div class="deploy-tip"><span class="text-danger">*</span>请选择部署流程分类：</div>
          <t-tree-select
            v-model="selectCategory"
            :data="categoryOptions"
            :keys="{ value: 'id', label: 'label', children: 'children' }"
            filterable
            :tree-props="{ checkStrictly: true }"
            style="width: 240px"
          />
        </div>
        <!-- t-upload：beforeUpload 保留原校验，requestMethod 承接原 http-request 的自定义上传 -->
        <t-upload
          theme="custom"
          draggable
          multiple
          accept="application/json,application/text"
          :before-upload="handlerBeforeUpload"
          :request-method="handlerImportDefinition"
        >
          <div class="upload-drag-content">
            <CloudUploadIcon />
            <div><em>点击上传，选择JSON流程文件</em></div>
            <div>仅支持json格式文件</div>
            <div>PS:如若部署请部署从本项目模型管理导出的数据</div>
          </div>
        </t-upload>
      </t-loading>
    </t-dialog>

    <!-- 新增/编辑流程定义：原 EP 把表单误放在 #footer，内容区为空；TDesign #footer 会渲染默认按钮，故表单移入默认插槽 -->
    <t-dialog
      v-model:visible="modelDialog.visible"
      :header="modelDialog.title"
      width="650px"
      attach="body"
      :close-on-overlay-click="false"
      :footer="false"
      destroy-on-close
    >
      <t-form ref="defFormRef" :data="form" :rules="rules" label-width="120px">
        <t-form-item label="流程类别" name="category">
          <t-tree-select
            v-model="form.category"
            :data="categoryOptions"
            :keys="{ value: 'id', label: 'label', children: 'children' }"
            filterable
            :tree-props="{ checkStrictly: true }"
            style="width: 100%"
          />
        </t-form-item>
        <t-form-item label="流程编码" name="flowCode">
          <t-input v-model="form.flowCode" placeholder="请输入流程编码" :maxlength="40" show-limit-number />
        </t-form-item>
        <t-form-item label="流程名称" name="flowName">
          <t-input v-model="form.flowName" placeholder="请输入流程名称" :maxlength="100" show-limit-number />
        </t-form-item>
        <t-form-item label="设计器模式" name="modelValue">
          <t-radio-group v-model="form.modelValue" :disabled="!!form.id">
            <t-radio value="CLASSICS">经典模式</t-radio>
            <t-radio value="MIMIC">仿钉钉模式</t-radio>
          </t-radio-group>
        </t-form-item>
        <t-form-item label="流程配置">
          <t-checkbox v-model="autoPass" label="下一节点执行人是当前任务处理人自动审批" />
        </t-form-item>
        <t-form-item label="是否动态表单" name="formCustom">
          <t-radio-group v-model="form.formCustom">
            <t-radio value="Y" disabled>是</t-radio>
            <t-radio value="N">否</t-radio>
          </t-radio-group>
        </t-form-item>
        <t-form-item label="表单路径" name="formPath">
          <t-input v-model="form.formPath" placeholder="请输入表单路径" :maxlength="100" show-limit-number />
        </t-form-item>
      </t-form>
      <div class="dialog-footer">
        <t-button theme="default" variant="outline" @click="modelDialog.visible = false">取消</t-button>
        <t-button theme="primary" @click="handleSubmit">保存</t-button>
      </div>
    </t-dialog>
  </div>
</template>

<script setup name="processDefinition" lang="ts">
import { listDefinition, deleteDefinition, active, importDef, unPublishList, publish, add, edit, getInfo, copy } from '@/api/workflow/definition';
import { categoryTree } from '@/api/workflow/category';
import { CategoryTreeVO } from '@/api/workflow/category/types';
import { FlowDefinitionQuery, FlowDefinitionVo, FlowDefinitionForm } from '@/api/workflow/definition/types';
import { checkPermi } from '@/utils/permission';
// TDesign 图标必须显式 import，不会自动解析（DOC/35 §3）
import {
  BrowseIcon,
  CheckCircleIcon,
  CloudUploadIcon,
  DeleteIcon,
  DownloadIcon,
  Edit1Icon,
  FileCopyIcon,
  PlusIcon,
  RefreshIcon,
  SearchIcon,
  UploadIcon,
  ViewListIcon
} from 'tdesign-icons-vue-next';
import type { TreeNodeModel } from 'tdesign-vue-next';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const queryFormRef = ref<any>();
const categoryTreeRef = ref<any>();

const loading = ref(true);
const ids = ref<Array<any>>([]);
const flowCodeList = ref<Array<any>>([]);
const single = ref(true);
const multiple = ref(true);
const showSearch = ref(true);
const total = ref(0);
const uploadDialogLoading = ref(false);
const processDefinitionList = ref<FlowDefinitionVo[]>([]);
const categoryOptions = ref<CategoryTreeVO[]>([]);
const categoryName = ref('');
const autoPass = ref(false);
/** 部署文件分类选择 */
const selectCategory = ref();
const defFormRef = ref<any>();
const activeName = ref('0');
/** t-tree 受控高亮数组 */
const categoryActived = ref<Array<string | number>>([]);
const uploadDialog = reactive<DialogOption>({
  visible: false,
  title: '部署流程文件'
});

const processDefinitionDialog = reactive<DialogOption>({
  visible: false,
  title: '历史版本'
});

const modelDialog = reactive<DialogOption>({
  visible: false,
  title: ''
});

// 查询参数
const queryParams = ref<FlowDefinitionQuery>({
  pageNum: 1,
  pageSize: 10,
  flowName: undefined,
  flowCode: undefined,
  category: undefined
});
/** 表单校验规则：t-form 的 rules 形状与 EP 的 ElFormRules 不兼容，单独用 Record 声明（DOC/35 §5） */
const rules: Record<string, any> = {
  category: [{ required: true, message: '分类名称不能为空', trigger: 'blur' }],
  flowName: [{ required: true, message: '流程定义名称不能为空', trigger: 'blur' }],
  formCustom: [{ required: true, message: '请选择是否动态表单', trigger: 'change' }],
  modelValue: [{ required: true, message: '设计器模式不能为空', trigger: 'change' }],
  flowCode: [{ required: true, message: '流程定义编码不能为空', trigger: 'blur' }]
};
const initFormData: FlowDefinitionForm = {
  id: '',
  flowName: '',
  flowCode: '',
  category: '',
  ext: '',
  formPath: '',
  formCustom: '',
  modelValue: ''
};
//流程定义参数
const form = ref<FlowDefinitionForm>({
  id: '',
  flowName: '',
  flowCode: '',
  category: '',
  ext: '',
  formPath: '',
  formCustom: '',
  modelValue: ''
});

/** 流程定义列表列配置：t-table 用 columns 数组，cell 字符串与 #插槽同名（DOC/35 §5）；show-overflow-tooltip → ellipsis */
const definitionColumns: any[] = [
  { colKey: 'row-select', type: 'multiple', width: 55, align: 'center' },
  { colKey: 'flowName', title: '流程定义名称', align: 'center', ellipsis: true },
  { colKey: 'flowCode', title: '标识KEY', align: 'center', ellipsis: true },
  { colKey: 'categoryName', title: '流程分类', align: 'center', ellipsis: true },
  { colKey: 'version', title: '版本号', align: 'center', width: 80, cell: 'version' },
  { colKey: 'activityStatus', title: '激活状态', align: 'center', width: 130, cell: 'activityStatus' },
  { colKey: 'isPublish', title: '发布状态', align: 'center', width: 100, cell: 'isPublish' },
  { colKey: 'operation', title: '操作', fixed: 'right', align: 'center', width: 170, cell: 'operation' }
];

/** 分类树过滤条件：t-tree 的 filter 是响应式 prop，categoryTree 返回字段是 label（对齐 post 页惯例） */
const categoryFilter = computed<((node: TreeNodeModel) => boolean) | undefined>(() => {
  if (!categoryName.value) return undefined;
  return (node: TreeNodeModel) => String(node.label ?? '').indexOf(categoryName.value) !== -1;
});

onMounted(() => {
  getPageList();
  getTreeselect();
});

/** 节点单击事件：t-tree 的 click 上下文是 { node, e }，node.data 为原始数据 */
const handleNodeClick = (context: { node: TreeNodeModel }) => {
  const id = (context.node.data as any).id;
  queryParams.value.category = id === '0' ? '' : id;
  handleQuery();
};
/** t-tabs 的 change 回传 value 字符串，替代 EP tab-click 的 paneName 取值 */
const handleTabChange = (value: string | number) => {
  activeName.value = String(value);
  handleQuery();
};

/** 查询流程分类下拉树结构 */
const getTreeselect = async () => {
  const res = await categoryTree();
  categoryOptions.value = res.data;
};
/** 搜索按钮操作 */
const handleQuery = () => {
  queryParams.value.pageNum = 1;
  if (activeName.value === '0') {
    getList();
  } else {
    getUnPublishList();
  }
};
/** 重置按钮操作 */
const resetQuery = () => {
  queryFormRef.value?.reset();
  queryParams.value.category = '';
  queryParams.value.pageNum = 1;
  queryParams.value.pageSize = 10;
  categoryActived.value = [];
  handleQuery();
};
// 多选框选中数据：t-table 的 select-change 只回传选中的 rowKey（rowKey=id）
const handleSelectionChange = (keys: Array<string | number>) => {
  ids.value = keys;
  const selection = processDefinitionList.value.filter((item) => keys.includes(item.id));
  flowCodeList.value = selection.map((item) => item.flowCode);
  single.value = selection.length !== 1;
  multiple.value = !selection.length;
};
//分页
const getPageList = async () => {
  const query = proxy.$route.query;
  if (query.activeName) {
    activeName.value = Array.isArray(query.activeName) ? query.activeName[0] || '0' : query.activeName;
    proxy.$route.query.activeName = '';
  }
  if (activeName.value === '0') {
    getList();
  } else {
    getUnPublishList();
  }
};
//分页
const getList = async () => {
  loading.value = true;
  const resp = await listDefinition(queryParams.value);
  processDefinitionList.value = resp.rows;
  total.value = resp.total;
  loading.value = false;
};
//查询未发布的流程定义列表
const getUnPublishList = async () => {
  loading.value = true;
  const resp = await unPublishList(queryParams.value);
  processDefinitionList.value = resp.rows;
  total.value = resp.total;
  loading.value = false;
};

/** 删除按钮操作 */
const handleDelete = async (row?: FlowDefinitionVo) => {
  const id = row?.id || ids.value;
  const defList = processDefinitionList.value.filter((x) => id.indexOf(x.id) != -1).map((x) => x.flowCode);
  await proxy?.$modal.confirm('是否确认删除流程定义编码为【' + defList + '】的数据项？');
  loading.value = true;
  await deleteDefinition(id).finally(() => (loading.value = false));
  await handleQuery();
  proxy?.$modal.msgSuccess('删除成功');
};

/** 发布流程定义 */
const handlePublish = async (row?: FlowDefinitionVo) => {
  await proxy?.$modal.confirm(
    '是否确认发布流程定义编码为【' + row.flowCode + '】版本为【' + row.version + '】的数据项？，发布后会将已发布流程定义改为失效！'
  );
  loading.value = true;
  await publish(row.id).finally(() => (loading.value = false));
  processDefinitionDialog.visible = false;
  activeName.value = '0';
  await handleQuery();
  proxy?.$modal.msgSuccess('发布成功');
};
/** 挂起/激活 */
const handleProcessDefState = async (row: FlowDefinitionVo, status: number | string | boolean) => {
  let msg: string;
  if (status === 0) {
    msg = `暂停后，此流程下的所有任务都不允许往后流转，您确定挂起【${row.flowName || row.flowCode}】吗？`;
  } else {
    msg = `启动后，此流程下的所有任务都允许往后流转，您确定激活【${row.flowName || row.flowCode}】吗？`;
  }
  try {
    loading.value = true;
    await proxy?.$modal.confirm(msg);
    await active(row.id, !!status);
    await handleQuery();
    proxy?.$modal.msgSuccess('操作成功');
  } catch (error) {
    row.activityStatus = status === 0 ? 1 : 0;
    console.error(error);
  } finally {
    loading.value = false;
  }
};

//上传文件前的钩子：t-upload 的 beforeUpload 回传 UploadFile，返回 false 阻止上传
const handlerBeforeUpload = (file: any) => {
  if (selectCategory.value === 'ALL') {
    proxy?.$modal.msgError('顶级节点不可作为分类！');
    return false;
  }
  if (!selectCategory.value) {
    proxy?.$modal.msgError('请选择左侧要上传的分类！');
    return false;
  }
};
//部署文件：t-upload 的 requestMethod 承接原 http-request，file.raw 即原始 File
const handlerImportDefinition = (data: any): Promise<{ status: 'success'; response: { url: string } }> => {
  const file = Array.isArray(data) ? data[0] : data;
  const formData = new FormData();
  uploadDialogLoading.value = true;
  formData.append('file', file?.raw ?? file);
  formData.append('category', selectCategory.value);
  importDef(formData)
    .then(() => {
      uploadDialog.visible = false;
      proxy?.$modal.msgSuccess('部署成功');
      activeName.value = '1';
      handleQuery();
    })
    .finally(() => {
      uploadDialogLoading.value = false;
    });
  return Promise.resolve({ status: 'success', response: { url: '' } });
};
/**
 * 设计流程
 * @param row
 */
const design = async (row: FlowDefinitionVo) => {
  proxy.$router.push({
    path: `/workflow/design/index`,
    query: {
      definitionId: row.id,
      disabled: 'false',
      activeName: activeName.value
    }
  });
};

/**
 * 查看流程
 * @param row
 */
const designView = async (row: FlowDefinitionVo) => {
  proxy.$router.push({
    path: `/workflow/design/index`,
    query: {
      definitionId: row.id,
      disabled: 'true',
      activeName: activeName.value
    }
  });
};
/** 表单重置：t-form 只清校验态，表单值以 initFormData 为准 */
const reset = () => {
  form.value = { ...initFormData };
  defFormRef.value?.clearValidate();
};
/**
 * 新增
 */
const handleAdd = async () => {
  reset();
  if (queryParams.value.category != '') {
    form.value.category = String(queryParams.value.category);
  }
  form.value.modelValue = 'CLASSICS';
  form.value.formCustom = 'N';
  modelDialog.visible = true;
  modelDialog.title = '新增流程';
};
/** 修改按钮操作 */
const handleUpdate = async (row?: FlowDefinitionVo) => {
  reset();
  const id = row?.id || ids.value[0];
  const res = await getInfo(id);
  Object.assign(form.value, res.data);
  autoPass.value = false;
  if (form.value.ext != null && form.value.ext != '') {
    const extJson = JSON.parse(form.value.ext);
    if (extJson.autoPass != null && extJson.autoPass != '') {
      autoPass.value = extJson.autoPass;
    }
  }
  modelDialog.visible = true;
  modelDialog.title = '修改流程';
};

/** 提交按钮：t-form.validate() 失败 resolve(false)，不 reject（DOC/35 §5） */
const handleSubmit = async () => {
  const result = await defFormRef.value?.validate().catch(() => false);
  const valid = result === true || result === undefined;
  if (!valid) return;
  loading.value = true;
  const ext = { autoPass: autoPass.value };
  form.value.ext = JSON.stringify(ext);
  if (form.value.id) {
    await edit(form.value).finally(() => (loading.value = false));
  } else {
    await add(form.value).finally(() => (loading.value = false));
    activeName.value = '1';
  }
  proxy?.$modal.msgSuccess('操作成功');
  modelDialog.visible = false;
  handleQuery();
};
//复制流程定义：统一走 proxy.$modal.confirm，确定才执行复制，取消/关闭 reject 不触发
const handleCopyDef = async (row: FlowDefinitionVo) => {
  proxy?.$modal.confirm(`是否确认复制【${row.flowCode}】版本为【${row.version}】的流程定义！`).then(() => {
    loading.value = true;
    copy(row.id)
      .then((resp) => {
        if (resp.code === 200) {
          proxy?.$modal.msgSuccess('操作成功');
          activeName.value = '1';
          handleQuery();
        }
      })
      .finally(() => (loading.value = false));
  });
};

/** 导出按钮操作 */
const handleExportDef = () => {
  proxy?.download(`/workflow/definition/exportDef/${ids.value[0]}`, {}, `${flowCodeList.value[0]}.json`);
};
</script>

<style lang="scss" scoped>
.definition-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.definition-operations {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
}
.deploy-tip {
  margin-bottom: 5px;
}
.upload-drag-content {
  text-align: center;
}
</style>
