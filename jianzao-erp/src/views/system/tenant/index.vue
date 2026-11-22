<template>
  <div class="p-2">
    <transition :enter-active-class="proxy?.animate.searchAnimate.enter" :leave-active-class="proxy?.animate.searchAnimate.leave">
      <div v-show="showSearch" class="mb-[10px]">
        <t-card :bordered="false" class="erp-filter-card" title="筛选条件">
          <t-form ref="queryFormRef" :data="queryParams" class="erp-filter-row" label-align="right" :label-width="'auto'" @submit="handleQuery">
            <t-form-item label="租户编号" name="tenantId">
              <t-input v-model="queryParams.tenantId" placeholder="请输入租户编号" clearable class="query-w-md" @enter="handleQuery" />
            </t-form-item>
            <t-form-item label="联系人" name="contactUserName">
              <t-input v-model="queryParams.contactUserName" placeholder="请输入联系人" clearable class="query-w-md" @enter="handleQuery" />
            </t-form-item>
            <t-form-item label="联系电话" name="contactPhone">
              <t-input v-model="queryParams.contactPhone" placeholder="请输入联系电话" clearable class="query-w-md" @enter="handleQuery" />
            </t-form-item>
            <t-form-item label="企业名称" name="companyName">
              <t-input v-model="queryParams.companyName" placeholder="请输入企业名称" clearable class="query-w-md" @enter="handleQuery" />
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
        <div class="flex items-center gap-2 flex-wrap">
          <t-button v-hasPermi="['system:tenant:add']" theme="primary" variant="outline" @click="handleAdd">
            <template #icon><PlusIcon /></template>新增
          </t-button>
          <t-button v-hasPermi="['system:tenant:edit']" theme="success" variant="outline" :disabled="single" @click="handleUpdate()">
            <template #icon><Edit1Icon /></template>修改
          </t-button>
          <t-button v-hasPermi="['system:tenant:remove']" theme="danger" variant="outline" :disabled="multiple" @click="handleDelete()">
            <template #icon><DeleteIcon /></template>删除
          </t-button>
          <t-button v-hasPermi="['system:tenant:export']" theme="warning" variant="outline" @click="handleExport">
            <template #icon><DownloadIcon /></template>导出
          </t-button>
          <t-button v-if="userId === 1" theme="success" variant="outline" @click="handleSyncTenantDict">
            <template #icon><RefreshIcon /></template>同步租户字典
          </t-button>
          <t-button v-if="userId === 1" theme="success" variant="outline" @click="handleSyncTenantConfig">
            <template #icon><RefreshIcon /></template>同步租户参数配置
          </t-button>
        </div>
        <right-toolbar v-model:show-search="showSearch" @query-table="getList"></right-toolbar>
      </div>

      <t-table
        class="erp-table"
        row-key="id"
        :data="tenantList"
        :columns="listColumns"
        :loading="loading"
        :hover="true"
        :resizable="true"
        size="small"
        cell-empty-content="-"
        :selected-row-keys="ids"
        @select-change="handleSelectionChange"
        @column-resize-change="(ctx: any) => saveColumnWidths('system-tenant/list', ctx.columnsWidth)"
      >
        <template #expireTime="{ row }">
          <span>{{ proxy.parseTime(row.expireTime, '{y}-{m}-{d}') }}</span>
        </template>
        <template #status="{ row }">
          <!-- customValue 把 EP 的 active-value/inactive-value 语义映射到 TDesign；@change 时 v-model 已写入新值 -->
          <t-switch v-model="row.status" :custom-value="['0', '1']" @change="handleStatusChange(row)" />
        </template>
        <template #operation="{ row }">
          <div class="flex items-center justify-center gap-1">
            <!-- 权限判断改挂外层 t-tooltip：v-hasPermi 在 mounted 里删节点，会与 tooltip 的包裹结构打架 -->
            <t-tooltip v-if="checkPermi(['system:tenant:edit'])" content="修改" placement="top">
              <t-button theme="primary" variant="text" shape="square" size="small" @click="handleUpdate(row)">
                <template #icon><Edit1Icon /></template>
              </t-button>
            </t-tooltip>
            <t-tooltip v-if="checkPermi(['system:tenant:edit'])" content="同步套餐" placement="top">
              <t-button theme="primary" variant="text" shape="square" size="small" @click="handleSyncTenantPackage(row)">
                <template #icon><RefreshIcon /></template>
              </t-button>
            </t-tooltip>
            <t-tooltip v-if="checkPermi(['system:tenant:remove'])" content="删除" placement="top">
              <t-button theme="danger" variant="text" shape="square" size="small" @click="handleDelete(row)">
                <template #icon><DeleteIcon /></template>
              </t-button>
            </t-tooltip>
          </div>
        </template>
      </t-table>

      <pagination v-show="total > 0" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" :total="total" @pagination="getList" />
    </div>

    <!-- 添加或修改租户对话框 -->
    <t-dialog v-model:visible="dialog.visible" width="500px" attach="body">
      <template #header>{{ dialog.title }}</template>
      <t-form ref="tenantFormRef" :data="form" :rules="rules" label-align="top">
        <t-form-item label="企业名称" name="companyName">
          <t-input v-model="form.companyName" placeholder="请输入企业名称" />
        </t-form-item>
        <t-form-item label="联系人" name="contactUserName">
          <t-input v-model="form.contactUserName" placeholder="请输入联系人" />
        </t-form-item>
        <t-form-item label="管理员手机号" name="contactPhone">
          <t-input v-model="form.contactPhone" placeholder="请输入 11 位手机号，同时作为管理员登录账号" maxlength="11" />
        </t-form-item>
        <t-form-item v-if="!form.id" label="用户名" name="username">
          <t-input v-model="form.username" placeholder="请输入系统用户名" :maxlength="30" />
        </t-form-item>
        <t-form-item v-if="!form.id" label="用户密码" name="password">
          <t-input v-model="form.password" type="password" placeholder="请输入系统用户密码" :maxlength="20" />
        </t-form-item>
        <t-form-item label="租户套餐" name="packageId">
          <t-select
            v-model="form.packageId"
            :disabled="!!form.tenantId"
            placeholder="请选择租户套餐"
            clearable
            :options="packageOptions"
            style="width: 100%"
          />
        </t-form-item>
        <t-form-item label="过期时间" name="expireTime" help="日期面板确认后生效；点击清除按钮表示清空，取消选择保持原值">
          <t-date-picker
            :model-value="form.expireTime ?? ''"
            @change="handleExpireTimeChange"
            @clear="handleExpireTimeClear"
            clearable
            enable-time-picker
            value-type="YYYY-MM-DD HH:mm:ss"
            placeholder="请选择过期时间"
            style="width: 100%"
          />
        </t-form-item>
        <t-form-item label="用户数量" name="accountCount">
          <t-input v-model="form.accountCount" placeholder="请输入用户数量" />
        </t-form-item>
        <t-form-item label="绑定域名" name="domain">
          <t-input v-model="form.domain" placeholder="请输入绑定域名" />
        </t-form-item>
        <t-form-item label="企业地址" name="address">
          <t-input v-model="form.address" placeholder="请输入企业地址" />
        </t-form-item>
        <t-form-item label="企业代码" name="licenseNumber">
          <t-input v-model="form.licenseNumber" placeholder="请输入统一社会信用代码" />
        </t-form-item>
        <t-form-item label="企业简介" name="intro">
          <t-textarea v-model="form.intro" :autosize="{ minRows: 2, maxRows: 4 }" placeholder="请输入企业简介" />
        </t-form-item>
        <t-form-item label="备注" name="remark">
          <t-input v-model="form.remark" placeholder="请输入备注" />
        </t-form-item>
      </t-form>
      <template #footer>
        <t-button theme="default" variant="outline" @click="cancel">取 消</t-button>
        <t-button theme="primary" :loading="buttonLoading" @click="submitForm">确 定</t-button>
      </template>
    </t-dialog>
  </div>
