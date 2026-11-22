<template>
  <div class="designer">
    <!-- 顶部工具条 -->
    <div class="designer-toolbar">
      <div class="designer-toolbar-left">
        <t-button size="small" variant="outline" :loading="metaLoading" @click="applyPreset">
          <template #icon><TemplateIcon /></template>
          套用预置版式
        </t-button>
        <t-button size="small" variant="outline" :disabled="!schema.blocks.length" @click="clearAll"> 清空画布 </t-button>
        <span class="designer-count">共 {{ schema.blocks.length }} 个区块</span>
      </div>
      <div class="designer-toolbar-right">
        <t-radio-group :value="schema.paper" variant="default-filled" size="small" @change="onPaperChange">
          <t-radio-button v-for="(size, key) in PAPER_SIZES" :key="key" :value="key">
            {{ size.label }}
          </t-radio-button>
        </t-radio-group>
      </div>
    </div>

    <!-- 四栏：组件库 / 图层 / 画布 / 属性 -->
    <div class="designer-body">
      <div class="designer-left">
        <BlockPalette class="designer-palette" @append="appendBlock" />

        <div class="layers">
          <div class="layers-title">图层</div>
          <div class="layers-list">
            <div v-if="!schema.blocks.length" class="layers-empty">暂无区块</div>
            <div
              v-for="(block, index) in schema.blocks"
              :key="block.id"
              class="layer-row"
              :class="{ 'layer-row-active': block.id === activeId }"
              @click="activeId = block.id"
            >
              <span class="layer-name">{{ blockLabel(block.type) }}</span>
              <span class="layer-summary">{{ summaryOf(block) }}</span>
              <span class="layer-ops">
                <ArrowUpIcon class="layer-op" :class="{ 'layer-op-off': index === 0 }" @click.stop="move(index, -1)" />
                <ArrowDownIcon class="layer-op" :class="{ 'layer-op-off': index === schema.blocks.length - 1 }" @click.stop="move(index, 1)" />
                <DeleteIcon class="layer-op layer-op-del" @click.stop="removeBlock(block.id)" />
              </span>
            </div>
          </div>
        </div>
      </div>

      <DesignCanvas
        class="designer-canvas"
        :schema="schema"
        :active-id="activeId"
        :field-examples="fieldExamples"
        :column-examples="columnExamples"
        @update:active-id="(id) => (activeId = id)"
        @reorder="onReorder"
        @drop-new="onDropNew"
        @layout-resize="onLayoutResize"
        @image-resize="onImageResize"
        @column-resize="onColumnResize"
        @block-move="onBlockMove"
        @image-ratio="onImageRatio"
        @image-move="onImageMove"
      />

      <PropertyPanel
        class="designer-props"
        :schema="schema"
        :block="activeBlock"
        :fields="meta.fields"
        :table-sources="meta.tableSources"
        :columns-by-source="meta.columns"
        @update-schema="onSchemaChange"
        @remove="removeBlock(activeId!)"
        @duplicate="duplicateBlock"
      />
    </div>
  </div>
</template>

<script setup lang="ts" name="PrintDesigner">
import { computed, ref, watch } from 'vue';
import { ArrowDownIcon, ArrowUpIcon, DeleteIcon, TemplateIcon } from 'tdesign-icons-vue-next';
import type { PrintDesignBlock, PrintDesignSchema, PrintDesignMeta } from '@/api/erp/types';
import { getPrintDesignMeta, getPrintPreset } from '@/api/erp/print';
import BlockPalette from './BlockPalette.vue';
import DesignCanvas from './DesignCanvas.vue';
import PropertyPanel from './PropertyPanel.vue';
import { PALETTE, PAPER_SIZES, blockLabel, cloneSchema, emptySchema, type PaletteItem } from './designerOptions';

const props = defineProps<{
  /** 组件树（v-model:schema） */
  schema: PrintDesignSchema;
  /** 当前场景，决定变量字典与预置版式 */
  scene: string;
}>();

const emit = defineEmits<{
  (e: 'update:schema', value: PrintDesignSchema): void;
}>();

const activeId = ref<string | null>(null);
const metaLoading = ref(false);

const meta = ref<PrintDesignMeta>({
  fields: [],
  tableSources: [{ key: 'items', label: '单据明细', group: '表格' }],
  columns: { items: [], 'statement.items': [] }
});

