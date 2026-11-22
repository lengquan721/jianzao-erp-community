<template>
  <div class="p-2">
    <transition :enter-active-class="proxy?.animate.searchAnimate.enter" :leave-active-class="proxy?.animate.searchAnimate.leave">
      <div v-show="showSearch" class="mb-[10px]">
        <t-card :bordered="false" class="erp-filter-card">
          <t-form ref="queryFormRef" :data="queryParams" class="erp-filter-row" label-align="right" :label-width="'auto'">
            <t-form-item label="组件名称" name="componentName">
              <t-input v-model="queryParams.componentName" placeholder="请输入组件名称" clearable class="query-w-md" @enter="handleQuery" />
            </t-form-item>
            <t-form-item label="方法名" name="methodName">
              <t-input v-model="queryParams.methodName" placeholder="请输入方法名" clearable class="query-w-md" @enter="handleQuery" />
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
        <div class="spel-toolbar">
          <t-button v-if="checkPermi(['workflow:spel:add'])" theme="primary" variant="outline" @click="handleAdd">
            <template #icon><AddIcon /></template>
            新增
          </t-button>
          <t-button v-if="checkPermi(['workflow:spel:edit'])" theme="success" variant="outline" :disabled="single" @click="handleUpdate()">
            <template #icon><Edit1Icon /></template>
            修改
          </t-button>
          <t-button v-if="checkPermi(['workflow:spel:remove'])" theme="danger" variant="outline" :disabled="multiple" @click="handleDelete()">
            <template #icon><DeleteIcon /></template>
            删除
          </t-button>
          <right-toolbar v-model:showSearch="showSearch" @queryTable="getList"></right-toolbar>
        </div>
      </template>

      <t-table
        class="erp-table"
        row-key="id"
        :data="spelList"
        :columns="spelColumns"
        :loading="loading"
        :hover="true"
        :resizable="true"
        size="small"
        bordered
        cell-empty-content="-"
        :selected-row-keys="ids"
        @select-change="handleSelectionChange"
        @column-resize-change="(ctx: any) => saveColumnWidths('workflow-spel/list', ctx.columnsWidth)"
      >
        <template #rowIndex="{ rowIndex }">
          <span>{{ (queryParams.pageNum - 1) * queryParams.pageSize + rowIndex + 1 }}</span>
        </template>
        <template #componentName="{ row }">{{ row.componentName || '-' }}</template>
        <template #methodName="{ row }">{{ row.methodName || '-' }}</template>
        <template #methodParams="{ row }">{{ row.methodParams || '-' }}</template>
        <template #status="{ row }">
          <t-tag v-if="row.status === '0'" theme="primary" variant="light">正常</t-tag>
          <t-tag v-else theme="danger" variant="light">停用</t-tag>
        </template>
        <template #remark="{ row }">{{ row.remark || '-' }}</template>
        <template #operation="{ row }">
          <t-tooltip v-if="checkPermi(['workflow:spel:edit'])" content="修改" placement="top">
            <t-button theme="primary" variant="text" shape="square" size="small" @click="handleUpdate(row)">
              <template #icon><Edit1Icon /></template>
            </t-button>
          </t-tooltip>
          <t-tooltip v-if="checkPermi(['workflow:spel:remove'])" content="删除" placement="top">
            <t-button theme="danger" variant="text" shape="square" size="small" @click="handleDelete(row)">
              <template #icon><DeleteIcon /></template>
            </t-button>
          </t-tooltip>
        </template>
      </t-table>

      <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </t-card>
    <!-- 添加或修改流程spel表达式定义对话框 -->
    <t-dialog v-model:visible="dialog.visible" width="550px" attach="body">
      <template #header>{{ dialog.title }}</template>
      <t-form ref="spelFormRef" :data="form" :rules="rules" label-width="100px">
        <!-- 组件名称 -->
        <t-form-item name="componentName">
          <template #label>
            <span>
              <t-tooltip content="注册到Spring容器中的组件名，如：spelRuleComponent" placement="top">
                <HelpCircleIcon />
              </t-tooltip>
              组件名称
            </span>
          </template>
          <t-input v-model="form.componentName" placeholder="请输入组件名称" @change="updateViewSpel" />
        </t-form-item>
        <t-form-item name="methodName">
          <template #label>
            <span>
              <t-tooltip content="组件中的方法名称，如：selectDeptLeaderById" placement="top">
                <HelpCircleIcon />
              </t-tooltip>
              方法名称
            </span>
          </template>
          <t-input v-model="form.methodName" placeholder="请输入方法名称" @change="updateViewSpel" />
        </t-form-item>
        <t-form-item name="methodParams">
          <template #label>
            <span>
              <t-tooltip content="方法参数，如：deptId, 多个使用 ',' 分隔，单参数变量仅支持单个方法参数" placement="top">
                <HelpCircleIcon />
              </t-tooltip>
              方法参数
            </span>
          </template>
          <t-input v-model="form.methodParams" placeholder="请输入方法参数" @change="updateViewSpel" />
        </t-form-item>

        <!-- 改为只读文本展示 -->
        <t-form-item label="SPEL表达式">
          <span class="preview-box">
            {{ form.viewSpel || '例如：#{@组件名.方法名(#方法参数)} 或 ${方法参数}' }}
          </span>
        </t-form-item>
        <t-form-item label="状态" name="status">
          <t-radio-group v-model="form.status">
            <t-radio v-for="dict in sys_normal_disable" :key="dict.value" :value="dict.value">
              {{ dict.label }}
            </t-radio>
          </t-radio-group>
        </t-form-item>
        <t-form-item label="备注" name="remark">
          <t-textarea v-model="form.remark" :autosize="{ minRows: 3, maxRows: 3 }" placeholder="请输入备注" />
        </t-form-item>
      </t-form>
      <template #footer>
        <div class="dialog-footer">
          <t-button theme="default" variant="outline" @click="cancel">取 消</t-button>
          <t-button theme="primary" :loading="buttonLoading" @click="submitForm">确 定</t-button>
        </div>
      </template>
    </t-dialog>
  </div>
