<template>
  <div class="p-2">
    <t-row :gutter="20">
      <!-- 部门树 -->
      <t-col :lg="2" :xs="12" style="">
        <t-card hover-shadow>
          <t-input v-model="deptName" placeholder="请输入部门名称" clearable>
            <template #prefix-icon><SearchIcon /></template>
          </t-input>
          <t-tree
            ref="deptTreeRef"
            class="mt-2"
            :data="deptOptions"
            :keys="{ value: 'id', label: 'label', children: 'children' }"
            :activable="true"
            :actived="deptActived"
            :expand-on-click-node="false"
            :filter="deptFilter"
            :hover="true"
            expand-all
            @click="handleNodeClick"
          />
        </t-card>
      </t-col>
      <t-col :lg="10" :xs="12">
        <transition :enter-active-class="proxy?.animate.searchAnimate.enter" :leave-active-class="proxy?.animate.searchAnimate.leave">
          <div v-show="showSearch" class="mb-[10px]">
            <t-card hover-shadow>
              <t-form ref="queryFormRef" :data="queryParams" layout="inline" reset-type="initial">
                <t-form-item label="用户名称" name="userName">
                  <t-input v-model="queryParams.userName" placeholder="请输入用户名称" clearable @enter="handleQuery" />
                </t-form-item>
                <t-form-item label="用户昵称" name="nickName">
                  <t-input v-model="queryParams.nickName" placeholder="请输入用户昵称" clearable @enter="handleQuery" />
                </t-form-item>
                <t-form-item label="手机号码" name="phonenumber">
                  <t-input v-model="queryParams.phonenumber" placeholder="请输入手机号码" clearable @enter="handleQuery" />
                </t-form-item>

                <t-form-item label="状态" name="status">
                  <t-select v-model="queryParams.status" placeholder="用户状态" clearable :options="sys_normal_disable" />
                </t-form-item>
                <t-form-item label="创建时间">
                  <!-- 值类型保持 YYYY-MM-DD HH:mm:ss 字符串，addDateRange 写入的 beginTime/endTime 请求字段格式不变 -->
                  <t-date-range-picker
                    v-model="dateRange"
                    value-type="YYYY-MM-DD HH:mm:ss"
                    :placeholder="['开始日期', '结束日期']"
                    style="width: 308px"
                  />
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
            <t-row :gutter="10">
              <t-col :span="12">
                <div class="flex items-center gap-2">
                  <t-button v-hasPermi="['system:user:add']" theme="primary" variant="outline" @click="handleAdd()">
                    <template #icon><PlusIcon /></template>
                    新增
                  </t-button>
                  <t-button v-hasPermi="['system:user:edit']" theme="success" variant="outline" :disabled="single" @click="handleUpdate()">
                    <template #icon><Edit1Icon /></template>
                    修改
                  </t-button>
                  <t-button v-hasPermi="['system:user:remove']" theme="danger" variant="outline" :disabled="multiple" @click="handleDelete()">
                    <template #icon><DeleteIcon /></template>
                    删除
                  </t-button>
                  <t-dropdown class="mt-[1px]">
                    <t-button theme="default" variant="outline">
                      更多
                      <template #suffix><ChevronDownIcon /></template>
                    </t-button>
                    <template #dropdown>
                      <t-dropdown-menu>
                        <t-dropdown-item @click="importTemplate">
                          <template #prefix-icon><DownloadIcon /></template>
                          下载模板
                        </t-dropdown-item>
                        <!-- 注意 由于下拉项标签是延迟加载的 所以 v-hasPermi 自定义标签不生效 需要使用 v-if 调用方法执行 -->
                        <t-dropdown-item v-if="checkPermi(['system:user:import'])" @click="handleImport">
                          <template #prefix-icon><UploadIcon /></template>
                          导入数据
                        </t-dropdown-item>
                        <t-dropdown-item v-if="checkPermi(['system:user:export'])" @click="handleExport">
                          <template #prefix-icon><DownloadIcon /></template>
                          导出数据
                        </t-dropdown-item>
                      </t-dropdown-menu>
                    </template>
                  </t-dropdown>
                  <right-toolbar v-model:show-search="showSearch" :columns="columns" :search="true" @query-table="getList"></right-toolbar>
                </div>
              </t-col>
            </t-row>
          </template>

          <t-table
            class="erp-table"
            row-key="userId"
            bordered
            :data="userList"
            :columns="userColumns"
            :loading="loading"
            :hover="true"
            :resizable="true"
            size="small"
            cell-empty-content="-"
            :selected-row-keys="ids"
            @select-change="handleSelectionChange"
            @column-resize-change="(ctx: any) => saveColumnWidths('system-user/list', ctx.columnsWidth)"
          >
            <template #status="{ row }">
              <t-switch v-model="row.status" :custom-value="['0', '1']" @change="handleStatusChange(row)"></t-switch>
            </template>
            <template #createTime="{ row }">
              <span>{{ row.createTime }}</span>
            </template>
            <template #operation="{ row }">
              <div class="flex items-center justify-center gap-1">
                <!-- 权限判断挂外层 t-tooltip：v-hasPermi 会 removeChild，与 tooltip 的包裹结构打架（DOC/36 P6） -->
                <t-tooltip v-if="row.userId !== 1 && checkPermi(['system:user:edit'])" content="修改" placement="top">
                  <t-button theme="primary" variant="text" shape="square" size="small" @click="handleUpdate(row)">
                    <template #icon><Edit1Icon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.userId !== 1 && checkPermi(['system:user:remove'])" content="删除" placement="top">
                  <t-button theme="primary" variant="text" shape="square" size="small" @click="handleDelete(row)">
                    <template #icon><DeleteIcon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.userId !== 1 && checkPermi(['system:user:resetPwd'])" content="重置密码" placement="top">
                  <t-button theme="primary" variant="text" shape="square" size="small" @click="handleResetPwd(row)">
                    <template #icon><KeyIcon /></template>
                  </t-button>
                </t-tooltip>
                <t-tooltip v-if="row.userId !== 1 && checkPermi(['system:user:edit'])" content="分配角色" placement="top">
                  <t-button theme="primary" variant="text" shape="square" size="small" @click="handleAuthRole(row)">
                    <template #icon><CheckCircleIcon /></template>
                  </t-button>
                </t-tooltip>
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

    <!-- 添加或修改用户配置对话框 -->
    <t-dialog ref="formDialogRef" v-model:visible="dialog.visible" :header="dialog.title" width="600px" attach="body" @close="closeDialog">
      <t-form ref="userFormRef" :data="form" :rules="rules" label-align="right" label-width="80px">
        <t-row :gutter="16">
          <t-col :span="6">
            <t-form-item label="用户昵称" name="nickName">
              <t-input v-model="form.nickName" placeholder="请输入用户昵称" maxlength="30" />
            </t-form-item>
          </t-col>
          <t-col :span="6" v-if="form.userId == null || form.userId != useUserStore().userId">
            <t-form-item label="归属部门" name="deptId">
              <t-tree-select
                v-model="form.deptId"
                :data="enabledDeptOptions"
                :keys="{ value: 'id', label: 'label', children: 'children' }"
                :tree-props="{ checkStrictly: true }"
                placeholder="请选择归属部门"
                @change="handleDeptChange"
              />
            </t-form-item>
          </t-col>
        </t-row>
        <t-row :gutter="16">
          <t-col :span="6">
            <t-form-item label="手机号码" name="phonenumber">
              <!-- 手机号即全局账户的登录账号：新增按手机号关联/创建 account，编辑只读（DOC/44 §4.5） -->
              <t-input
                v-model="form.phonenumber"
                placeholder="请输入手机号码（即登录账号）"
                maxlength="11"
                :readonly="form.userId != undefined"
                :tips="
                  form.userId != undefined
                    ? '手机号是账户级登录账号，编辑时不可修改；如需变更请联系平台管理员'
                    : '手机号即登录账号：已注册的手机号会自动关联已有账户，此处密码不会覆盖原密码'
                "
              />
            </t-form-item>
          </t-col>
          <t-col :span="6">
            <t-form-item label="邮箱" name="email">
              <t-input v-model="form.email" placeholder="请输入邮箱" maxlength="50" />
            </t-form-item>
          </t-col>
        </t-row>
        <t-row :gutter="16">
          <t-col :span="6">
            <t-form-item v-if="form.userId == undefined" label="用户名称" name="userName">
              <t-input v-model="form.userName" placeholder="请输入用户名称" maxlength="30" />
            </t-form-item>
          </t-col>
          <t-col :span="6">
            <t-form-item v-if="form.userId == undefined" label="用户密码" name="password">
              <t-input v-model="form.password" placeholder="请输入用户密码" type="password" maxlength="20" />
            </t-form-item>
          </t-col>
        </t-row>
        <t-row :gutter="16">
          <t-col :span="6">
            <t-form-item label="用户性别" name="sex">
              <t-select v-model="form.sex" placeholder="请选择" :options="sys_user_sex" />
            </t-form-item>
          </t-col>
          <t-col :span="6">
            <t-form-item label="状态" name="status">
              <t-radio-group v-model="form.status">
                <t-radio v-for="dict in sys_normal_disable" :key="dict.value" :value="dict.value">{{ dict.label }}</t-radio>
              </t-radio-group>
            </t-form-item>
          </t-col>
        </t-row>
        <t-row :gutter="16">
          <t-col :span="6" v-if="form.userId == null || form.userId != useUserStore().userId">
            <t-form-item label="岗位" name="postIds">
              <t-select v-model="form.postIds" multiple placeholder="请选择" :options="postSelectOptions" />
            </t-form-item>
          </t-col>
          <t-col :span="6" v-if="form.userId == null || form.userId != useUserStore().userId">
            <t-form-item label="角色" name="roleIds">
              <t-select v-model="form.roleIds" filterable multiple placeholder="请选择" :options="roleSelectOptions" />
            </t-form-item>
          </t-col>
        </t-row>
        <t-row :gutter="16">
          <t-col :span="12">
            <t-form-item label="备注" name="remark">
              <t-textarea v-model="form.remark" placeholder="请输入内容" :autosize="{ minRows: 2, maxRows: 4 }" />
            </t-form-item>
          </t-col>
        </t-row>
      </t-form>
      <template #footer>
        <div class="dialog-footer">
          <t-button theme="primary" @click="submitForm">确 定</t-button>
          <t-button theme="default" variant="outline" @click="cancel()">取 消</t-button>
        </div>
      </template>
    </t-dialog>

    <!-- 用户导入对话框 -->
    <t-dialog v-model:visible="upload.open" :header="upload.title" width="400px" attach="body">
      <t-upload
        ref="uploadRef"
        v-model="uploadFileList"
        theme="file"
        draggable
        :max="1"
        accept=".xlsx, .xls"
        :headers="upload.headers"
        :action="upload.url + '?updateSupport=' + upload.updateSupport"
        :disabled="upload.isUploading"
        :auto-upload="false"
        @progress="handleFileUploadProgress"
        @success="handleFileSuccess"
      >
        <div class="flex flex-col items-center gap-2 py-2">
          <CloudUploadIcon size="32px" />
          <div>将文件拖到此处，或<em>点击上传</em></div>
        </div>
      </t-upload>
      <div class="mt-2 flex flex-col items-center gap-1 text-center">
        <t-checkbox :checked="upload.updateSupport === 1" @change="onUpdateSupportChange">是否更新已经存在的用户数据</t-checkbox>
        <span>仅允许导入xls、xlsx格式文件。</span>
        <t-link theme="primary" :underline="false" style="font-size: 12px; vertical-align: baseline" @click="importTemplate">下载模板</t-link>
      </div>
      <template #footer>
        <div class="dialog-footer">
          <t-button theme="primary" @click="submitFileForm">确 定</t-button>
          <t-button theme="default" variant="outline" @click="upload.open = false">取 消</t-button>
        </div>
      </template>
    </t-dialog>
  </div>
