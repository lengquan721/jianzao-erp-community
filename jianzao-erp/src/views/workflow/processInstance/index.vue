<template>
  <div class="p-2">
    <t-row :gutter="20">
      <!-- 流程分类树：TDesign 12 栅格，span 由原 24 栅格减半（4→2，20→10） -->
      <t-col :span="2">
        <t-card hover-shadow>
          <t-input v-model="categoryName" placeholder="请输入流程分类名" clearable>
            <template #prefix-icon><SearchIcon /></template>
          </t-input>
          <!-- t-tree 的 filter 是响应式 prop；click 上下文取 node.data（DOC/35 §4） -->
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
              <t-form ref="queryFormRef" :data="queryParams" layout="inline" class="erp-filter-row">
                <t-form-item>
                  <t-badge :count="userSelectCount" :max-count="10">
                    <t-button theme="primary" @click="openUserSelect">选择申请人</t-button>
                  </t-badge>
                </t-form-item>
                <t-form-item label="任务名称" name="nodeName">
                  <t-input v-model="queryParams.nodeName" placeholder="请输入任务名称" @enter="handleQuery" />
                </t-form-item>
                <t-form-item label="流程定义名称" name="flowName">
                  <t-input v-model="queryParams.flowName" placeholder="请输入流程定义名称" @enter="handleQuery" />
                </t-form-item>
                <t-form-item label="流程定义编码" name="flowCode">
                  <t-input v-model="queryParams.flowCode" placeholder="请输入流程定义编码" @enter="handleQuery" />
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
            <div class="instance-toolbar">
              <t-button v-hasPermi="['workflow:instance:remove']" theme="danger" variant="outline" :disabled="multiple" @click="handleDelete()">
                <template #icon><DeleteIcon /></template>
                删除
              </t-button>
              <right-toolbar v-model:show-search="showSearch" @query-table="handleQuery"></right-toolbar>
            </div>
          </template>
          <!-- t-tabs 只渲染 TTabPanel 子组件，表格/分页放在 tabs 外层，避免被 getSlotPanels 过滤掉 -->
          <t-tabs :value="tab" @change="changeTab">
            <t-tab-panel name="running" label="运行中" value="running"></t-tab-panel>
            <t-tab-panel name="finish" label="已完成" value="finish"></t-tab-panel>
          </t-tabs>
          <t-table
            class="erp-table"
            row-key="id"
            :data="processInstanceList"
            :columns="instanceColumns"
            :loading="loading"
            :hover="true"
            :resizable="true"
            size="small"
            bordered
            cell-empty-content="-"
            :selected-row-keys="ids"
            @select-change="handleSelectionChange"
          >
            <template #rowIndex="{ rowIndex }">{{ rowIndex + 1 }}</template>
            <template #flowName="{ row }">
              <span>{{ row.flowName }}v{{ row.version }}</span>
            </template>
            <template #version="{ row }"> v{{ row.version }}.0</template>
            <template #isSuspended="{ row }">
              <t-tag v-if="!row.isSuspended" theme="success">激活</t-tag>
              <t-tag v-else theme="danger">挂起</t-tag>
            </template>
            <template #flowStatus="{ row }">
              <dict-tag :options="wf_business_status" :value="row.flowStatus"></dict-tag>
            </template>
            <template #operation="{ row }">
              <div class="instance-operations">
                <!-- el-popover → t-popup：默认插槽是触发器、#content 才是面板（DOC/35 §3） -->
                <t-popup
                  v-if="tab === 'running'"
                  trigger="click"
                  placement="left"
                  :visible="popoverRowId === row.id"
                  @visible-change="(v: boolean) => onPopoverVisibleChange(v, row.id)"
                >
                  <!-- t-popup 的 trigger=click 已在触发器上自带 show/hide 切换，按钮不再另挂 @click，避免两次 toggle -->
                  <t-button theme="danger" size="small">
                    <template #icon><CloseCircleIcon /></template>
                    作废
                  </t-button>
                  <template #content>
                    <div class="invalid-popup">
                      <t-textarea v-model="deleteReason" :autosize="{ minRows: 3 }" placeholder="请输入作废原因" />
                      <div class="invalid-popup__actions">
                        <t-button size="small" variant="text" @click="cancelPopover()">取消</t-button>
                        <t-button size="small" theme="primary" @click="handleInvalid(row)">确认</t-button>
                      </div>
                    </div>
                  </template>
                </t-popup>
                <t-button v-if="tab === 'running' && checkPermi(['workflow:instance:remove'])" theme="danger" size="small" @click="handleDelete(row)">
                  <template #icon><DeleteIcon /></template>
                  删除
                </t-button>
                <t-button v-if="checkPermi(['workflow:instance:query'])" theme="primary" size="small" @click="handleView(row)">
                  <template #icon><BrowseIcon /></template>
                  查看
                </t-button>
                <t-button v-if="checkPermi(['workflow:instance:variableQuery'])" theme="primary" size="small" @click="handleInstanceVariable(row)">
                  <template #icon><FileIcon /></template>
                  变量
                </t-button>
              </div>
            </template>
          </t-table>
          <pagination
            v-show="total > 0"
            v-model:page="queryParams.pageNum"
            v-model:limit="queryParams.pageSize"
            :total="total"
            @pagination="handleQuery"
          />
        </t-card>
      </t-col>
    </t-row>

    <t-dialog
      v-model:visible="processDefinitionDialog.visible"
      :header="processDefinitionDialog.title"
      width="70%"
      attach="body"
      :footer="false"
      destroy-on-close
    >
      <t-table
        class="erp-table"
        row-key="id"
        :data="processDefinitionHistoryList"
        :columns="historyColumns"
        :loading="loading"
        :hover="true"
        :resizable="true"
        size="small"
        bordered
        cell-empty-content="-"
      >
        <template #rowIndex="{ rowIndex }">{{ rowIndex + 1 }}</template>
        <template #version="{ row }"> v{{ row.version }}.0</template>
        <template #suspensionState="{ row }">
          <t-tag v-if="row.suspensionState == 1" theme="success">激活</t-tag>
          <t-tag v-else theme="danger">挂起</t-tag>
        </template>
      </t-table>
    </t-dialog>
    <!-- 流程变量开始 -->
    <t-dialog
      v-model:visible="variableVisible"
      :header="'流程变量'"
      width="60%"
      attach="body"
      :close-on-overlay-click="false"
      :footer="false"
      destroy-on-close
    >
      <t-loading :loading="variableLoading">
        <t-card>
          <template #header>
            <div class="clearfix">
              <span
                >流程定义名称：<t-tag theme="default">{{ processDefinitionName }}</t-tag></span
              >
            </div>
          </template>
          <div class="max-h-500px overflow-y-auto">
            <VueJsonPretty :data="formatToJsonObject(variables)" />
          </div>
        </t-card>
      </t-loading>
      <t-loading :loading="variableLoading">
        <t-card>
          <t-form ref="ruleFormRef" :data="form" layout="inline" :rules="rules" label-width="120px">
            <t-form-item label="变量KEY" name="key">
              <t-input v-model="form.key" placeholder="请输入变量KEY" />
            </t-form-item>
            <t-form-item label="变量值" name="value">
              <t-input v-model="form.value" placeholder="请输入变量值" />
            </t-form-item>
            <t-form-item>
              <t-button v-if="checkPermi(['workflow:instance:variable'])" theme="primary" @click="handleVariable(ruleFormRef)">确认</t-button>
            </t-form-item>
          </t-form>
        </t-card>
      </t-loading>
    </t-dialog>
    <!-- 流程变量结束 -->

    <!-- 申请人 -->
    <UserSelect ref="userSelectRef" :multiple="true" :data="selectUserIds" @confirm-call-back="userSelectCallBack"></UserSelect>
  </div>
