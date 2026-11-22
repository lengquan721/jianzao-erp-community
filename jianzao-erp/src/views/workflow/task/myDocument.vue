<template>
  <div class="p-2">
    <t-row :gutter="20">
      <!-- 流程分类树 -->
      <t-col :lg="2" :xs="12" style="">
        <t-card :bordered="false" class="erp-filter-card" hover-shadow>
          <t-input v-model="categoryName" placeholder="请输入流程分类名" clearable>
            <template #prefix-icon><SearchIcon /></template>
          </t-input>
          <t-tree
            ref="categoryTreeRef"
            class="mt-2"
            :data="categoryOptions"
            :keys="{ value: 'id', label: 'label', children: 'children' }"
            :activable="true"
            :expand-on-click-node="false"
            :filter="categoryFilter"
            expand-all
            @click="handleNodeClick"
          ></t-tree>
        </t-card>
      </t-col>
      <t-col :lg="10" :xs="12">
        <transition :enter-active-class="proxy?.animate.searchAnimate.enter" :leave-active-class="proxy?.animate.searchAnimate.leave">
          <div v-show="showSearch" class="mb-[10px]">
            <t-card :bordered="false" class="erp-filter-card">
              <t-form v-show="showSearch" ref="queryFormRef" :data="queryParams" class="erp-filter-row" label-align="right" :label-width="'auto'">
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
            <div class="mydoc-toolbar">
              <right-toolbar v-model:show-search="showSearch" @query-table="handleQuery"></right-toolbar>
            </div>
          </template>

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
            :selected-row-keys="instanceIds"
            @select-change="handleSelectionChange"
            @column-resize-change="(ctx: any) => saveColumnWidths('workflow-myDocument/list', ctx.columnsWidth)"
          >
            <template #version="{ row }"> v{{ row.version }}.0</template>
            <template #isSuspended="{ row }">
              <t-tag v-if="!row.isSuspended" theme="success" variant="light">激活</t-tag>
              <t-tag v-else theme="danger" variant="light">挂起</t-tag>
            </template>
            <template #flowStatus="{ row }">
              <dict-tag :options="wf_business_status" :value="row.flowStatus"></dict-tag>
            </template>
            <template #operation="{ row }">
              <div class="mydoc-actions">
                <div class="mydoc-actions__row">
                  <t-button
                    v-if="row.flowStatus === 'draft' || row.flowStatus === 'cancel' || row.flowStatus === 'back'"
                    theme="primary"
                    variant="outline"
                    size="small"
                    @click="handleOpen(row, 'update')"
                  >
                    <template #icon><Edit1Icon /></template>
                    编辑
                  </t-button>
                  <t-button
                    v-if="row.flowStatus === 'draft' || row.flowStatus === 'cancel' || row.flowStatus === 'back'"
                    theme="danger"
                    variant="outline"
                    size="small"
                    @click="handleDelete(row)"
                  >
                    <template #icon><DeleteIcon /></template>
                    删除
                  </t-button>
                </div>
                <div class="mydoc-actions__row">
                  <t-button theme="primary" variant="outline" size="small" @click="handleOpen(row, 'view')">
                    <template #icon><BrowseIcon /></template>
                    查看
                  </t-button>
                  <t-button
                    v-if="row.flowStatus === 'waiting'"
                    theme="warning"
                    variant="outline"
                    size="small"
                    @click="handleCancelProcessApply(row.businessId)"
                  >
                    <template #icon><NotificationIcon /></template>
                    撤销
                  </t-button>
                </div>
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
        </t-card>
      </t-col>
    </t-row>
    <!-- 提交组件 -->
    <submitVerify ref="submitVerifyRef" @submit-callback="getList" />
  </div>
</template>

<script setup lang="ts">
import { pageByCurrent, deleteByInstanceIds, cancelProcessApply } from '@/api/workflow/instance';
import { categoryTree } from '@/api/workflow/category';
import { CategoryTreeVO } from '@/api/workflow/category/types';
import { FlowInstanceQuery, FlowInstanceVO } from '@/api/workflow/instance/types';
import workflowCommon from '@/api/workflow/workflowCommon';
import { RouterJumpVo } from '@/api/workflow/workflowCommon/types';
import type { TreeNodeModel } from 'tdesign-vue-next';
// 列宽记忆：拖拽后按表名存 localStorage，进页面还原（见 hooks/useTableColumnWidth）
import { mergeSavedWidths, saveColumnWidths } from '@/hooks/useTableColumnWidth';
// TDesign 图标必须显式 import，不会自动解析（DOC/35 §3）
import { BrowseIcon, DeleteIcon, Edit1Icon, NotificationIcon, RefreshIcon, SearchIcon } from 'tdesign-icons-vue-next';
const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const { wf_business_status } = toRefs<any>(proxy?.useDict('wf_business_status'));
const queryFormRef = ref<any>();
const categoryTreeRef = ref<any>();

// 遮罩层
const loading = ref(true);
// 选中数组
const businessIds = ref<Array<number | string>>([]);
const instanceIds = ref<Array<number | string>>([]);
// 非单个禁用
const single = ref(true);
// 非多个禁用
const multiple = ref(true);
// 显示搜索条件
const showSearch = ref(true);
// 总条数
const total = ref(0);
// 模型定义表格数据
const processInstanceList = ref<FlowInstanceVO[]>([]);

