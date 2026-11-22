<template>
  <div class="palette">
    <div class="palette-title">组件库</div>
    <div class="palette-hint">拖拽到画布，或点击快速追加</div>
    <div class="palette-list">
      <div
        v-for="item in PALETTE"
        :key="item.type"
        class="palette-item"
        draggable="true"
        @dragstart="onDragStart($event, item)"
        @click="emit('append', item)"
      >
        <div class="palette-item-main">
          <span class="palette-item-label">{{ item.label }}</span>
          <span class="palette-item-desc">{{ item.desc }}</span>
        </div>
        <PlusIcon class="palette-item-add" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts" name="BlockPalette">
import { PlusIcon } from 'tdesign-icons-vue-next';
import { PALETTE, type PaletteItem } from './designerOptions';

/**
 * 拖拽数据通过 dataTransfer 传给画布：
 * 只传 type 字符串，画布侧调用 create() 生成新区块，避免多实例共享同一对象引用。
 */
const DRAG_MIME = 'application/x-erp-print-block';

const emit = defineEmits<{
  (e: 'append', item: PaletteItem): void;
}>();

const onDragStart = (ev: DragEvent, item: PaletteItem) => {
  if (!ev.dataTransfer) return;
  ev.dataTransfer.effectAllowed = 'copy';
  ev.dataTransfer.setData(DRAG_MIME, item.type);
  // text/plain 兜底：部分浏览器/环境下自定义 MIME 取不到
  ev.dataTransfer.setData('text/plain', item.type);
};

defineExpose({ DRAG_MIME });
</script>

<style scoped lang="scss">
.palette {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  border-right: 1px solid var(--td-component-border);
  background: var(--td-bg-color-container);
}

.palette-title {
  padding: 12px 14px 4px;
  font-weight: 600;
  font-size: 13px;
}

.palette-hint {
  padding: 0 14px 10px;
  font-size: 12px;
  color: var(--td-text-color-placeholder);
}

.palette-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0 10px 10px;
}

.palette-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  margin-bottom: 6px;
  border: 1px solid var(--td-component-border);
  border-radius: 4px;
  cursor: grab;
  transition: all 0.15s;

  &:hover {
    border-color: var(--td-brand-color);
    background: var(--td-bg-color-container-hover);
  }

  &:active {
    cursor: grabbing;
  }
}

.palette-item-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
}

.palette-item-label {
  font-size: 13px;
  font-weight: 500;
}

.palette-item-desc {
  font-size: 11px;
  color: var(--td-text-color-placeholder);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.palette-item-add {
  flex-shrink: 0;
  font-size: 14px;
  color: var(--td-text-color-placeholder);
}
</style>
