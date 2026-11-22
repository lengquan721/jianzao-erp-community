<template>
  <div class="p-2">
    <transition :enter-active-class="proxy?.animate.searchAnimate.enter" :leave-active-class="proxy?.animate.searchAnimate.leave">
      <div v-show="showSearch" class="mb-[10px]">
        <t-card hover-shadow>
          <t-form ref="queryFormRef" :data="queryParams" layout="inline" @submit="handleQuery">
            <t-form-item label="公告标题" name="noticeTitle">
              <t-input v-model="queryParams.noticeTitle" placeholder="请输入公告标题" clearable class="query-w-md" @enter="handleQuery" />
            </t-form-item>
            <t-form-item label="操作人员" name="createByName">
              <t-input v-model="queryParams.createByName" placeholder="请输入操作人员" clearable class="query-w-md" @enter="handleQuery" />
            </t-form-item>
            <t-form-item label="类型" name="noticeType">
              <t-select v-model="queryParams.noticeType" placeholder="公告类型" clearable class="query-w-sm" :options="sys_notice_type" />
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
        <div class="flex items-center justify-between w-full">
          <div class="flex items-center gap-2">
            <t-button v-hasPermi="['system:notice:add']" theme="primary" variant="outline" @click="handleAdd">
              <template #icon><PlusIcon /></template>
              新增
            </t-button>
            <t-button v-hasPermi="['system:notice:edit']" theme="success" variant="outline" :disabled="single" @click="handleUpdate()">
              <template #icon><Edit1Icon /></template>
              修改
            </t-button>
            <t-button v-hasPermi="['system:notice:remove']" theme="danger" variant="outline" :disabled="multiple" @click="handleDelete()">
              <template #icon><DeleteIcon /></template>
              删除
            </t-button>
          </div>
          <right-toolbar v-model:show-search="showSearch" @query-table="getList"></right-toolbar>
        </div>
      </template>

      <t-table
        class="erp-table"
        row-key="noticeId"
        :data="noticeList"
        :columns="listColumns"
        :loading="loading"
        :hover="true"
        :resizable="true"
        size="small"
        cell-empty-content="-"
        :selected-row-keys="ids"
        @select-change="handleSelectionChange"
        @column-resize-change="(ctx: any) => saveColumnWidths('system-notice/list', ctx.columnsWidth)"
      >
        <template #noticeType="{ row }">
          <dict-tag :options="sys_notice_type" :value="row.noticeType" />
        </template>
        <template #status="{ row }">
          <dict-tag :options="sys_notice_status" :value="row.status" />
        </template>
        <template #createTime="{ row }">
          <span>{{ proxy.parseTime(row.createTime, '{y}-{m}-{d}') }}</span>
        </template>
        <template #operation="{ row }">
          <div class="flex items-center justify-center gap-1">
            <!-- 权限判断改挂外层：v-hasPermi 在 mounted 里删节点，会与 t-tooltip 的包裹结构打架 -->
            <t-tooltip v-if="checkPermi(['system:notice:edit'])" content="修改" placement="top">
              <t-button theme="primary" variant="text" shape="square" size="small" @click="handleUpdate(row)">
                <template #icon><Edit1Icon /></template>
              </t-button>
            </t-tooltip>
            <t-tooltip v-if="checkPermi(['system:notice:remove'])" content="删除" placement="top">
              <t-button theme="danger" variant="text" shape="square" size="small" @click="handleDelete(row)">
                <template #icon><DeleteIcon /></template>
              </t-button>
            </t-tooltip>
          </div>
        </template>
      </t-table>

      <pagination v-show="total > 0" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" :total="total" @pagination="getList" />
    </t-card>
    <!-- 添加或修改公告对话框 -->
    <t-dialog v-model:visible="dialog.visible" :header="dialog.title" width="780px" attach="body" :destroy-on-close="true">
      <t-form ref="noticeFormRef" :data="form" :rules="rules" label-align="top">
        <t-row :gutter="16">
          <t-col :span="6">
            <t-form-item label="公告标题" name="noticeTitle">
              <t-input v-model="form.noticeTitle" placeholder="请输入公告标题" />
            </t-form-item>
          </t-col>
          <t-col :span="6">
            <t-form-item label="公告类型" name="noticeType">
              <t-select v-model="form.noticeType" placeholder="请选择" :options="sys_notice_type" />
            </t-form-item>
          </t-col>
          <t-col :span="12">
            <t-form-item label="状态">
              <t-radio-group v-model="form.status">
                <t-radio v-for="dict in sys_notice_status" :key="dict.value" :value="dict.value">{{ dict.label }}</t-radio>
              </t-radio-group>
            </t-form-item>
          </t-col>
          <t-col :span="12">
            <t-form-item label="内容">
              <editor v-model="form.noticeContent" :min-height="192" />
            </t-form-item>
          </t-col>
        </t-row>
      </t-form>
      <template #footer>
        <t-button theme="primary" @click="submitForm">确 定</t-button>
        <t-button theme="default" variant="outline" @click="cancel">取 消</t-button>
      </template>
    </t-dialog>
  </div>
</template>

<script setup name="Notice" lang="ts">
import { listNotice, getNotice, delNotice, addNotice, updateNotice } from '@/api/system/notice';
import { NoticeForm, NoticeQuery, NoticeVO } from '@/api/system/notice/types';
import { checkPermi } from '@/utils/permission';
// 列宽记忆：拖拽后按表名存 localStorage，进页面还原（见 hooks/useTableColumnWidth）
import { mergeSavedWidths, saveColumnWidths } from '@/hooks/useTableColumnWidth';
import { DeleteIcon, Edit1Icon, PlusIcon, RefreshIcon, SearchIcon } from 'tdesign-icons-vue-next';
import { sanitizeNoticeHtml } from '@/components/Editor/sanitizeHtml';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const { sys_notice_status, sys_notice_type } = toRefs<any>(proxy?.useDict('sys_notice_status', 'sys_notice_type'));

