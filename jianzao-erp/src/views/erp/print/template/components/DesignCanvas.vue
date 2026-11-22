<template>
  <div class="canvas-wrap">
    <div class="canvas-toolbar">
      <span class="canvas-tip"> {{ PAPER_SIZES[schema.paper || 'A4']?.label || schema.paper }} · 页边距 {{ schema.pageMargin }}mm </span>
      <span class="canvas-tip-sub">拖区块调顺序 · 拖右侧边改区块宽度 · 按住 ⠿ 左右移位置 · 拖表头改列宽 · Logo 拖右下角圆点缩放</span>
    </div>

    <div class="canvas-scroll">
      <div class="paper" :style="paperStyle" @dragover.prevent="onDragOverPaper" @drop.prevent="onDropToPaper">
        <div v-if="!schema.blocks.length" class="paper-empty">
          <span>从左侧拖入组件开始设计</span>
          <span class="paper-empty-sub">或点击组件快速追加</span>
        </div>

        <div
          v-for="(block, index) in schema.blocks"
          :key="block.id"
          class="block"
          :class="{
            'block-active': block.id === activeId,
            'block-image': block.type === 'IMAGE',
            'block-drop-before': dropIndex === index && dropPosition === 'before',
            'block-drop-after': dropIndex === index && dropPosition === 'after'
          }"
          :style="blockBoxStyle(block)"
          draggable="true"
          @dragstart="onBlockDragStart($event, index)"
          @dragover.prevent="onDragOverBlock($event, index)"
          @drop.prevent.stop="onDropToBlock($event, index)"
          @dragend="resetDragState"
          @click.stop="selectBlock(block.id)"
        >
          <!-- 区块内容：按类型渲染近似预览 -->
          <div class="block-body">
            <div v-if="block.type === 'TITLE'">
              <div :style="titleStyle(block)">{{ block.props.text || '标题' }}</div>
              <div v-if="block.props.subText" class="title-sub">{{ block.props.subText }}</div>
            </div>

            <div v-else-if="block.type === 'TEXT'" :style="textStyle(block)">
              {{ block.props.text || '文本' }}
            </div>

            <div v-else-if="block.type === 'FIELD'" :style="textStyle(block)">
              <template v-if="block.props.showLabel !== false && block.props.label"> {{ block.props.label }}： </template>
              <b class="field-value">{{ fieldExample(block.props.fieldKey) }}</b>
            </div>

            <div v-else-if="block.type === 'IMAGE'" class="image-slot">
              <img
                v-if="block.props.src"
                :src="block.props.src"
                alt="logo"
                :style="imageStyle(block)"
                @load="onImageLoad($event, block)"
                @mousedown.stop.prevent="startImageMove($event, block)"
              />
              <div v-else class="image-placeholder" :style="imageStyle(block)" @mousedown.stop.prevent="startImageMove($event, block)">LOGO</div>
            </div>

            <table v-else-if="block.type === 'TABLE'" class="preview-table">
              <thead v-if="block.props.showHeader !== false">
                <tr>
                  <th v-for="(col, ci) in visibleColumns(block)" :key="col.key" :style="columnStyle(col)">
                    {{ col.label }}
                    <!-- 表头分隔线：拖动直接调列宽，比在属性面板输数字直观 -->
                    <span
                      v-if="ci < visibleColumns(block).length - 1"
                      class="col-resizer"
                      @mousedown.stop.prevent="startColumnResize($event, block, ci)"
                      @click.stop
                    ></span>
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in 2" :key="row">
                  <td v-for="col in visibleColumns(block)" :key="col.key" :style="columnStyle(col)">
                    {{ columnExample(col) }}
                  </td>
                </tr>
              </tbody>
            </table>

            <hr
              v-else-if="block.type === 'DIVIDER'"
              :style="{
                border: 'none',
                borderTop: `${block.props.thickness || 1}px solid ${block.props.color || '#333'}`,
                margin: `${block.style?.marginTop ?? 10}px 0 ${block.style?.marginBottom ?? 10}px`
              }"
            />

            <div v-else-if="block.type === 'SIGNATURE'" class="sign-row">
              <span v-for="(label, i) in block.props.labels || []" :key="i">{{ label }}：______</span>
            </div>

            <div v-else-if="block.type === 'SPACER'" :style="{ height: (block.props.height || 12) + 'px' }"></div>

            <div v-else-if="block.type === 'PAGE_BREAK'" class="page-break-line">
              <span>分页符</span>
            </div>
          </div>

          <!-- 选中态：右侧手柄改区块宽度（IMAGE 除外——它的宽度就是图片宽度，用角标手柄改） -->
          <div
            v-if="block.id === activeId && block.type !== 'IMAGE'"
            class="resize-handle"
            title="拖动调整区块宽度"
            @mousedown.stop.prevent="startResize($event, block)"
          ></div>

          <!-- Logo 单独一套手柄：改的是图片自身像素尺寸，与区块占位宽度解耦 -->
          <template v-if="block.id === activeId && block.type === 'IMAGE'">
            <div class="resize-corner" title="拖动调整 Logo 尺寸" @mousedown.stop.prevent="startImageResize($event, block)"></div>
            <span class="size-badge">{{ imageWidth(block) }}px</span>
          </template>

          <span v-if="block.id === activeId" class="block-tag">{{ blockLabel(block.type) }}</span>

          <!-- 选中态：左上角抓手，按住左右挪动区块位置（与排序拖拽分开，避免冲突） -->
          <div v-if="block.id === activeId" class="move-grip" title="按住左右拖动，调整区块位置" @mousedown.stop.prevent="startMove($event, block)">
            ⠿
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts" name="DesignCanvas">
import { computed, ref } from 'vue';
import type { PrintDesignBlock, PrintDesignSchema, PrintTableColumn } from '@/api/erp/types';
import { PAPER_SIZES, blockLabel } from './designerOptions';

