<template>
  <div class="prop-panel">
    <div class="prop-title">
      <span>属性</span>
      <span v-if="block" class="prop-title-type">{{ blockLabel(block.type) }}</span>
    </div>

    <div v-if="!block" class="prop-empty">
      <span>在画布中选中一个区块</span>
      <span class="prop-empty-sub">选中后可在此调整内容与样式</span>
    </div>

    <div v-else class="prop-scroll">
      <!-- 区块内容属性 -->
      <template v-if="block.type === 'TITLE'">
        <t-form-item label="标题文字" label-align="top">
          <t-input :value="block.props.text" @change="(v: any) => setProp('text', v)" />
        </t-form-item>
        <t-form-item label="副标题" label-align="top">
          <t-input :value="block.props.subText" placeholder="可留空" @change="(v: any) => setProp('subText', v)" />
        </t-form-item>
      </template>

      <template v-else-if="block.type === 'TEXT'">
        <t-form-item label="文本内容" label-align="top">
          <t-textarea :value="block.props.text" :autosize="{ minRows: 3, maxRows: 8 }" @change="(v: any) => setProp('text', v)" />
        </t-form-item>
      </template>

      <template v-else-if="block.type === 'FIELD'">
        <t-form-item label="绑定字段" label-align="top">
          <t-select :value="block.props.fieldKey" :options="fieldOptions" filterable @change="(v: any) => onFieldChange(v)" />
        </t-form-item>
        <t-form-item label="显示标签" label-align="top">
          <t-switch :value="block.props.showLabel !== false" @change="(v: any) => setProp('showLabel', v)" />
        </t-form-item>
        <t-form-item label="标签文字" label-align="top">
          <t-input :value="block.props.label" @change="(v: any) => setProp('label', v)" />
        </t-form-item>
      </template>

      <template v-else-if="block.type === 'IMAGE'">
        <t-form-item label="Logo 图片" label-align="top">
          <t-upload :auto-upload="false" accept="image/*" :max="1" :show-upload-progress="false" @change="onLogoChange" />
        </t-form-item>
        <t-form-item label="尺寸（px）" label-align="top">
          <div class="size-row">
            <t-input-number :value="imageWidth" :min="20" :max="600" :step="10" @change="(v: any) => setProp('width', v)" />
            <span class="size-hint">宽</span>
            <t-input-number
              :value="imageHeight"
              :min="0"
              :max="600"
              :step="10"
              placeholder="自动"
              @change="(v: any) => setProp('height', v || null)"
            />
            <span class="size-hint">高</span>
          </div>
        </t-form-item>
        <div class="prop-tip">高度留空则按原图比例自适应；也可直接在画布上拖右下角圆点缩放。 区块尺寸与位置自动跟随图片，无需单独设置。</div>
      </template>

      <template v-else-if="block.type === 'TABLE'">
        <t-form-item label="数据来源" label-align="top">
          <t-select :value="block.props.dataSource || 'items'" :options="dataSourceOptions" @change="(v: any) => onDataSourceChange(v)" />
        </t-form-item>
        <t-form-item label="显示表头" label-align="top">
          <t-switch :value="block.props.showHeader !== false" @change="(v: any) => setProp('showHeader', v)" />
        </t-form-item>
        <TableColumnEditor :columns="block.props.columns || []" :available="availableColumns" @change="(cols) => setProp('columns', cols)" />
      </template>

      <template v-else-if="block.type === 'DIVIDER'">
        <t-form-item label="线宽（px）" label-align="top">
          <t-input-number :value="block.props.thickness || 1" :min="1" :max="8" @change="(v: any) => setProp('thickness', v)" />
        </t-form-item>
        <t-form-item label="颜色" label-align="top">
          <t-input :value="block.props.color || '#333'" @change="(v: any) => setProp('color', v)" />
        </t-form-item>
      </template>

      <template v-else-if="block.type === 'SIGNATURE'">
        <t-form-item label="签章项" label-align="top">
          <div class="sign-labels">
            <div v-for="(label, i) in block.props.labels || []" :key="i" class="sign-label-row">
              <t-input size="small" :value="label" @change="(v: any) => setLabel(i, v)" />
              <t-button size="small" variant="text" theme="danger" @click="removeLabel(i)">删除</t-button>
            </div>
            <t-button size="small" variant="outline" @click="addLabel">+ 添加签章项</t-button>
          </div>
        </t-form-item>
      </template>

      <template v-else-if="block.type === 'SPACER'">
        <t-form-item label="高度（px）" label-align="top">
          <t-input-number :value="block.props.height || 12" :min="0" :max="300" @change="(v: any) => setProp('height', v)" />
        </t-form-item>
      </template>

      <template v-else-if="block.type === 'PAGE_BREAK'">
        <div class="prop-tip">该区块之后强制换页，无其他可配置项。</div>
      </template>

      <!-- 通用样式 -->
      <div v-if="block.type !== 'TABLE' && block.type !== 'PAGE_BREAK'" class="prop-section">
        <div class="prop-section-title">样式</div>
        <!-- IMAGE 的尺寸与位置由图片自身决定，不再单独提供区块宽度/缩进，避免两套值打架 -->
        <t-form-item v-if="block.type !== 'IMAGE'" label="区块宽度（占纸宽 %）" label-align="top">
          <div class="width-row">
            <t-slider :value="layoutWidth" :min="10" :max="100" :step="5" @change="(v: any) => setProp('layoutWidth', v)" />
            <t-input-number
              :value="layoutWidth"
              :min="10"
              :max="100"
              :step="5"
              size="small"
              class="width-num"
              @change="(v: any) => setProp('layoutWidth', v ?? 100)"
            />
          </div>
        </t-form-item>
        <t-form-item v-if="block.type !== 'IMAGE'" label="水平位置（左缩进 %）" label-align="top">
          <div class="width-row">
            <t-slider :value="offsetX" :min="0" :max="maxOffset" :step="1" @change="(v: any) => setProp('offsetX', v)" />
            <t-input-number
              :value="offsetX"
              :min="0"
              :max="maxOffset"
              :step="1"
              size="small"
              class="width-num"
              @change="(v: any) => setProp('offsetX', v ?? 0)"
            />
          </div>
        </t-form-item>
        <t-form-item label="字号（px）" label-align="top">
          <t-input-number :value="block.style?.fontSize ?? 12" :min="6" :max="40" @change="(v: any) => setStyle('fontSize', v)" />
        </t-form-item>
        <t-form-item v-if="hasTextAlign" label="对齐" label-align="top">
          <t-radio-group :value="alignMode" variant="default-filled" @change="onAlignModeChange">
            <t-radio-button value="left">左</t-radio-button>
            <t-radio-button value="center">中</t-radio-button>
            <t-radio-button value="right">右</t-radio-button>
            <t-radio-button value="custom">自定义</t-radio-button>
          </t-radio-group>
        </t-form-item>
        <t-form-item v-if="hasTextAlign && alignMode === 'custom'" label="距纸左边（px）" label-align="top">
          <div class="width-row">
            <t-slider :value="offsetPx" :min="0" :max="2000" :step="5" @change="(v: any) => setStyle('offsetPx', v)" />
            <t-input-number
              :value="offsetPx"
              :min="0"
              :max="2000"
              :step="5"
              size="small"
              class="width-num"
              @change="(v: any) => setStyle('offsetPx', v ?? 0)"
            />
          </div>
        </t-form-item>
        <t-form-item v-if="hasTextAlign" label="文字颜色" label-align="top">
          <div class="color-row">
            <input type="color" class="color-pick" :value="block.style?.color || '#000000'" @input="(e: any) => setStyle('color', e.target.value)" />
            <t-input size="small" :value="block.style?.color || '#000000'" @change="(v: any) => setStyle('color', v)" />
          </div>
        </t-form-item>
        <t-form-item label="加粗" label-align="top">
          <t-switch :value="block.style?.fontWeight === 'bold'" @change="(v: any) => setStyle('fontWeight', v ? 'bold' : 'normal')" />
        </t-form-item>
        <t-form-item label="上下间距（px）" label-align="top">
          <div class="margin-row">
            <t-input-number
              :value="block.style?.marginTop ?? 0"
              :min="0"
              :max="200"
              placeholder="上"
              @change="(v: any) => setStyle('marginTop', v ?? 0)"
            />
            <t-input-number
              :value="block.style?.marginBottom ?? 0"
              :min="0"
              :max="200"
              placeholder="下"
              @change="(v: any) => setStyle('marginBottom', v ?? 0)"
            />
          </div>
        </t-form-item>
      </div>

      <div class="prop-actions">
        <t-button size="small" variant="outline" theme="default" @click="emit('duplicate')">复制</t-button>
        <t-button size="small" variant="outline" theme="danger" @click="emit('remove')">删除区块</t-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts" name="PropertyPanel">
