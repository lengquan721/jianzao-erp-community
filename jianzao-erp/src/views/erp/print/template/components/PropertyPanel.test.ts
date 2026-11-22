// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Button, Checkbox, FormItem, Input, InputNumber, RadioButton, RadioGroup, Select, Slider, Switch, Textarea, Upload } from 'tdesign-vue-next';
import { computed, createApp, h, nextTick, ref, shallowRef } from 'vue';
import type { PrintDesignBlock, PrintDesignSchema } from '@/api/erp/types';
import modal from '@/plugins/modal';
import PropertyPanel from './PropertyPanel.vue';

vi.mock('tdesign-vue-next', async () => {
  const { defineComponent, h } = await import('vue');
  const slot = defineComponent({
    setup(_, { slots }) {
      return () => h('div', slots.default?.());
    }
  });
  const Upload = defineComponent({
    emits: ['change'],
    setup(_, { emit }) {
      return () =>
        h('input', {
          type: 'file',
          onChange(e: Event) {
            const files = (e.target as HTMLInputElement).files ?? [];
            const uploadFiles = Array.from(files, (raw) => ({ raw }));
            emit('change', uploadFiles);
          }
        });
    }
  });
  return {
    Upload,
    Button: slot,
    Checkbox: slot,
    FormItem: slot,
    Input: slot,
    InputNumber: slot,
    RadioButton: slot,
    RadioGroup: slot,
    Select: slot,
    Slider: slot,
    Switch: slot,
    Textarea: slot
  };
});

const originalSrc = 'data:image/svg+xml;base64,b3JpZ2luYWw=';
const firstSrc = 'data:image/svg+xml;base64,Zmlyc3Q=';
const latestSrc = 'data:image/svg+xml;base64,bGF0ZXN0';

// 只控制 FileReader 的完成顺序；实际 onLogoChange、压缩入口与 schema 更新仍由面板执行。
class DeferredReader {
  result = '';
  onload?: () => void;
  onerror?: () => void;

  readAsDataURL() {
    readers.push(this);
  }

  succeed(uri: string) {
    this.result = uri;
    this.onload?.();
  }

  fail() {
    this.onerror?.();
  }
}

const readers: DeferredReader[] = [];
let cleanup: (() => void) | undefined;
// 完成顺序由桩决定；只用下一轮任务等 Promise 和 Vue 的更新队列结清。
const flushLogo = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

beforeEach(() => {
  readers.length = 0;
  vi.stubGlobal('FileReader', DeferredReader);
});

afterEach(() => {
  cleanup?.();
  cleanup = undefined;
  vi.unstubAllGlobals();
});

function mountPanel() {
  const image = (id: string): PrintDesignBlock => ({ id, type: 'IMAGE', props: { src: originalSrc, width: 120 }, style: {} });
  const schema = shallowRef<PrintDesignSchema>({ paper: 'A4', blocks: [image('a'), image('b')] });
  const activeId = ref('a');
  const block = computed(() => schema.value.blocks.find((entry) => entry.id === activeId.value) ?? null);
  const update = vi.fn((next: PrintDesignSchema) => (schema.value = next));
  const warning = vi.fn();
  const host = document.createElement('div');
  const app = createApp(() =>
    h(PropertyPanel, {
      schema: schema.value,
      block: block.value,
      fields: [],
      tableSources: [],
      columnsBySource: {},
      onUpdateSchema: update
    })
  );
  // Vue-only Vitest 运行时没有自动导入；普通 Vite 运行时静态导入也使用同一组桩。
  const components = { Button, Checkbox, FormItem, Input, InputNumber, RadioButton, RadioGroup, Select, Slider, Switch, Textarea, Upload };
  for (const [name, component] of Object.entries(components)) app.component(`T${name}`, component);
  app.config.globalProperties.$modal = { ...modal, msgWarning: warning };
  app.mount(host);
  cleanup = () => app.unmount();

  const changeFiles = (files: File[]) => {
    const input = host.querySelector<HTMLInputElement>('input[type=file]')!;
    Object.defineProperty(input, 'files', { value: files, configurable: true });
    input.dispatchEvent(new Event('change'));
  };
  const pick = () => {
    const index = readers.length;
    changeFiles([new File(['<svg/>'], 'logo.svg', { type: 'image/svg+xml' })]);
    expect(readers).toHaveLength(index + 1);
    return readers[index];
  };

  return {
    schema,
    activeId,
    update,
    warning,
    pick,
    clear: () => changeFiles([]),
    unmount: () => {
      cleanup?.();
      cleanup = undefined;
    }
  };
}

