<template>
  <div class="p-2">
    <transition :enter-active-class="proxy?.animate.searchAnimate.enter" :leave-active-class="proxy?.animate.searchAnimate.leave">
      <div v-show="showSearch" class="mb-[10px]">
        <t-card :bordered="false" class="erp-filter-card">
          <t-form v-show="showSearch" ref="queryFormRef" :data="queryParams" class="erp-filter-row" label-align="right" :label-width="'auto'">
            <t-form-item>
              <t-badge :count="userSelectCount" :max-count="10" class="item">
                <t-button theme="primary" @click="openUserSelect">选择申请人</t-button>
              </t-badge>
            </t-form-item>
            <t-form-item label="任务名称" name="nodeName">
              <t-input v-model="queryParams.nodeName" placeholder="请输入任务名称" class="query-w-md" @enter="handleQuery" />
            </t-form-item>
            <t-form-item label="流程定义名称" name="flowName">
              <t-input v-model="queryParams.flowName" placeholder="请输入流程定义名称" class="query-w-md" @enter="handleQuery" />
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
    <t-card :bordered="false" class="erp-filter-card">
      <template #header>
        <div class="task-toolbar">
          <template v-if="tab === 'waiting'">
            <t-button class="todo-action-btn" theme="primary" variant="outline" :disabled="multiple" @click="handleUserOpen()">
              <template #icon><Edit1Icon /></template>
              修改办理人
            </t-button>
            <t-button class="todo-action-btn" theme="warning" variant="outline" :disabled="multiple" @click="handleUrgeTaskOpen()">
              <template #icon><NotificationIcon /></template>
              催办
            </t-button>
          </template>
          <right-toolbar v-model:show-search="showSearch" @query-table="handleQuery"></right-toolbar>
        </div>
      </template>
      <!-- 旧结构把表格与分页作为标签页子节点常驻渲染；t-tabs 只渲染 t-tab-panel 子节点，故将表格与分页移到标签栏外，保持常驻语义 -->
      <t-tabs v-model="tab" @change="changeTab">
        <t-tab-panel value="waiting" label="待办任务"> </t-tab-panel>
        <t-tab-panel value="finish" label="已办任务"> </t-tab-panel>
      </t-tabs>
      <t-table
        class="erp-table"
        row-key="id"
        :data="taskList"
        :columns="taskColumns"
        :loading="loading"
        :hover="true"
        :resizable="true"
        size="small"
        bordered
        cell-empty-content="-"
        :selected-row-keys="ids"
        @select-change="handleSelectionChange"
        @column-resize-change="(ctx: any) => saveColumnWidths('workflow-allTaskWaiting/list', ctx.columnsWidth)"
      >
        <template #version="{ row }"> v{{ row.version }}.0</template>
        <template #assignee="{ row }">
          <template v-if="tab === 'waiting'">
            <template v-if="row.assigneeNames">
              <t-tag v-for="(name, index) in row.assigneeNames.split(',')" :key="index" theme="success" variant="light" style="margin: 2px">
                {{ name }}
              </t-tag>
            </template>
            <template v-else>
              <t-tag theme="success" variant="light"> 无</t-tag>
            </template>
          </template>
          <template v-else>
            <t-tag theme="success" variant="light"> {{ row.approveName }}</t-tag>
          </template>
        </template>
        <template #flowStatus="{ row }">
          <dict-tag :options="wf_business_status" :value="row.flowStatus"></dict-tag>
        </template>
        <template #flowTaskStatus="{ row }">
          <dict-tag :options="wf_task_status" :value="row.flowTaskStatus"></dict-tag>
        </template>
        <template #operation="{ row }">
          <div class="task-actions">
            <t-button v-if="tab === 'waiting' || tab === 'finish'" theme="primary" variant="outline" size="small" @click="handleView(row)">
              <template #icon><BrowseIcon /></template>
              查看
            </t-button>
            <t-button v-if="tab === 'waiting'" theme="primary" variant="outline" size="small" @click="handleMeddle(row)">
              <template #icon><SettingIcon /></template>
              流程干预
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
    <!-- 选人组件 -->
    <UserSelect ref="userSelectRef" :multiple="userMultiple" @confirm-call-back="submitCallback"></UserSelect>
    <!-- 流程干预组件 -->
    <processMeddle ref="processMeddleRef" @submitCallback="getWaitingList"></processMeddle>
    <!-- 申请人 -->
    <UserSelect ref="applyUserSelectRef" :multiple="true" :data="selectUserIds" @confirm-call-back="userSelectCallBack"></UserSelect>
    <!-- 流程干预组件 -->
    <messageType ref="messageTypeRef" @submitCallback="handleUserTask"></messageType>
  </div>
