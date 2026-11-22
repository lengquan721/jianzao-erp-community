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
            <t-form-item label="流程定义编码" name="flowCode">
              <t-input v-model="queryParams.flowCode" placeholder="请输入流程定义编码" class="query-w-md" @enter="handleQuery" />
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
        <div class="finish-toolbar">
          <right-toolbar v-model:show-search="showSearch" @query-table="handleQuery"></right-toolbar>
        </div>
      </template>

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
        @column-resize-change="(ctx: any) => saveColumnWidths('workflow-taskFinish/list', ctx.columnsWidth)"
      >
        <template #version="{ row }"> v{{ row.version }}.0</template>
        <template #approverName="{ row }">
          <t-tag theme="success" variant="light">
            {{ row.approveName || '无' }}
          </t-tag>
        </template>
        <template #flowStatus="{ row }">
          <dict-tag :options="wf_business_status" :value="row.flowStatus"></dict-tag>
        </template>
        <template #flowTaskStatus="{ row }">
          <dict-tag :options="wf_task_status" :value="row.flowTaskStatus"></dict-tag>
        </template>
        <template #operation="{ row }">
          <t-button theme="primary" variant="outline" size="small" @click="handleView(row)">
            <template #icon><BrowseIcon /></template>
            查看
          </t-button>
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
    <!-- 申请人 -->
    <UserSelect ref="userSelectRef" :multiple="true" :data="selectUserIds" @confirm-call-back="userSelectCallBack"></UserSelect>
  </div>
</template>

<script setup lang="ts">
import { pageByTaskFinish } from '@/api/workflow/task';
import { TaskQuery, FlowTaskVO } from '@/api/workflow/task/types';
import workflowCommon from '@/api/workflow/workflowCommon';
import { RouterJumpVo } from '@/api/workflow/workflowCommon/types';
// 列宽记忆：拖拽后按表名存 localStorage，进页面还原（见 hooks/useTableColumnWidth）
import { mergeSavedWidths, saveColumnWidths } from '@/hooks/useTableColumnWidth';
// TDesign 图标必须显式 import，不会自动解析（DOC/35 §3）
import { BrowseIcon, RefreshIcon, SearchIcon } from 'tdesign-icons-vue-next';
//审批记录组件
const queryFormRef = ref<any>();
const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const { wf_business_status } = toRefs<any>(proxy?.useDict('wf_business_status'));
const { wf_task_status } = toRefs<any>(proxy?.useDict('wf_task_status'));
import UserSelect from '@/components/UserSelect/index.vue';
import { ref } from 'vue';
import { UserVO } from '@/api/system/user/types';

const userSelectRef = ref<InstanceType<typeof UserSelect>>();
// 遮罩层
const loading = ref(true);
// 选中数组
const ids = ref<Array<any>>([]);
// 非单个禁用
const single = ref(true);
// 非多个禁用
const multiple = ref(true);
// 显示搜索条件
const showSearch = ref(true);
// 总条数
const total = ref(0);
// 模型定义表格数据
const taskList = ref([]);
// 查询参数
const queryParams = ref<TaskQuery>({
  pageNum: 1,
  pageSize: 10,
  nodeName: undefined,
  flowName: undefined,
  flowCode: undefined,
  createByIds: []
});
//申请人id
const selectUserIds = ref<Array<number | string>>([]);
//申请人选择数量
const userSelectCount = ref(0);
/** 已办任务列表列配置：t-table 用 columns 数组，插槽名与 cell 字符串同名（DOC/35 §5） */
const taskColumns: any[] = mergeSavedWidths('workflow-taskFinish/list', [
  { colKey: 'row-select', type: 'multiple', width: 55, align: 'center' },
  { colKey: 'rowIndex', title: '序号', width: 60, align: 'center' },
  { colKey: 'businessCode', title: '业务编码', align: 'center', ellipsis: true },
  { colKey: 'businessTitle', title: '业务标题', align: 'center', ellipsis: true },
  { colKey: 'flowName', title: '流程定义名称', align: 'center', width: 120 },
  { colKey: 'flowCode', title: '流程定义编码', align: 'center', width: 120 },
  { colKey: 'categoryName', title: '流程分类', align: 'center' },
  { colKey: 'version', title: '版本号', align: 'center', width: 90, cell: 'version' },
  { colKey: 'nodeName', title: '任务名称', align: 'center', ellipsis: true },
  { colKey: 'createByName', title: '申请人', align: 'center', ellipsis: true },
  { colKey: 'approverName', title: '办理人', align: 'center', cell: 'approverName' },
  { colKey: 'flowStatus', title: '流程状态', align: 'center', minWidth: 80, cell: 'flowStatus' },
  { colKey: 'flowTaskStatus', title: '任务状态', align: 'center', minWidth: 80, cell: 'flowTaskStatus' },
  { colKey: 'createTime', title: '创建时间', align: 'center', width: 150, ellipsis: true },
  { colKey: 'operation', title: '操作', align: 'center', width: 200, cell: 'operation' }
]);
/** 搜索按钮操作 */
const handleQuery = () => {
  getFinishList();
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
const getFinishList = () => {
  loading.value = true;
  pageByTaskFinish(queryParams.value).then((resp) => {
    taskList.value = resp.rows;
    total.value = resp.total;
    loading.value = false;
  });
};
/** 查看按钮操作 */
const handleView = (row: FlowTaskVO) => {
  const routerJumpVo = reactive<RouterJumpVo>({
    businessId: row.businessId,
    taskId: row.id,
    type: 'view',
    formCustom: row.formCustom,
    formPath: row.formPath
  });
  workflowCommon.routerJump(routerJumpVo, proxy);
};
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
onMounted(() => {
  getFinishList();
});
</script>

<style lang="scss" scoped>
// 卡片头工具栏：原栅格行的 flex 布局收敛到本页 scoped 类
.finish-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
</style>