</template>

<script setup lang="ts">
import {
  pageByRunning,
  pageByFinish,
  deleteByInstanceIds,
  deleteHisByInstanceIds,
  instanceVariable,
  invalid,
  updateVariable
} from '@/api/workflow/instance';
import { categoryTree } from '@/api/workflow/category';
import { CategoryTreeVO } from '@/api/workflow/category/types';
import { FlowInstanceQuery, FlowInstanceVO } from '@/api/workflow/instance/types';
import workflowCommon from '@/api/workflow/workflowCommon';
import { RouterJumpVo } from '@/api/workflow/workflowCommon/types';
import VueJsonPretty from 'vue-json-pretty';
import 'vue-json-pretty/lib/styles.css';
import UserSelect from '@/components/UserSelect/index.vue';
import { UserVO } from '@/api/system/user/types';
import { checkPermi } from '@/utils/permission';
// TDesign 图标必须显式 import，不会自动解析（DOC/35 §3）
import { BrowseIcon, CloseCircleIcon, DeleteIcon, FileIcon, RefreshIcon, SearchIcon } from 'tdesign-icons-vue-next';
import type { TreeNodeModel } from 'tdesign-vue-next';
//审批记录组件
const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const { wf_business_status } = toRefs<any>(proxy?.useDict('wf_business_status'));
const queryFormRef = ref<any>();
const categoryTreeRef = ref<any>();
const form = ref<Record<string, any>>({
  instanceId: undefined,
  key: undefined,
  value: undefined
});
const ruleFormRef = ref<any>();
const userSelectRef = ref<InstanceType<typeof UserSelect>>();
// 遮罩层
const loading = ref(true);
// 选中数组
const ids = ref<Array<any>>([]);
// 选中实例id数组
const instanceIds = ref<Array<number | string>>([]);
// 非单个禁用
const single = ref(true);
// 非多个禁用
const multiple = ref(true);
// 显示搜索条件
const showSearch = ref(true);
// 总条数
const total = ref(0);
// 实例id
const instanceId = ref(undefined);