const props = defineProps<{
  schema: PrintDesignSchema;
  activeId: string | null;
  /** 字段示例值，来自后端变量字典 key → example */
  fieldExamples: Record<string, string>;
  /** 列示例值，来自后端变量字典 key → example */
  columnExamples: Record<string, string>;
}>();

const emit = defineEmits<{
  (e: 'update:activeId', id: string | null): void;
  (e: 'reorder', from: number, to: number): void;
  (e: 'drop-new', type: string, index: number): void;
  /** 区块占位宽度（百分比） */
  (e: 'layout-resize', id: string, widthPct: number): void;
  /** Logo 像素宽度 */
  (e: 'image-resize', id: string, widthPx: number): void;
  /** 表格某一列的宽度（百分比） */
  (e: 'column-resize', blockId: string, columnIndex: number, widthPct: number): void;
  /** 区块左偏移（占纸宽百分比） */
  (e: 'block-move', id: string, offsetPct: number): void;
  /** Logo 原图宽高比 */
  (e: 'image-ratio', id: string, ratio: number): void;
  /** Logo 距容器左侧像素（自定义定位） */
  (e: 'image-move', id: string, offsetPx: number): void;
}>();

const DRAG_MIME = 'application/x-erp-print-block';

/** 拖拽状态：区分「从组件库拖入」与「画布内重排」 */
const draggingBlockIndex = ref<number | null>(null);
const dropIndex = ref<number | null>(null);
const dropPosition = ref<'before' | 'after'>('before');

const paperStyle = computed(() => {
  const size = PAPER_SIZES[props.schema.paper || 'A4'] || PAPER_SIZES.A4;
  // 画布按毫米等比缩放到屏幕：1mm ≈ 2.6px，A4 宽约 546px，适合中栏
  const scale = 2.6;
  return {
    width: `${size.width * scale}px`,
    minHeight: `${size.height * scale * 0.6}px`,
    padding: `${(props.schema.pageMargin || 12) * scale}px`
  };
});

const selectBlock = (id: string) => {
  emit('update:activeId', id);
};

