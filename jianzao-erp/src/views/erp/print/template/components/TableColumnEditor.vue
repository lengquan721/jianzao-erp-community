<template>
  <div class="col-editor">
    <div class="col-editor-head">
      <span class="col-editor-title">表格列</span>
      <span class="col-editor-hint">勾选显示，拖动排序，可改标题与宽度</span>
    </div>

    <div class="col-list">
      <div
        v-for="(col, index) in columns"
        :key="col.key"
        class="col-row"
        :class="{ 'col-row-off': col.visible === false }"
        draggable="true"
        @dragstart="onDragStart(index)"
        @dragover.prevent
        @drop.prevent="onDrop(index)"
      >
        <t-checkbox :checked="col.visible !== false" @change="(v: boolean) => toggle(col, v)" />
        <span class="col-drag">⋮⋮</span>
        <t-input size="small" class="col-label" :value="col.label" @change="(v: any) => update(col, 'label', v)" />
        <t-input-number
          size="small"
          class="col-width"
          theme="normal"
          :value="col.width ?? undefined"
          :min="5"
          :max="100"
          placeholder="自适应"
          @change="(v: any) => update(col, 'width', v ?? null)"
        />
        <t-select size="small" class="col-align" :value="col.align || 'left'" :options="alignOptions" @change="(v: any) => update(col, 'align', v)" />
        <t-button size="small" variant="text" theme="danger" :disabled="columns.length <= 1" @click="remove(index)"> 删除 </t-button>
      </div>
    </div>

    <div v-if="availableToAdd.length" class="col-add">
      <span class="col-add-label">添加列：</span>
      <t-button v-for="c in availableToAdd" :key="c.key" size="small" variant="outline" @click="add(c)">
        {{ c.label }}
      </t-button>
    </div>
  </div>
</template>

<script setup lang="ts" name="TableColumnEditor">
import { computed } from 'vue';
import type { PrintTableColumn, PrintVariable } from '@/api/erp/types';

const props = defineProps<{
  columns: PrintTableColumn[];
  /** 当前数据源可选的全部列（来自后端变量字典） */
  available: PrintVariable[];
}>();

const emit = defineEmits<{
  (e: 'change', columns: PrintTableColumn[]): void;
}>();

const alignOptions = [
  { label: '左', value: 'left' },
  { label: '中', value: 'center' },
  { label: '右', value: 'right' }
];

/** 尚未加入的列，供一键补加 */
const availableToAdd = computed(() => props.available.filter((a) => !props.columns.some((c) => c.key === a.key)));

let dragIndex: number | null = null;
const onDragStart = (index: number) => {
  dragIndex = index;
};
const onDrop = (index: number) => {
  if (dragIndex === null || dragIndex === index) return;
  const next = [...props.columns];
  const [moved] = next.splice(dragIndex, 1);
  next.splice(index, 0, moved);
  dragIndex = null;
  emit('change', next);
};

const toggle = (col: PrintTableColumn, visible: boolean) => {
  emit(
    'change',
    props.columns.map((c) => (c.key === col.key ? { ...c, visible } : c))
  );
};

const update = (col: PrintTableColumn, field: keyof PrintTableColumn, value: any) => {
  emit(
    'change',
    props.columns.map((c) => (c.key === col.key ? { ...c, [field]: value } : c))
  );
};

const remove = (index: number) => {
  emit(
    'change',
    props.columns.filter((_, i) => i !== index)
  );
};

const add = (variable: PrintVariable) => {
  const numeric = ['qty', 'qtyText', 'price', 'amount', 'settled', 'unsettled'].includes(variable.key);
  emit('change', [
    ...props.columns,
    {
      key: variable.key,
      label: variable.label,
      width: null,
      align: numeric ? 'right' : 'left',
      visible: true,
      numeric
    }
  ]);
};
</script>

<style scoped lang="scss">
.col-editor {
  border-top: 1px solid var(--td-component-border);
  padding-top: 10px;
  margin-top: 10px;
}

.col-editor-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 8px;
}

.col-editor-title {
  font-size: 13px;
  font-weight: 600;
}

.col-editor-hint {
  font-size: 11px;
  color: var(--td-text-color-placeholder);
}

.col-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.col-row {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 3px;
  border: 1px solid transparent;
  border-radius: 3px;

  &:hover {
    border-color: var(--td-component-border);
  }
}

.col-row-off {
  opacity: 0.45;
}

.col-drag {
  cursor: grab;
  color: var(--td-text-color-placeholder);
  font-size: 11px;
  user-select: none;
}

.col-label {
  flex: 1;
  min-width: 0;
}

.col-width {
  width: 76px;
  flex-shrink: 0;
}

.col-align {
  width: 68px;
  flex-shrink: 0;
}

.col-add {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
}

.col-add-label {
  font-size: 12px;
  color: var(--td-text-color-secondary);
}
</style>
