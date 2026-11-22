// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, nextTick } from 'vue';
import Template from './index.vue';
import type { PrintDesignSchema, PrintTemplateForm, PrintTemplateVO } from '@/api/erp/types';
import { PAPER_SIZES } from './components/designerOptions';

interface Config {
  url: string;
  method?: string;
  params?: Record<string, unknown>;
  data?: unknown;
}
const request = vi.hoisted(() => vi.fn<(config: Config) => Promise<unknown>>());
vi.mock('@/utils/request', () => ({ default: request }));
vi.mock('@/utils/permission', () => ({ checkPermi: () => true }));
vi.mock('@/components/erp/PartnerSelect.vue', () => ({ default: { render: () => null } }));
vi.mock('@/components/Pagination/index.vue', () => ({ default: { render: () => null } }));
vi.mock('./components/PrintDesigner.vue', async () => {
  const { defineComponent, h } = await import('vue');
  return { default: defineComponent({ props: { schema: Object }, setup: (props) => () => h('div', { 'data-canvas-paper': props.schema?.paper }) }) };
});
vi.mock('tdesign-vue-next', async () => {
  const { defineComponent, h } = await import('vue');
  const slot = defineComponent({
    setup(_, { slots, expose }) {
      expose({ validate: async () => true });
      return () => h('div', [slots.default?.(), slots.footer?.()]);
    }
  });
  const dialog = defineComponent({
    props: { visible: Boolean },
    setup:
      (props, { slots }) =>
      () =>
        props.visible ? h('section', [slots.default?.(), slots.footer?.()]) : null
  });
  return Object.fromEntries(
    [
      'Card',
      'Form',
      'FormItem',
      'Select',
      'Input',
      'Row',
      'Col',
      'RadioGroup',
      'RadioButton',
      'Switch',
      'Textarea',
      'Button',
      'Tooltip',
      'Tag',
      'Table',
      'Tabs',
      'TabPanel',
      'Empty',
      'Alert'
    ]
      .map((name) => [name, slot])
      .concat([['Dialog', dialog]])
  );
});
const schema = (): PrintDesignSchema => ({
  schemaVersion: 1,
  paper: 'A5',
  pageMargin: 12,
  fontSize: 12,
  blocks: [{ id: 'old-logo', type: 'IMAGE', props: { src: 'data:image/png;base64,AA==', width: 120 }, style: {} }]
});
const detail = (): PrintTemplateVO => ({
  id: '9007199254740993',
  name: '用户设计器',
  scene: 'SALE_DELIVERY',
  paper: 'A4',
  schemaJson: JSON.stringify(schema())
});
const deferred = () => {
  let resolve!: (value: unknown) => void;
  const promise = new Promise<unknown>((done) => {
    resolve = done;
  });
  return { promise, resolve };
};
const flush = async () => {
  await new Promise((resolve) => setTimeout(resolve, 0));
  await nextTick();
};
const warning = vi.fn();
let cleanup: (() => void) | undefined;
function mount() {
  const root = document.createElement('div');
  const app = createApp(Template);
  app.directive('hasPermi', {});
  Object.defineProperty(app.config.globalProperties, '$modal', { value: { msgWarning: warning, msgSuccess: vi.fn() } });
  const vm = app.mount(root) as unknown as {
    $: {
      setupState: {
        form: PrintTemplateForm;
        designSchema: PrintDesignSchema;
        editMode: 'design' | 'source';
        selectedPaper: string;
        paperLocked: boolean;
        dialogVisible: boolean;
        previewVisible: boolean;
        previewHtml: string;
        previewingDesign: boolean;
        handleAdd: () => void;
        handleUpdate: (row: PrintTemplateVO) => Promise<void>;
        handleSave: () => Promise<void>;
        handlePreview: (row: PrintTemplateVO) => Promise<void>;
        handlePreviewDesign: () => Promise<void>;
      };
    };
  };
  cleanup = () => app.unmount();
  return { root, state: vm.$.setupState };
}
beforeEach(() => {
  warning.mockReset();
  request.mockReset().mockImplementation(async ({ url, method }) => {
    if (url === '/erp/print/templates' && method === 'get') return { rows: [], total: 0 };
    if (url.startsWith('/erp/print/templates/') && method === 'get') return { data: detail() };
    return '<main>有效预览</main>';
  });
});
afterEach(() => {
  cleanup?.();
  cleanup = undefined;
});