const noticeList = ref<NoticeVO[]>([]);
const loading = ref(true);
const showSearch = ref(true);
const ids = ref<Array<string | number>>([]);
/** 工具栏「修改」禁用条件：勾选行数不是 1；「删除」禁用条件：一行未勾选（原由 selection-change 手写同步，改由 ids 派生） */
const single = computed(() => ids.value.length !== 1);
const multiple = computed(() => ids.value.length === 0);
const total = ref(0);

// 查询区与公告表单均已换 t-form（TDesign 的 ref 提供 reset()/validate()，与 EP 的 resetFields()/validate() 返回契约不同）
const queryFormRef = ref<any>();
const noticeFormRef = ref<any>();

const dialog = reactive<DialogOption>({
  visible: false,
  title: ''
});

const initFormData: NoticeForm = {
  noticeId: undefined,
  noticeTitle: '',
  noticeType: '',
  noticeContent: '',
  status: '0',
  remark: '',
  createByName: ''
};

/** t-form 的 rules 形状与 EP 的 ElFormRules 不兼容，用宽松类型声明 */
const rules: Record<string, any> = {
  noticeTitle: [{ required: true, message: '公告标题不能为空', trigger: 'blur' }],
  noticeType: [{ required: true, message: '公告类型不能为空', trigger: 'change' }]
};

const data = reactive<{ form: NoticeForm; queryParams: NoticeQuery }>({
  form: { ...initFormData },
  queryParams: {
    pageNum: 1,
    pageSize: 10,
    noticeTitle: '',
    createByName: '',
    status: '',
    noticeType: ''
  }
});

const { queryParams, form } = toRefs<{ form: NoticeForm; queryParams: NoticeQuery }>(data);

/** 主列表列配置：t-table 用 columns 数组，插槽名与 cell 字符串同名（DOC/35 §5） */
const listColumns: any[] = [
  { colKey: 'row-select', type: 'multiple', width: 46, align: 'center' },
  { colKey: 'noticeTitle', title: '公告标题', align: 'center', minWidth: 160, ellipsis: true },
  { colKey: 'noticeType', title: '公告类型', align: 'center', width: 100, cell: 'noticeType' },
  { colKey: 'status', title: '状态', align: 'center', width: 100, cell: 'status' },
  { colKey: 'createByName', title: '创建者', align: 'center', width: 100, ellipsis: true },
  { colKey: 'createTime', title: '创建时间', align: 'center', width: 160, cell: 'createTime' },
  { colKey: 'operation', title: '操作', align: 'center', width: 100, cell: 'operation' }
];
mergeSavedWidths('system-notice/list', listColumns);

/** 查询公告列表 */
const getList = async () => {
  loading.value = true;
  const res = await listNotice(queryParams.value);
  noticeList.value = res.rows;
  total.value = res.total;
  // 受控选中：换数据后清空勾选，保持 EP 的 el-table「重取即丢选中」行为
  ids.value = [];
  loading.value = false;
};
/** 取消按钮 */
const cancel = () => {
  reset();
  dialog.visible = false;
};
/** 表单重置：t-form 的 reset() 默认按 empty 清值，这里以 initFormData 为准，只清校验态 */
const reset = () => {
  form.value = { ...initFormData };
  noticeFormRef.value?.clearValidate();
};
/** 搜索按钮操作 */
const handleQuery = () => {
  queryParams.value.pageNum = 1;
  getList();
};
/** 重置按钮操作 */
const resetQuery = () => {
  queryFormRef.value?.reset();
  handleQuery();
};
/** 多选框选中数据：t-table 的 select-change 只回传选中的 rowKey（rowKey=noticeId） */
const handleSelectionChange = (keys: Array<string | number>) => {
  ids.value = keys;
};
/** 新增按钮操作 */
const handleAdd = () => {
  reset();
  dialog.visible = true;
  dialog.title = '添加公告';
};
/**修改按钮操作 */
const handleUpdate = async (row?: NoticeVO) => {
  reset();
  const noticeId = row?.noticeId || ids.value[0];
  const { data } = await getNotice(noticeId);
  Object.assign(form.value, data, { noticeContent: sanitizeNoticeHtml(data.noticeContent) });
  dialog.visible = true;
  dialog.title = '修改公告';
};
/** 提交按钮 */
const submitForm = async () => {
  // TDesign t-form.validate() 成功 resolve(true)，失败 resolve(false)（不 reject）；兼容两种返回
  const result = await noticeFormRef.value?.validate().catch(() => false);
  const valid = result === true || result === undefined;
  if (!valid) return;
  form.value.noticeId ? await updateNotice(form.value) : await addNotice(form.value);
  proxy?.$modal.msgSuccess('操作成功');
  dialog.visible = false;
  await getList();
};
/** 删除按钮操作 */
const handleDelete = async (row?: NoticeVO) => {
  const noticeIds = row?.noticeId || ids.value;
  await proxy?.$modal.confirm('是否确认删除公告编号为"' + noticeIds + '"的数据项？');
  await delNotice(noticeIds);
  await getList();
  proxy?.$modal.msgSuccess('删除成功');
};

onMounted(() => {
  getList();
});
</script>