const onBlockDragStart = (ev: DragEvent, index: number) => {
  draggingBlockIndex.value = index;
  if (ev.dataTransfer) {
    ev.dataTransfer.effectAllowed = 'move';
    // 标记来源为画布内重排，避免画布误判为新增
    ev.dataTransfer.setData('text/plain', 'reorder');
  }
};

const onDragOverBlock = (ev: DragEvent, index: number) => {
  if (draggingBlockIndex.value === null) return;
  if (draggingBlockIndex.value === index) {
    dropIndex.value = null;
    return;
  }
  // 按鼠标在区块上半/下半决定插入到前还是后
  const rect = (ev.currentTarget as HTMLElement).getBoundingClientRect();
  dropPosition.value = ev.clientY - rect.top < rect.height / 2 ? 'before' : 'after';
  dropIndex.value = index;
};

const onDropToBlock = (ev: DragEvent, index: number) => {
  const newType = ev.dataTransfer?.getData(DRAG_MIME);
  const from = draggingBlockIndex.value;
  if (newType) {
    // 组件库拖入：插到当前区块前/后
    const target = dropPosition.value === 'before' ? index : index + 1;
    emit('drop-new', newType, target);
  } else if (from !== null) {
    let to = dropPosition.value === 'before' ? index : index + 1;
    if (from < to) to -= 1;
    if (from !== to) emit('reorder', from, to);
  }
  resetDragState();
};

const onDragOverPaper = () => {
  // 落在空白区域时取消区块级高亮，表示追加到末尾
  if (draggingBlockIndex.value === null) return;
  dropIndex.value = null;
};

const onDropToPaper = (ev: DragEvent) => {
  const newType = ev.dataTransfer?.getData(DRAG_MIME);
  if (newType) {
    emit('drop-new', newType, props.schema.blocks.length);
  } else if (draggingBlockIndex.value !== null) {
    const from = draggingBlockIndex.value;
    const to = props.schema.blocks.length - 1;
    if (from !== to) emit('reorder', from, to);
  }
  resetDragState();
};

const resetDragState = () => {
  draggingBlockIndex.value = null;
  dropIndex.value = null;
};

/* ------------------------------ 尺寸拖拽 ------------------------------ */

/**
 * 区块盒尺寸拖拽。
 *
 * 改的是 props.layoutWidth（百分比），作用在最外层 .block 盒上，
 * 所以选中框会跟着一起变窄，而不是只缩内容、外面仍占满一行。
 *
 * 与 props.width 严格分开：width 是 Logo 等组件的像素尺寸。
 */
let resizeStartX = 0;
let resizeStartWidth = 0;
let resizeBlockId = '';
let resizePct = 0;

const startResize = (ev: MouseEvent, block: PrintDesignBlock) => {
  resizeBlockId = block.id;
  resizeStartX = ev.clientX;
  resizeStartWidth = layoutWidthOf(block);
  resizePct = resizeStartWidth;

  const onMove = (e: MouseEvent) => {
    const deltaPx = e.clientX - resizeStartX;
    // 画布缩放 2.6px/mm，纸张宽度换算成百分比
    const size = PAPER_SIZES[props.schema.paper || 'A4'] || PAPER_SIZES.A4;
    const paperPx = size.width * 2.6;
    const deltaPct = (deltaPx / paperPx) * 100;
    resizePct = Math.max(10, Math.min(100, Math.round(resizeStartWidth + deltaPct)));
  };
  const onUp = () => {
    document.removeEventListener('mousemove', onMove);
    document.removeEventListener('mouseup', onUp);
    emit('layout-resize', resizeBlockId, resizePct);
  };
  document.addEventListener('mousemove', onMove);
  document.addEventListener('mouseup', onUp);
};

/**
 * 区块水平拖动：按住卡片内空白处左右挪，改 props.offsetX（占纸宽百分比）。
 * 上限按剩余空间算，保证整块不超出纸张右边缘。
 */
let posStartX = 0;
let posStartOffset = 0;
let posBlockId = '';
let posNewOffset = 0;