</template>

<script setup name="Tenant" lang="ts">
import {
  listTenant,
  getTenant,
  delTenant,
  addTenant,
  updateTenant,
  changeTenantStatus,
  syncTenantPackage,
  syncTenantDict,
  syncTenantConfig
} from '@/api/system/tenant';
import { selectTenantPackage } from '@/api/system/tenantPackage';
import { useUserStore } from '@/store/modules/user';
import { TenantForm, TenantQuery, TenantVO } from '@/api/system/tenant/types';
import { TenantPkgVO } from '@/api/system/tenantPackage/types';
import { checkPermi } from '@/utils/permission';
import { mergeSavedWidths, saveColumnWidths } from '@/hooks/useTableColumnWidth';
import { DeleteIcon, DownloadIcon, Edit1Icon, PlusIcon, RefreshIcon, SearchIcon } from 'tdesign-icons-vue-next';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const userStore = useUserStore();
const userId = ref(userStore.userId);
const tenantList = ref<TenantVO[]>([]);
const packageList = ref<TenantPkgVO[]>([]);
const buttonLoading = ref(false);
const loading = ref(true);
const showSearch = ref(true);
const ids = ref<Array<string | number>>([]);
const single = ref(true);
const multiple = ref(true);
const total = ref(0);

const queryFormRef = ref<any>();
const tenantFormRef = ref<any>();