import { computed, getCurrentInstance, nextTick, onBeforeUnmount, watch } from 'vue';
import type { PrintDesignBlock, PrintDesignSchema, PrintVariable } from '@/api/erp/types';
import { blockLabel, cloneSchema } from './designerOptions';
import TableColumnEditor from './TableColumnEditor.vue';

const { proxy } = getCurrentInstance() as any;

const props = defineProps<{
  schema: PrintDesignSchema;
  block: PrintDesignBlock | null;
  /** 变量字典：字段列表 */
  fields: PrintVariable[];
  /** 表格数据源 */
  tableSources: PrintVariable[];
  /** 数据源 → 可选列 */
  columnsBySource: Record<string, PrintVariable[]>;
}>();

const emit = defineEmits<{
  (e: 'update-schema', schema: PrintDesignSchema): void;
  (e: 'remove'): void;
  (e: 'duplicate'): void;
}>();

const fieldOptions = computed(() => {
  const groups = new Map<string, PrintVariable[]>();
  for (const f of props.fields) {
    const g = f.group || '其他';
    if (!groups.has(g)) groups.set(g, []);
    groups.get(g)!.push(f);
  }
  return [...groups.entries()].map(([group, children]) => ({
    label: group,
    value: `__group_${group}`,
    children: children.map((c) => ({ label: `${c.label}（${c.key}）`, value: c.key }))
  }));
});