const categoryOptions = ref<CategoryTreeVO[]>([]);
const categoryName = ref('');

const tab = ref('running');
// 查询参数
const queryParams = ref<FlowInstanceQuery>({
  pageNum: 1,
  pageSize: 10,
  flowCode: undefined,
  category: undefined
});

onMounted(() => {
  getList();
  getTreeselect();
});

/** 节点单击事件：t-tree 的 click 上下文是 { node, e }，node.data 为原始数据 */
const handleNodeClick = (context: { node: TreeNodeModel }) => {
  queryParams.value.category = context.node.data.id;
  if (context.node.data.id === '0') {
    queryParams.value.category = '';
  }
  handleQuery();
};
/** 分类树过滤：t-tree 的 filter 是响应式 prop，按节点显示名子串匹配（与 processDefinition 同口径）；未录入时不过滤 */
const categoryFilter = computed<((node: TreeNodeModel) => boolean) | undefined>(() => {
  const keyword = categoryName.value;
  if (!keyword) return undefined;
  return (node: TreeNodeModel) => String(node.label ?? '').indexOf(keyword) !== -1;
});

/** 查询流程分类下拉树结构 */
const getTreeselect = async () => {
  const res = await categoryTree();
  categoryOptions.value = res.data;
};

/** 我的单据列表列配置：t-table 用 columns 数组，插槽名与 cell 字符串同名；状态列按 tab 条件插入（DOC/35 §5） */
const instanceColumns = computed<any[]>(() => {
  const cols: any[] = [
    { colKey: 'row-select', type: 'multiple', width: 55, align: 'center' },
    { colKey: 'rowIndex', title: '序号', width: 60, align: 'center' },
    { colKey: 'flowName', title: '流程定义名称', align: 'center', ellipsis: true },
    { colKey: 'flowCode', title: '流程定义编码', align: 'center' },
    { colKey: 'categoryName', title: '流程分类', align: 'center' },
    { colKey: 'version', title: '版本号', align: 'center', width: 90, cell: 'version' }
  ];
  if (tab.value === 'running') {
    cols.push({ colKey: 'isSuspended', title: '状态', align: 'center', minWidth: 70, cell: 'isSuspended' });
  }
  cols.push(
    { colKey: 'flowStatus', title: '流程状态', align: 'center', minWidth: 70, cell: 'flowStatus' },
    { colKey: 'createTime', title: '启动时间', align: 'center', width: 160 },
    { colKey: 'operation', title: '操作', align: 'center', width: 162, cell: 'operation' }
  );
  return mergeSavedWidths('workflow-myDocument/list', cols);
});

/** 搜索按钮操作 */
const handleQuery = () => {
  getList();
};
/** 重置按钮操作：t-form 使用 reset() 方法 */
const resetQuery = () => {
  queryFormRef.value?.reset();
  queryParams.value.category = '';
  queryParams.value.pageNum = 1;
  queryParams.value.pageSize = 10;
  handleQuery();
};
// 多选框选中数据：t-table 的 select-change 只回传选中的 rowKey（rowKey=id）
const handleSelectionChange = (keys: Array<string | number>) => {
  const selection = processInstanceList.value.filter((item) => keys.includes(item.id));
  businessIds.value = selection.map((item: any) => item.businessId);
  instanceIds.value = keys;
  single.value = keys.length !== 1;
  multiple.value = !keys.length;
};
//分页
const getList = () => {
  loading.value = true;
  pageByCurrent(queryParams.value).then((resp) => {
    processInstanceList.value = resp.rows;
    total.value = resp.total;
    loading.value = false;
  });
};

/** 删除按钮操作 */
const handleDelete = async (row: FlowInstanceVO) => {
  const instanceIdList = row.id || instanceIds.value;
  await proxy?.$modal.confirm('是否确认删除？');
  loading.value = true;
  if ('running' === tab.value) {
    await deleteByInstanceIds(instanceIdList).finally(() => (loading.value = false));
    getList();
  }
  proxy?.$modal.msgSuccess('删除成功');
};

/** 撤销按钮操作 */
const handleCancelProcessApply = async (businessId: string) => {
  await proxy?.$modal.confirm('是否确认撤销当前单据？');
  loading.value = true;
  if ('running' === tab.value) {
    const data = {
      businessId: businessId,
      message: '申请人撤销流程！'
    };
    await cancelProcessApply(data).finally(() => (loading.value = false));
    getList();
  }
  proxy?.$modal.msgSuccess('撤销成功');
};

//办理
const handleOpen = async (row, type) => {
  const routerJumpVo = reactive<RouterJumpVo>({
    businessId: row.businessId,
    taskId: row.id,
    type: type,
    formCustom: row.formCustom,
    formPath: row.formPath
  });
  workflowCommon.routerJump(routerJumpVo, proxy);
};
</script>

<style lang="scss" scoped>
// 卡片头工具栏：原栅格行的 flex 布局收敛到本页 scoped 类
.mydoc-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.mydoc-actions {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.mydoc-actions__row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
</style>