const dialog = reactive<DialogOption>({
  visible: false,
  title: ''
});

const initFormData: TenantForm = {
  id: undefined,
  tenantId: undefined,
  contactUserName: '',
  contactPhone: '',
  username: '',
  password: '',
  companyName: '',
  licenseNumber: '',
  domain: '',
  address: '',
  intro: '',
  remark: '',
  packageId: '',
  expireTime: '',
  accountCount: 0,
  status: '0'
};
/** 表单校验规则：t-form 的 rules 形状与 EP 的 ElFormRules 不兼容，单独用 Record 声明 */
const rules: Record<string, any> = {
  id: [{ required: true, message: 'id不能为空', trigger: 'blur' }],
  tenantId: [{ required: true, message: '租户编号不能为空', trigger: 'blur' }],
  contactUserName: [{ required: true, message: '联系人不能为空', trigger: 'blur' }],
  contactPhone: [{ required: true, message: '联系电话不能为空', trigger: 'blur' }],
  companyName: [{ required: true, message: '企业名称不能为空', trigger: 'blur' }],
  username: [
    { required: true, message: '用户名不能为空', trigger: 'blur' },
    { min: 2, max: 20, message: '用户名称长度必须介于 2 和 20 之间', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '密码不能为空', trigger: 'blur' },
    { min: 5, max: 20, message: '用户密码长度必须介于 5 和 20 之间', trigger: 'blur' }
  ]
};

const data = reactive<{ form: TenantForm; queryParams: TenantQuery }>({
  form: { ...initFormData },
  queryParams: {
    pageNum: 1,
    pageSize: 10,
    tenantId: '',
    contactUserName: '',
    contactPhone: '',
    companyName: ''
  }
});

const { queryParams, form } = toRefs<{ form: TenantForm; queryParams: TenantQuery }>(data);

/** 租户套餐下拉：t-select 用 {label,value} 数组替代 el-option */
const packageOptions = computed<Array<{ label: string; value: string | number }>>(() =>
  packageList.value.map((item) => ({ label: item.packageName, value: item.packageId }))
);

/** 主列表列配置：t-table 用 columns 数组，插槽名与 cell 字符串同名（DOC/35 §4） */
const listColumns: any[] = [
  { colKey: 'row-select', type: 'multiple', width: 46, align: 'center' },
  { colKey: 'tenantId', title: '租户编号', align: 'center', minWidth: 120, ellipsis: true },
  { colKey: 'contactUserName', title: '联系人', align: 'center', minWidth: 120, ellipsis: true },
  { colKey: 'contactPhone', title: '联系电话', align: 'center', minWidth: 140, ellipsis: true },
  { colKey: 'companyName', title: '企业名称', align: 'center', minWidth: 180, ellipsis: true },
  { colKey: 'licenseNumber', title: '社会信用代码', align: 'center', minWidth: 180, ellipsis: true },
  { colKey: 'expireTime', title: '过期时间', align: 'center', width: 180, cell: 'expireTime' },
  { colKey: 'status', title: '租户状态', align: 'center', width: 100, cell: 'status' },
  { colKey: 'operation', title: '操作', align: 'center', width: 110, fixed: 'right', cell: 'operation' }
];
mergeSavedWidths('system-tenant/list', listColumns);

/** 查询所有租户套餐 */
const getTenantPackage = async () => {
  const res = await selectTenantPackage();
  packageList.value = res.data;
};

/** 查询租户列表 */
const getList = async () => {
  loading.value = true;
  const res = await listTenant(queryParams.value);
  tenantList.value = res.rows;
  total.value = res.total;
  loading.value = false;
};

// 租户套餐状态修改
const handleStatusChange = async (row: TenantVO) => {
  const text = row.status === '0' ? '启用' : '停用';
  try {
    await proxy?.$modal.confirm('确认要"' + text + '""' + row.companyName + '"租户吗？');
    await changeTenantStatus(row.id, row.tenantId, row.status);
    proxy?.$modal.msgSuccess(text + '成功');
  } catch {
    row.status = row.status === '0' ? '1' : '0';
  }
};

// 取消按钮
const cancel = () => {
  reset();
  dialog.visible = false;
};