const startMove = (ev: MouseEvent, block: PrintDesignBlock) => {
  const paperEl = (ev.currentTarget as HTMLElement).closest('.paper') as HTMLElement | null;
  const paperPx = paperEl?.getBoundingClientRect().width ?? 546;
  const widthPct = layoutWidthOf(block);
  const maxOffset = Math.max(0, Math.round(((100 - widthPct) / 100) * 1000) / 10);

  posBlockId = block.id;
  posStartX = ev.clientX;
  posStartOffset = offsetXOf(block);
  posNewOffset = posStartOffset;

  const onMove = (e: MouseEvent) => {
    const deltaPct = ((e.clientX - posStartX) / paperPx) * 100;
    posNewOffset = Math.max(0, Math.min(maxOffset, Math.round(posStartOffset + deltaPct)));
  };
  const onUp = () => {
    document.removeEventListener('mousemove', onMove);
    document.removeEventListener('mouseup', onUp);
    if (posNewOffset !== posStartOffset) {
      emit('block-move', posBlockId, posNewOffset);
    }
  };
  document.addEventListener('mousemove', onMove);
  document.addEventListener('mouseup', onUp);
};

/**
 * Logo 尺寸拖拽：右下角手柄，等比缩放到像素宽度。
 * 图片高度随宽度自适应（后端按 width 输出），所以只算一个维度。
 */
let imgStartX = 0;
let imgStartWidth = 120;
let imgBlockId = '';
let imgWidth = 120;

const startImageResize = (ev: MouseEvent, block: PrintDesignBlock) => {
  imgBlockId = block.id;
  imgStartX = ev.clientX;
  imgStartWidth = imageWidth(block);
  imgWidth = imgStartWidth;

  const onMove = (e: MouseEvent) => {
    const delta = e.clientX - imgStartX;
    imgWidth = Math.max(20, Math.min(600, Math.round(imgStartWidth + delta)));
  };
  const onUp = () => {
    document.removeEventListener('mousemove', onMove);
    document.removeEventListener('mouseup', onUp);
    emit('image-resize', imgBlockId, imgWidth);
  };
  document.addEventListener('mousemove', onMove);
  document.addEventListener('mouseup', onUp);
};

/**
 * Logo 直接拖动：按住图片左右拖，实时改 style.offsetPx（距左侧像素）。
 *
 * 区块宽度由图片尺寸决定，offsetPx 同时就是区块自身的位置，
 * 所以拖图片等于拖整个区块，两者不会脱节。
 */
let imageMoveStartX = 0;
let imageMoveStartPx = 0;
let imageMoveBlockId = '';
let imageMovePx = 0;

const startImageMove = (ev: MouseEvent, block: PrintDesignBlock) => {
  const paperEl = (ev.currentTarget as HTMLElement).closest('.paper') as HTMLElement | null;
  const paperPx = paperEl?.getBoundingClientRect().width ?? 546;
  const imgWidthPx = imageWidth(block);

  imageMoveBlockId = block.id;
  imageMoveStartX = ev.clientX;
  imageMoveStartPx = imageOffsetPx(block);
  imageMovePx = imageMoveStartPx;

  const onMove = (e: MouseEvent) => {
    const delta = e.clientX - imageMoveStartX;
    // 上限按纸张宽度减图片宽度算，保证整块不超出右边缘
    const maxPx = Math.max(0, Math.round(paperPx - imgWidthPx));
    imageMovePx = Math.max(0, Math.min(maxPx, Math.round(imageMoveStartPx + delta)));
  };
  const onUp = () => {
    document.removeEventListener('mousemove', onMove);
    document.removeEventListener('mouseup', onUp);
    emit('image-move', imageMoveBlockId, imageMovePx);
  };
  document.addEventListener('mousemove', onMove);
  document.addEventListener('mouseup', onUp);
};

/* ------------------------------ 表格列宽拖拽 ------------------------------ */

