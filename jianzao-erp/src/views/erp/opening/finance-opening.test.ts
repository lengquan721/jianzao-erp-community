// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, nextTick } from 'vue';
import Opening from './index.vue';
import { exportFinOpening, listFinOpening, listFinOpeningPage } from '@/api/erp/finance';
import type { ErpAmount, FinOpeningVO } from '@/api/erp/types';

type Config = { url: string; method?: string; params?: Record<string, unknown>; data?: Record<string, unknown> };
const { request } = vi.hoisted(() => ({ request: vi.fn<(config: Config) => Promise<unknown>>() }));
vi.mock('@/utils/request', () => ({ default: request, globalHeaders: () => ({}) }));
vi.mock('@/utils/permission', () => ({ checkPermi: () => true }));
vi.mock('@/components/erp/PartnerSelect.vue', () => ({ default: { render: () => null } }));
vi.mock('@/components/erp/ProductSelect.vue', () => ({ default: { render: () => null } }));
vi.mock('@/components/erp/ErpPagedSelect.vue', () => ({ default: { render: () => null } }));
vi.mock('tdesign-icons-vue-next', () =>
  Object.fromEntries(
    ['CheckCircleIcon', 'ChevronDownIcon', 'DeleteIcon', 'DownloadIcon', 'Edit1Icon', 'PlusIcon', 'RollbackIcon', 'UploadIcon'].map((name) => [
      name,
      { render: () => null }
    ])
  )
);

