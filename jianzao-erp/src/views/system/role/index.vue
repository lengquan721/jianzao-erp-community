<template>
  <div class="p-2">
    <transition :enter-active-class="proxy?.animate.searchAnimate.enter" :leave-active-class="proxy?.animate.searchAnimate.leave">
      <div v-show="showSearch" class="mb-[10px]">
        <t-card :bordered="false" class="erp-filter-card" title="筛选条件">
          <t-form ref="queryFormRef" :data="queryParams" class="erp-filter-row" label-align="right" :label-width="'auto'" @submit="handleQuery">
            <t-form-item label="角色名称" name="roleName">
              <t-input v-model="queryParams.roleName" placeholder="请输入角色名称" clearable class="query-w-md" @enter="handleQuery" />
            </t-form-item>
            <t-form-item label="权限字符" name="roleKey">
              <t-input v-model="queryParams.roleKey" placeholder="请输入权限字符" clearable class="query-w-md" @enter="handleQuery" />
            </t-form-item>
            <t-form-item label="状态" name="status">
              <t-select v-model="queryParams.status" placeholder="角色状态" clearable class="query-w-sm" :options="statusOptions" />
            </t-form-item>
            <t-form-item label="创建时间">
              <!-- el-date-picker type=daterange → t-date-range-picker：start/end-placeholder 合并为数组（DOC/35 §3） -->
              <t-date-range-picker
                v-model="dateRange"
                mode="date"
                value-type="YYYY-MM-DD HH:mm:ss"
                :placeholder="['开始日期', '结束日期']"
                :default-time="['00:00:00', '23:59:59']"
                class="query-w-lg"
              />
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
          <t-button v-hasPermi="['system:role:add']" theme="primary" variant="outline" @click="handleAdd()">
            <template #icon><PlusIcon /></template>新增
          </t-button>
          <t-button v-hasPermi="['system:role:edit']" theme="success" variant="outline" :disabled="single" @click="handleUpdate()">
            <template #icon><Edit1Icon /></template>修改
          </t-button>
          <t-button v-hasPermi="['system:role:remove']" theme="danger" variant="outline" :disabled="ids.length === 0" @click="handleDelete()">
            <template #icon><DeleteIcon /></template>删除
          </t-button>
          <t-button v-hasPermi="['system:role:export']" theme="warning" variant="outline" @click="handleExport">
            <template #icon><DownloadIcon /></template>导出
          </t-button>
        </div>
        <right-toolbar v-model:show-search="showSearch" @query-table="getList"></right-toolbar>
      </div>

      <t-table
        class="erp-table"
        row-key="roleId"
        :data="roleList"
        :columns="listColumns"
        :loading="loading"
        :hover="true"
        :resizable="true"
        size="small"
        cell-empty-content="-"
        :selected-row-keys="ids"
        @select-change="handleSelectionChange"
        @column-resize-change="(ctx: any) => saveColumnWidths('system-role/list', ctx.columnsWidth)"
      >
        <template #status="{ row }">
          <!-- customValue 把 EP 的 active-value/inactive-value 语义映射到 TDesign；@change 时 v-model 已写入新值 -->
          <t-switch v-model="row.status" :custom-value="['0', '1']" @change="handleStatusChange(row)" />
        </template>
        <template #createTime="{ row }">
          <span>{{ proxy.parseTime(row.createTime) }}</span>
        </template>
        <template #operation="{ row }">
          <div v-if="row.roleId !== 1" class="flex items-center justify-center gap-1">
            <!-- 权限判断改挂外层 t-tooltip：v-hasPermi 在 mounted 里删节点，会与 tooltip 的包裹结构打架 -->
            <t-tooltip v-if="checkPermi(['system:role:edit'])" content="修改" placement="top">
              <t-button theme="primary" variant="text" shape="square" size="small" @click="handleUpdate(row)">
                <template #icon><Edit1Icon /></template>
              </t-button>
            </t-tooltip>
            <t-tooltip v-if="checkPermi(['system:role:remove'])" content="删除" placement="top">
              <t-button theme="danger" variant="text" shape="square" size="small" @click="handleDelete(row)">
                <template #icon><DeleteIcon /></template>
              </t-button>
            </t-tooltip>
            <t-tooltip v-if="checkPermi(['system:role:edit'])" content="数据权限" placement="top">
              <t-button theme="primary" variant="text" shape="square" size="small" @click="handleDataScope(row)">
                <template #icon><CheckCircleIcon /></template>
              </t-button>
            </t-tooltip>
            <t-tooltip v-if="checkPermi(['system:role:edit'])" content="分配用户" placement="top">
              <t-button theme="primary" variant="text" shape="square" size="small" @click="handleAuthUser(row)">
                <template #icon><UserIcon /></template>
              </t-button>
            </t-tooltip>
          </div>
        </template>
      </t-table>

      <pagination
        v-if="total > 0"
        v-model:total="total"
        v-model:page="queryParams.pageNum"
        v-model:limit="queryParams.pageSize"
        @pagination="getList"
      />
    </div>

    <!-- 添加或修改角色对话框 -->
    <t-dialog v-model:visible="dialog.visible" width="500px" attach="body">
      <template #header>{{ dialog.title }}</template>
      <t-form ref="roleFormRef" :data="form" :rules="rules" label-align="top">
        <t-form-item label="角色名称" name="roleName">
          <t-input v-model="form.roleName" placeholder="请输入角色名称" />
        </t-form-item>
        <t-form-item name="roleKey">
          <template #label>
            <span class="inline-flex items-center gap-1">
              <t-tooltip content="控制器中定义的权限字符，如：@SaCheckRole('admin')" placement="top">
                <HelpCircleIcon />
              </t-tooltip>
              权限字符
            </span>
          </template>
          <t-input v-model="form.roleKey" placeholder="请输入权限字符" />
        </t-form-item>
        <t-form-item label="角色顺序" name="roleSort">
          <t-input-number v-model="form.roleSort" :decimal-places="0" :min="0" theme="normal" style="width: 100%" />
        </t-form-item>
        <t-form-item label="状态" name="status">
          <t-radio-group v-model="form.status">
            <t-radio v-for="dict in sys_normal_disable" :key="dict.value" :value="dict.value">{{ dict.label }}</t-radio>
          </t-radio-group>
        </t-form-item>
        <t-form-item label="菜单权限">
          <div class="flex flex-col gap-2" style="width: 100%">
            <div class="flex items-center gap-3 flex-wrap">
              <t-checkbox :checked="menuExpand" @change="(v: boolean) => handleCheckedTreeExpand(v, 'menu')">展开/折叠</t-checkbox>
              <t-checkbox :checked="menuNodeAll" @change="(v: boolean) => handleCheckedTreeNodeAll(v, 'menu')">全选/全不选</t-checkbox>
              <t-checkbox :checked="form.menuCheckStrictly" @change="(v: boolean) => handleCheckedTreeConnect(v, 'menu')">父子联动</t-checkbox>
            </div>
            <t-tree
              ref="menuRef"
              class="tree-border"
              checkable
              value-mode="all"
              :data="menuOptions"
              :keys="treeKeys"
              :check-strictly="!form.menuCheckStrictly"
              :value="menuCheckedKeys"
              :expanded="menuExpandedKeys"
              empty="加载中，请稍候"
              @change="onMenuTreeChange"
              @expand="onMenuTreeExpand"
            />
          </div>
        </t-form-item>
        <t-form-item label="备注" name="remark">
          <t-textarea v-model="form.remark" :autosize="{ minRows: 2, maxRows: 4 }" placeholder="请输入内容" />
        </t-form-item>
      </t-form>
      <template #footer>
        <t-button theme="default" variant="outline" @click="cancel">取 消</t-button>
        <t-button theme="primary" @click="submitForm">确 定</t-button>
      </template>
    </t-dialog>

    <!-- 分配角色数据权限对话框 -->
    <t-dialog v-model:visible="openDataScope" width="500px" attach="body">
      <template #header>{{ dialog.title }}</template>
      <t-form ref="dataScopeRef" :data="form" label-align="top">
        <t-form-item label="角色名称">
          <t-input v-model="form.roleName" :disabled="true" />
        </t-form-item>
        <t-form-item label="权限字符">
          <t-input v-model="form.roleKey" :disabled="true" />
        </t-form-item>
        <t-form-item label="权限范围">
          <t-select v-model="form.dataScope" :options="dataScopeOptions" @change="dataScopeSelectChange" />
        </t-form-item>
        <t-form-item v-show="form.dataScope === '2'" label="数据权限">
          <div class="flex flex-col gap-2" style="width: 100%">
            <div class="flex items-center gap-3 flex-wrap">
              <t-checkbox :checked="deptExpand" @change="(v: boolean) => handleCheckedTreeExpand(v, 'dept')">展开/折叠</t-checkbox>
              <t-checkbox :checked="deptNodeAll" @change="(v: boolean) => handleCheckedTreeNodeAll(v, 'dept')">全选/全不选</t-checkbox>
              <t-checkbox :checked="form.deptCheckStrictly" @change="(v: boolean) => handleCheckedTreeConnect(v, 'dept')">父子联动</t-checkbox>
            </div>
            <t-tree
              ref="deptRef"
              class="tree-border"
              checkable
              value-mode="all"
              :data="deptOptions"
              :keys="treeKeys"
              :check-strictly="!form.deptCheckStrictly"
              :value="deptCheckedKeys"
              :expanded="deptExpandedKeys"
              empty="加载中，请稍候"
              @change="onDeptTreeChange"
              @expand="onDeptTreeExpand"
            />
          </div>
        </t-form-item>
      </t-form>
      <template #footer>
        <t-button theme="default" variant="outline" @click="cancelDataScope">取 消</t-button>
        <t-button theme="primary" @click="submitDataScope">确 定</t-button>
      </template>
    </t-dialog>
  </div>