const dataSourceOptions = computed(() => props.tableSources.map((s) => ({ label: s.label, value: s.key })));

const availableColumns = computed(() => {
  const source = props.block?.props?.dataSource || 'items';
  return props.columnsBySource[source] || [];
});

const hasTextAlign = computed(() => ['TITLE', 'TEXT', 'FIELD', 'IMAGE'].includes(props.block?.type || ''));

const imageWidth = computed(() => {
  const w = Number(props.block?.props?.width);
  return Number.isFinite(w) && w > 0 ? w : 120;
});

const imageHeight = computed(() => {
  const h = Number(props.block?.props?.height);
  return Number.isFinite(h) && h > 0 ? h : undefined;
});

/** 区块占纸宽百分比，默认 100（整行） */
const layoutWidth = computed(() => {
  const w = Number(props.block?.props?.layoutWidth);
  return Number.isFinite(w) && w > 0 ? Math.min(100, w) : 100;
});

/** 水平位置：左缩进占纸宽百分比 */
const offsetX = computed(() => {
  const o = Number(props.block?.props?.offsetX);
  return Number.isFinite(o) && o > 0 ? o : 0;
});

/** 区块左移上限，保证右边缘不越界 */
const maxOffset = computed(() => Math.max(0, 100 - layoutWidth.value));

/** 对齐模式：设了 offsetPx 就是自定义定位，否则看 textAlign */
const alignMode = computed(() => {
  const px = Number(props.block?.style?.offsetPx);
  return Number.isFinite(px) && px >= 0 && props.block?.style?.offsetPx != null ? 'custom' : props.block?.style?.textAlign || 'left';
});

/** 自定义左偏移像素 */
const offsetPx = computed(() => {
  const px = Number(props.block?.style?.offsetPx);
  return Number.isFinite(px) && px > 0 ? px : 0;
});