</template>

<script setup name="Spel" lang="ts">
import { listSpel, getSpel, delSpel, addSpel, updateSpel } from '@/api/workflow/spel';
import { SpelVO, SpelQuery, SpelForm } from '@/api/workflow/spel/types';
// 列宽记忆：拖拽后按表名存 localStorage，进页面还原（见 hooks/useTableColumnWidth）
import { mergeSavedWidths, saveColumnWidths } from '@/hooks/useTableColumnWidth';
// 权限判断改外层 v-if：原权限指令在 mounted 里 removeChild，与 t-tooltip 包裹结构打架（DOC/36 P1）
import { checkPermi } from '@/utils/permission';
// TDesign 图标必须显式 import，不会自动解析（DOC/35 §3）
import { AddIcon, DeleteIcon, Edit1Icon, HelpCircleIcon, RefreshIcon, SearchIcon } from 'tdesign-icons-vue-next';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const { sys_show_hide, sys_normal_disable } = toRefs<any>(proxy?.useDict('sys_show_hide', 'sys_normal_disable'));

const spelList = ref<SpelVO[]>([]);
const buttonLoading = ref(false);
const loading = ref(true);
const showSearch = ref(true);
const ids = ref<Array<string | number>>([]);
const single = ref(true);
const multiple = ref(true);
const total = ref(0);

const queryFormRef = ref<any>();
const spelFormRef = ref<any>();

const dialog = reactive<DialogOption>({
  visible: false,
  title: ''
});

const initFormData: SpelForm = {
  id: undefined,
  componentName: undefined,
  methodName: undefined,
  methodParams: undefined,
  viewSpel: undefined,
  status: '0',
  remark: undefined
};
const data = reactive<{ form: SpelForm; queryParams: SpelQuery; rules: Record<string, any> }>({
  form: { ...initFormData },
  queryParams: {
    pageNum: 1,
    pageSize: 10,
    componentName: undefined,
    methodName: undefined,
    methodParams: undefined,
    viewSpel: undefined,
    status: '0',
    params: {}
  },
  rules: {
    status: [{ required: true, message: '状态不能为空', trigger: 'change' }]
  }
});

const { queryParams, form, rules } = toRefs(data);

/** 流程spel表达式定义列表列配置：t-table 用 columns 数组，插槽名与 cell 字符串同名（DOC/35 §5） */
const spelColumns: any[] = mergeSavedWidths('workflow-spel/list', [
  { colKey: 'row-select', type: 'multiple', width: 55, align: 'center' },
  { colKey: 'rowIndex', title: '序号', width: 60, align: 'center', cell: 'rowIndex' },
  { colKey: 'componentName', title: '组件名称', align: 'center', cell: 'componentName' },
  { colKey: 'methodName', title: '方法名称', align: 'center', cell: 'methodName' },
  { colKey: 'methodParams', title: '参数名称', align: 'center', cell: 'methodParams' },
  { colKey: 'viewSpel', title: 'SPEL表达式', align: 'center' },
  { colKey: 'status', title: '状态', align: 'center', cell: 'status' },
  { colKey: 'remark', title: '备注', align: 'center', cell: 'remark' },
  { colKey: 'operation', title: '操作', align: 'center', width: 100, cell: 'operation' }
]);