// 表单重置：t-form 用 reset()，不再用 resetFields()
const reset = () => {
  form.value = { ...initFormData };
  expireTimeIntent.value = 'unchanged';
  tenantFormRef.value?.clearValidate();
};

/** 搜索按钮操作 */
const handleQuery = () => {
  queryParams.value.pageNum = 1;
  getList();
};

/** 重置按钮操作 */
const resetQuery = () => {
  queryFormRef.value?.reset();
  queryParams.value.tenantId = '';
  queryParams.value.contactUserName = '';
  queryParams.value.contactPhone = '';
  queryParams.value.companyName = '';
  handleQuery();
};

// 多选框选中数据：t-table 的 select-change 只回传选中的 rowKey（rowKey=id）
const handleSelectionChange = (keys: Array<string | number>) => {
  ids.value = keys;
  single.value = keys.length != 1;
  multiple.value = !keys.length;
};

/** 新增按钮操作 */
const handleAdd = () => {
  reset();
  getTenantPackage();
  dialog.visible = true;
  dialog.title = '添加租户';
};

/** 修改按钮操作 */
const handleUpdate = async (row?: TenantVO) => {
  reset();
  await getTenantPackage();
  const _id = row?.id || ids.value[0];
  const res = await getTenant(_id);
  Object.assign(form.value, res.data);
  dialog.visible = true;
  dialog.title = '修改租户';
};

// 不使用上游 @pick 直接回写：带时间的面板 pick 是临时选择，取消不得清空/修改原值。
const expireTimeIntent = ref<'unchanged' | 'clear' | 'set'>('unchanged');
const handleExpireTimeChange = (value: string | number | Date | Array<string | number | Date>) => {
  if (typeof value !== 'string' || !value) return;
  form.value.expireTime = value;
  expireTimeIntent.value = 'set';
};
const handleExpireTimeClear = () => {
  form.value.expireTime = null;
  expireTimeIntent.value = 'clear';
};
const tenantPayload = (): TenantForm => {
  const payload = { ...form.value };
  if (expireTimeIntent.value === 'unchanged') delete payload.expireTime;
  else if (expireTimeIntent.value === 'clear') payload.expireTime = null;
  return payload;
};

/** 提交按钮：t-form.validate() 失败 resolve(false)，判定 r===true||r===undefined（DOC/35 §5） */
const submitForm = async () => {
  const result = await tenantFormRef.value?.validate().catch(() => false);
  const valid = result === true || result === undefined;
  if (!valid) return;
  buttonLoading.value = true;
  if (form.value.id) {
    await updateTenant(tenantPayload()).finally(() => (buttonLoading.value = false));
  } else {
    await addTenant(tenantPayload()).finally(() => (buttonLoading.value = false));
  }
  proxy?.$modal.msgSuccess('操作成功');
  dialog.visible = false;
  await getList();
};

/** 删除按钮操作 */
const handleDelete = async (row?: TenantVO) => {
  const _ids = row?.id || ids.value;
  await proxy?.$modal.confirm('是否确认删除租户编号为"' + _ids + '"的数据项？');
  loading.value = true;
  await delTenant(_ids).finally(() => (loading.value = false));
  await getList();
  proxy?.$modal.msgSuccess('删除成功');
};

/** 同步租户套餐按钮操作 */
const handleSyncTenantPackage = async (row: TenantVO) => {
  try {
    await proxy?.$modal.confirm('是否确认同步租户套餐租户编号为"' + row.tenantId + '"的数据项？');
    loading.value = true;
    await syncTenantPackage(row.tenantId, row.packageId);
    await getList();
    proxy?.$modal.msgSuccess('同步成功');
  } catch {
    return;
  } finally {
    loading.value = false;
  }
};

/** 导出按钮操作 */
const handleExport = () => {
  proxy?.download(
    'system/tenant/export',
    {
      ...queryParams.value
    },
    `tenant_${new Date().getTime()}.xlsx`
  );
};

/**同步租户字典*/
const handleSyncTenantDict = async () => {
  await proxy?.$modal.confirm('确认要同步所有租户字典吗？');
  const res = await syncTenantDict();
  proxy?.$modal.msgSuccess(res.msg);
};

/**同步租户参数配置*/
const handleSyncTenantConfig = async () => {
  await proxy?.$modal.confirm('确认要同步所有租户参数配置吗？');
  const res = await syncTenantConfig();
  proxy?.$modal.msgSuccess(res.msg);
};

onMounted(() => {
  getList();
});
</script>
