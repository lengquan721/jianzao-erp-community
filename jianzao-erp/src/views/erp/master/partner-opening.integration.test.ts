// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, nextTick } from 'vue';
import type { Component } from 'vue';
import Customer from './customer/index.vue';
import Supplier from './supplier/index.vue';

interface Config {
  url: string;
  method?: string;
  params?: Record<string, unknown>;
  data?: Record<string, unknown>;
}
const boundary = vi.hoisted(() => ({ request: vi.fn<(config: Config) => Promise<unknown>>(), granted: true }));
vi.mock('@/utils/request', () => ({ default: boundary.request, globalHeaders: () => ({}) }));
vi.mock('@/utils/permission', () => ({ checkPermi: () => boundary.granted }));
vi.mock('@/components/RightToolbar/index.vue', () => ({ default: { render: () => null } }));
vi.mock('@/components/Pagination/index.vue', () => ({ default: { render: () => null } }));
vi.mock('tdesign-vue-next', async () => {
  const { defineComponent, h } = await import('vue');
  const slot = defineComponent({
    setup(_, { slots, expose }) {
      expose({ validate: async () => true, clearValidate: () => undefined });
      return () => h('div', [slots.default?.(), slots.footer?.(), slots.dropdown?.()]);
    }
  });
  const table = defineComponent({
    props: { data: Array<Record<string, unknown>>, columns: Array<{ colKey: string }> },
    setup:
      (props, { slots }) =>
      () =>
        h(
          'table',
          props.data?.map((row) =>
            h(
              'tr',
              { 'data-row': row.id },
              props.columns?.map((col) => h('td', { 'data-col': col.colKey }, slots[col.colKey]?.({ row }) ?? String(row[col.colKey] ?? '')))
            )
          )
        )
  });
  return Object.fromEntries(
    [
      'Row',
      'Col',
      'Card',
      'Form',
      'FormItem',
      'Select',
      'Input',
      'InputNumber',
      'Radio',
      'RadioGroup',
      'Textarea',
      'Tooltip',
      'Button',
      'Dropdown',
      'DropdownItem',
      'DropdownMenu',
      'Dialog',
      'Descriptions',
      'Alert',
      'Link',
      'Upload',
      'Checkbox',
      'Tag'
    ]
      .map((name) => [name, slot])
      .concat([['Table', table]])
  );
});
const id = '9007199254740993';
const nextId = '9007199254740995';
const ok = (data: unknown) => ({ code: 200, msg: 'ok', data });
const page = (ids = [id, nextId]) => ({
  code: 200,
  rows: ids.map((id) => ({ id, name: `单位${id}`, openingDebt: '9999', openingPayable: '9999' })),
  total: 6001
});
const flush = async () => {
  await new Promise((resolve) => setTimeout(resolve, 0));
  await nextTick();
};
let cleanup: (() => void) | undefined;
function mount(component: Component) {
  const root = document.createElement('div');
  const app = createApp(component);
  app.directive('hasPermi', {});
  app.directive('loading', {});
  Object.defineProperty(app.config.globalProperties, 'animate', { value: { searchAnimate: { enter: '', leave: '' } } });
  Object.defineProperty(app.config.globalProperties, '$modal', { value: { msgWarning: vi.fn(), msgSuccess: vi.fn() } });
  const vm = app.mount(root) as unknown as {
    $: {
      setupState: {
        queryParams: { pageNum: number; pageSize: number };
        form: Record<string, unknown>;
        getList: () => Promise<void>;
        handleUpdate: (row: { id: string }) => Promise<void>;
        submitForm: () => Promise<void>;
      };
    };
  };
  cleanup = () => app.unmount();
  return { root, state: vm.$.setupState };
}
beforeEach(() => {
  boundary.request.mockReset();
  boundary.granted = true;
});
afterEach(() => {
  cleanup?.();
  cleanup = undefined;
});

describe.each([
  { component: Customer, resource: 'customers', direction: 'AR', field: 'openingDebt' },
  { component: Supplier, resource: 'suppliers', direction: 'AP', field: 'openingPayable' }
])('$resource current-page opening display', ({ component, resource, direction, field }) => {
  it('uses only visible IDs, keeps paging/decimal strings and shows missing authorized data as unknown', async () => {
    boundary.request.mockImplementation(async ({ url }) => (url.endsWith('/list') ? page() : ok([{ partyId: id, amount: '123.45' }])));
    const { root, state } = mount(component);
    await flush();
    expect(boundary.request).toHaveBeenCalledWith(
      expect.objectContaining({ url: `/erp/master/${resource}/list`, params: expect.objectContaining({ pageNum: 1, pageSize: 10 }) })
    );
    expect(boundary.request).toHaveBeenCalledWith({
      url: '/erp/finance/openings/confirmed/by-party-ids',
      method: 'get',
      params: { partyType: direction, partyIds: `${id},${nextId}` }
    });
    expect(root.querySelector(`[data-row="${id}"] [data-col="${field}"]`)?.textContent).toBe('123.45');
    expect(root.querySelector(`[data-row="${nextId}"] [data-col="${field}"]`)?.textContent).toBe('未知');
    state.queryParams.pageNum = 302;
    await state.getList();
    expect(boundary.request.mock.calls.some(([config]) => config.params?.pageNum === 302)).toBe(true);
    expect(boundary.request.mock.calls.some(([config]) => config.url.includes('ar-ap/summary') || config.url.includes('openings/list'))).toBe(false);
  });

  it.each(['no permission', 'failed request'])('shows unknown rather than legacy value or zero for %s', async (failure) => {
    boundary.granted = failure !== 'no permission';
    boundary.request.mockImplementation(async ({ url }) => {
      if (url.endsWith('/list')) return page();
      throw new Error('denied');
    });
    const { root } = mount(component);
    await flush();
    expect(root.querySelector(`[data-col="${field}"]`)?.textContent).toBe('未知');
    if (!boundary.granted) expect(boundary.request).toHaveBeenCalledTimes(1);
  });

  it('never sends historical opening fields from master detail back to the write API', async () => {
    boundary.request.mockImplementation(async ({ url, method }) => {
      if (url.endsWith('/list')) return page();
      if (url.includes('/confirmed/')) return ok([{ partyId: id, amount: '12.34' }]);
      if (method === 'get') return ok({ id, name: '可编辑', openingDebt: '777', openingPayable: '888', taxRate: '3' });
      return ok(null);
    });
    const { state } = mount(component);
    await flush();
    await state.handleUpdate({ id });
    expect(state.form).not.toHaveProperty('openingDebt');
    expect(state.form).not.toHaveProperty('openingPayable');
    await state.submitForm();
    const write = boundary.request.mock.calls.find(([config]) => config.method === 'put')?.[0];
    expect(write?.data?.id).toBe(id);
    expect(write?.data).not.toHaveProperty('openingDebt');
    expect(write?.data).not.toHaveProperty('openingPayable');
  });
});