/** 查询流程spel表达式定义列表 */
const getList = async () => {
  loading.value = true;
  const res = await listSpel(queryParams.value);
  spelList.value = res.rows;
  total.value = res.total;
  loading.value = false;
};

/** 取消按钮 */
const cancel = () => {
  reset();
  dialog.visible = false;
};

/** 表单重置：t-form 使用 reset() 方法 */
const reset = () => {
  form.value = { ...initFormData };
  spelFormRef.value?.reset();
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

/** 多选框选中数据：t-table 的 select-change 只回传选中的 rowKey（rowKey=id） */
const handleSelectionChange = (keys: Array<string | number>) => {
  ids.value = keys;
  single.value = keys.length != 1;
  multiple.value = !keys.length;
};

/** 新增按钮操作 */
const handleAdd = () => {
  reset();
  dialog.visible = true;
  dialog.title = '添加流程spel表达式定义';
};

/** 修改按钮操作 */
const handleUpdate = async (row?: SpelVO) => {
  reset();
  const _id = row?.id || ids.value[0];
  const res = await getSpel(_id);
  Object.assign(form.value, res.data);
  dialog.visible = true;
  dialog.title = '修改流程spel表达式定义';
};

/** 提交按钮：t-form.validate() 失败 resolve(false)，判定 r===true||r===undefined（DOC/35 §5） */
const submitForm = async () => {
  const result = await spelFormRef.value?.validate().catch(() => false);
  const valid = result === true || result === undefined;
  if (valid) {
    buttonLoading.value = true;
    if (form.value.id) {
      await updateSpel(form.value).finally(() => (buttonLoading.value = false));
    } else {
      await addSpel(form.value).finally(() => (buttonLoading.value = false));
    }
    proxy?.$modal.msgSuccess('操作成功');
    dialog.visible = false;
    await getList();
  }
};

/** 删除按钮操作 */
const handleDelete = async (row?: SpelVO) => {
  const _ids = row?.id || ids.value;
  await proxy?.$modal.confirm('是否确认删除流程spel表达式定义编号为"' + _ids + '"的数据项？').finally(() => (loading.value = false));
  await delSpel(_ids);
  proxy?.$modal.msgSuccess('删除成功');
  await getList();
};

/** 控制是否显示 viewSpel 输入框 */
const showViewSpelInput = ref(false);

/** 更新 spel 预览值并决定是否显示输入框 */
const updateViewSpel = () => {
  const comp = (form.value.componentName || '').trim();
  const method = (form.value.methodName || '').trim();
  const paramStr = (form.value.methodParams || '').trim();

  if (!comp && !method && !paramStr) {
    form.value.viewSpel = '';
    return;
  }

  // 替换变量值：只有参数存在，组件和方法都不存在
  if (!comp && !method && paramStr) {
    const paramList = paramStr
      .split(',')
      .map((p) => p.trim())
      .filter((p) => p.length > 0);

    if (paramList.length === 1) {
      form.value.viewSpel = `\${${paramList[0]}}`;
      return;
    }
  }

  // 如果缺少组件或方法，提示填写
  if (!comp || !method) {
    form.value.viewSpel = '请填写组件名称和方法名';
    return;
  }

  let paramList = [];

  if (paramStr) {
    // 分割并过滤掉空参数
    paramList = paramStr
      .split(',')
      .map((p) => p.trim())
      .filter((p) => p.length > 0);
  }

  const paramPart = paramList.length > 0 ? '(' + paramList.map((p) => `#${p}`).join(',') + ')' : '()';

  form.value.viewSpel = `#{@${comp}.${method}${paramPart}}`;
};

/** 监听所有字段变化 */
watch(() => [form.value.componentName, form.value.methodName, form.value.methodParams], updateViewSpel);

onMounted(() => {
  getList();
});
</script>
<style lang="scss" scoped>
.preview-box {
  width: 100%;
  padding: 10px 12px;
  background-color: var(--td-bg-color-container-hover);
  border-radius: 4px;
  color: var(--td-text-color-primary);
  font-family: monospace; /* 等宽字体更清晰 */
  white-space: nowrap; /* 禁止换行 */
  overflow-x: auto; /* 超出宽度时显示水平滚动条 */
  min-height: 36px; /* 与输入框高度对齐 */
  line-height: 1.5;
}
.spel-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
