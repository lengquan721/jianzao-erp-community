<template>
  <div class="p-2">
    <transition :enter-active-class="proxy?.animate.searchAnimate.enter" :leave-active-class="proxy?.animate.searchAnimate.leave">
      <div v-show="showSearch" class="mb-[10px]">
        <t-card :bordered="false" class="erp-filter-card" title="筛选条件">
          <t-form ref="queryFormRef" :data="queryParams" class="erp-filter-row" label-align="right" :label-width="'auto'" @submit="handleQuery">
            <t-form-item label="用户名称" name="userName">
              <t-input v-model="queryParams.userName" placeholder="请输入用户名称" clearable class="query-w-md" @enter="handleQuery" />
            </t-form-item>
            <t-form-item label="手机号码" name="phonenumber">
              <t-input v-model="queryParams.phonenumber" placeholder="请输入手机号码" clearable class="query-w-md" @enter="handleQuery" />
            </t-form-item>
            <div class="erp-filter-actions">
              <t-button theme="primary" @click="handleQuery"
                ><template #icon><SearchIcon /></template>搜索</t-button
              >
              <t-button theme="default" variant="outline" @click="resetQuery"
                ><template #icon><RefreshIcon /></template>重置</t-button
              >
            </div>
          </t-form>
        </t-card>
      </div>
    </transition>

    <div class="erp-surface erp-panel">
      <div class="erp-panel__head">
        <div class="flex items-center gap-2">
          <t-button v-hasPermi="['system:role:add']" theme="primary" variant="outline" @click="openSelectUser">
            <template #icon><PlusIcon /></template>添加用户
          </t-button>
          <t-button v-hasPermi="['system:role:remove']" theme="danger" variant="outline" :disabled="multiple" @click="cancelAuthUserAll">
            <template #icon><CloseCircleIcon /></template>批量取消授权
          </t-button>
          <t-button theme="warning" variant="outline" @click="handleClose"
            ><template #icon><CloseIcon /></template>关闭</t-button
          >
        </div>
        <right-toolbar v-model:show-search="showSearch" :search="true" @query-table="getList"></right-toolbar>
      </div>

      <t-table
        class="erp-table"
        row-key="userId"
        :data="userList"
        :columns="listColumns"
        :loading="loading"
        :hover="true"
        :resizable="true"
        size="small"
        cell-empty-content="-"
        :selected-row-keys="selectedKeys"
        @select-change="handleSelectionChange"
        @column-resize-change="(ctx: any) => saveColumnWidths('system-role/auth-user', ctx.columnsWidth)"
      >
        <template #status="{ row }">
          <dict-tag :options="sys_normal_disable" :value="row.status" />
        </template>
        <template #operation="{ row }">
          <!-- 权限判断改挂外层 t-tooltip：v-hasPermi 在 mounted 里删节点，会与 tooltip 的包裹结构打架 -->
          <t-tooltip v-if="checkPermi(['system:role:remove'])" content="取消授权" placement="top">
            <t-button theme="primary" variant="text" shape="square" size="small" @click="cancelAuthUser(row)">
              <template #icon><CloseCircleIcon /></template>
            </t-button>
          </t-tooltip>
        </template>
      </t-table>

      <pagination v-show="total > 0" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" :total="total" @pagination="getList" />
      <select-user ref="selectRef" :role-id="queryParams.roleId" @ok="handleQuery" />
    </div>
  </div>
</template>

<script setup name="AuthUser" lang="ts">
import { allocatedUserList, authUserCancel, authUserCancelAll } from '@/api/system/role';
import { UserQuery } from '@/api/system/user/types';
import { UserVO } from '@/api/system/user/types';
import SelectUser from './selectUser.vue';
import { RouteLocationRaw } from 'vue-router';
import { checkPermi } from '@/utils/permission';
import { mergeSavedWidths, saveColumnWidths } from '@/hooks/useTableColumnWidth';
import { CloseCircleIcon, CloseIcon, PlusIcon, RefreshIcon, SearchIcon } from 'tdesign-icons-vue-next';