describe('actual template editor integration preserves user designer semantics', () => {
  it('uses one paper source including 241x93, retains schemaJson/logo and writes matching schema/top-level paper', async () => {
    const { root, state } = mount();
    await state.handleUpdate(detail());
    expect(state.selectedPaper).toBe('A5');
    state.selectedPaper = '241x93';
    await nextTick();
    expect(root.querySelector('[data-canvas-paper]')?.getAttribute('data-canvas-paper')).toBe('241x93');
    expect(PAPER_SIZES['241x93']).toMatchObject({ width: 241, height: 93 });
    await state.handleSave();
    const data = request.mock.calls.find(([config]) => config.method === 'put')?.[0].data as PrintTemplateForm;
    expect(data.id).toBe('9007199254740993');
    expect(data.paper).toBe('241x93');
    expect(JSON.parse(data.schemaJson!)).toMatchObject({
      paper: '241x93',
      blocks: [{ id: 'old-logo', props: { src: 'data:image/png;base64,AA==' } }]
    });
  });

  it('respects real source @page but not comments, and passes paper/scene to source preview', async () => {
    const { root, state } = mount();
    state.handleAdd();
    state.editMode = 'source';
    state.form.scene = 'STATEMENT_RANGE';
    state.form.content = '<style>/* @page { size: A4 } */</style><main>源码</main>';
    expect(state.paperLocked).toBe(false);
    state.selectedPaper = '241x93';
    state.form.content = '<style>@page { size: A5 }</style><main>源码</main>';
    expect(state.paperLocked).toBe(true);
    await state.handlePreviewDesign();
    await nextTick();
    expect(request).toHaveBeenCalledWith(
      expect.objectContaining({ url: '/erp/print/preview', params: expect.objectContaining({ scene: 'STATEMENT_RANGE', paper: '241x93' }) })
    );
    expect(root.querySelector('iframe')?.getAttribute('sandbox')).toBe('allow-same-origin allow-modals');
  });

  it('invalidates preview on close/ABA and never accepts late or non-HTML responses', async () => {
    const old = deferred();
    const current = deferred();
    let previews = 0;
    request.mockImplementation(async ({ url }) =>
      url === '/erp/print/preview/design' ? (++previews === 1 ? old.promise : current.promise) : { rows: [], total: 0 }
    );
    const { state } = mount();
    const a = state.handlePreview(detail());
    state.previewVisible = false;
    const b = state.handlePreview(detail());
    old.resolve('<main>旧预览</main>');
    await a;
    expect(state.previewHtml).toBe('');
    expect(state.previewingDesign).toBe(true);
    current.resolve('{"code":500,"msg":"不可打印"}');
    await b;
    expect(state.previewHtml).toBe('');
    expect(state.previewingDesign).toBe(false);
    expect(warning).toHaveBeenCalled();
  });

  it('does not let an old design-to-source compile overwrite a new mode, closed editor or user edits', async () => {
    const pending = deferred();
    request.mockImplementation(async ({ url, method }) => {
      if (url === '/erp/print/preview/design') return pending.promise;
      return method === 'get' && url.includes('/templates/') ? { data: detail() } : { rows: [], total: 0 };
    });
    const { state } = mount();
    await state.handleUpdate(detail());
    state.editMode = 'source';
    state.form.content = '<main>用户继续输入</main>';
    state.editMode = 'design';
    state.dialogVisible = false;
    pending.resolve('<main>旧编译</main>');
    await flush();
    expect(state.form.content).toBe('<main>用户继续输入</main>');
    expect(state.previewHtml).toBe('');
  });

  it('paper/scene/customer changes invalidate a running editor preview before response', async () => {
    const pending = deferred();
    request.mockImplementation(async ({ url, method }) =>
      url === '/erp/print/preview/design'
        ? pending.promise
        : method === 'get' && url.includes('/templates/')
          ? { data: detail() }
          : { rows: [], total: 0 }
    );
    const { state } = mount();
    await state.handleUpdate(detail());
    const preview = state.handlePreviewDesign();
    state.selectedPaper = '241x93';
    state.form.customerId = '9007199254740995';
    state.form.scene = 'STATEMENT_RANGE';
    pending.resolve('<main>旧纸张/客户/场景</main>');
    await preview;
    expect(state.previewHtml).toBe('');
  });
});
