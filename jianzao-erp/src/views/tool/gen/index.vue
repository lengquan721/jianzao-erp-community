<template>
  <div class="p-2">
    <transition :enter-active-class="proxy?.animate.searchAnimate.enter" :leave-active-class="proxy?.animate.searchAnimate.leave">
      <div v-show="showSearch" class="mb-[10px]">
        <t-card :bordered="false">
          <t-form ref="queryFormRef" :data="queryParams" layout="inline" label-width="auto">
            <t-form-item label="数据源" name="dataName">
              <t-select v-model="queryParams.dataName" filterable clearable placeholder="请选择/输入数据源名称" :options="dataNameOptions" />
            </t-form-item>
            <t-form-item label="表名称" name="tableName">
              <t-input v-model="queryParams.tableName" placeholder="请输入表名称" clearable @enter="handleQuery" />
            </t-form-item>
            <t-form-item label="表描述" name="tableComment">
              <t-input v-model="queryParams.tableComment" placeholder="请输入表描述" clearable @enter="handleQuery" />
            </t-form-item>
            <t-form-item label="创建时间">
              <t-date-range-picker
                v-model="dateRange"
                mode="date"
                value-type="YYYY-MM-DD"
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

    <t-card :bordered="false">
      <template #header>
        <t-row :gutter="10" class="mb8">
          <t-col :span="12">
            <div class="flex items-center gap-2">
              <t-button v-if="checkPermi(['tool:gen:code'])" theme="primary" variant="outline" @click="handleGenTable()">
                <template #icon><DownloadIcon /></template>
                生成
              </t-button>
              <t-button v-if="checkPermi(['tool:gen:import'])" theme="default" variant="outline" @click="openImportTable">
                <template #icon><UploadIcon /></template>
                导入
              </t-button>
              <t-button v-if="checkPermi(['tool:gen:edit'])" theme="success" variant="outline" :disabled="single" @click="handleEditTable()">
                <template #icon><Edit1Icon /></template>
                修改
              </t-button>
              <t-button v-if="checkPermi(['tool:gen:remove'])" theme="danger" variant="outline" :disabled="multiple" @click="handleDelete()">
                <template #icon><DeleteIcon /></template>
                删除
              </t-button>
              <right-toolbar v-model:show-search="showSearch" @query-table="getList"></right-toolbar>
            </div>
          </t-col>
        </t-row>
      </template>

      <t-table
        class="erp-table"
        row-key="tableId"
        bordered
        :data="tableList"
        :columns="tableColumns"
        :loading="loading"
        :hover="true"
        :resizable="true"
        size="small"
        cell-empty-content="-"
        :selected-row-keys="ids"
        @select-change="handleSelectionChange"
      >
        <template #serialNumber="{ rowIndex }">
          <span>{{ (queryParams.pageNum - 1) * queryParams.pageSize + rowIndex + 1 }}</span>
        </template>
        <template #operation="{ row }">
          <div class="flex items-center justify-center gap-1">
            <!-- 权限判断改挂外层 t-tooltip：v-hasPermi 在 mounted 里删节点，会与 tooltip 的包裹结构打架 -->
            <t-tooltip v-if="checkPermi(['tool:gen:preview'])" content="预览" placement="top">
              <t-button theme="primary" variant="text" shape="square" size="small" @click="handlePreview(row)">
                <template #icon><BrowseIcon /></template>
              </t-button>
            </t-tooltip>
            <t-tooltip v-if="checkPermi(['tool:gen:edit'])" content="编辑" placement="top">
              <t-button theme="primary" variant="text" shape="square" size="small" @click="handleEditTable(row)">
                <template #icon><Edit1Icon /></template>
              </t-button>
            </t-tooltip>
            <t-tooltip v-if="checkPermi(['tool:gen:remove'])" content="删除" placement="top">
              <t-button theme="primary" variant="text" shape="square" size="small" @click="handleDelete(row)">
                <template #icon><DeleteIcon /></template>
              </t-button>
            </t-tooltip>
            <t-tooltip v-if="checkPermi(['tool:gen:edit'])" content="同步" placement="top">
              <t-button theme="primary" variant="text" shape="square" size="small" @click="handleSynchDb(row)">
                <template #icon><RefreshIcon /></template>
              </t-button>
            </t-tooltip>
            <t-tooltip v-if="checkPermi(['tool:gen:code'])" content="生成代码" placement="top">
              <t-button theme="primary" variant="text" shape="square" size="small" @click="handleGenTable(row)">
                <template #icon><DownloadIcon /></template>
              </t-button>
            </t-tooltip>
          </div>
        </template>
      </t-table>
      <pagination v-show="total > 0" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" :total="total" @pagination="getList" />
    </t-card>

    <!-- 预览界面 -->
    <t-dialog v-model:visible="dialog.visible" :header="dialog.title" width="80%" top="5vh" attach="body" class="scrollbar">
      <t-tabs v-model="preview.activeName">
        <t-tab-panel
          v-for="(value, key) in preview.data"
          :key="value"
          :label="key.substring(key.lastIndexOf('/') + 1, key.indexOf('.vm'))"
          :value="key.substring(key.lastIndexOf('/') + 1, key.indexOf('.vm'))"
        >
          <t-link v-copyText="value" v-copyText:callback="copyTextSuccess" :underline="false" theme="primary" style="float: right">
            <template #prefix-icon><FileCopyIcon /></template>
            &nbsp;复制
          </t-link>
          <highlightjs :code="value" />
        </t-tab-panel>
      </t-tabs>
    </t-dialog>
    <import-table ref="importRef" @ok="handleQuery" />
  </div>