/** 字段示例值：画布用真实示例文字代替 ${} 占位，所见即所得 */
const fieldExamples = computed<Record<string, string>>(() => {
  const map: Record<string, string> = {};
  for (const f of meta.value.fields) {
    map[f.key] = f.example || f.label;
  }
  return map;
});

const columnExamples = computed<Record<string, string>>(() => {
  const map: Record<string, string> = {};
  for (const list of Object.values(meta.value.columns)) {
    for (const c of list) {
      map[c.key] = c.example || c.label;
    }
  }
  return map;
});

const activeBlock = computed(() => props.schema.blocks.find((b) => b.id === activeId.value) ?? null);

const loadMeta = async (scene: string) => {
  metaLoading.value = true;
  try {
    const res = await getPrintDesignMeta(scene);
    if (res.data) {
      meta.value = res.data;
    }
  } finally {
    metaLoading.value = false;
  }
};

watch(
  () => props.scene,
  (scene) => {
    if (scene) loadMeta(scene);
  },
  { immediate: true }
);

/** 区块在图层里的摘要，帮助区分多个同类区块 */
const summaryOf = (block: PrintDesignBlock): string => {
  const p = block.props || {};
  switch (block.type) {
    case 'TITLE':
      return p.text || '';
    case 'TEXT':
      return String(p.text || '').slice(0, 12);
    case 'FIELD':
      return p.fieldKey || '';
    case 'TABLE':
      return `${p.dataSource || 'items'} · ${(p.columns || []).filter((c: any) => c.visible !== false).length} 列`;
    case 'SIGNATURE':
      return (p.labels || []).join('/');
    case 'IMAGE':
      return 'Logo';
    default:
      return '';
  }
};

const onSchemaChange = (next: PrintDesignSchema) => {
  emit('update:schema', next);
};

const appendBlock = (item: PaletteItem) => {
  const block = item.create();
  const next = cloneSchema(props.schema);
  next.blocks.push(block);
  emit('update:schema', next);
  activeId.value = block.id;
};

const onDropNew = (type: string, index: number) => {
  const item = PALETTE.find((p) => p.type === type);
  if (!item) return;
  const block = item.create();
  const next = cloneSchema(props.schema);
  next.blocks.splice(Math.min(index, next.blocks.length), 0, block);
  emit('update:schema', next);
  activeId.value = block.id;
};

const onReorder = (from: number, to: number) => {
  const next = cloneSchema(props.schema);
  const [moved] = next.blocks.splice(from, 1);
  next.blocks.splice(to, 0, moved);
  emit('update:schema', next);
};

/** 区块占位宽度：只影响该区块占几成纸宽，与图片尺寸无关 */
const onLayoutResize = (id: string, widthPct: number) => {
  const next = cloneSchema(props.schema);
  const block = next.blocks.find((b) => b.id === id);
  if (block) {
    block.props = { ...block.props, layoutWidth: widthPct };
    // 变窄后若原偏移会让右边缘越界，一起收回来
    const maxOffset = Math.max(0, 100 - widthPct);
    if (Number(block.props.offsetX) > maxOffset) {
      block.props.offsetX = maxOffset;
    }
    emit('update:schema', next);
  }
};

/** 区块水平位置 */
const onBlockMove = (id: string, offsetPct: number) => {
  const next = cloneSchema(props.schema);
  const block = next.blocks.find((b) => b.id === id);
  if (block) {
    block.props = { ...block.props, offsetX: offsetPct };
    emit('update:schema', next);
  }
};

/** Logo 原图宽高比：拖角标时用来算高度，保持等比 */
const onImageRatio = (id: string, ratio: number) => {
  const next = cloneSchema(props.schema);
  const block = next.blocks.find((b) => b.id === id);
  if (block && block.props.ratio !== ratio) {
    block.props = { ...block.props, ratio };
    emit('update:schema', next);
  }
};

/** Logo 拖动定位：写 style.offsetPx，后端按距左侧像素输出 */
const onImageMove = (id: string, offsetPx: number) => {
  const next = cloneSchema(props.schema);
  const block = next.blocks.find((b) => b.id === id);
  if (block) {
    block.style = { ...(block.style || {}), offsetPx };
    emit('update:schema', next);
  }
};

/** Logo 像素尺寸：直接改 props.width，后端按此输出 img width */
const onImageResize = (id: string, widthPx: number) => {
  const next = cloneSchema(props.schema);
  const block = next.blocks.find((b) => b.id === id);
  if (block) {
    block.props = { ...block.props, width: widthPx };
    emit('update:schema', next);
  }
};