// 正式 Vite 的自动组件导入绕过全局注册，因此显式桩化导入模块。
// opening 页面、API 封装和状态展示保持真实；仅替换控件、request、下载边界。
vi.mock('tdesign-vue-next', async () => {
  const { defineComponent, h } = await import('vue');
  const container = defineComponent({
    setup(_, { slots, expose }) {
      expose({ clearValidate: () => undefined, validate: async () => true });
      return () => h('div', [slots.default?.(), slots.dropdown?.()]);
    }
  });
  const button = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('button', slots.default?.())
  });
  const table = defineComponent({
    props: { data: Array<Record<string, unknown>>, columns: Array<{ colKey: string }>, empty: String },
    setup:
      (props, { slots }) =>
      () =>
        h('table', { 'data-table': props.columns?.[0]?.colKey }, [
          h(
            'tbody',
            props.data?.length
              ? props.data.map((row) =>
                  h(
                    'tr',
                    { 'data-row': String(row.id) },
                    props.columns?.map((col) => h('td', { 'data-col': col.colKey }, slots[col.colKey]?.({ row }) ?? String(row[col.colKey] ?? '')))
                  )
                )
              : [h('tr', h('td', props.empty))]
          )
        ])
  });
  const select = defineComponent({
    props: { options: Array<{ value: string; label: string }> },
    emits: ['update:modelValue', 'change'],
    setup:
      (props, { emit }) =>
      () =>
        h(
          'div',
          { 'data-select': '' },
          props.options?.map((option) =>
            h(
              'button',
              {
                'data-value': option.value,
                onClick: () => {
                  emit('update:modelValue', option.value);
                  emit('change', option.value);
                }
              },
              option.label
            )
          )
        )
  });
  const tooltip = defineComponent({
    props: { content: String },
    setup:
      (props, { slots }) =>
      () =>
        h('span', { 'data-action': props.content }, slots.default?.())
  });
  const dialog = defineComponent({
    props: { visible: Boolean },
    setup:
      (props, { slots }) =>
      () =>
        props.visible ? h('section', { 'data-dialog': '' }, slots.default?.()) : null
  });
  const upload = defineComponent({
    props: { action: String },
    emits: ['success'],
    setup:
      (props, { emit }) =>
      () =>
        h(
          'button',
          {
            'data-upload-url': props.action,
            onClick: () => emit('success', { response: { code: 200, data: { total: 1, success: 1, failed: 0, errors: [] } } })
          },
          '合成导入完成'
        )
  });
  return {
    Tabs: container,
    TabPanel: container,
    Dropdown: container,
    DropdownMenu: container,
    Tag: container,
    Alert: container,
    Descriptions: container,
    DescriptionsItem: container,
    Link: container,
    FormItem: container,
    InputNumber: container,
    DatePicker: container,
    Form: container,
    Textarea: container,
    Button: button,
    DropdownItem: button,
    Table: table,
    Select: select,
    Tooltip: tooltip,
    Dialog: dialog,
    Upload: upload
  };
});
vi.mock('@/components/Pagination/index.vue', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    default: defineComponent({
      props: { page: Number, total: Number },
      emits: ['update:page', 'pagination'],
      setup:
        (props, { emit }) =>
        () =>
          h('nav', { 'data-page': props.page, 'data-total': props.total }, [
            h(
              'button',
              {
                onClick: () => {
                  emit('update:page', (props.page ?? 1) + 1);
                  emit('pagination');
                }
              },
              '下一页'
            )
          ])
    })
  };
});
vi.mock('@/components/RightToolbar/index.vue', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    default: defineComponent({
      emits: ['query-table'],
      setup:
        (_, { emit }) =>
        () =>
          h('button', { 'data-refresh': '', onClick: () => emit('query-table') }, '刷新')
    })
  };
});
const ok = (data: unknown = null) => ({ code: 200, msg: 'ok', data });
const page = (rows: unknown[], total: number) => ({ code: 200, msg: 'ok', rows, total });
const row = (id = '9007199254740993', name = '原名称', status = 'DRAFT') => ({
  id,
  partyId: '9007199254740995',
  partyType: 'AR',
  partyName: name,
  amount: '123.45',
  status,
  statusText: status === 'DRAFT' ? '草稿' : '已确认',
  remark: '原备注'
});
const flush = async () => {
  await new Promise((resolve) => setTimeout(resolve, 0));
  await nextTick();
};
const deferred = () => {
  let resolve!: (value: unknown) => void;
  const promise = new Promise<unknown>((done) => {
    resolve = done;
  });
  return { promise, resolve };
};
let cleanup: (() => void) | undefined;
function mount() {
  const root = document.createElement('div');
  document.body.append(root);
  const app = createApp(Opening);
  app.directive('hasPermi', {});
  app.directive('loading', {});
  const download = vi.fn();
  const confirm = vi.fn(async () => undefined);
  app.config.globalProperties.download = download;
  // 满足正式 ComponentCustomProperties 的完整合同，不用窄对象/类型断言掩盖 TS2740。
  app.config.globalProperties.$modal = {
    msg: vi.fn(),
    msgError: vi.fn(),
    msgSuccess: vi.fn(),
    msgWarning: vi.fn(),
    alert: vi.fn(),
    alertError: vi.fn(),
    alertSuccess: vi.fn(),
    alertWarning: vi.fn(),
    notify: vi.fn(),
    notifyError: vi.fn(),
    notifySuccess: vi.fn(),
    notifyWarning: vi.fn(),
    confirm,
    prompt: vi.fn(async () => ({ value: '' })),
    loading: vi.fn(),
    closeLoading: vi.fn()
  };
  const vm = app.mount(root) as unknown as {
    $: {
      setupState: {
        finForm: { amount: ErpAmount; openingDate: string; partyId?: string | number };
        finDialog: { visible: boolean };
        handleFinAdd: () => Promise<void>;
        handleFinEdit: (row: FinOpeningVO) => Promise<void>;
        handleFinConfirm: (row: FinOpeningVO) => Promise<void>;
        submitFin: () => Promise<void>;
      };
    };
  };
  cleanup = () => {
    app.unmount();
    root.remove();
  };
  return { root, download, confirm, state: vm.$.setupState, modal: app.config.globalProperties.$modal };
}
function click(root: HTMLElement, selector: string) {
  const element = root.querySelector<HTMLElement>(selector);
  expect(element, selector).not.toBeNull();
  element!.click();
}
function financePanel(root: HTMLElement) {
  return root.querySelector('[data-table="partyType"]')!.parentElement!;
}
function clickText(root: HTMLElement, text: string) {
  const element = Array.from(root.querySelectorAll('button')).find((button) => button.textContent?.trim() === text);
  expect(element, text).not.toBeUndefined();
  element!.click();
}
beforeEach(() => {
  request.mockReset();
});
afterEach(() => {
  cleanup?.();
  cleanup = undefined;
});

