<template>
  <div class="p-2">
    <div class="panel">
      <h4 class="panel-title">基本信息</h4>
      <t-form :data="form" layout="inline" label-width="auto">
        <t-row :gutter="10">
          <t-col :span="3">
            <t-form-item label="用户昵称" name="nickName">
              <t-input v-model="form.nickName" disabled />
            </t-form-item>
          </t-col>
          <t-col :span="3">
            <t-form-item label="登录账号" name="userName">
              <t-input v-model="form.userName" disabled />
            </t-form-item>
          </t-col>
        </t-row>
      </t-form>
    </div>
    <div class="panel">
      <h4 class="panel-title">角色信息</h4>
      <div>
        <t-table
          ref="tableRef"
          class="erp-table"
          row-key="roleId"
          bordered
          :data="roles.slice((pageNum - 1) * pageSize, pageNum * pageSize)"
          :columns="roleColumns"
          :loading="loading"
          :selected-row-keys="roleIds"
          :resizable="true"
          size="small"
          cell-empty-content="-"
          @column-resize-change="(ctx: any) => saveColumnWidths('system-user/auth-role', ctx.columnsWidth)"
          @row-click="(ctx: any) => clickRow(ctx.row)"
          @select-change="handleSelectionChange"
        >
          <template #index="{ rowIndex }">
            <span>{{ (pageNum - 1) * pageSize + rowIndex + 1 }}</span>
          </template>
          <template #createTime="{ row }">
            <span>{{ proxy.parseTime(row.createTime) }}</span>
          </template>
        </t-table>
        <pagination v-show="total > 0" v-model:page="pageNum" v-model:limit="pageSize" :total="total" />
        <div style="text-align: center; margin-left: -120px; margin-top: 30px">
          <t-button theme="primary" @click="submitForm()">提交</t-button>
          <t-button theme="default" variant="outline" @click="close()">返回</t-button>
        </div>
        <div></div>
      </div>
    </div>
  </div>
</template>

<script setup name="AuthRole" lang="ts">
import { RoleVO } from '@/api/system/role/types';
import { getAuthRole, updateAuthRole } from '@/api/system/user';
import { UserForm } from '@/api/system/user/types';
import { RouteLocationRaw } from 'vue-router';
import { parseTime } from '@/utils/ruoyi';
import { mergeSavedWidths, saveColumnWidths } from '@/hooks/useTableColumnWidth';

const route = useRoute();
const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const loading = ref(true);
const total = ref(0);
const pageNum = ref(1);
const pageSize = ref(10);
const roleIds = ref<Array<string | number>>([]);
const roles = ref<RoleVO[]>([]);
const form = ref<Partial<UserForm>>({
  nickName: undefined,
  userName: '',
  userId: undefined
});

const tableRef = ref<any>();

/** 角色列表列配置：t-table 用 columns 数组，type=selection→type='multiple'，selectable→disabled */
const roleColumns: any[] = [
  { colKey: 'index', title: '序号', width: 55, align: 'center', cell: 'index' },
  { colKey: 'row-select', type: 'multiple', width: 55, align: 'center', disabled: ({ row }: any) => !checkSelectable(row) },
  { colKey: 'roleId', title: '角色编号', align: 'center' },
  { colKey: 'roleName', title: '角色名称', align: 'center' },
  { colKey: 'roleKey', title: '权限字符', align: 'center' },
  { colKey: 'createTime', title: '创建时间', align: 'center', width: 180, cell: 'createTime' }
];
mergeSavedWidths('system-user/auth-role', roleColumns);

/** 单击选中行数据：受控 selectedRowKeys 下按行主键增删，保持原 toggleRowSelection 语义 */
const clickRow = (row: RoleVO) => {
  if (checkSelectable(row)) {
    const key = row.roleId;
    if (roleIds.value.includes(key)) {
      roleIds.value = roleIds.value.filter((id) => id !== key);
    } else {
      roleIds.value = [...roleIds.value, key];
    }
  }
};
/** 多选框选中数据：t-table 的 select-change 回传选中的 rowKey 数组（rowKey=roleId） */
const handleSelectionChange = (selection: Array<string | number>) => {
  roleIds.value = selection;
};
/** 检查角色状态 */
const checkSelectable = (row: RoleVO): boolean => {
  return row.status === '0';
};
/** 关闭按钮 */
const close = () => {
  const obj: RouteLocationRaw = { path: '/system/user' };
  proxy?.$tab.closeOpenPage(obj);
};
/** 提交按钮 */
const submitForm = async () => {
  const userId = form.value.userId;
  const rIds = roleIds.value.join(',');
  await updateAuthRole({ userId: userId as string, roleIds: rIds });
  proxy?.$modal.msgSuccess('授权成功');
  close();
};

const getList = async () => {
  const userId = route.params && route.params.userId;
  if (userId) {
    loading.value = true;
    const res = await getAuthRole(userId as string);
    Object.assign(form.value, res.data.user);
    Object.assign(roles.value, res.data.roles);
    total.value = roles.value.length;
    // 初始勾选：把已授权角色（flag）的 roleId 写入受控 selectedRowKeys，替代 EP 的 toggleRowSelection
    await nextTick(() => {
      roleIds.value = roles.value.filter((row) => row?.flag).map((row) => row.roleId);
    });
    loading.value = false;
  }
};
onMounted(() => {
  getList();
});
</script>
