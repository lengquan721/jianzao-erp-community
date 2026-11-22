<template>
  <div class="p-2">
    <transition :enter-active-class="proxy?.animate.searchAnimate.enter" :leave-active-class="proxy?.animate.searchAnimate.leave">
      <div v-show="showSearch" class="mb-[10px]">
        <t-card hover-shadow>
          <t-form v-show="showSearch" ref="queryFormRef" :data="queryParams" layout="inline">
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
        <t-row :gutter="10" class="mb8">
          <right-toolbar v-model:show-search="showSearch" @query-table="handleQuery"></right-toolbar>
        </t-row>
      </template>

      <t-table
        class="erp-table"
        row-key="id"
        :data="taskList"
        :columns="taskColumns"
        :loading="loading"
        :hover="true"
        :resizable="true"
        bordered
        size="small"
        cell-empty-content="-"
        :selected-row-keys="ids"
        @select-change="handleSelectionChange"
      >
        <template #rowIndex="{ rowIndex }">{{ rowIndex + 1 }}</template>
        <template #assigneeNames="{ row }">
          <template v-if="row.assigneeNames">
            <t-tag v-for="(name, index) in row.assigneeNames.split(',')" :key="index" theme="success" size="small">
              {{ name }}
            </t-tag>
          </template>
          <t-tag v-else theme="success" size="small">无</t-tag>
        </template>
        <template #flowStatusName="{ row }">
          <dict-tag :options="wf_business_status" :value="row.flowStatus" />
        </template>
        <template #operation="{ row }">
          <t-button theme="primary" variant="outline" size="small" @click="handleOpen(row)">
            <template #icon><Edit1Icon /></template>
            办理
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
import { pageByTaskWait } from '@/api/workflow/task';
import { TaskQuery, FlowTaskVO } from '@/api/workflow/task/types';
import workflowCommon from '@/api/workflow/workflowCommon';
import { RouterJumpVo } from '@/api/workflow/workflowCommon/types';
// TDesign 图标必须显式 import，不会自动解析（DOC/35 §3）
import { Edit1Icon, RefreshIcon, SearchIcon } from 'tdesign-icons-vue-next';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const { wf_business_status } = toRefs<any>(proxy?.useDict('wf_business_status'));
import UserSelect from '@/components/UserSelect/index.vue';
import { ref } from 'vue';
import { UserVO } from '@/api/system/user/types';

const userSelectRef = ref<InstanceType<typeof UserSelect>>();
//提交组件
const queryFormRef = ref<any>();
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

/** 待办任务列表列配置：t-table 用 columns 数组，cell 字符串与 #插槽同名（DOC/35 §4） */
const taskColumns: any[] = [
  { colKey: 'row-select', type: 'multiple', width: 55, align: 'center' },
  { colKey: 'rowIndex', title: '序号', width: 60, align: 'center', cell: 'rowIndex' },
  { colKey: 'businessCode', title: '业务编码', align: 'center', ellipsis: true },
  { colKey: 'businessTitle', title: '业务标题', align: 'center', ellipsis: true },
  { colKey: 'flowName', title: '流程定义名称', align: 'center', ellipsis: true },
  { colKey: 'flowCode', title: '流程定义编码', align: 'center' },
  { colKey: 'categoryName', title: '流程分类', align: 'center' },
  { colKey: 'nodeName', title: '任务名称', align: 'center' },
  { colKey: 'createByName', title: '申请人', align: 'center' },
  { colKey: 'assigneeNames', title: '办理人', align: 'center', cell: 'assigneeNames' },
  { colKey: 'flowStatusName', title: '流程状态', minWidth: 70, align: 'center', cell: 'flowStatusName' },
  { colKey: 'createTime', title: '创建时间', width: 160, align: 'center' },
  { colKey: 'operation', title: '操作', width: 200, align: 'center', cell: 'operation' }
];

onMounted(() => {
  getWaitingList();
});
/** 搜索按钮操作 */
const handleQuery = () => {
  getWaitingList();
};
/** 重置按钮操作：t-form 提供 reset()，与 EP resetFields 契约不同 */
const resetQuery = () => {
  queryFormRef.value?.reset();
  queryParams.value.pageNum = 1;
  queryParams.value.pageSize = 10;
  queryParams.value.createByIds = [];
  userSelectCount.value = 0;
  selectUserIds.value = [];
  handleQuery();
};
/** 多选框选中数据：t-table 的 select-change 只回传选中的 rowKey（rowKey=id） */
const handleSelectionChange = (keys: Array<string | number>) => {
  ids.value = keys;
  single.value = keys.length !== 1;
  multiple.value = !keys.length;
};
//分页
const getWaitingList = () => {
  loading.value = true;
  pageByTaskWait(queryParams.value).then((resp) => {
    taskList.value = resp.rows;
    total.value = resp.total;
    loading.value = false;
  });
};
//办理
const handleOpen = async (row: FlowTaskVO) => {
  const routerJumpVo = reactive<RouterJumpVo>({
    businessId: row.businessId,
    taskId: row.id,
    type: 'approval',
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
</script>