type Panel = ReturnType<typeof mountPanel>;
const invalidations: [string, (panel: Panel) => void | Promise<void>][] = [
  ['switching to a different IMAGE', ({ activeId }) => void (activeId.value = 'b')],
  [
    'switching away and back',
    async ({ activeId }) => {
      activeId.value = 'b';
      await nextTick();
      activeId.value = 'a';
    }
  ],
  [
    'deleting the IMAGE',
    ({ schema }) => {
      schema.value = { ...schema.value, blocks: schema.value.blocks.filter(({ id }) => id !== 'a') };
    }
  ],
  ['clearing the file list', ({ clear }) => clear()],
  ['unmounting the panel', ({ unmount }) => unmount()]
];

describe.each(['success', 'failure'] as const)('obsolete Logo %s', (outcome) => {
  const finish = (reader: DeferredReader) => (outcome === 'success' ? reader.succeed(firstSrc) : reader.fail());

  it.each(invalidations)('ignores completion after %s', async (_, invalidate) => {
    const panel = mountPanel();
    const reader = panel.pick();
    await invalidate(panel);
    await nextTick();
    finish(reader);
    await flushLogo();
    expect(panel.update).not.toHaveBeenCalled();
    expect(panel.warning).not.toHaveBeenCalled();
    expect(panel.schema.value.blocks.every(({ props }) => props.src === originalSrc)).toBe(true);
  });

  it('ignores completion already queued before a same-turn selection update reaches the panel', async () => {
    const { activeId, pick, update, warning } = mountPanel();
    finish(pick());
    activeId.value = 'b';
    await flushLogo();
    expect(update).not.toHaveBeenCalled();
    expect(warning).not.toHaveBeenCalled();
  });

  it.each(['before', 'after'] as const)('keeps the newest file when the previous file finishes %s it on the same IMAGE', async (order) => {
    const { pick, update, warning, schema } = mountPanel();
    const first = pick();
    const latest = pick();
    if (order === 'before') {
      finish(first);
      await flushLogo();
      expect(update).not.toHaveBeenCalled();
    }
    latest.succeed(latestSrc);
    await flushLogo();
    if (order === 'after') {
      finish(first);
      await flushLogo();
    }
    expect(update).toHaveBeenCalledTimes(1);
    expect(warning).not.toHaveBeenCalled();
    expect(schema.value.blocks.map(({ props }) => props.src)).toEqual([latestSrc, originalSrc]);
  });
});

it('warns only for the current failure and does not fall back to an older file', async () => {
  const { pick, update, warning, schema } = mountPanel();
  const first = pick();
  pick().fail();
  await flushLogo();
  first.succeed(firstSrc);
  await flushLogo();
  expect(warning).toHaveBeenCalledExactlyOnceWith('图片读取失败');
  expect(update).not.toHaveBeenCalled();
  expect(schema.value.blocks[0].props.src).toBe(originalSrc);
});

it('writes only src on the latest schema after synchronous size and position edits', async () => {
  const { pick, update, warning, schema } = mountPanel();
  const reader = pick();
  const [a, b] = schema.value.blocks;
  const edited = { ...a, props: { ...a.props, width: 240, height: 90 }, style: { offsetPx: 42 } };
  const beforeUpload = { paper: 'A5', blocks: [{ ...b, props: { ...b.props, width: 300 } }, edited] };
  schema.value = beforeUpload;
  await nextTick();
  reader.succeed(latestSrc);
  await flushLogo();
  expect(update).toHaveBeenCalledExactlyOnceWith({
    ...beforeUpload,
    blocks: [beforeUpload.blocks[0], { ...edited, props: { ...edited.props, src: latestSrc } }]
  });
  expect(warning).not.toHaveBeenCalled();
  expect(beforeUpload.blocks[1].props).toMatchObject({ src: originalSrc });
});