</template>

<script setup name="Role" lang="ts">
import { addRole, changeRoleStatus, dataScope, delRole, getRole, listRole, updateRole, deptTreeSelect } from '@/api/system/role';
import { roleMenuTreeselect, treeselect as menuTreeselect } from '@/api/system/menu/index';
import { RoleVO, RoleForm, RoleQuery, DeptTreeOption } from '@/api/system/role/types';
import { MenuTreeOption, RoleMenuTree } from '@/api/system/menu/types';
import { checkPermi } from '@/utils/permission';
import { mergeSavedWidths, saveColumnWidths } from '@/hooks/useTableColumnWidth';
import {
  CheckCircleIcon,
  DeleteIcon,
  DownloadIcon,
  Edit1Icon,
  HelpCircleIcon,
  PlusIcon,
  RefreshIcon,
  SearchIcon,
  UserIcon
} from 'tdesign-icons-vue-next';

const router = useRouter();
const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const { sys_normal_disable } = toRefs<any>(proxy?.useDict('sys_normal_disable'));

const roleList = ref<RoleVO[]>();
const loading = ref(true);
const showSearch = ref(true);
const ids = ref<Array<string | number>>([]);
const single = ref(true);
const multiple = ref(true);
const total = ref(0);
const dateRange = ref<[string, string]>(['', '']);
const menuOptions = ref<MenuTreeOption[]>([]);
const menuExpand = ref(false);
const menuNodeAll = ref(false);
const deptExpand = ref(true);
const deptNodeAll = ref(false);
const deptOptions = ref<DeptTreeOption[]>([]);
const openDataScope = ref(false);
/** t-tree 的受控勾选值（节点 id 数组），替代 EP 的 setCheckedKeys/setChecked/setCheckedNodes */
const menuCheckedKeys = ref<Array<string | number>>([]);
const deptCheckedKeys = ref<Array<string | number>>([]);
/** t-tree 的受控展开值，替代 EP 的 store.nodesMap[id].expanded */
const menuExpandedKeys = ref<Array<string | number>>([]);
const deptExpandedKeys = ref<Array<string | number>>([]);
/** t-tree 的节点字段映射：EP 的 node-key="id" + props {label,children} */
const treeKeys = { value: 'id', label: 'label', children: 'children' };

