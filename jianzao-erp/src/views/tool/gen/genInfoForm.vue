<template>
  <t-form ref="genInfoForm" :data="infoForm" :rules="rules" label-width="150px" label-align="right">
    <t-row :gutter="20">
      <t-col :span="6">
        <t-form-item name="tplCategory">
          <template #label>生成模板</template>
          <t-select v-model="infoForm.tplCategory" :options="tplCategoryOptions" @change="tplSelectChange" />
        </t-form-item>
      </t-col>

      <t-col :span="6">
        <t-form-item name="packageName">
          <template #label>
            生成包路径
            <t-tooltip content="生成在哪个java包下，例如 com.ruoyi.system" placement="top">
              <HelpCircleIcon class="gen-info-tip-icon" />
            </t-tooltip>
          </template>
          <t-input v-model="infoForm.packageName" />
        </t-form-item>
      </t-col>

      <t-col :span="6">
        <t-form-item name="moduleName">
          <template #label>
            生成模块名
            <t-tooltip content="可理解为子系统名，例如 system" placement="top">
              <HelpCircleIcon class="gen-info-tip-icon" />
            </t-tooltip>
          </template>
          <t-input v-model="infoForm.moduleName" />
        </t-form-item>
      </t-col>

      <t-col :span="6">
        <t-form-item name="businessName">
          <template #label>
            生成业务名
            <t-tooltip content="可理解为功能英文名，例如 user" placement="top">
              <HelpCircleIcon class="gen-info-tip-icon" />
            </t-tooltip>
          </template>
          <t-input v-model="infoForm.businessName" />
        </t-form-item>
      </t-col>

      <t-col :span="6">
        <t-form-item name="functionName">
          <template #label>
            生成功能名
            <t-tooltip content="用作类描述，例如 用户" placement="top">
              <HelpCircleIcon class="gen-info-tip-icon" />
            </t-tooltip>
          </template>
          <t-input v-model="infoForm.functionName" />
        </t-form-item>
      </t-col>

      <t-col :span="6">
        <t-form-item>
          <template #label>
            上级菜单
            <t-tooltip content="分配到指定菜单下，例如 系统管理" placement="top">
              <HelpCircleIcon class="gen-info-tip-icon" />
            </t-tooltip>
          </template>
          <t-tree-select
            v-model="infoForm.parentMenuId"
            :data="menuOptions"
            :keys="{ value: 'menuId', label: 'menuName', children: 'children' }"
            placeholder="选择上级菜单"
            filterable
            clearable
          />
        </t-form-item>
      </t-col>
    </t-row>

    <template v-if="info.tplCategory == 'tree'">
      <h4 class="form-header">其他信息</h4>
      <t-row v-show="info.tplCategory == 'tree'" :gutter="20">
        <t-col :span="6">
          <t-form-item>
            <template #label>
              树编码字段
              <t-tooltip content="树显示的编码字段名， 如：dept_id" placement="top">
                <HelpCircleIcon class="gen-info-tip-icon" />
              </t-tooltip>
            </template>
            <t-select v-model="infoForm.treeCode" placeholder="请选择" :options="treeCodeOptions" />
          </t-form-item>
        </t-col>
        <t-col :span="6">
          <t-form-item>
            <template #label>
              树父编码字段
              <t-tooltip content="树显示的父编码字段名， 如：parent_Id" placement="top">
                <HelpCircleIcon class="gen-info-tip-icon" />
              </t-tooltip>
            </template>
            <t-select v-model="infoForm.treeParentCode" placeholder="请选择" :options="treeParentCodeOptions" />
          </t-form-item>
        </t-col>
        <t-col :span="6">
          <t-form-item>
            <template #label>
              树名称字段
              <t-tooltip content="树节点的显示名称字段名， 如：dept_name" placement="top">
                <HelpCircleIcon class="gen-info-tip-icon" />
              </t-tooltip>
            </template>
            <t-select v-model="infoForm.treeName" placeholder="请选择" :options="treeNameOptions" />
          </t-form-item>
        </t-col>
      </t-row>
    </template>

    <template v-if="info.tplCategory == 'sub'">
      <h4 class="form-header">关联信息</h4>
      <t-row :gutter="20">
        <t-col :span="6">
          <t-form-item>
            <template #label>
              关联子表的表名
              <t-tooltip content="关联子表的表名， 如：sys_user" placement="top">
                <HelpCircleIcon class="gen-info-tip-icon" />
              </t-tooltip>
            </template>
            <t-select v-model="infoForm.subTableName" placeholder="请选择" :options="subTableNameOptions" @change="subSelectChange" />
          </t-form-item>
        </t-col>
        <t-col :span="6">
          <t-form-item>
            <template #label>
              子表关联的外键名
              <t-tooltip content="子表关联的外键名， 如：user_id" placement="top">
                <HelpCircleIcon class="gen-info-tip-icon" />
              </t-tooltip>
            </template>
            <t-select v-model="infoForm.subTableFkName" placeholder="请选择" :options="subTableFkNameOptions" />
          </t-form-item>
        </t-col>
      </t-row>
    </template>
  </t-form>
