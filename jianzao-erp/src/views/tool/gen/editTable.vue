<template>
  <t-card :bordered="false">
    <t-tabs v-model="activeName">
      <t-tab-panel label="基本信息" value="basic">
        <basic-info-form ref="basicInfo" :info="info" />
      </t-tab-panel>
      <t-tab-panel label="字段信息" value="columnInfo">
        <t-table
          row-key="columnId"
          bordered
          :data="columns"
          :columns="columnColumns"
          :max-height="tableHeight"
          :resizable="true"
          size="small"
          cell-empty-content="-"
        >
          <template #serialNumber="{ rowIndex }">{{ rowIndex + 1 }}</template>
          <template #columnComment="{ row }">
            <t-input v-model="row.columnComment" />
          </template>
          <template #javaType="{ row }">
            <t-select v-model="row.javaType" :options="javaTypeOptions" />
          </template>
          <template #javaField="{ row }">
            <t-input v-model="row.javaField" />
          </template>
          <template #isInsert="{ row }">
            <t-checkbox :checked="String(row.isInsert) === '1'" @change="(v: boolean) => (row.isInsert = v ? '1' : '0')" />
          </template>
          <template #isEdit="{ row }">
            <t-checkbox :checked="String(row.isEdit) === '1'" @change="(v: boolean) => (row.isEdit = v ? '1' : '0')" />
          </template>
          <template #isList="{ row }">
            <t-checkbox :checked="String(row.isList) === '1'" @change="(v: boolean) => (row.isList = v ? '1' : '0')" />
          </template>
          <template #isQuery="{ row }">
            <t-checkbox :checked="String(row.isQuery) === '1'" @change="(v: boolean) => (row.isQuery = v ? '1' : '0')" />
          </template>
          <template #queryType="{ row }">
            <t-select v-model="row.queryType" :options="queryTypeOptions" />
          </template>
          <template #isRequired="{ row }">
            <t-checkbox :checked="String(row.isRequired) === '1'" @change="(v: boolean) => (row.isRequired = v ? '1' : '0')" />
          </template>
          <template #htmlType="{ row }">
            <t-select v-model="row.htmlType" :options="htmlTypeOptions" />
          </template>
          <template #dictType="{ row }">
            <t-select v-model="row.dictType" clearable filterable placeholder="请选择" :options="dictTypeOptions" @clear="row.dictType = ''" />
          </template>
        </t-table>
      </t-tab-panel>
      <t-tab-panel label="生成信息" value="genInfo">
        <gen-info-form ref="genInfo" :info="info" :tables="tables" />
      </t-tab-panel>
    </t-tabs>
    <t-form label-width="100px">
      <div style="text-align: center; margin-left: -100px; margin-top: 10px">
        <t-button theme="primary" @click="submitForm()">提交</t-button>
        <t-button theme="default" @click="close()">返回</t-button>
      </div>
    </t-form>
  </t-card>
</template>

<script setup name="GenEdit" lang="ts">
import { getGenTable, updateGenTable } from '@/api/tool/gen';
import { DbColumnVO, DbTableVO } from '@/api/tool/gen/types';
import { optionselect as getDictOptionselect } from '@/api/system/dict/type';
import { DictTypeVO } from '@/api/system/dict/type/types';
import BasicInfoForm from './basicInfoForm.vue';
import GenInfoForm from './genInfoForm.vue';
import { RouteLocationRaw } from 'vue-router';

const route = useRoute();
const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const activeName = ref('columnInfo');
const tableHeight = ref(document.documentElement.scrollHeight - 245 + 'px');
const tables = ref<DbTableVO[]>([]);
const columns = ref<DbColumnVO[]>([]);
const dictOptions = ref<DictTypeVO[]>([]);
const info = ref<Partial<DbTableVO>>({});

const basicInfo = ref<InstanceType<typeof BasicInfoForm>>();
const genInfo = ref<InstanceType<typeof GenInfoForm>>();