</template>

<script setup name="Gen" lang="ts">
import { delTable, getDataNames, listTable, previewTable, synchDb } from '@/api/tool/gen';
import { TableQuery, TableVO } from '@/api/tool/gen/types';
import router from '@/router';
import ImportTable from './importTable.vue';
import { checkPermi } from '@/utils/permission';
import { BrowseIcon, DeleteIcon, DownloadIcon, Edit1Icon, FileCopyIcon, RefreshIcon, SearchIcon, UploadIcon } from 'tdesign-icons-vue-next';

const route = useRoute();
const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const tableList = ref<TableVO[]>([]);
const loading = ref(true);
const showSearch = ref(true);
const ids = ref<Array<string | number>>([]);
const single = ref(true);
const multiple = ref(true);
const total = ref(0);
const dateRange = ref<[DateModelType, DateModelType]>(['', '']);
const uniqueId = ref('');
const dataNameList = ref<Array<string>>([]);

const queryFormRef = ref<any>();
const importRef = ref<InstanceType<typeof ImportTable>>();

const queryParams = ref<TableQuery>({
  pageNum: 1,
  pageSize: 10,
  tableName: '',
  tableComment: '',
  dataName: ''
});

const preview = ref<{
  data: Record<string, string>;
  activeName: string;
}>({
  data: {},
  activeName: 'domain.java'
});
const dialog = reactive<DialogOption>({
  visible: false,
  title: '代码预览'
});

/** 数据源下拉：t-select 用 { label, value } 数组，含「全部」空值项 */
const dataNameOptions = computed(() => [{ label: '全部', value: '' }, ...dataNameList.value.map((item) => ({ label: item, value: item }))]);