</template>

<script setup lang="ts">
import { listMenu } from '@/api/system/menu';
import { propTypes } from '@/utils/propTypes';
import { HelpCircleIcon } from 'tdesign-icons-vue-next';

interface MenuOptionsType {
  menuId: number | string;
  menuName: string;
  children?: MenuOptionsType[];
}
const { proxy } = getCurrentInstance();

const subColumns = ref<any>([]);
const menuOptions = ref<Array<MenuOptionsType>>([]);

const props = defineProps({
  info: propTypes.any.isRequired,
  tables: propTypes.any.isRequired
});

const infoForm = computed(() => props.info);

const table = computed(() => props.tables);

// 下拉选项：t-select 用 { label, value } 数组，替换原 el-option 列表
const tplCategoryOptions = [
  { label: '单表（增删改查）', value: 'crud' },
  { label: '树表（增删改查）', value: 'tree' }
];
/** 字段列下拉：值取 columnName，显示「列名：描述」 */
const columnOptions = (columns: any[]) =>
  (columns ?? []).map((column) => ({
    label: column.columnName + '：' + column.columnComment,
    value: column.columnName
  }));
const treeCodeOptions = computed(() => columnOptions(props.info.columns));
const treeParentCodeOptions = computed(() => columnOptions(infoForm.value.columns));
const treeNameOptions = computed(() => columnOptions(props.info.columns));
const subTableNameOptions = computed(() => table.value.map((t: any) => ({ label: t.tableName + '：' + t.tableComment, value: t.tableName })));
const subTableFkNameOptions = computed(() => columnOptions(subColumns.value));

// 表单校验：TDesign 的 rules 形状与 EP 不兼容，类型按约定写 Record<string, any>
const rules = ref<Record<string, any>>({
  tplCategory: [{ required: true, message: '请选择生成模板', trigger: 'blur' }],
  packageName: [{ required: true, message: '请输入生成包路径', trigger: 'blur' }],
  moduleName: [{ required: true, message: '请输入生成模块名', trigger: 'blur' }],
  businessName: [{ required: true, message: '请输入生成业务名', trigger: 'blur' }],
  functionName: [{ required: true, message: '请输入生成功能名', trigger: 'blur' }]
});
const subSelectChange = () => {
  infoForm.value.subTableFkName = '';
};
const tplSelectChange = (value: string) => {
  if (value !== 'sub') {
    infoForm.value.subTableName = '';
    infoForm.value.subTableFkName = '';
  }
};
const setSubTableColumns = (value: string) => {
  table.value.forEach((item: any) => {
    const name = item.tableName;
    if (value === name) {
      subColumns.value = item.columns;
      return;
    }
  });
};

/** 查询菜单下拉树结构 */
const getMenuTreeselect = async () => {
  const res = await listMenu();
  const data = proxy?.handleTree<MenuOptionsType>(res.data, 'menuId');

  if (data) {
    menuOptions.value = data;
  }
};

watch(
  () => props.info.subTableName,
  (val) => {
    setSubTableColumns(val);
  }
);

onMounted(() => {
  getMenuTreeselect();
});
</script>

<style lang="scss" scoped>
.gen-info-tip-icon {
  margin-left: 4px;
  vertical-align: middle;
  cursor: pointer;
}
</style>