/** 字段信息表列配置：t-table 用 columns 数组，插槽名与 cell 字符串同名 */
const columnColumns: any[] = [
  { colKey: 'serialNumber', title: '序号', width: 60, align: 'center', cell: 'serialNumber' },
  { colKey: 'columnName', title: '字段列名', minWidth: 100, ellipsis: true },
  { colKey: 'columnComment', title: '字段描述', minWidth: 120, cell: 'columnComment' },
  { colKey: 'columnType', title: '物理类型', minWidth: 100, ellipsis: true },
  { colKey: 'javaType', title: 'Java类型', minWidth: 120, cell: 'javaType' },
  { colKey: 'javaField', title: 'java属性', minWidth: 120, cell: 'javaField' },
  { colKey: 'isInsert', title: '插入', minWidth: 60, align: 'center', cell: 'isInsert' },
  { colKey: 'isEdit', title: '编辑', minWidth: 60, align: 'center', cell: 'isEdit' },
  { colKey: 'isList', title: '列表', minWidth: 60, align: 'center', cell: 'isList' },
  { colKey: 'isQuery', title: '查询', minWidth: 60, align: 'center', cell: 'isQuery' },
  { colKey: 'queryType', title: '查询方式', minWidth: 110, cell: 'queryType' },
  { colKey: 'isRequired', title: '必填', minWidth: 60, align: 'center', cell: 'isRequired' },
  { colKey: 'htmlType', title: '显示类型', minWidth: 130, cell: 'htmlType' },
  { colKey: 'dictType', title: '字典类型', minWidth: 160, cell: 'dictType' }
];

// t-select 用 { label, value } 数组，替换原 el-option 列表
const javaTypeOptions = [
  { label: 'Long', value: 'Long' },
  { label: 'String', value: 'String' },
  { label: 'Integer', value: 'Integer' },
  { label: 'Double', value: 'Double' },
  { label: 'BigDecimal', value: 'BigDecimal' },
  { label: 'Date', value: 'Date' },
  { label: 'Boolean', value: 'Boolean' }
];
const queryTypeOptions = [
  { label: '=', value: 'EQ' },
  { label: '!=', value: 'NE' },
  { label: '>', value: 'GT' },
  { label: '>=', value: 'GE' },
  { label: '<', value: 'LT' },
  { label: '<=', value: 'LE' },
  { label: 'LIKE', value: 'LIKE' },
  { label: 'BETWEEN', value: 'BETWEEN' }
];
const htmlTypeOptions = [
  { label: '文本框', value: 'input' },
  { label: '文本域', value: 'textarea' },
  { label: '下拉框', value: 'select' },
  { label: '单选框', value: 'radio' },
  { label: '复选框', value: 'checkbox' },
  { label: '日期控件', value: 'datetime' },
  { label: '图片上传', value: 'imageUpload' },
  { label: '文件上传', value: 'fileUpload' },
  { label: '富文本控件', value: 'editor' }
];
// 字典下拉：保留原「名称 + 类型」双信息，值仍取 dictType
const dictTypeOptions = computed(() =>
  dictOptions.value.map((dict) => ({ label: dict.dictName + '（' + dict.dictType + '）', value: dict.dictType }))
);

/** 提交按钮 */
const submitForm = () => {
  const basicForm = basicInfo.value?.$refs.basicInfoForm;
  const genForm = genInfo.value?.$refs.genInfoForm;

  Promise.all([basicForm, genForm].map(getFormPromise)).then(async (res) => {
    const validateResult = res.every((item) => !!item);
    if (validateResult) {
      const genTable: any = Object.assign({}, info.value);
      genTable.columns = columns.value;
      genTable.params = {
        treeCode: info.value?.treeCode,
        treeName: info.value.treeName,
        treeParentCode: info.value.treeParentCode,
        parentMenuId: info.value.parentMenuId
      };
      const response = await updateGenTable(genTable);
      proxy?.$modal.msgSuccess(response.msg);
      if (response.code === 200) {
        close();
      }
    } else {
      proxy?.$modal.msgError('表单校验未通过，请重新检查提交内容');
    }
  });
};
/** 兼容 EP(失败 reject) 与 TDesign(失败 resolve 错误对象) 的校验结果，统一归一为 boolean */
const getFormPromise = (form: any): Promise<boolean> =>
  form
    .validate()
    .then((r: any) => r === true || r === undefined)
    .catch(() => false);
const close = () => {
  const obj: RouteLocationRaw = { path: '/tool/gen', query: { t: Date.now().toString(), pageNum: route.query.pageNum } };
  proxy?.$tab.closeOpenPage(obj);
};

(async () => {
  const tableId = route.params && (route.params.tableId as string);
  if (tableId) {
    // 获取表详细信息
    const res = await getGenTable(tableId);
    columns.value = res.data.rows;
    info.value = res.data.info;
    tables.value = res.data.tables;
    /** 查询字典下拉列表 */
    const response = await getDictOptionselect();
    dictOptions.value = response.data;
  }
})();
</script>