/** 表格列宽：按原始列下标改 width，其余列保持自适应 */
const onColumnResize = (blockId: string, columnIndex: number, widthPct: number) => {
  const next = cloneSchema(props.schema);
  const block = next.blocks.find((b) => b.id === blockId);
  if (!block?.props?.columns) return;
  const columns = [...block.props.columns];
  if (!columns[columnIndex]) return;
  columns[columnIndex] = { ...columns[columnIndex], width: widthPct };
  block.props = { ...block.props, columns };
  emit('update:schema', next);
};

const move = (index: number, delta: number) => {
  const to = index + delta;
  if (to < 0 || to >= props.schema.blocks.length) return;
  onReorder(index, to);
};

const removeBlock = (id: string) => {
  const next = cloneSchema(props.schema);
  next.blocks = next.blocks.filter((b) => b.id !== id);
  emit('update:schema', next);
  if (activeId.value === id) activeId.value = null;
};

const duplicateBlock = () => {
  if (!activeBlock.value) return;
  const index = props.schema.blocks.findIndex((b) => b.id === activeBlock.value!.id);
  const next = cloneSchema(props.schema);
  const copy: PrintDesignBlock = JSON.parse(JSON.stringify(activeBlock.value));
  copy.id = `${copy.type.toLowerCase()}-copy-${Date.now().toString(36)}`;
  next.blocks.splice(index + 1, 0, copy);
  emit('update:schema', next);
  activeId.value = copy.id;
};

const onPaperChange = (paper: string | number | boolean) => {
  if (typeof paper !== 'string') return;
  const next = cloneSchema(props.schema);
  next.paper = paper;
  emit('update:schema', next);
};

const applyPreset = async () => {
  const res = await getPrintPreset(props.scene);
  if (!res.data) return;
  emit('update:schema', cloneSchema(res.data));
  activeId.value = null;
};

const clearAll = () => {
  const next = emptySchema();
  next.paper = props.schema.paper;
  next.pageMargin = props.schema.pageMargin;
  emit('update:schema', next);
  activeId.value = null;
};
</script>

<style scoped lang="scss">
.designer {
  display: flex;
  flex-direction: column;
  height: 72vh;
  min-height: 480px;
  border: 1px solid var(--td-component-border);
  border-radius: 4px;
  overflow: hidden;
}

.designer-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--td-component-border);
  background: var(--td-bg-color-container);
}

.designer-toolbar-left,
.designer-toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.designer-count {
  font-size: 12px;
  color: var(--td-text-color-placeholder);
}

.designer-body {
  display: flex;
  flex: 1;
  min-height: 0;
}

.designer-left {
  display: flex;
  flex-direction: column;
  width: 208px;
  flex-shrink: 0;
  border-right: 1px solid var(--td-component-border);
}

.designer-palette {
  flex: 1;
  min-height: 0;
}

.layers {
  display: flex;
  flex-direction: column;
  height: 38%;
  min-height: 120px;
  border-top: 1px solid var(--td-component-border);
  background: var(--td-bg-color-container);
}

.layers-title {
  padding: 8px 14px 4px;
  font-size: 13px;
  font-weight: 600;
}

.layers-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0 8px 8px;
}

.layers-empty {
  padding: 8px;
  font-size: 11px;
  color: var(--td-text-color-placeholder);
}

.layer-row {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 6px;
  font-size: 12px;
  border-radius: 3px;
  cursor: pointer;

  &:hover {
    background: var(--td-bg-color-container-hover);
  }
}

.layer-row-active {
  background: var(--td-brand-color-light);
}

.layer-name {
  flex-shrink: 0;
  font-weight: 500;
}

.layer-summary {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--td-text-color-placeholder);
  font-size: 11px;
}

.layer-ops {
  display: flex;
  flex-shrink: 0;
  gap: 2px;
}

.layer-op {
  font-size: 12px;
  color: var(--td-text-color-secondary);
  cursor: pointer;

  &:hover {
    color: var(--td-brand-color);
  }
}

.layer-op-off {
  color: var(--td-text-color-disabled);
  pointer-events: none;
}

.layer-op-del:hover {
  color: var(--td-error-color);
}

.designer-canvas {
  flex: 1;
  min-width: 0;
}

.designer-props {
  width: 288px;
  flex-shrink: 0;
}
</style>
