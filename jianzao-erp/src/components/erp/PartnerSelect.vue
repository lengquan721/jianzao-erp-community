<script setup lang="ts">
/** 往来单位远程选择，保留原 v-model / change 及「编码 名称 | 电话」文案。 */
import { customerOptions, customerOptionsByIds, supplierOptions, supplierOptionsByIds } from '@/api/erp/master';
import type { ErpCustomerVO, ErpId, ErpSupplierVO } from '@/api/erp/types';
import ErpPagedSelect from './ErpPagedSelect.vue';

withDefaults(
  defineProps<{
    modelValue?: ErpId | null;
    kind?: 'customer' | 'supplier';
    placeholder?: string;
    disabled?: boolean;
    clearable?: boolean;
  }>(),
  { kind: 'customer', placeholder: '请选择', disabled: false, clearable: true }
);

const emit = defineEmits<{
  (e: 'update:modelValue', value: ErpId | undefined): void;
  (e: 'change', party: ErpCustomerVO | ErpSupplierVO | undefined): void;
}>();
</script>

<template>
  <ErpPagedSelect
    :key="kind"
    :model-value="modelValue"
    :fetch-page="kind === 'customer' ? customerOptions : supplierOptions"
    :fetch-by-ids="kind === 'customer' ? customerOptionsByIds : supplierOptionsByIds"
    :placeholder="placeholder"
    :disabled="disabled"
    :clearable="clearable"
    show-phone
    @update:model-value="emit('update:modelValue', $event)"
    @change="emit('change', $event)"
  />
</template>