/**
 * 拖动表头分隔线调整列宽。
 *
 * 只调当前列的 width，相邻列保持自适应（不设 width），
 * 这样表格总宽仍为 100%，不会出现横向溢出。
 */
let colStartX = 0;
let colStartWidth = 0;
let colData: { blockId: string; index: number } | null = null;
let colNewWidth = 0;

const startColumnResize = (ev: MouseEvent, block: PrintDesignBlock, visibleIndex: number) => {
  const cols = visibleColumns(block);
  const col = cols[visibleIndex];
  if (!col) return;
  // visibleIndex 是过滤后的下标，需要换算回原始 columns 里的位置
  const rawIndex = (block.props.columns || []).findIndex((c: PrintTableColumn) => c.key === col.key);
  if (rawIndex < 0) return;

  colData = { blockId: block.id, index: rawIndex };
  colStartX = ev.clientX;
  colStartWidth = Number(col.width ?? 12);
  colNewWidth = colStartWidth;

  const th = ev.currentTarget as HTMLElement;
  const tableEl = th.closest('table');
  const tablePx = tableEl?.getBoundingClientRect().width ?? 500;

  const onMove = (e: MouseEvent) => {
    const deltaPct = ((e.clientX - colStartX) / tablePx) * 100;
    colNewWidth = Math.max(3, Math.min(80, Math.round(colStartWidth + deltaPct)));
  };
  const onUp = () => {
    document.removeEventListener('mousemove', onMove);
    document.removeEventListener('mouseup', onUp);
    if (colData) {
      emit('column-resize', colData.blockId, colData.index, colNewWidth);
      colData = null;
    }
  };
  document.addEventListener('mousemove', onMove);
  document.addEventListener('mouseup', onUp);
};

/* ------------------------------ 样式 ------------------------------ */

const imageWidth = (block: PrintDesignBlock): number => {
  const w = Number(block.props?.width);
  return Number.isFinite(w) && w > 0 ? w : 120;
};

const imageAlign = (block: PrintDesignBlock) => block.style?.textAlign || 'center';

/** Logo 距左侧像素（自定义定位） */
const imageOffsetPx = (block: PrintDesignBlock): number => {
  const px = Number(block.style?.offsetPx);
  return Number.isFinite(px) && px > 0 ? px : 0;
};

const imageStyle = (block: PrintDesignBlock) => ({
  // 显式像素宽：区块盒是 auto 宽度，若这里写 100% 会退化成图片固有宽度，改设置不生效
  width: `${imageWidth(block)}px`,
  height: 'auto',
  maxWidth: '100%',
  display: 'block'
});

/** 图片加载后按原始宽高比反推高度，拖角标时才能真正等比缩放 */
const onImageLoad = (ev: Event, block: PrintDesignBlock) => {
  const img = ev.target as HTMLImageElement;
  if (!img.naturalWidth) return;
  emit('image-ratio', block.id, img.naturalHeight / img.naturalWidth);
};

/** 区块占纸宽百分比，默认 100（整行） */
const layoutWidthOf = (block: PrintDesignBlock): number => {
  const w = Number(block.props?.layoutWidth);
  return Number.isFinite(w) && w > 0 ? Math.min(100, w) : 100;
};

/** 区块左偏移（占纸宽百分比） */
const offsetXOf = (block: PrintDesignBlock): number => {
  const o = Number(block.props?.offsetX);
  return Number.isFinite(o) && o > 0 ? o : 0;
};

/**
 * 区块盒样式：宽度与左偏移都落在这里，选中框才能跟着区块一起变。
 *
 * IMAGE 特殊：区块尺寸直接由图片决定（宽 = props.width，左距 = style.offsetPx），
 * 不再单独维护 layoutWidth / offsetX —— 否则调完图片还要再调一遍区块，两套值必然打架。
 *
 * 其余区块仍用 layoutWidth（占纸宽 %）与 offsetX（左缩进 %）。
 * 窄区块用 inline-block 可并排，整行区块用 block 强制换行。
 */