/** 数据范围选项：t-select 用 {label,value} 数组 */
const dataScopeOptions = ref([
  { value: '1', label: '全部数据权限' },
  { value: '2', label: '自定数据权限' },
  { value: '3', label: '本部门数据权限' },
  { value: '4', label: '本部门及以下数据权限' },
  { value: '5', label: '仅本人数据权限' },
  { value: '6', label: '部门及以下或本人数据权限' }
]);

/** 状态下拉：t-select 用 {label,value} 数组替代 el-option */
const statusOptions = computed<Array<{ label: string; value: string }>>(() =>
  (sys_normal_disable.value ?? []).map((dict: any) => ({ label: dict.label, value: dict.value }))
);

const queryFormRef = ref<any>();
const roleFormRef = ref<any>();
const dataScopeRef = ref<any>();
const menuRef = ref<any>();
const deptRef = ref<any>();

const initForm: RoleForm = {
  roleId: undefined,
  roleSort: 1,
  status: '0',
  roleName: '',
  roleKey: '',
  menuCheckStrictly: true,
  deptCheckStrictly: true,
  remark: '',
  dataScope: '1',
  menuIds: [],
  deptIds: []
};

/** 表单校验规则：t-form 的 rules 形状与 EP 的 ElFormRules 不兼容，单独用 Record 声明 */
const rules: Record<string, any> = {
  roleName: [{ required: true, message: '角色名称不能为空', trigger: 'blur' }],
  roleKey: [{ required: true, message: '权限字符不能为空', trigger: 'blur' }],
  roleSort: [{ required: true, message: '角色顺序不能为空', trigger: 'blur' }]
};