</template>

<script setup name="User" lang="ts">
import api from '@/api/system/user';
import { UserForm, UserQuery, UserVO } from '@/api/system/user/types';
import { DeptTreeVO, DeptVO } from '@/api/system/dept/types';
import { RoleVO } from '@/api/system/role/types';
import { PostVO } from '@/api/system/post/types';
import { globalHeaders } from '@/utils/request';
import { to } from 'await-to-js';
import { optionselect } from '@/api/system/post';
import { checkPermi } from '@/utils/permission';
import { sanitizeImportMessage } from '@/utils/sanitizeImportMessage';
import { useUserStore } from '@/store/modules/user';
import { mergeSavedWidths, saveColumnWidths } from '@/hooks/useTableColumnWidth';
import { DialogPlugin } from 'tdesign-vue-next';
import type { DialogInstance, TNode, TreeNodeModel } from 'tdesign-vue-next';
import {
  CheckCircleIcon,
  ChevronDownIcon,
  CloudUploadIcon,
  DeleteIcon,
  DownloadIcon,
  Edit1Icon,
  KeyIcon,
  PlusIcon,
  RefreshIcon,
  SearchIcon,
  UploadIcon
} from 'tdesign-icons-vue-next';

const router = useRouter();
const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const { sys_normal_disable, sys_user_sex } = toRefs<any>(proxy?.useDict('sys_normal_disable', 'sys_user_sex'));
const userList = ref<UserVO[]>();
const loading = ref(true);
const showSearch = ref(true);
const ids = ref<Array<number | string>>([]);
const single = ref(true);
const multiple = ref(true);
const total = ref(0);
const dateRange = ref<[DateModelType, DateModelType]>(['', '']);
const deptName = ref('');
const deptOptions = ref<DeptTreeVO[]>([]);
const enabledDeptOptions = ref<DeptTreeVO[]>([]);
const initPassword = ref<string>('');
const postOptions = ref<PostVO[]>([]);
const roleOptions = ref<RoleVO[]>([]);
/*** 用户导入参数 */
const upload = reactive<ImportOption>({
  // 是否显示弹出层（用户导入）
  open: false,
  // 弹出层标题（用户导入）
  title: '',
  // 是否禁用上传
  isUploading: false,
  // 是否更新已经存在的用户数据
  updateSupport: 0,
  // 设置上传的请求头部
  headers: globalHeaders(),
  // 上传的地址
  url: import.meta.env.VITE_APP_BASE_API + '/system/user/importData'
});
// 列显隐信息
const columns = ref<FieldOption[]>([
  { key: 0, label: `用户编号`, visible: false, children: [] },
  { key: 1, label: `用户名称`, visible: true, children: [] },
  { key: 2, label: `用户昵称`, visible: true, children: [] },
  { key: 3, label: `部门`, visible: true, children: [] },
  { key: 4, label: `手机号码`, visible: true, children: [] },
  { key: 5, label: `状态`, visible: true, children: [] },
  { key: 6, label: `创建时间`, visible: true, children: [] }
]);

