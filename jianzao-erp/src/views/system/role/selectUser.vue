<template>
  <div>
    <t-dialog v-model:visible="visible" width="800px" top="5vh" attach="body">
      <template #header>选择用户</template>
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
      <t-table
        class="erp-table"
        row-key="userId"
        :data="userList"
        :columns="listColumns"
        :hover="true"
        :resizable="true"
        size="small"
        max-height="260"
        cell-empty-content="-"
        :selected-row-keys="selectedKeys"
        @select-change="handleSelectionChange"
        @column-resize-change="(ctx: any) => saveColumnWidths('system-role/select-user', ctx.columnsWidth)"
      >
        <template #status="{ row }">
          <dict-tag :options="sys_normal_disable" :value="row.status" />
        </template>
        <template #createTime="{ row }">
          <span>{{ proxy.parseTime(row.createTime) }}</span>
        </template>
      </t-table>
      <pagination v-if="total > 0" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" :total="total" @pagination="getList" />
      <template #footer>
        <div class="dialog-footer">
          <t-button theme="primary" @click="handleSelectUser">确 定</t-button>
          <t-button theme="default" variant="outline" @click="visible = false">取 消</t-button>
        </div>
      </template>
    </t-dialog>
  </div>
</template>

<script setup name="SelectUser" lang="ts">
import { authUserSelectAll, unallocatedUserList } from '@/api/system/role';
import { UserVO } from '@/api/system/user/types';
import { UserQuery } from '@/api/system/user/types';
import { mergeSavedWidths, saveColumnWidths } from '@/hooks/useTableColumnWidth';
import { RefreshIcon, SearchIcon } from 'tdesign-icons-vue-next';

const props = defineProps({
  roleId: {
    type: [Number, String],
    required: true
  }
});

const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const { sys_normal_disable } = toRefs<any>(proxy?.useDict('sys_normal_disable'));

const userList = ref<UserVO[]>([]);
const visible = ref(false);
const total = ref(0);
/** t-table 的受控选中态（rowKey=userId）；原 EP 的 toggleRowSelection 行点击清选中已不需要：TDesign 复选框自身 stopPropagation */
const selectedKeys = ref<Array<string | number>>([]);
const userIds = ref<Array<string | number>>([]);

const queryParams = reactive<UserQuery>({
  pageNum: 1,
  pageSize: 10,
  roleId: undefined,
  userName: undefined,
  phonenumber: undefined
});

const queryFormRef = ref<any>();

/** 列表列配置：t-table 用 columns 数组，插槽名与 cell 字符串同名（DOC/35 §4） */
const listColumns: any[] = [
  { colKey: 'row-select', type: 'multiple', width: 46 },
  { colKey: 'userName', title: '用户名称', align: 'center', minWidth: 120, ellipsis: true },
  { colKey: 'nickName', title: '用户昵称', align: 'center', minWidth: 120, ellipsis: true },
  { colKey: 'email', title: '邮箱', align: 'center', minWidth: 160, ellipsis: true },
  { colKey: 'phonenumber', title: '手机', align: 'center', minWidth: 120, ellipsis: true },
  { colKey: 'status', title: '状态', align: 'center', width: 100, cell: 'status' },
  { colKey: 'createTime', title: '创建时间', align: 'center', width: 180, cell: 'createTime' }
];
mergeSavedWidths('system-role/select-user', listColumns);

const show = () => {
  queryParams.roleId = props.roleId;
  selectedKeys.value = [];
  userIds.value = [];
  getList();
  visible.value = true;
};

/** 多选框选中数据：t-table 的 select-change 只回传选中的 rowKey（rowKey=userId） */
const handleSelectionChange = (keys: Array<string | number>) => {
  selectedKeys.value = keys;
  userIds.value = keys;
};

/** 查询数据 */
const getList = async () => {
  const res = await unallocatedUserList(queryParams);
  userList.value = res.rows;
  total.value = res.total;
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
  getList();
};

const emit = defineEmits(['ok']);
/**选择授权用户操作 */
const handleSelectUser = async () => {
  const roleId = queryParams.roleId;
  const ids = userIds.value.join(',');
  if (ids == '') {
    proxy?.$modal.msgError('请选择要分配的用户');
    return;
  }
  await authUserSelectAll({ roleId, userIds: ids });
  proxy?.$modal.msgSuccess('分配成功');
  emit('ok');
  visible.value = false;
};
// 暴露
defineExpose({
  show
});
</script>