const data = reactive<{ form: RoleForm; queryParams: RoleQuery }>({
  form: { ...initForm },
  queryParams: {
    pageNum: 1,
    pageSize: 10,
    roleName: '',
    roleKey: '',
    status: ''
  }
});
const { form, queryParams } = toRefs<{ form: RoleForm; queryParams: RoleQuery }>(data);

const dialog = reactive<DialogOption>({
  visible: false,
  title: ''
});

/** 主列表列配置：t-table 用 columns 数组，插槽名与 cell 字符串同名（DOC/35 §4） */
const listColumns: any[] = [
  { colKey: 'row-select', type: 'multiple', width: 46, align: 'center' },
  { colKey: 'roleName', title: '角色名称', align: 'center', width: 150, ellipsis: true },
  { colKey: 'roleKey', title: '权限字符', align: 'center', width: 200, ellipsis: true },
  { colKey: 'roleSort', title: '显示顺序', align: 'center', width: 100 },
  { colKey: 'status', title: '状态', align: 'center', width: 100, cell: 'status' },
  { colKey: 'createTime', title: '创建时间', align: 'center', width: 180, cell: 'createTime' },
  { colKey: 'operation', title: '操作', align: 'center', width: 140, fixed: 'right', cell: 'operation' }
];
mergeSavedWidths('system-role/list', listColumns);

/** 递归收集树所有节点 id（全选/全不选 + 展开/折叠用） */
const collectTreeKeys = (list: any[]): Array<string | number> => {
  const keys: Array<string | number> = [];
  const walk = (nodes: any[]) => {
    nodes.forEach((node) => {
      keys.push(node.id);
      if (node.children?.length) walk(node.children);
    });
  };
  walk(list);
  return keys;
};

/** t-tree 勾选变化：第一参数即勾选值数组（DOC/36 §:61） */
const onMenuTreeChange = (value: Array<string | number>) => {
  menuCheckedKeys.value = value;
};
const onDeptTreeChange = (value: Array<string | number>) => {
  deptCheckedKeys.value = value;
};
/** t-tree 展开/折叠节点事件：受控回写 expanded 数组，解决单节点箭头点击无法展开的缺陷 */
const onMenuTreeExpand = (value: Array<string | number>) => {
  menuExpandedKeys.value = value;
};
const onDeptTreeExpand = (value: Array<string | number>) => {
  deptExpandedKeys.value = value;
};