const deptTreeRef = ref<any>();
const queryFormRef = ref<any>();
const userFormRef = ref<any>();
const uploadRef = ref<any>();
const formDialogRef = ref<any>();
// t-tree 的激活节点为数组，用单元素数组维持原部门树的单选高亮语义
const deptActived = ref<Array<string | number>>([]);
// t-upload 受控文件列表，替代 EP 实例的 clearFiles()/handleRemove()
const uploadFileList = ref<any[]>([]);

const dialog = reactive<DialogOption>({
  visible: false,
  title: ''
});

const initFormData: UserForm = {
  userId: undefined,
  deptId: undefined,
  userName: '',
  nickName: undefined,
  password: '',
  phonenumber: undefined,
  email: undefined,
  sex: undefined,
  status: '0',
  remark: '',
  postIds: [],
  roleIds: []
};

const initData: PageData<UserForm, UserQuery> = {
  form: { ...initFormData },
  queryParams: {
    pageNum: 1,
    pageSize: 10,
    userName: '',
    phonenumber: '',
    status: '',
    deptId: '',
    roleId: ''
  },
  rules: {}
};
const data = reactive<PageData<UserForm, UserQuery>>(initData);

const { queryParams, form } = toRefs<PageData<UserForm, UserQuery>>(data);

/**
 * 手机号校验：新增必填（手机号即登录账号）；编辑态手机号只读，存量账号可能本就为空，
 * 不做必填拦截，只校验非空时的格式
 * @param val 表单里的手机号
 * @returns TDesign 校验结果，true 表示通过
 */