const blockBoxStyle = (block: PrintDesignBlock) => {
  const style: Record<string, string> = {};
  const mt = block.style?.marginTop;
  const mb = block.style?.marginBottom;
  if (mt != null) style.marginTop = `${mt}px`;
  if (mb != null) style.marginBottom = `${mb}px`;

  if (block.type === 'IMAGE') {
    style.display = 'inline-block';
    style.verticalAlign = 'top';
    // 宽度留 auto，由图片自身像素宽撑开：区块盒的 padding/border 会计入，
    // 图片改多大区块就多宽，不会出现「区块宽 120、图片 110」这种错位
    style.width = 'auto';
    const left = imageOffsetPx(block);
    if (left > 0) {
      style.marginLeft = `${left}px`;
    }
    return style;
  }

  const width = layoutWidthOf(block);
  const offset = offsetXOf(block);
  if (width < 100) {
    style.width = `${width}%`;
    style.display = 'inline-block';
    style.verticalAlign = 'top';
  } else {
    style.display = 'block';
    style.width = '100%';
  }
  if (offset > 0) {
    style.marginLeft = `${offset}%`;
  }
  return style;
};

const titleStyle = (block: PrintDesignBlock) => ({
  textAlign: block.style?.textAlign || 'center',
  fontSize: `${block.style?.fontSize || 20}px`,
  fontWeight: block.style?.fontWeight === 'bold' ? 'bold' : 600,
  letterSpacing: '4px',
  color: safeColor(block.style?.color),
  margin: '0 0 8px'
});

const textStyle = (block: PrintDesignBlock) => ({
  textAlign: block.style?.textAlign || 'left',
  fontSize: `${block.style?.fontSize || 12}px`,
  fontWeight: block.style?.fontWeight === 'bold' ? 'bold' : 'normal',
  color: safeColor(block.style?.color)
});

const safeColor = (color?: string) => (/^#[0-9A-Fa-f]{3,8}$/.test(color ?? '') ? color : '#000');

const visibleColumns = (block: PrintDesignBlock): PrintTableColumn[] =>
  (block.props?.columns || []).filter((c: PrintTableColumn) => c.visible !== false);

const columnStyle = (col: PrintTableColumn) => ({
  width: col.width ? `${col.width}%` : undefined,
  textAlign: col.align || (col.numeric ? 'right' : 'left')
});

const fieldExample = (key: string) => props.fieldExamples[key] ?? `{${key}}`;

const columnExample = (col: PrintTableColumn) => props.columnExamples[col.key] ?? col.label;

defineExpose({ DRAG_MIME });
</script>

<style scoped lang="scss">
.canvas-wrap {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: var(--td-bg-color-page);
}

.canvas-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 14px;
  border-bottom: 1px solid var(--td-component-border);
  background: var(--td-bg-color-container);
}

.canvas-tip {
  font-size: 12px;
  font-weight: 500;
}

.canvas-tip-sub {
  font-size: 11px;
  color: var(--td-text-color-placeholder);
}

.canvas-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 18px;
  display: flex;
  justify-content: center;
}

.paper {
  background: #fff;
  box-shadow: 0 2px 12px rgb(0 0 0 / 12%);
  border-radius: 2px;
  position: relative;
  /* 行内区块之间的空白字符会产生缝隙，字号归零消掉，区块内部再恢复 */
  font-size: 0;
  line-height: 0;
}

.paper .block {
  font-size: 12px;
  line-height: normal;
}

.paper-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 60px 0;
  color: var(--td-text-color-placeholder);
  font-size: 13px;
  border: 1px dashed var(--td-component-border);
  border-radius: 4px;
}

.paper-empty-sub {
  font-size: 11px;
}

.block {
  position: relative;
  box-sizing: border-box;
  padding: 2px 4px;
  border: 1px solid transparent;
  border-radius: 3px;
  cursor: move;
  transition:
    border-color 0.12s,
    background 0.12s;

  &:hover {
    border-color: var(--td-brand-color-light);
    background: rgb(0 82 217 / 3%);
  }
}