describe('FinOpening actual page and bounded export contracts', () => {
  it('adds a paginated API without changing old list or export request/envelopes', async () => {
    const query = { partyType: 'AP' as const, pageNum: 3, pageSize: 20 };
    const payload = page([row()], 93);
    request.mockResolvedValueOnce(payload);
    expect(await listFinOpeningPage(query)).toBe(payload);
    expect(request).toHaveBeenLastCalledWith({ url: '/erp/finance/openings/list/page', method: 'get', params: query });
    const all = ok(Array.from({ length: 201 }, () => row()));
    request.mockResolvedValueOnce(all);
    expect(await listFinOpening('AR')).toBe(all);
    expect(request).toHaveBeenLastCalledWith({ url: '/erp/finance/openings/list', method: 'get', params: { partyType: 'AR' } });
    request.mockRejectedValueOnce(new Error('往来期初单次最多返回/导出 5000 行'));
    await expect(exportFinOpening({ partyType: 'AR' })).rejects.toThrow('5000');
    expect(request).toHaveBeenLastCalledWith({
      url: '/erp/finance/openings/export',
      method: 'post',
      params: { partyType: 'AR' },
      responseType: 'blob'
    });
  });

  it('uses real financial total and database pages while leaving stock pagination and export parameters intact', async () => {
    request.mockImplementation(async ({ url, params }) =>
      url.includes('/finance/openings/')
        ? page([row(String(params?.pageNum), `往来第${params?.pageNum}页`)], 93)
        : page([{ id: 'stock-1', productName: '库存不变', warehouseName: '主仓', qty: 1 }], 31)
    );
    const { root, download } = mount();
    await flush();
    expect(root.querySelectorAll('nav')[0].getAttribute('data-total')).toBe('31');
    expect(root.querySelectorAll('nav')[1].getAttribute('data-total')).toBe('93');
    click(financePanel(root), 'nav button');
    await flush();
    expect(request).toHaveBeenLastCalledWith(expect.objectContaining({ params: { partyType: 'AR', pageNum: 2, pageSize: 20 } }));
    expect(financePanel(root).textContent).toContain('往来第2页');
    expect(root.querySelector('[data-table="productCode"]')?.textContent).toContain('库存不变');
    expect(request.mock.calls.filter(([config]) => config.url === '/erp/stock/opening/list')).toHaveLength(1);
    clickText(financePanel(root), '导出数据');
    expect(download).toHaveBeenLastCalledWith(
      '/erp/finance/openings/export',
      { partyType: 'AR' },
      expect.stringMatching(/^fin_opening_AR_.*\.xlsx$/)
    );
  });

  it('resets financial page on AR/AP change and rejects stale type/page responses', async () => {
    const old = deferred();
    request.mockImplementation(async ({ url, params }) => {
      if (url.includes('/stock/')) return page([], 0);
      if (params?.partyType === 'AP') return page([row('ap-1', '当前供应商')], 45);
      return params?.pageNum === 2 ? old.promise : page([row()], 93);
    });
    const { root } = mount();
    await flush();
    click(financePanel(root), 'nav button');
    await nextTick();
    click(financePanel(root), '[data-value="AP"]');
    await flush();
    old.resolve(page([row('stale', '过期客户')], 999));
    await flush();
    expect(financePanel(root).textContent).toContain('当前供应商');
    expect(financePanel(root).textContent).not.toContain('过期客户');
    expect(financePanel(root).querySelector('nav')?.getAttribute('data-page')).toBe('1');
    expect(financePanel(root).querySelector('nav')?.getAttribute('data-total')).toBe('45');
    expect(request).toHaveBeenLastCalledWith(expect.objectContaining({ params: { partyType: 'AP', pageNum: 1, pageSize: 20 } }));
  });

  it('keeps financial confirm/unconfirm and import completion refreshing the current database page', async () => {
    request.mockImplementation(async ({ url, method }) => {
      if (url.includes('/stock/')) return page([], 0);
      if (method === 'post') return ok();
      return page([{ ...row(), openingDate: '2026-10-31' }, row('confirmed-1', '已确认往来', 'CONFIRMED')], 65);
    });
    const { root, confirm } = mount();
    await flush();
    const panel = financePanel(root);
    click(panel, '[data-action="确认"] button');
    await flush();
    expect(confirm).toHaveBeenCalled();
    expect(request).toHaveBeenCalledWith(expect.objectContaining({ url: '/erp/finance/openings/9007199254740993/confirm', method: 'post' }));
    click(panel, '[data-action="反确认"] button');
    await flush();
    expect(request).toHaveBeenCalledWith(expect.objectContaining({ url: '/erp/finance/openings/confirmed-1/unconfirm', method: 'post' }));
    click(panel, 'nav button');
    await flush();
    clickText(panel, '导入数据');
    await nextTick();
    expect(root.querySelector('[data-upload-url]')?.getAttribute('data-upload-url')).toMatch(/\/erp\/finance\/openings\/importData$/);
    click(root, '[data-upload-url]');
    await flush();
    expect(request).toHaveBeenLastCalledWith(expect.objectContaining({ params: { partyType: 'AR', pageNum: 2, pageSize: 20 } }));
  });

  it('preserves historical NULL dates, requires an explicit date, and keeps exact amounts on re-save', async () => {
    request.mockImplementation(async ({ method }) => (method === 'post' ? ok({ overwritten: true }) : page([], 0)));
    const { state, modal } = mount();
    await flush();
    await state.handleFinAdd();
    expect(state.finForm.openingDate).toBe('');
    const historical: FinOpeningVO = {
      id: '9007199254740993',
      partyType: 'AR',
      partyId: '9007199254740995',
      amount: '9007199254740993.12',
      openingDate: null,
      status: 'DRAFT'
    };
    await state.handleFinEdit(historical);
    expect(state.finForm.openingDate).toBe('');
    expect(state.finForm.amount).toBe('9007199254740993.12');
    await state.submitFin();
    await state.handleFinConfirm(historical);
    expect(request.mock.calls.some(([config]) => config.method === 'post')).toBe(false);
    expect(modal.msgWarning).toHaveBeenCalled();
    state.finForm.openingDate = '2026-10-31';
    await state.submitFin();
    expect(request).toHaveBeenCalledWith(
      expect.objectContaining({
        method: 'post',
        data: expect.objectContaining({ partyId: '9007199254740995', amount: '9007199254740993.12', openingDate: '2026-10-31' })
      })
    );
  });

  it('distinguishes failure from empty data, retries, and keeps real total on an empty page', async () => {
    let financialReads = 0;
    request.mockImplementation(async ({ url }) => {
      if (url.includes('/stock/')) return page([], 0);
      if (++financialReads === 1) throw new Error('synthetic read failure');
      return page([], 93);
    });
    const { root } = mount();
    await flush();
    expect(financePanel(root).textContent).toContain('往来期初加载失败，请重试');
    click(financePanel(root), '[data-refresh]');
    await flush();
    expect(financePanel(root).textContent).toContain('暂无往来期初数据');
    expect(financePanel(root).querySelector('nav')?.getAttribute('data-total')).toBe('93');
  });
});