</template>

<script setup lang="ts">
import { pageByAllTaskWait, pageByAllTaskFinish, updateAssignee, urgeTask } from '@/api/workflow/task';
import UserSelect from '@/components/UserSelect/index.vue';
import { TaskQuery } from '@/api/workflow/task/types';
import workflowCommon from '@/api/workflow/workflowCommon';
import { RouterJumpVo } from '@/api/workflow/workflowCommon/types';
import processMeddle from '@/components/Process/processMeddle.vue';
import messageType from '@/components/Process/MessageType.vue';
import { UserVO } from '@/api/system/user/types';
// 列宽记忆：拖拽后按表名存 localStorage，进页面还原（见 hooks/useTableColumnWidth）
import { mergeSavedWidths, saveColumnWidths } from '@/hooks/useTableColumnWidth';
// TDesign 图标必须显式 import，不会自动解析（DOC/35 §3）
import { BrowseIcon, Edit1Icon, NotificationIcon, RefreshIcon, SearchIcon, SettingIcon } from 'tdesign-icons-vue-next';
//选人组件
const userSelectRef = ref<InstanceType<typeof UserSelect>>();
//流程干预组件
const processMeddleRef = ref<InstanceType<typeof processMeddle>>();
//选人组件
const applyUserSelectRef = ref<InstanceType<typeof UserSelect>>();
//消息组件
const messageTypeRef = ref<InstanceType<typeof messageType>>();
const queryFormRef = ref<any>();
const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const { wf_business_status } = toRefs<any>(proxy?.useDict('wf_business_status'));
const { wf_task_status } = toRefs<any>(proxy?.useDict('wf_task_status'));
// 遮罩层
const loading = ref(true);
// 选中数组
const ids = ref<Array<any>>([]);
// 非单个禁用
const single = ref(true);
// 非多个禁用
const multiple = ref(true);
const userMultiple = ref(false);
// 显示搜索条件
const showSearch = ref(true);
// 总条数
const total = ref(0);
// 模型定义表格数据
const taskList = ref([]);
const buttonType = ref('');
//申请人id
const selectUserIds = ref<Array<number | string>>([]);
//申请人选择数量
const userSelectCount = ref(0);
// 查询参数
const queryParams = ref<TaskQuery>({
  pageNum: 1,
  pageSize: 10,
  nodeName: undefined,
  flowName: undefined,
  flowCode: undefined,
  createByIds: []
});
const tab = ref('waiting');