const route = useRoute();
const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const { sys_normal_disable } = toRefs<any>(proxy?.useDict('sys_normal_disable'));

const userList = ref<UserVO[]>([]);
const loading = ref(true);
const showSearch = ref(true);
const multiple = ref(true);
const total = ref(0);
/** t-table 的受控选中态（rowKey=userId） */
const selectedKeys = ref<Array<string | number>>([]);
const userIds = ref<Array<string | number>>([]);

const queryFormRef = ref<any>();
const selectRef = ref<InstanceType<typeof SelectUser>>();

const queryParams = reactive<UserQuery>({
  pageNum: 1,
  pageSize: 10,
  roleId: route.params.roleId as string,
  userName: undefined,
  phonenumber: undefined
});

/** 主列表列配置：t-table 用 columns 数组，插槽名与 cell 字符串同名（DOC/35 §4） */
const listColumns: any[] = [
  { colKey: 'row-select', type: 'multiple', width: 46, align: 'center' },
  { colKey: 'userName', title: '用户名称', align: 'center', minWidth: 140, ellipsis: true },
  { colKey: 'nickName', title: '用户昵称', align: 'center', minWidth: 140, ellipsis: true },
  { colKey: 'email', title: '邮箱', align: 'center', minWidth: 180, ellipsis: true },
  { colKey: 'phonenumber', title: '手机', align: 'center', minWidth: 140, ellipsis: true },
  { colKey: 'status', title: '状态', align: 'center', width: 100, cell: 'status' },
  { colKey: 'createTime', title: '创建时间', align: 'center', width: 180 },
  { colKey: 'operation', title: '操作', align: 'center', width: 80, cell: 'operation' }
];
mergeSavedWidths('system-role/auth-user', listColumns);

/** 查询授权用户列表 */
const getList = async () => {
  loading.value = true;
  const res = await allocatedUserList(queryParams);
  userList.value = res.rows;
  total.value = res.total;
  loading.value = false;
};
// 返回按钮
const handleClose = () => {
  const obj: RouteLocationRaw = { path: '/system/role' };
  proxy?.$tab.closeOpenPage(obj);
};
/** 搜索按钮操作 */
const handleQuery = () => {
  queryParams.pageNum = 1;
  getList();
};
/** 重置按钮操作：t-form 用 reset()，不再用 resetFields() */
const resetQuery = () => {
  queryFormRef.value?.reset();
  queryParams.userName = undefined;
  queryParams.phonenumber = undefined;
  handleQuery();
};
/** 多选框选中数据：t-table 的 select-change 只回传选中的 rowKey（rowKey=userId） */
const handleSelectionChange = (keys: Array<string | number>) => {
  selectedKeys.value = keys;
  userIds.value = keys;
  multiple.value = !keys.length;
};
/** 打开授权用户表弹窗 */
const openSelectUser = () => {
  selectRef.value?.show();
};
/** 取消授权按钮操作 */
const cancelAuthUser = async (row: UserVO) => {
  await proxy?.$modal.confirm('确认要取消该用户"' + row.userName + '"角色吗？');
  await authUserCancel({ userId: row.userId, roleId: queryParams.roleId });
  await getList();
  proxy?.$modal.msgSuccess('取消授权成功');
};
/** 批量取消授权按钮操作 */
const cancelAuthUserAll = async () => {
  const roleId = queryParams.roleId;
  const uIds = userIds.value.join(',');
  await proxy?.$modal.confirm('是否取消选中用户授权数据项?');
  await authUserCancelAll({ roleId: roleId, userIds: uIds });
  selectedKeys.value = [];
  await getList();
  proxy?.$modal.msgSuccess('取消授权成功');
};

onMounted(() => {
  getList();
});
</script>