const updateBlock = (nextBlock: PrintDesignBlock) => {
  const { schema } = props;
  const index = schema.blocks.findIndex((block) => block.id === nextBlock.id);
  if (index < 0) return;
  const blocks = [...schema.blocks];
  blocks[index] = nextBlock;
  emit('update-schema', cloneSchema({ ...schema, blocks }));
};

const updateProps = (patch: PrintDesignBlock['props']) => {
  const block = props.block;
  if (!block) return;
  updateBlock({ ...block, props: { ...block.props, ...patch } });
};

const setProp = (key: string, value: any) => updateProps({ [key]: value });

const updateStyle = (patch: PrintDesignBlock['style']) => {
  const block = props.block;
  if (!block) return;
  const style = { ...(block.style || {}) };
  // null 表示删除该样式：保留空键会让「有值判断」类逻辑误判
  for (const [key, value] of Object.entries(patch)) {
    if (value == null) {
      delete style[key];
    } else {
      style[key] = value;
    }
  }
  updateBlock({ ...block, style });
};

const setStyle = (key: string, value: any) => updateStyle({ [key]: value });

/**
 * 切换对齐方式。
 * 选左中右时清掉 offsetPx，否则残留的像素值会一直压过对齐设置。
 */
const onAlignModeChange = (mode: any) => {
  if (mode === 'custom') {
    setStyle('offsetPx', offsetPx.value);
    return;
  }
  updateStyle({ offsetPx: null, textAlign: mode });
};

/** 切换字段时同步默认标签，减少手工输入 */
const onFieldChange = (fieldKey: string) => {
  const block = props.block;
  if (!block) return;
  const found = props.fields.find((f) => f.key === fieldKey);
  updateProps({ fieldKey, label: found?.label ?? block.props.label });
};

/** 切换数据源时重置列定义，避免把 items 的列套到 statement.items 上 */
const onDataSourceChange = (dataSource: string) => {
  if (!props.block) return;
  const cols = props.columnsBySource[dataSource] || [];
  const numericKeys = ['qty', 'qtyText', 'price', 'amount', 'settled', 'unsettled'];
  updateProps({
    dataSource,
    columns: cols.map((c) => ({
      key: c.key,
      label: c.label,
      width: null,
      align: numericKeys.includes(c.key) ? 'right' : 'left',
      visible: true,
      numeric: numericKeys.includes(c.key)
    }))
  });
};

/**
 * Logo 上传：压缩后读成 data: URI 内嵌。
 *
 * 原图直传会把几 MB 的 base64 塞进组件树，保存和预览都要来回传一遍，
 * 既慢又容易在网关/超时处失败。打印时 Logo 通常只有 30~50mm 宽，
 * 按 300dpi 算约 470px 就足够，这里统一压到 800px 以内再编码。
 *
 * 后端 TemplateSanitizer 只允许 data: 协议的 img src，外链会被拒绝。
 */
const LOGO_MAX_EDGE = 800;

const compressImage = (file: File): Promise<string> =>
  new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error('图片读取失败'));
    reader.onload = () => {
      const raw = String(reader.result || '');
      // SVG 是矢量，压缩会失真，直接保留
      if (file.type === 'image/svg+xml') {
        resolve(raw);
        return;
      }
      const img = new Image();
      img.onerror = () => reject(new Error('图片解析失败'));
      img.onload = () => {
        const scale = Math.min(1, LOGO_MAX_EDGE / Math.max(img.width, img.height));
        const w = Math.max(1, Math.round(img.width * scale));
        const h = Math.max(1, Math.round(img.height * scale));
        const canvas = document.createElement('canvas');
        canvas.width = w;
        canvas.height = h;
        const ctx = canvas.getContext('2d');
        if (!ctx) {
          resolve(raw);
          return;
        }
        ctx.drawImage(img, 0, 0, w, h);
        // 透明背景的 PNG 用 PNG 保住透明通道，其余走 JPEG 压体积
        const hasAlpha = file.type === 'image/png' || file.type === 'image/webp';
        const out = hasAlpha ? canvas.toDataURL('image/png') : canvas.toDataURL('image/jpeg', 0.9);
        // 压缩反而更大时（如小图）用原图
        resolve(out.length < raw.length ? out : raw);
      };
      img.src = raw;
    };
    reader.readAsDataURL(file);
  });