/** 任务列表列配置：t-table 用 columns 数组，插槽名与 cell 字符串同名；按 tab 增删条件列（DOC/35 §5） */
const taskColumns = computed<any[]>(() => {
  const cols: any[] = [
    { colKey: 'row-select', type: 'multiple', width: 55, align: 'center' },
    { colKey: 'rowIndex', title: '序号', width: 60, align: 'center' },
    { colKey: 'businessCode', title: '业务编码', align: 'center', ellipsis: true },
    { colKey: 'businessTitle', title: '业务标题', align: 'center', ellipsis: true },
    { colKey: 'flowName', title: '流程定义名称', align: 'center', width: 120, ellipsis: true },
    { colKey: 'flowCode', title: '流程定义编码', align: 'center', width: 120 },
    { colKey: 'categoryName', title: '流程分类', align: 'center' },
    { colKey: 'version', title: '版本号', align: 'center', width: 90, cell: 'version' },
    { colKey: 'nodeName', title: '任务名称', align: 'center', ellipsis: true },
    { colKey: 'createByName', title: '申请人', align: 'center', ellipsis: true },
    { colKey: 'assignee', title: '办理人', align: 'center', cell: 'assignee' },
    { colKey: 'flowStatus', title: '流程状态', align: 'center', minWidth: 70, cell: 'flowStatus' }
  ];
  if (tab.value === 'finish') {
    cols.push({ colKey: 'flowTaskStatus', title: '任务状态', align: 'center', minWidth: 70, cell: 'flowTaskStatus' });
  }
  cols.push({ colKey: 'createTime', title: '创建时间', align: 'center', width: 160 });
  cols.push({ colKey: 'operation', title: '操作', align: 'center', width: tab.value === 'finish' ? 88 : 188, cell: 'operation' });
  return mergeSavedWidths('workflow-allTaskWaiting/list', cols);
});

/** 搜索按钮操作 */
const handleQuery = () => {
  if ('waiting' === tab.value) {
    getWaitingList();
  } else {
    getFinishList();
  }
};
/** 重置按钮操作：t-form 使用 reset() 方法 */
const resetQuery = () => {
  queryFormRef.value?.reset();
  queryParams.value.pageNum = 1;
  queryParams.value.pageSize = 10;
  queryParams.value.createByIds = [];
  userSelectCount.value = 0;
  selectUserIds.value = [];
  handleQuery();
};
// 多选框选中数据：t-table 的 select-change 只回传选中的 rowKey（rowKey=id）
const handleSelectionChange = (keys: Array<string | number>) => {
  ids.value = keys;
  single.value = keys.length !== 1;
  multiple.value = !keys.length;
};
// t-tabs 的 change 回传 value（TabValue），回调参数是当前标签值
const changeTab = async (value: string) => {
  taskList.value = [];
  queryParams.value.pageNum = 1;
  if ('waiting' === value) {
    getWaitingList();
  } else {
    getFinishList();
  }
};
//分页
const getWaitingList = () => {
  loading.value = true;
  pageByAllTaskWait(queryParams.value).then((resp) => {
    taskList.value = resp.rows;
    total.value = resp.total;
    loading.value = false;
  });
};
const getFinishList = () => {
  loading.value = true;
  pageByAllTaskFinish(queryParams.value).then((resp) => {
    taskList.value = resp.rows;
    total.value = resp.total;
    loading.value = false;
  });
};
// 打开催办
const handleUrgeTaskOpen = () => {
  messageTypeRef.value.open();
};
//打开修改选人
const handleUserOpen = () => {
  userSelectRef.value.open();
};

//打开修改选人
const handleUserTask = async (data) => {
  await proxy?.$modal.confirm('是否确认提交？');
  data.taskIdList = ids.value;
  await urgeTask(data);
  messageTypeRef.value.close();
  proxy?.$modal.msgSuccess('操作成功');
  handleQuery();
};
//修改办理人
const submitCallback = async (data) => {
  if (data && data.length > 0) {
    await proxy?.$modal.confirm('是否确认提交？');
    loading.value = true;
    await updateAssignee(ids.value, data[0].userId);
    handleQuery();
    proxy?.$modal.msgSuccess('操作成功');
  } else {
    proxy?.$modal.msgWarning('请选择用户！');
  }
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
const handleMeddle = (row) => {
  processMeddleRef.value.open(row.id);
};
//打开申请人选择
const openUserSelect = () => {
  applyUserSelectRef.value.open();
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
onMounted(() => {
  getWaitingList();
});
</script>

<style lang="scss" scoped>
// 卡片头工具栏：原栅格行的 flex 布局收敛到本页 scoped 类
.task-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.todo-action-btn {
  height: 32px;
  padding: 0 14px;
  border-radius: var(--app-radius-md);
  font-weight: 600;
}
.task-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
</style>
