<script setup lang="ts" generic="T extends { id: ErpId; code?: string; name?: string; phone?: string }">
import { computed, h, onBeforeUnmount, ref, shallowRef, watch } from 'vue';
import type { ErpId, ErpOptionPage, ErpOptionQuery } from '@/api/erp/types';

const props = withDefaults(
  defineProps<{
    modelValue?: ErpId | null;
    fetchPage: (query: ErpOptionQuery) => Promise<ErpOptionPage<T> & { total?: number }>;
    fetchByIds?: (ids: ErpId[]) => Promise<T[]>;
    status?: number;
    placeholder?: string;
    clearable?: boolean;
    disabled?: boolean;
    label?: (item: T) => string;
    showPhone?: boolean;
  }>(),
  { placeholder: '请选择', clearable: true }
);

const emit = defineEmits<{
  (e: 'update:modelValue', id: ErpId | undefined): void;
  (e: 'change', item: T | undefined): void;
}>();

const rows = shallowRef<T[]>([]);
const selected = shallowRef<T>();
const visible = ref(false);
const keyword = ref('');
const pageNum = ref(1);
const nextPageNum = ref<number | null>(null);
const total = ref<number>();
const loading = ref(false);
const failed = ref(false);
let pageRequest = 0;

const labelFor = (item: T) => props.label?.(item) ?? `${item.code ?? ''} ${item.name ?? ''}`.trim();
const options = computed(() =>
  rows.value.map((item) => ({
    value: item.id,
    label: labelFor(item),
    ...(props.showPhone && item.phone
      ? {
          slots: {
            default: () =>
              h('div', { class: 'flex items-center justify-between gap-4' }, [
                h('span', { class: 'truncate' }, labelFor(item)),
                h('span', { class: 'shrink-0 text-xs erp-text-secondary' }, item.phone)
              ])
          }
        }
      : {})
  }))
);
const hasValue = computed(() => props.modelValue != null && props.modelValue !== '');
const displayLabel = computed(() => {
  if (!hasValue.value) return '';
  return selected.value && String(selected.value.id) === String(props.modelValue) ? labelFor(selected.value) : String(props.modelValue);
});

const load = async (page: number) => {
  const seq = ++pageRequest;
  loading.value = true;
  failed.value = false;
  rows.value = [];
  nextPageNum.value = null;
  total.value = undefined;
  pageNum.value = page;
  try {
    const res = await props.fetchPage({ pageNum: page, pageSize: 30, keyword: keyword.value || undefined, status: props.status });
    if (seq !== pageRequest) return;
    rows.value = res.rows;
    total.value = res.total;
    nextPageNum.value = res.hasMore ? (res.nextPageNum ?? null) : null;
    const hit = res.rows.find((row) => String(row.id) === String(props.modelValue));
    if (hit) selected.value = hit;
  } catch {
    if (seq === pageRequest) failed.value = true;
  } finally {
    if (seq === pageRequest) loading.value = false;
  }
};

// TDesign select 自带搜索防抖；这里只处理已触发的远程查询。
const onSearch = (value: string) => {
  if (!visible.value) return;
  const search = value.trim();
  if (keyword.value === search) return;
  keyword.value = search;
  void load(1);
};

const onPopup = (open: boolean) => {
  visible.value = open;
  if (open) {
    keyword.value = '';
    void load(1);
  } else {
    ++pageRequest;
    loading.value = false;
  }
};

watch([() => props.fetchPage, () => props.status], () => {
  if (visible.value) void load(1);
});

// 已选项不插进检索页；独立回显，避免过滤后清空值或污染分页顺序。
watch(
  () => props.modelValue,
  async (id, _old, onCleanup) => {
    if (id != null && id !== '' && selected.value && String(selected.value.id) === String(id)) return;
    selected.value = rows.value.find((row) => String(row.id) === String(id));
    if (selected.value || id == null || id === '' || !props.fetchByIds) return;
    let current = true;
    onCleanup(() => (current = false));
    try {
      const [hit] = await props.fetchByIds([id]);
      if (current && hit) selected.value = hit;
    } catch {
      // 保留原值（例如没有读取权限或主数据已删除）。
    }
  },
  { immediate: true }
);

const onChange = (value: unknown) => {
  if (value == null || value === '') {
    selected.value = undefined;
    emit('update:modelValue', undefined);
    emit('change', undefined);
    return;
  }
  const item = rows.value.find((row) => String(row.id) === String(value));
  if (!item) return;
  selected.value = item;
  emit('update:modelValue', item.id);
  emit('change', item);
};

onBeforeUnmount(() => {
  ++pageRequest;
});
</script>

<template>
  <t-select
    :model-value="modelValue"
    :title="displayLabel"
    :placeholder="placeholder"
    :disabled="disabled"
    :clearable="clearable"
    :loading="loading"
    :options="options"
    :input-props="{ maxlength: 100 }"
    :empty="failed ? '加载失败，请重试' : '暂无匹配项'"
    filterable
    @change="onChange"
    @search="onSearch"
    @popup-visible-change="onPopup"
  >
    <template v-if="hasValue" #valueDisplay>{{ displayLabel }}</template>
    <template #panelBottomContent>
      <div
        v-if="failed || pageNum > 1 || nextPageNum != null || total != null"
        class="flex flex-wrap items-center gap-1 px-2 py-1"
        @mousedown.prevent
      >
        <span class="text-xs erp-text-secondary"
          >第 {{ pageNum }} 页<span v-if="total != null"> · 共 {{ total }} 条</span></span
        >
        <t-button v-if="pageNum > 1" size="small" variant="text" :disabled="loading" @click.stop="load(pageNum - 1)">上一页</t-button>
        <t-button v-if="nextPageNum != null" size="small" variant="text" :disabled="loading" @click.stop="load(nextPageNum)">下一页</t-button>
        <t-button v-if="failed" size="small" variant="text" @click.stop="load(pageNum)">重试</t-button>
      </div>
    </template>
  </t-select>
</template>
