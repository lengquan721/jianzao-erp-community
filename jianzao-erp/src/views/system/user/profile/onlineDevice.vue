<template>
  <div>
    <t-table
      class="erp-table"
      row-key="tokenId"
      :data="devices"
      :columns="deviceColumns"
      :resizable="true"
      size="small"
      cell-empty-content="-"
      @column-resize-change="(ctx: any) => saveColumnWidths('system-user/online-device', ctx.columnsWidth)"
    >
      <template #deviceType="{ row }">
        <dict-tag :options="sys_device_type" :value="row.deviceType" />
      </template>
      <template #loginTime="{ row }">
        <span>{{ proxy.parseTime(row.loginTime) }}</span>
      </template>
      <template #operation="{ row }">
        <t-tooltip content="删除" placement="top">
          <t-button theme="primary" variant="text" shape="square" size="small" @click="handldDelOnline(row)">
            <template #icon><DeleteIcon /></template>
          </t-button>
        </t-tooltip>
      </template>
    </t-table>
  </div>
</template>

<script setup name="Online" lang="ts">
import { delOnline } from '@/api/monitor/online';
import { propTypes } from '@/utils/propTypes';
import { mergeSavedWidths, saveColumnWidths } from '@/hooks/useTableColumnWidth';
import { DeleteIcon } from 'tdesign-icons-vue-next';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const { sys_device_type } = toRefs<any>(proxy?.useDict('sys_device_type'));

const props = defineProps({
  devices: propTypes.any.isRequired
});
const devices = computed(() => props.devices);

/** 在线设备列表列配置：t-table 用 columns 数组，插槽名与 cell 字符串同名，show-overflow-tooltip→ellipsis */
const deviceColumns: any[] = [
  { colKey: 'deviceType', title: '设备类型', align: 'center', cell: 'deviceType' },
  { colKey: 'ipaddr', title: '主机', align: 'center', ellipsis: true },
  { colKey: 'loginLocation', title: '登录地点', align: 'center', ellipsis: true },
  { colKey: 'os', title: '操作系统', align: 'center', ellipsis: true },
  { colKey: 'browser', title: '浏览器', align: 'center', ellipsis: true },
  { colKey: 'loginTime', title: '登录时间', align: 'center', width: 180, cell: 'loginTime' },
  { colKey: 'operation', title: '操作', align: 'center', width: 80, cell: 'operation' }
];
mergeSavedWidths('system-user/online-device', deviceColumns);

/** 删除按钮操作 */
const handldDelOnline = (row: any) => {
  proxy.$modal
    .confirm('删除设备后，在该设备登录需要重新进行验证')
    .then(() => {
      return delOnline(row.tokenId);
    })
    .then((res: any) => {
      if (res.code === 200) {
        proxy?.$modal.msgSuccess('删除成功');
        proxy?.$tab.refreshPage();
      } else {
        proxy?.$modal.msgError(res.msg);
      }
    })
    .catch(() => {});
};
</script>