// 同一块的同步编辑会替换对象；用 id 跟踪选择，且区块必须仍在 schema 中。
const selectedImageId = computed(() => {
  const block = props.block;
  if (block?.type !== 'IMAGE') return null;
  return props.schema.blocks.some((item) => item.id === block.id && item.type === 'IMAGE') ? block.id : null;
});
let logoRequest = 0;
let logoDisposed = false;
watch(
  selectedImageId,
  () => {
    ++logoRequest;
  },
  { flush: 'sync' }
);
onBeforeUnmount(() => {
  logoDisposed = true;
});

const onLogoChange = async (files: { raw?: File }[]) => {
  const request = ++logoRequest; // 清空文件列表也使上一次请求失效。
  const id = selectedImageId.value;
  const file = files?.[0]?.raw;
  if (!file || id == null || logoDisposed) return;
  const isCurrent = () => !logoDisposed && request === logoRequest && selectedImageId.value === id;
  try {
    // 先让同一轮待更新的选择和 schema 传到面板，再处理成功或失败。
    const uri = await compressImage(file).finally(() => nextTick());
    if (isCurrent()) setProp('src', uri);
  } catch (e: any) {
    if (isCurrent()) proxy?.$modal?.msgWarning?.(e?.message || 'Logo 处理失败，请换一张图片');
  }
};

// labels 是 any[]（props 为弱类型 Map），v-for 的 index 可能是 string | number，统一取数值
const setLabel = (index: string | number, value: string) => {
  if (!props.block) return;
  const labels = [...(props.block.props.labels || [])];
  labels[Number(index)] = value;
  setProp('labels', labels);
};

const removeLabel = (index: string | number) => {
  if (!props.block) return;
  const labels = [...(props.block.props.labels || [])];
  labels.splice(Number(index), 1);
  setProp('labels', labels);
};

const addLabel = () => {
  if (!props.block) return;
  setProp('labels', [...(props.block.props.labels || []), '签章']);
};
</script>

<style scoped lang="scss">
.prop-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  border-left: 1px solid var(--td-component-border);
  background: var(--td-bg-color-container);
}

.prop-title {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 14px 8px;
  font-size: 13px;
  font-weight: 600;
  border-bottom: 1px solid var(--td-component-border);
}

.prop-title-type {
  padding: 0 6px;
  font-size: 11px;
  font-weight: 400;
  line-height: 18px;
  color: var(--td-brand-color);
  border: 1px solid var(--td-brand-color-light);
  border-radius: 2px;
}

.prop-empty {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  font-size: 12px;
  color: var(--td-text-color-placeholder);
}

.prop-empty-sub {
  font-size: 11px;
}

.prop-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 12px 14px;
}

.prop-section {
  margin-top: 14px;
  padding-top: 10px;
  border-top: 1px solid var(--td-component-border);
}

.prop-section-title {
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
}

.prop-tip {
  margin-top: 6px;
  font-size: 11px;
  color: var(--td-text-color-placeholder);
}

.size-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.color-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.color-pick {
  width: 34px;
  height: 30px;
  padding: 2px;
  border: 1px solid var(--td-component-border);
  border-radius: 3px;
  background: #fff;
  cursor: pointer;
  flex-shrink: 0;
}

.size-hint {
  font-size: 11px;
  color: var(--td-text-color-placeholder);
}

.width-row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;

  /* 滑杆让出空间给数字框，否则窄面板里数字会被挤掉 */
  :deep(.t-slider) {
    flex: 1;
    min-width: 0;
  }
}

.width-num {
  width: 96px;
  flex-shrink: 0;
}

.margin-row {
  display: flex;
  gap: 8px;
}

.sign-labels {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}

.sign-label-row {
  display: flex;
  align-items: center;
  gap: 4px;
}

.prop-actions {
  display: flex;
  gap: 8px;
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--td-component-border);
}
</style>