// 流程变量是否显示
const variableVisible = ref(false);
const variableLoading = ref(true);
const variables = ref<string>('');
//流程定义名称
const processDefinitionName = ref();
// 模型定义表格数据
const processInstanceList = ref<FlowInstanceVO[]>([]);
const processDefinitionHistoryList = ref<Array<any>>([]);
const categoryOptions = ref<CategoryTreeVO[]>([]);
const categoryName = ref('');

const processDefinitionDialog = reactive<DialogOption>({
  visible: false,
  title: '流程定义'
});

type CategoryOption = {
  id: string;
  categoryName: string;
  children?: CategoryOption[];
};

const tab = ref('running');
// 作废原因
const deleteReason = ref('');
/** t-tree 受控高亮数组 */
const categoryActived = ref<Array<string | number>>([]);
/** t-popup 受控显隐：原 EP 通过 $refs[`popoverRef${index}`].hide() 控制，改用行 id 记录当前打开的气泡 */
const popoverRowId = ref<string | number | null>(null);

//申请人id
const selectUserIds = ref<Array<number | string>>([]);
//申请人选择数量
const userSelectCount = ref(0);
// 查询参数
const queryParams = ref<FlowInstanceQuery>({
  pageNum: 1,
  pageSize: 10,
  nodeName: undefined,
  flowName: undefined,
  flowCode: undefined,
  createByIds: [],
  category: undefined
});

/** 分类树过滤条件：t-tree 的 filter 是响应式 prop，categoryTree 返回字段是 label（对齐 post 页惯例） */
const categoryFilter = computed<((node: TreeNodeModel) => boolean) | undefined>(() => {
  if (!categoryName.value) return undefined;
  return (node: TreeNodeModel) => String(node.label ?? '').indexOf(categoryName.value) !== -1;
});