.block-active {
  border-color: var(--td-brand-color);
  background: rgb(0 82 217 / 5%);
}

/**
 * 图片区块：去掉内边距与边框，让区块盒严丝合缝地包住图片。
 *
 * 选中框改用 outline 绘制 —— outline 不占布局空间，不会把区块撑宽 2px，
 * 图片多宽区块就多宽，缩放时两者始终同步。
 */
.block-image {
  padding: 0;
  border-color: transparent;

  &:hover,
  &.block-active {
    border-color: transparent;
    background: transparent;
    outline: 1px solid var(--td-brand-color);
    outline-offset: 2px;
  }
}

.block-drop-before::before,
.block-drop-after::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  height: 2px;
  background: var(--td-brand-color);
}

.block-drop-before::before {
  top: -1px;
}

.block-drop-after::after {
  bottom: -1px;
}

.block-tag {
  position: absolute;
  top: -9px;
  left: 4px;
  padding: 0 5px;
  font-size: 10px;
  line-height: 16px;
  color: #fff;
  background: var(--td-brand-color);
  border-radius: 2px;
  pointer-events: none;
}

/* 拖动区块位置：左上角抓手 */
.move-grip {
  position: absolute;
  top: -9px;
  left: 32px;
  z-index: 3;
  padding: 0 5px;
  font-size: 12px;
  line-height: 16px;
  color: #fff;
  background: var(--td-brand-color);
  border-radius: 2px;
  cursor: grab;
  opacity: 0.9;
  user-select: none;

  &:active {
    cursor: grabbing;
  }
}

.resize-handle {
  position: absolute;
  top: 50%;
  right: -4px;
  width: 8px;
  height: 26px;
  margin-top: -13px;
  cursor: ew-resize;
  background: var(--td-brand-color);
  border-radius: 2px;
  opacity: 0.85;
}

.resize-corner {
  position: absolute;
  right: -5px;
  bottom: -5px;
  width: 12px;
  height: 12px;
  cursor: nwse-resize;
  background: var(--td-brand-color);
  border: 2px solid #fff;
  border-radius: 50%;
  box-shadow: 0 0 0 1px var(--td-brand-color);
}

.size-badge {
  position: absolute;
  right: -4px;
  bottom: 12px;
  padding: 0 4px;
  font-size: 10px;
  line-height: 14px;
  color: #fff;
  background: rgb(0 0 0 / 55%);
  border-radius: 2px;
  pointer-events: none;
  white-space: nowrap;
}

.title-sub {
  text-align: center;
  color: #666;
  font-size: 12px;
  margin-bottom: 6px;
}

.field-value {
  border-bottom: 1px dashed #ddd;
}

.image-slot {
  line-height: 0;
}

.image-placeholder {
  display: block;
  height: 40px;
  line-height: 40px;
  border: 1px dashed #bbb;
  color: #999;
  font-size: 12px;
  text-align: center;
}

.preview-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;

  th,
  td {
    border: 1px solid #333;
    padding: 4px 5px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  th {
    position: relative;
    background: #f0f0f0;
    font-weight: 600;
  }
}

/* 表头右边缘的列宽拖拽条 */
.col-resizer {
  position: absolute;
  top: 0;
  right: -3px;
  z-index: 2;
  width: 7px;
  height: 100%;
  cursor: col-resize;

  &::after {
    content: '';
    position: absolute;
    top: 15%;
    left: 3px;
    width: 1px;
    height: 70%;
    background: var(--td-gray-color-6);
  }

  &:hover {
    background: rgb(0 82 217 / 18%);

    &::after {
      background: var(--td-brand-color);
    }
  }
}

.sign-row {
  display: flex;
  justify-content: space-between;
  margin-top: 24px;
  font-size: 12px;
}

.page-break-line {
  border-top: 1px dashed var(--td-warning-color);
  text-align: center;
  color: var(--td-warning-color);
  font-size: 11px;

  span {
    position: relative;
    top: -9px;
    background: #fff;
    padding: 0 8px;
  }
}
</style>