const validatePhonenumber = (val: any) => {
  const value = String(val ?? '').trim();
  if (!value) {
    return form.value.userId == undefined ? { result: false, message: '手机号码不能为空（登录账号）' } : true;
  }
  if (!/^1[3456789][0-9]\d{8}$/.test(value)) {
    return { result: false, message: '请输入正确的手机号码' };
  }
  return true;
};

/** 表单校验规则：t-form 的 rules 形状与 EP 的 ElFormRules 不兼容，单独用 Record<string, any> 声明；type:'email'→email:true，trigger 数组→'blur' */
const rules: Record<string, any> = {
  userName: [
    { required: true, message: '用户名称不能为空', trigger: 'blur' },
    {
      min: 2,
      max: 20,
      message: '用户名称长度必须介于 2 和 20 之间',
      trigger: 'blur'
    }
  ],
  nickName: [{ required: true, message: '用户昵称不能为空', trigger: 'blur' }],
  password: [
    { required: true, message: '用户密码不能为空', trigger: 'blur' },
    {
      min: 5,
      max: 20,
      message: '用户密码长度必须介于 5 和 20 之间',
      trigger: 'blur'
    },
    { pattern: /^[^<>"'|\\]+$/, message: '不能包含非法字符：< > " \' \\ |', trigger: 'blur' }
  ],
  email: [{ email: true, message: '请输入正确的邮箱地址', trigger: 'all' }],
  phonenumber: [{ validator: validatePhonenumber, trigger: 'blur' }],
  roleIds: [{ required: true, message: '用户角色不能为空', trigger: 'blur' }]
};

/** 部门树过滤条件：t-tree 的 filter 是响应式 prop，函数引用变化才会触发节点可见性重算；按 label 子串匹配 */
const deptFilter = computed<((node: TreeNodeModel) => boolean) | undefined>(() => {
  const keyword = deptName.value;
  if (!keyword) return undefined;
  return (node: TreeNodeModel) => String(node.label ?? '').indexOf(keyword) !== -1;
});

/** 岗位下拉：t-select 用 { label, value } 数组，禁用项由原 :disabled 映射 */
const postSelectOptions = computed<any[]>(() =>
  postOptions.value.map((item: PostVO) => ({ label: item.postName, value: item.postId, disabled: item.status == '1' }))
);
/** 角色下拉：t-select 用 { label, value } 数组，禁用项由原 :disabled 映射 */
const roleSelectOptions = computed<any[]>(() =>
  roleOptions.value.map((item: RoleVO) => ({ label: item.roleName, value: item.roleId, disabled: item.status == '1' }))
);

/** 主列表列配置：t-table 用 columns 数组，show-overflow-tooltip→ellipsis，显隐由 RightToolbar 的 columns.visible 受控推导 */
const userColumns = computed<any[]>(() => {
  const cols: any[] = [{ colKey: 'row-select', type: 'multiple', width: 50, align: 'center' }];
  if (columns.value[0].visible) cols.push({ colKey: 'userId', title: '用户编号', align: 'center' });
  if (columns.value[1].visible) cols.push({ colKey: 'userName', title: '用户名称', align: 'center', ellipsis: true });
  if (columns.value[2].visible) cols.push({ colKey: 'nickName', title: '用户昵称', align: 'center', ellipsis: true });
  if (columns.value[3].visible) cols.push({ colKey: 'deptName', title: '部门', align: 'center', ellipsis: true });
  if (columns.value[4].visible) cols.push({ colKey: 'phonenumber', title: '手机号码', align: 'center', width: 120 });
  if (columns.value[5].visible) cols.push({ colKey: 'status', title: '状态', align: 'center', cell: 'status' });
  if (columns.value[6].visible) cols.push({ colKey: 'createTime', title: '创建时间', align: 'center', width: 160, cell: 'createTime' });
  cols.push({ colKey: 'operation', title: '操作', align: 'center', width: 180, fixed: 'right', cell: 'operation' });
  return mergeSavedWidths('system-user/list', cols);
});

/** 查询用户列表 */
const getList = async () => {
  loading.value = true;
  const res = await api.listUser(proxy?.addDateRange(queryParams.value, dateRange.value));
  loading.value = false;
  userList.value = res.rows;
  total.value = res.total;
};

/** 查询部门下拉树结构 */
const getDeptTree = async () => {
  const res = await api.deptTreeSelect();
  deptOptions.value = res.data;
  enabledDeptOptions.value = filterDisabledDept(res.data);
};

/** 过滤禁用的部门 */
const filterDisabledDept = (deptList: DeptTreeVO[]) => {
  return deptList.filter((dept) => {
    if (dept.disabled) {
      return false;
    }
    if (dept.children && dept.children.length) {
      dept.children = filterDisabledDept(dept.children);
    }
    return true;
  });
};

/** 节点单击事件：t-tree 的 click 回调是上下文对象，取 context.node.data */
const handleNodeClick = (context: { node: TreeNodeModel }) => {
  queryParams.value.deptId = (context.node.data as DeptVO).id;
  handleQuery();
};

/** 搜索按钮操作 */
const handleQuery = () => {
  queryParams.value.pageNum = 1;
  getList();
};
/** 重置按钮操作 */
const resetQuery = () => {
  dateRange.value = ['', ''];
  queryFormRef.value?.reset();
  queryParams.value.pageNum = 1;
  queryParams.value.deptId = undefined;
  deptActived.value = [];
  handleQuery();
};

/** 删除按钮操作 */
const handleDelete = async (row?: UserVO) => {
  const userIds = row?.userId || ids.value;
  const [err] = await to(proxy?.$modal.confirm('是否确认删除用户编号为"' + userIds + '"的数据项？') as any);
  if (!err) {
    await api.delUser(userIds);
    await getList();
    proxy?.$modal.msgSuccess('删除成功');
  }
};

/** 用户状态修改  */
const handleStatusChange = async (row: UserVO) => {
  const text = row.status === '0' ? '启用' : '停用';
  try {
    await proxy?.$modal.confirm('确认要"' + text + '""' + row.userName + '"用户吗?');
    await api.changeUserStatus(row.userId, row.status);
    proxy?.$modal.msgSuccess(text + '成功');
  } catch (err) {
    row.status = row.status === '0' ? '1' : '0';
  }
};
/** 跳转角色分配 */
const handleAuthRole = (row: UserVO) => {
  const userId = row.userId;
  router.push('/system/user-auth/role/' + userId);
};

/** 重置密码按钮操作：密码归 account，多企业账号只能由本人修改（DOC/44 §4.4） */
const handleResetPwd = async (row: UserVO) => {
  const tip = '请输入"' + row.userName + '"的新密码（多企业账号重置会被拒绝，需由账号本人到个人中心修改）';
  const [err, res] = await to(proxy?.$modal.prompt(tip) as Promise<{ value: string }>);
  if (!err && res) {
    await api.resetUserPwd(row.userId, res.value);
    proxy?.$modal.msgSuccess('修改成功，新密码是：' + res.value);
  }
};

/** 选择条数：t-table 的 select-change 只回传选中的 rowKey（rowKey=userId） */
const handleSelectionChange = (selection: Array<string | number>) => {
  ids.value = selection;
  single.value = selection.length != 1;
  multiple.value = !selection.length;
};

/** 导入按钮操作 */
const handleImport = () => {
  upload.title = '用户导入';
  upload.open = true;
};
/** 导出按钮操作 */
const handleExport = () => {
  proxy?.download(
    'system/user/export',
    {
      ...queryParams.value
    },
    `user_${new Date().getTime()}.xlsx`
  );
};
/** 下载模板操作 */
const importTemplate = () => {
  proxy?.download('system/user/importTemplate', {}, `user_template_${new Date().getTime()}.xlsx`);
};

/**文件上传中处理 */
const handleFileUploadProgress = () => {
  upload.isUploading = true;
};
/** 文件上传成功处理：导入消息仅保留文本与 <br>，并保持结果列表可滚动。 */
const handleFileSuccess = (ctx: any) => {
  const response = ctx?.response;
  upload.open = false;
  upload.isUploading = false;
  uploadFileList.value = [];
  // 账号和异常信息可能来自不可信输入，写入 HTML 前必须净化；保留原可滚动 div 结构。
  const body: TNode = (h) =>
    h('div', {
      style: 'overflow: auto;overflow-x: hidden;max-height: 70vh;padding: 10px 20px 0;',
      innerHTML: sanitizeImportMessage(response?.msg)
    });
  let dismissed = false;
  // 确定会连带触发 onConfirm 与 onClose，用标记保证只销毁一次
  const dismiss = () => {
    if (dismissed) return;
    dismissed = true;
    ins.hide();
    ins.destroy();
  };
  const ins: DialogInstance = DialogPlugin.alert({
    attach: 'body',
    header: '导入结果',
    body,
    confirmBtn: '确定',
    onConfirm: dismiss,
    onClose: dismiss
  });
  getList();
};

/** 「是否更新已存在的数据」：t-checkbox 只给 boolean，写回导入参数时映射成 1/0 */
const onUpdateSupportChange = (checked: boolean) => {
  upload.updateSupport = checked ? 1 : 0;
};

/** 提交上传文件：t-upload 关闭自动上传后由按钮触发 */
function submitFileForm() {
  uploadRef.value?.uploadFiles();
}

/** 重置操作表单 */
const reset = () => {
  form.value = { ...initFormData };
  userFormRef.value?.clearValidate();
};
/** 取消按钮 */
const cancel = () => {
  dialog.visible = false;
  reset();
};

/** 新增按钮操作 */
const handleAdd = async () => {
  reset();
  const { data } = await api.getUser();
  dialog.visible = true;
  dialog.title = '新增用户';
  postOptions.value = data.posts;
  roleOptions.value = data.roles;
  form.value.password = initPassword.value.toString();
  // 员工默认落到套餐生成的 staff 角色：菜单来自套餐 employee_menu_ids，数据范围仅本人
  const staffRole = data.roles.find((role) => role.roleKey === 'staff');
  if (staffRole) {
    form.value.roleIds = [staffRole.roleId] as any;
  }
};

/** 修改按钮操作 */
const handleUpdate = async (row?: UserForm) => {
  reset();
  const userId = row?.userId || ids.value[0];
  const { data } = await api.getUser(userId);
  dialog.visible = true;
  dialog.title = '修改用户';
  Object.assign(form.value, data.user);
  postOptions.value = data.posts;
  roleOptions.value = Array.from(new Map([...data.roles, ...data.user.roles].map((role) => [role.roleId, role])).values());
  form.value.postIds = data.postIds;
  form.value.roleIds = data.roleIds;
  form.value.password = '';
};

/** 提交按钮：t-form.validate() 成功 resolve(true)，失败 resolve(false)/错误对象，判定 r===true||r===undefined */
const submitForm = async () => {
  const result = await userFormRef.value?.validate().catch(() => false);
  const valid = result === true || result === undefined;
  if (valid) {
    if (form.value.userId) {
      // 自己编辑自己的情况下 不允许编辑角色部门岗位
      if (form.value.userId == useUserStore().userId) {
        form.value.roleIds = null;
        form.value.deptId = null;
        form.value.postIds = null;
      }
      await api.updateUser(form.value);
    } else {
      await api.addUser(form.value);
    }
    proxy?.$modal.msgSuccess('操作成功');
    dialog.visible = false;
    await getList();
  }
};

/**
 * 关闭用户弹窗
 */
const closeDialog = () => {
  dialog.visible = false;
  resetForm();
};

/**
 * 重置表单
 */
const resetForm = () => {
  userFormRef.value?.clearValidate();

  form.value.id = undefined;
  form.value.status = '1';
};
onMounted(() => {
  getDeptTree(); // 初始化部门数据
  getList(); // 初始化列表数据
  proxy?.getConfigKey('sys.user.initPassword').then((response) => {
    initPassword.value = response.data;
  });
});

async function handleDeptChange(value: number | string) {
  const response = await optionselect(value);
  postOptions.value = response.data;
  form.value.postIds = [];
}
</script>