/** 流程实例列表列配置：t-table 用 columns 数组，cell 字符串与 #插槽同名（DOC/35 §5）；show-overflow-tooltip → ellipsis */
const instanceColumns = computed<any[]>(() => [
  { colKey: 'row-select', type: 'multiple', width: 55, align: 'center' },
  { colKey: 'rowIndex', title: '序号', width: 60, align: 'center', cell: 'rowIndex' },
  { colKey: 'businessCode', title: '业务编码', align: 'center', ellipsis: true },
  { colKey: 'businessTitle', title: '业务标题', align: 'center', ellipsis: true },
  { colKey: 'flowName', title: '流程定义名称', align: 'center', width: 120, cell: 'flowName' },
  { colKey: 'flowCode', title: '流程定义编码', align: 'center', width: 120 },
  { colKey: 'categoryName', title: '流程分类', align: 'center' },
  { colKey: 'nodeName', title: '任务名称', align: 'center' },
  { colKey: 'createByName', title: '申请人', align: 'center', ellipsis: true },
  { colKey: 'version', title: '版本号', align: 'center', width: 90, cell: 'version' },
  ...(tab.value === 'running' ? [{ colKey: 'isSuspended', title: '状态', align: 'center', minWidth: 70, cell: 'isSuspended' }] : []),
  { colKey: 'flowStatus', title: '流程状态', align: 'center', minWidth: 80, cell: 'flowStatus' },
  { colKey: 'createTime', title: '启动时间', align: 'center', width: 160 },
  ...(tab.value === 'finish' ? [{ colKey: 'updateTime', title: '结束时间', align: 'center', width: 160 }] : []),
  { colKey: 'operation', title: '操作', align: 'center', width: 165, fixed: 'right', cell: 'operation' }
]);

/** 历史版本表格列配置 */
const historyColumns: any[] = [
  { colKey: 'rowIndex', title: '序号', width: 60, align: 'center', fixed: 'left', cell: 'rowIndex' },
  { colKey: 'name', title: '流程定义名称', align: 'center', fixed: 'left' },
  { colKey: 'nodeName', title: '任务名称', align: 'center', fixed: 'left' },
  { colKey: 'key', title: '标识Key', align: 'center' },
  { colKey: 'version', title: '版本号', align: 'center', width: 90, cell: 'version' },
  { colKey: 'suspensionState', title: '状态', align: 'center', minWidth: 70, cell: 'suspensionState' },
  { colKey: 'deploymentTime', title: '部署时间', align: 'center', ellipsis: true }
];

/** 节点单击事件：t-tree 的 click 上下文是 { node, e }，node.data 为原始数据 */
const handleNodeClick = (context: { node: TreeNodeModel }) => {
  const id = (context.node.data as any).id;
  queryParams.value.category = id === '0' ? '' : id;
  handleQuery();
};

/** 查询流程分类下拉树结构 */
const getTreeselect = async () => {
  const res = await categoryTree();
  categoryOptions.value = res.data;
};

/** 搜索按钮操作 */
const handleQuery = () => {
  if ('running' === tab.value) {
    getProcessInstanceRunningList();
  } else {
    getProcessInstanceFinishList();
  }
};
/** 重置按钮操作 */
const resetQuery = () => {
  queryFormRef.value?.reset();
  queryParams.value.category = '';
  queryParams.value.pageNum = 1;
  queryParams.value.pageSize = 10;
  queryParams.value.createByIds = [];
  userSelectCount.value = 0;
  handleQuery();
};
// 多选框选中数据：t-table 的 select-change 只回传选中的 rowKey（rowKey=id）
const handleSelectionChange = (keys: Array<string | number>) => {
  ids.value = keys;
  instanceIds.value = keys;
  single.value = keys.length !== 1;
  multiple.value = !keys.length;
};
//分页
const getProcessInstanceRunningList = () => {
  loading.value = true;
  pageByRunning(queryParams.value).then((resp) => {
    processInstanceList.value = resp.rows;
    total.value = resp.total;
    loading.value = false;
  });
};
//分页
const getProcessInstanceFinishList = () => {
  loading.value = true;
  pageByFinish(queryParams.value).then((resp) => {
    processInstanceList.value = resp.rows;
    total.value = resp.total;
    loading.value = false;
  });
};