/** 表集合列表列配置：t-table 用 columns 数组，插槽名与 cell 字符串同名 */
const tableColumns: any[] = [
  { colKey: 'row-select', type: 'multiple', width: 55, align: 'center' },
  { colKey: 'serialNumber', title: '序号', width: 50, align: 'center', cell: 'serialNumber' },
  { colKey: 'dataName', title: '数据源', align: 'center', minWidth: 100, ellipsis: true },
  { colKey: 'tableName', title: '表名称', align: 'center', minWidth: 140, ellipsis: true },
  { colKey: 'tableComment', title: '表描述', align: 'center', minWidth: 140, ellipsis: true },
  { colKey: 'className', title: '实体', align: 'center', minWidth: 140, ellipsis: true },
  { colKey: 'createTime', title: '创建时间', align: 'center', width: 160 },
  { colKey: 'updateTime', title: '更新时间', align: 'center', width: 160 },
  { colKey: 'operation', title: '操作', align: 'center', width: 200, cell: 'operation' }
];

/** 查询多数据源名称 */
const getDataNameList = async () => {
  const res = await getDataNames();
  dataNameList.value = res.data;
};

/** 查询表集合 */
const getList = async () => {
  loading.value = true;
  const res = await listTable(proxy?.addDateRange(queryParams.value, dateRange.value));
  tableList.value = res.rows;
  total.value = res.total;
  loading.value = false;
};
/** 搜索按钮操作 */
const handleQuery = () => {
  queryParams.value.pageNum = 1;
  getList();
};
/** 生成代码操作 */
const handleGenTable = async (row?: TableVO) => {
  const tbIds = row?.tableId || ids.value;
  if (tbIds == '') {
    proxy?.$modal.msgError('请选择要生成的数据');
    return;
  }
  proxy?.$download.zip('/tool/gen/batchGenCode?tableIdStr=' + tbIds, 'ruoyi.zip');
};
/** 同步数据库操作 */
const handleSynchDb = async (row: TableVO) => {
  const tableId = row.tableId;
  await proxy?.$modal.confirm('确认要强制同步"' + row.tableName + '"表结构吗？');
  await synchDb(tableId);
  proxy?.$modal.msgSuccess('同步成功');
};
/** 打开导入表弹窗 */
const openImportTable = () => {
  importRef.value?.show(queryParams.value.dataName);
};
/** 重置按钮操作：TDesign 的 reset 替代 EP 的 resetFields */
const resetQuery = () => {
  dateRange.value = ['', ''];
  queryFormRef.value?.reset();
  handleQuery();
};
/** 预览按钮 */
const handlePreview = async (row: TableVO) => {
  const res = await previewTable(row.tableId);
  preview.value.data = res.data;
  dialog.visible = true;
  preview.value.activeName = 'domain.java';
};
/** 复制代码成功 */
const copyTextSuccess = () => {
  proxy?.$modal.msgSuccess('复制成功');
};
// 多选框选中数据：t-table 的 select-change 只回传选中的 rowKey（rowKey=tableId）
const handleSelectionChange = (keys: Array<string | number>) => {
  ids.value = keys;
  single.value = keys.length != 1;
  multiple.value = !keys.length;
};
/** 修改按钮操作 */
const handleEditTable = (row?: TableVO) => {
  const tableId = row?.tableId || ids.value[0];
  router.push({ path: '/tool/gen-edit/index/' + tableId, query: { pageNum: queryParams.value.pageNum } });
};
/** 删除按钮操作 */
const handleDelete = async (row?: TableVO) => {
  const tableIds = row?.tableId || ids.value;
  await proxy?.$modal.confirm('是否确认删除表编号为"' + tableIds + '"的数据项？');
  await delTable(tableIds);
  await getList();
  proxy?.$modal.msgSuccess('删除成功');
};

onMounted(() => {
  const time = route.query.t;
  if (time != null && time != uniqueId.value) {
    uniqueId.value = time as string;
    queryParams.value.pageNum = Number(route.query.pageNum);
    dateRange.value = ['', ''];
    queryFormRef.value?.reset();
  }
  getList();
  getDataNameList();
});
</script>

<style lang="scss" scoped>
.t-tab-panel {
  background-color: #282c34;
  :deep(.t-link) {
    color: #fff;
  }
}
</style>
