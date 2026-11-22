<script setup lang="ts">
import { computed, ref } from 'vue';
import { listPurchaseOrder, listSaleOrder } from '@/api/erp/order';
import type { ErpId, ErpOptionQuery, ErpPurchaseOrderVO, ErpSaleOrderVO } from '@/api/erp/types';
import ErpPagedSelect from './ErpPagedSelect.vue';
import PartnerSelect from './PartnerSelect.vue';

const props = defineProps<{ modelValue?: ErpId; kind: 'sale' | 'purchase' }>();
type SourceOrder = ErpSaleOrderVO | ErpPurchaseOrderVO;
const emit = defineEmits<{
  (e: 'update:modelValue', id: ErpId | undefined): void;
  (e: 'change', order: SourceOrder | undefined): void;
}>();

// 这是来源候选项的独立筛选；不改变执行单上的往来单位，仍可从任意已确认订单选入。
const partyId = ref<ErpId>();
const isSale = computed(() => props.kind === 'sale');
const partyLabel = computed(() => (isSale.value ? '客户' : '供应商'));
const fetchPage = computed(() => {
  const sale = isSale.value;
  const partner = partyId.value;
  return async ({ pageNum, pageSize = 30, keyword }: ErpOptionQuery) => {
    const query = { pageNum, pageSize, keyword, status: 'CONFIRMED' };
    // 服务端搜订单号/对方单号，往来单位按 ID 过滤；不排除已完成/关闭的确认单，不重排结果。
    const res = sale ? await listSaleOrder({ ...query, customerId: partner }) : await listPurchaseOrder({ ...query, supplierId: partner });
    const hasMore = pageNum * pageSize < res.total;
    return { rows: res.rows, total: res.total, hasMore, nextPageNum: hasMore ? pageNum + 1 : null };
  };
});
const label = (order: SourceOrder) => {
  const name = 'customerId' in order ? order.customerName : order.supplierName;
  return `${order.no ?? ''}（${name || partyLabel.value}）`;
};
</script>

<template>
  <ErpPagedSelect
    :model-value="modelValue"
    :fetch-page="fetchPage"
    :label="label"
    :placeholder="`选择已确认的${isSale ? '销售' : '采购'}订单（可选）`"
    style="width: 320px"
    @update:model-value="emit('update:modelValue', $event)"
    @change="emit('change', $event)"
  />
  <PartnerSelect v-model="partyId" :kind="isSale ? 'customer' : 'supplier'" :placeholder="`按${partyLabel}筛选来源订单`" style="width: 200px" />
</template>