/** 删除按钮操作 */
const handleDelete = async (row?: FlowInstanceVO) => {
  const instanceIdList = row?.id || instanceIds.value;
  await proxy?.$modal.confirm('是否确认删除？');
  loading.value = true;
  if ('running' === tab.value) {
    await deleteByInstanceIds(instanceIdList).finally(() => (loading.value = false));
    getProcessInstanceRunningList();
  } else {
    await deleteHisByInstanceIds(instanceIdList).finally(() => (loading.value = false));
    getProcessInstanceFinishList();
  }
  proxy?.$modal.msgSuccess('删除成功');
};
/** t-tabs 的 change 回传 value 字符串，替代 EP tab-click 的 paneName 取值 */
const changeTab = async (value: string | number) => {
  processInstanceList.value = [];
  queryParams.value.pageNum = 1;
  if ('running' === value) {
    getProcessInstanceRunningList();
  } else {
    getProcessInstanceFinishList();
  }
};
/** 作废按钮操作 */
const handleInvalid = async (row: FlowInstanceVO) => {
  await proxy?.$modal.confirm('是否确认作废？');
  loading.value = true;
  if ('running' === tab.value) {
    const param = {
      id: row.id,
      comment: deleteReason.value
    };
    await invalid(param).finally(() => (loading.value = false));
    getProcessInstanceRunningList();
    proxy?.$modal.msgSuccess('操作成功');
  }
  popoverRowId.value = null;
};
/** t-popup 受控显隐：true 记录当前行 id，false 清除 */
const onPopoverVisibleChange = (visible: boolean, id: string | number) => {
  popoverRowId.value = visible ? id : null;
};
/** 关闭作废气泡：原 EP 通过 $refs[`popoverRef${index}`].hide() */
const cancelPopover = async () => {
  popoverRowId.value = null;
};
/** 查看按钮操作 */
const handleView = (row) => {
  const routerJumpVo = reactive<RouterJumpVo>({
    businessId: row.businessId,
    taskId: row.id,
    type: 'view',
    formCustom: row.formCustom,
    formPath: row.formPath
  });
  workflowCommon.routerJump(routerJumpVo, proxy);
};

//查询流程变量
const handleInstanceVariable = async (row: FlowInstanceVO) => {
  instanceId.value = row.id;
  variableLoading.value = true;
  variableVisible.value = true;
  processDefinitionName.value = row.flowName;
  const data = await instanceVariable(row.id);
  variables.value = data.data.variable;
  variableLoading.value = false;
  form.value.instanceId = undefined;
  form.value.key = undefined;
  form.value.value = undefined;
};

/**
 * json转为对象
 * @param data 原始数据
 */
function formatToJsonObject(data: string) {
  try {
    return JSON.parse(data);
  } catch (error) {
    return data;
  }
}

//打开申请人选择
const openUserSelect = () => {
  userSelectRef.value.open();
};
//确认选择申请人
const userSelectCallBack = (data: UserVO[]) => {
  userSelectCount.value = 0;
  selectUserIds.value = [];
  queryParams.value.createByIds = [];

  if (data && data.length > 0) {
    userSelectCount.value = data.length;
    selectUserIds.value = data.map((item) => item.userId);
    queryParams.value.createByIds = selectUserIds.value;
  }
};
const rules: Record<string, any> = {
  key: [
    {
      required: true,
      message: '请输入KEY',
      trigger: 'blur'
    }
  ],
  value: [
    {
      required: true,
      message: '请输入变量值',
      trigger: 'blur'
    }
  ]
};

/** 提交变量：t-form.validate() 失败 resolve(false)，不 reject（DOC/35 §5） */
const handleVariable = async (formEl: any) => {
  const result = await formEl.validate().catch(() => false);
  const valid = result === true || result === undefined;
  if (!valid) return;
  form.value.instanceId = instanceId.value;
  await proxy?.$modal.confirm('是否确认提交？');
  await updateVariable(form.value);
  proxy?.$modal.msgSuccess('操作成功');
  const data = await instanceVariable(instanceId.value);
  variables.value = data.data.variable;
};

onMounted(() => {
  getProcessInstanceRunningList();
  getTreeselect();
});
</script>

<style lang="scss" scoped>
.instance-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.instance-operations {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
}
.invalid-popup {
  width: 300px;
}
.invalid-popup__actions {
  text-align: right;
  margin: 5px 0 0 0;
}
</style>