/** 取树的 checked + indeterminate 合并，替代 EP 的 getCheckedKeys/getHalfCheckedKeys */
const getCheckedKeys = (treeRef: any): Array<string | number> => {
  const models = treeRef?.getItems?.() ?? [];
  const checked = models.filter((m: any) => m.checked).map((m: any) => m.value ?? m.data?.id);
  const halfChecked = models.filter((m: any) => m.indeterminate).map((m: any) => m.value ?? m.data?.id);
  return [...halfChecked, ...checked];
};

/**
 * 查询角色列表
 */
const getList = () => {
  loading.value = true;
  listRole(proxy?.addDateRange(queryParams.value, dateRange.value)).then((res) => {
    roleList.value = res.rows;
    total.value = res.total;
    loading.value = false;
  });
};

/**
 * 搜索按钮操作
 */
const handleQuery = () => {
  queryParams.value.pageNum = 1;
  getList();
};

/** 重置：t-form 用 reset()；日期范围单独清空 */
const resetQuery = () => {
  dateRange.value = ['', ''];
  queryFormRef.value?.reset();
  queryParams.value.roleName = '';
  queryParams.value.roleKey = '';
  queryParams.value.status = '';
  handleQuery();
};
/**删除按钮操作 */
const handleDelete = async (row?: RoleVO) => {
  const roleids = row?.roleId || ids.value;
  await proxy?.$modal.confirm('是否确认删除角色编号为' + roleids + '数据项目');
  await delRole(roleids);
  getList();
  proxy?.$modal.msgSuccess('删除成功');
};

/** 导出按钮操作 */
const handleExport = () => {
  proxy?.download(
    'system/role/export',
    {
      ...queryParams.value
    },
    `role_${new Date().getTime()}.xlsx`
  );
};
/** 多选框选中数据：t-table 的 select-change 只回传选中的 rowKey（rowKey=roleId） */
const handleSelectionChange = (keys: Array<string | number>) => {
  ids.value = keys;
  single.value = keys.length != 1;
  multiple.value = !keys.length;
};

/** 角色状态修改 */
const handleStatusChange = async (row: RoleVO) => {
  const text = row.status === '0' ? '启用' : '停用';
  try {
    await proxy?.$modal.confirm('确认要"' + text + '""' + row.roleName + '"角色吗?');
    await changeRoleStatus(row.roleId, row.status);
    proxy?.$modal.msgSuccess(text + '成功');
  } catch {
    row.status = row.status === '0' ? '1' : '0';
  }
};

/** 分配用户 */
const handleAuthUser = (row: RoleVO) => {
  router.push('/system/role-auth/user/' + row.roleId);
};

/** 查询菜单树结构 */
const getMenuTreeselect = async () => {
  const res = await menuTreeselect();
  menuOptions.value = res.data;
};
/** 所有部门节点数据 */
const getDeptAllCheckedKeys = (): any => {
  return getCheckedKeys(deptRef.value);
};
/** 重置新增的表单以及其他数据：树勾选/展开改受控清空，t-form 用 reset() */
const reset = () => {
  menuCheckedKeys.value = [];
  deptCheckedKeys.value = [];
  menuExpandedKeys.value = [];
  deptExpandedKeys.value = [];
  menuExpand.value = false;
  menuNodeAll.value = false;
  deptExpand.value = true;
  deptNodeAll.value = false;
  form.value = { ...initForm };
  roleFormRef.value?.reset();
};

/** 添加角色 */
const handleAdd = () => {
  reset();
  getMenuTreeselect();
  dialog.visible = true;
  dialog.title = '添加角色';
};
/** 修改角色：受控 value 直接回填 checkedKeys，替代 EP 的 nextTick+setChecked 循环 */
const handleUpdate = async (row?: RoleVO) => {
  reset();
  const roleId = row?.roleId || ids.value[0];
  const { data } = await getRole(roleId);
  Object.assign(form.value, data);
  form.value.roleSort = Number(form.value.roleSort);
  const res = await getRoleMenuTreeselect(roleId);
  menuCheckedKeys.value = [...res.checkedKeys];
  dialog.title = '修改角色';
  dialog.visible = true;
};
/** 根据角色ID查询菜单树结构 */
const getRoleMenuTreeselect = (roleId: string | number) => {
  return roleMenuTreeselect(roleId).then((res): RoleMenuTree => {
    menuOptions.value = res.data.menus;
    return res.data;
  });
};
/** 根据角色ID查询部门树结构 */
const getRoleDeptTreeSelect = async (roleId: string | number) => {
  const res = await deptTreeSelect(roleId);
  deptOptions.value = res.data.depts;
  return res.data;
};
/** 树权限（展开/折叠）：受控 expanded 给全部节点 id 或空数组 */
const handleCheckedTreeExpand = (value: boolean, type: string) => {
  if (type == 'menu') {
    menuExpand.value = value;
    menuExpandedKeys.value = value ? collectTreeKeys(menuOptions.value) : [];
  } else if (type == 'dept') {
    deptExpand.value = value;
    deptExpandedKeys.value = value ? collectTreeKeys(deptOptions.value) : [];
  }
};
/** 树权限（全选/全不选）：受控 value 给全部节点 id 或空数组 */
const handleCheckedTreeNodeAll = (value: boolean, type: string) => {
  if (type == 'menu') {
    menuNodeAll.value = value;
    menuCheckedKeys.value = value ? collectTreeKeys(menuOptions.value) : [];
  } else if (type == 'dept') {
    deptNodeAll.value = value;
    deptCheckedKeys.value = value ? collectTreeKeys(deptOptions.value) : [];
  }
};
/** 树权限（父子联动）：切换 check-strictly，与 EP 语义一致 */
const handleCheckedTreeConnect = (value: boolean, type: string) => {
  if (type == 'menu') {
    form.value.menuCheckStrictly = value;
  } else if (type == 'dept') {
    form.value.deptCheckStrictly = value;
  }
};
/** 所有菜单节点数据 */
const getMenuAllCheckedKeys = (): any => {
  return getCheckedKeys(menuRef.value);
};
/** 提交按钮：t-form.validate() 失败 resolve(false)，判定 r===true||r===undefined（DOC/35 §5） */
const submitForm = async () => {
  const result = await roleFormRef.value?.validate().catch(() => false);
  const valid = result === true || result === undefined;
  if (!valid) return;
  form.value.menuIds = getMenuAllCheckedKeys();
  form.value.roleId ? await updateRole(form.value) : await addRole(form.value);
  proxy?.$modal.msgSuccess('操作成功');
  dialog.visible = false;
  getList();
};
/** 取消按钮 */
const cancel = () => {
  reset();
  dialog.visible = false;
};
/** 选择角色权限范围触发 */
const dataScopeSelectChange = (value: string) => {
  if (value !== '2') {
    deptCheckedKeys.value = [];
  }
};
/** 分配数据权限操作：受控 value 直接回填 checkedKeys，替代 EP 的 nextTick+setCheckedKeys */
const handleDataScope = async (row: RoleVO) => {
  const response = await getRole(row.roleId);
  Object.assign(form.value, response.data);
  const res = await getRoleDeptTreeSelect(row.roleId);
  deptCheckedKeys.value = [...res.checkedKeys];
  openDataScope.value = true;
  dialog.title = '分配数据权限';
};
/** 提交按钮（数据权限） */
const submitDataScope = async () => {
  if (form.value.roleId) {
    form.value.deptIds = getDeptAllCheckedKeys();
    await dataScope(form.value);
    proxy?.$modal.msgSuccess('修改成功');
    openDataScope.value = false;
    getList();
  }
};
/** 取消按钮（数据权限）*/
const cancelDataScope = () => {
  dataScopeRef.value?.reset();
  form.value = { ...initForm };
  openDataScope.value = false;
};

onMounted(() => {
  getList();
});
</script>

<style lang="scss" scoped>
.tree-border {
  width: 100%;
  max-height: 260px;
  overflow: auto;
  border: 1px solid var(--td-border-level-1-color);
  border-radius: 3px;
  padding: 8px;
}
</style>
