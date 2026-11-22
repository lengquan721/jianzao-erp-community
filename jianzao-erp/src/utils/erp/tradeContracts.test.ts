// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, type Component } from 'vue';
import SaleOrder from '@/views/erp/order/sale/index.vue';
import PurchaseOrder from '@/views/erp/order/purchase/index.vue';
import Receipt from '@/views/erp/finance/receipt/index.vue';
import SaleReturn from '@/views/erp/sale-return/index.vue';
import SourceOrderSelect from '@/components/erp/SourceOrderSelect.vue';
import Stock from '@/views/erp/stock/index.vue';
import ProductSelect from '@/components/erp/ProductSelect.vue';

interface RequestConfig {
  url: string;
  method: string;
  data?: unknown;
  params?: Record<string, unknown>;
}
const { request } = vi.hoisted(() => ({ request: vi.fn<(config: RequestConfig) => Promise<unknown>>() }));
vi.mock('@/utils/request', () => ({ default: request }));
vi.mock('@/utils/permission', () => ({ checkPermi: () => true }));
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ currentRoute: { value: { query: {} } }, push: vi.fn() })
}));

// 保留真实 ERP 页面、选择器、模板插槽、请求封装，替换仅负责控件渲染/事件的 TDesign。
vi.mock('tdesign-vue-next', async () => {
  const { h, defineComponent } = await import('vue');
  const renderSlot = (slot?: (props?: Record<string, unknown>) => unknown, props?: Record<string, unknown>): any[] => {
    const content = slot?.(props);
    if (content == null) return [];
    return Array.isArray(content) ? content : [content];
  };
  const container = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', renderSlot(slots.default))
  });
  const dialog = defineComponent({
    props: { visible: Boolean },
    setup:
      (props, { slots }) =>
      () =>
        props.visible
          ? h(
              'section',
              { 'data-dialog': '' },
              [
                slots.header ? h('header', renderSlot(slots.header)) : null,
                slots.default ? h('div', renderSlot(slots.default)) : null,
                slots.footer ? h('footer', renderSlot(slots.footer)) : null
              ].filter(Boolean)
            )
          : null
  });
  const checkTag = defineComponent({
    props: { checked: Boolean },
    emits: ['change'],
    setup:
      (props, { emit, slots }) =>
      () =>
        h(
          'button',
          {
            type: 'button',
            'data-check-tag': '',
            'data-checked': props.checked,
            onClick: () => emit('change', !props.checked)
          },
          renderSlot(slots.default)
        )
  });
  return {
    ...Object.fromEntries(
      [
        'Row',
        'Col',
        'Card',
        'Descriptions',
        'DescriptionsItem',
        'Tag',
        'Checkbox',
        'Progress',
        'Alert',
        'DatePicker',
        'Switch',
        'TabPanel',
        'Tabs',
        'Popup',
        'Tree',
        'Textarea'
      ].map((name) => [name, container])
    ),
    CheckTag: checkTag,
    Drawer: dialog,
    Dialog: dialog,
    Form: defineComponent({
      setup: (_, { slots, expose }) => {
        expose({ validate: async () => true, reset: () => {}, clearValidate: () => {} });
        return () => h('div', renderSlot(slots.default));
      }
    }),
    FormItem: defineComponent({
      props: { label: String },
      setup:
        (props, { slots }) =>
        () =>
          h('div', { 'data-label': props.label }, renderSlot(slots.default))
    }),
    Tooltip: defineComponent({
      props: { content: String },
      setup:
        (props, { slots }) =>
        () =>
          h('span', { 'data-action': props.content }, renderSlot(slots.default))
    }),
    Button: defineComponent({
      props: { disabled: Boolean },
      setup:
        (props, { slots }) =>
        () =>
          h('button', { type: 'button', disabled: props.disabled }, renderSlot(slots.default))
    }),
    Input: defineComponent({
      props: { value: [String, Number], modelValue: [String, Number], placeholder: String },
      emits: ['update:modelValue'],
      setup:
        (props, { emit }) =>
        () =>
          h('input', {
            placeholder: props.placeholder,
            value: props.value ?? props.modelValue,
            onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value)
          })
    }),
    InputNumber: container,
    DateRangePicker: defineComponent({
      emits: ['update:modelValue', 'change'],
      setup:
        (_, { emit }) =>
        () =>
          h('input', {
            'data-dates': '',
            onChange: (event: Event) => {
              const dates = (event.target as HTMLInputElement).value.split(',');
              emit('update:modelValue', dates);
              emit('change', dates);
            }
          })
    }),
    Select: defineComponent({
      props: { modelValue: [String, Number], placeholder: String, options: Array<{ value: string | number; label: string }> },
      emits: ['popup-visible-change', 'search', 'update:modelValue', 'change'],
      setup:
        (props, { emit, slots }) =>
        () =>
          h('div', { 'data-select': props.placeholder, 'data-selected': props.modelValue }, [
            h('input', {
              placeholder: props.placeholder,
              onFocus: () => emit('popup-visible-change', true),
              onInput: (event: Event) => emit('search', (event.target as HTMLInputElement).value)
            }),
            ...renderSlot(slots.valueDisplay),
            ...(props.options ?? []).map(({ value, label }) =>
              h(
                'button',
                {
                  onClick: () => {
                    emit('update:modelValue', value);
                    emit('change', value);
                  }
                },
                label
              )
            ),
            ...renderSlot(slots.panelBottomContent)
          ])
    }),
    Table: defineComponent({
      props: {
        data: Array<Record<string, unknown>>,
        columns: Array<{ colKey: string; cell?: string; type?: string }>,
        rowKey: String,
        selectedRowKeys: Array<string | number>
      },
      emits: ['row-click', 'row-dblclick', 'select-change'],
      setup:
        (props, { slots, emit }) =>
        () =>
          h('table', [
            h(
              'tbody',
              (props.data ?? []).map((row, rowIndex) => {
                const context = { row, rowIndex };
                return h(
                  'tr',
                  {
                    'data-row': String(row[props.rowKey ?? 'id']),
                    onClick: () => emit('row-click', context),
                    onDblclick: () => emit('row-dblclick', context)
                  },
                  (props.columns ?? []).map((col) => {
                    const content = renderSlot(slots[col.cell ?? col.colKey], context);
                    return h('td', { 'data-col': col.colKey }, content.length ? content : String(row[col.colKey] ?? ''));
                  })
                );
              })
            )
          ])
    }),
    Pagination: defineComponent({
      props: { total: Number, current: Number },
      emits: ['current-change'],
      setup:
        (props, { emit }) =>
        () =>
          h('button', { 'data-total': props.total, onClick: () => emit('current-change', (props.current ?? 1) + 1) }, '表格翻页')
    })
  };
});

const ok = (data: unknown) => ({ code: 200, msg: 'ok', data });
const page = (rows: unknown[], total = rows.length) => ({ code: 200, msg: 'ok', rows, total });
const options = (rows: unknown[], nextPageNum: number | null = null) => ok({ rows, hasMore: nextPageNum != null, nextPageNum });
const deferred = () => {
  let resolve!: (value: unknown) => void;
  const response = new Promise<unknown>((done) => {
    resolve = done;
  });
  return { resolve, response };
};
const mount = (component: Component, props = {}) => {
  const root = document.createElement('div');
  document.body.append(root);
  const app = createApp({ render: () => h(component, props) });
  app.config.globalProperties.animate = {
    animateList: [],
    defaultAnimate: '',
    searchAnimate: { enter: '', leave: '' },
    menuSearchAnimate: { enter: '', leave: '' },
    logoAnimate: { enter: '', leave: '' }
  };
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
    confirm: vi.fn(async () => undefined),
    prompt: vi.fn(async () => ({ value: '' })),
    loading: vi.fn(),
    closeLoading: vi.fn()
  };
  app.directive('hasPermi', {});
  app.directive('loading', {});
  app.mount(root);
  cleanups.push(() => {
    app.unmount();
    root.remove();
  });
  return root;
};
const cleanups: (() => void)[] = [];
afterEach(async () => {
  // 先等待组件内已发出的请求和 Vue 更新完成，再卸载，避免异步 slot 更新污染下一个 case。
  await settle();
  cleanups.splice(0).forEach((cleanup) => cleanup());
  request.mockReset();
});
const settle = async (rounds = 2) => {
  for (let index = 0; index < rounds; index += 1) {
    await new Promise((resolve) => setTimeout(resolve, 0));
    await nextTick();
  }
};
const button = (root: ParentNode, text: string) => {
  const found = Array.from(root.querySelectorAll('button')).find((node) => node.textContent?.trim() === text);
  if (!found) throw new Error(`Missing test button: ${text}`);
  return found;
};
const enter = (input: HTMLInputElement, value: string, event = 'input') => {
  input.value = value;
  input.dispatchEvent(new Event(event));
};
const calls = (url: string) => request.mock.calls.flatMap(([config]) => (config.url === url ? [config] : []));
const id = '9007199254740993';
const otherId = '9007199254740994';

describe('ERP pages consume server paging and signed amounts', () => {
  it.each([
    { component: SaleOrder, path: 'sale-orders', party: 'customerId', execution: 'DELIVERY' },
    { component: PurchaseOrder, path: 'purchase-orders', party: 'supplierId', execution: 'RECEIPT' }
  ])('shows $path aggregates and signed mixed records without any per-document detail requests', async ({ component, path, party, execution }) => {
    const base = `/erp/${path}`;
    const row = { id, [party]: 1, no: 'ORDER-1', status: 'CONFIRMED', execStatus: 'PARTIAL', executedAmount: '71.25' };
    const records = [
      { id: otherId, no: 'RET-2', type: 'RETURN', status: 'CONFIRMED', amount: '-32.50' },
      { id, no: 'EXEC-1', type: execution, status: 'CONFIRMED', amount: '100.00' }
    ];
    request.mockImplementation(async ({ url }) => {
      if (url === `${base}/list`) return page([row], 241);
      if (url === `${base}/${id}`) return ok({ ...row, executedAmount: '67.50', items: [] });
      if (url === `${base}/${id}/executions`) return ok(records);
      return ok([]);
    });
    const root = mount(component);
    await settle();
    expect(root.querySelector('[data-total="241"]')).not.toBeNull();
    expect(root.querySelector('[data-col="executedAmount"]')?.textContent).toBe('71.25');
    expect(request.mock.calls.map(([config]) => config.url)).toEqual([`${base}/list`]);
    button(root, 'ORDER-1').click();
    await settle();
    expect(root.querySelector('[data-dialog]')?.textContent).toContain('67.50');
    expect(Array.from(root.querySelectorAll('[data-col="amount"]')).map((el) => el.textContent)).toEqual(['-32.50', '100.00']);
    expect(request.mock.calls.map(([config]) => config.url).filter((url) => !url.endsWith('/options/by-ids'))).toEqual([
      `${base}/list`,
      `${base}/${id}`,
      `${base}/${id}/executions`
    ]);
  });

  it.each([
    { kind: 'sale', path: 'sale-orders', party: 'customerId', resource: 'customers', filter: '客户', name: '销售' },
    { kind: 'purchase', path: 'purchase-orders', party: 'supplierId', resource: 'suppliers', filter: '供应商', name: '采购' }
  ])(
    'paginates $kind source orders and reissues a first page with server search and party filter',
    async ({ kind, path, party, resource, filter, name }) => {
      const url = `/erp/${path}/list`;
      const order = { id: otherId, [party]: id, no: 'ORDER-NEXT', status: 'CONFIRMED', executedAmount: '0.00' };
      request.mockImplementation(async (config) => (config.url === url ? page([order], 235) : options([{ id, code: 'X1', name: '往来单位' }])));
      const onChange = vi.fn();
      const root = mount(SourceOrderSelect, { kind, onChange });
      const source = root.querySelector<HTMLInputElement>(`input[placeholder="选择已确认的${name}订单（可选）"]`)!;
      source.dispatchEvent(new Event('focus'));
      await settle();
      expect(root.textContent).toContain('共 235 条');
      button(root, '下一页').click();
      await settle();
      expect(calls(url).at(-1)?.params).toMatchObject({ pageNum: 2, pageSize: 30, status: 'CONFIRMED' });
      root.querySelector<HTMLInputElement>(`input[placeholder="按${filter}筛选来源订单"]`)!.dispatchEvent(new Event('focus'));
      await settle();
      expect(calls(`/erp/master/${resource}/options`)).toHaveLength(1);
      button(root, 'X1 往来单位').click();
      await settle();
      enter(source, ' 对方单号 ');
      await settle();
      expect(calls(url).at(-1)?.params).toEqual({ pageNum: 1, pageSize: 30, status: 'CONFIRMED', [party]: id, keyword: '对方单号' });
      button(root, `ORDER-NEXT（${filter}）`).click();
      expect(onChange).toHaveBeenCalledWith(order);
    }
  );

  it('renders refund receipt amount negative in list and detail and leaves no ordinary change actions', async () => {
    const refund = { id, no: 'RF-1', negative: 1, status: 'CONFIRMED', amount: '50.00' };
    const draft = { id: otherId, no: 'SK-1', negative: 0, status: 'DRAFT', amount: '21.00' };
    request.mockImplementation(async ({ url }) => {
      if (url.endsWith('/list')) return page([refund, draft]);
      if (url.endsWith(id)) return ok({ ...refund, allocations: [] });
      return ok([]);
    });
    const root = mount(Receipt);
    await settle();
    const refundRow = root.querySelector(`[data-row="${id}"]`)!;
    const draftRow = root.querySelector(`[data-row="${otherId}"]`)!;
    expect(refundRow.querySelector('[data-col="amount"]')?.textContent).toBe('-50.00');
    expect(draftRow.querySelector('[data-col="amount"]')?.textContent).toBe('21.00');
    expect(Array.from(refundRow.querySelectorAll('[data-action]')).map((el) => el.getAttribute('data-action'))).toEqual(['查看', '打印']);
    expect(
      refundRow.querySelector('[data-action="编辑"], [data-action="确认"], [data-action="撤回"], [data-action="作废"], [data-action="删除"]')
    ).toBeNull();
    expect(draftRow.querySelector('[data-action="编辑"]')).not.toBeNull();
    refundRow.querySelector<HTMLButtonElement>('[data-action="查看"] button')!.click();
    await settle();
    expect(root.querySelector<HTMLInputElement>('[data-label="收款金额"] input')?.value).toBe('-50.00');
    expect(root.querySelector('[data-dialog]')?.textContent).not.toContain('保存草稿');
  });

  it('keeps the refund account selected when editing a sale return and sends the original ID on save', async () => {
    const draft = { id, no: 'RET-1', status: 'DRAFT', saleId: '9', needRefund: 1, refundAmount: '50.00', refundAccountId: otherId };
    request.mockImplementation(async ({ url, method }) => {
      if (url.endsWith('/list')) return page([draft]);
      if (url.endsWith('/options')) return ok([{ id: otherId, name: '退款结算账户' }]);
      if (url.endsWith(id) && method === 'get') return ok({ ...draft, items: [{ saleItemId: '10', qty: 2, price: 40 }] });
      return ok(null);
    });
    const root = mount(SaleReturn);
    await settle();
    root.querySelector<HTMLButtonElement>(`[data-row="${id}"] [data-action="编辑"] button`)!.click();
    await settle();
    expect(root.querySelector('[data-select="请选择账户"]')?.getAttribute('data-selected')).toBe(otherId);
    button(root, '保存草稿').click();
    await settle();
    expect(request.mock.calls.find(([config]) => config.method === 'put')?.[0].data).toMatchObject({ refundAccountId: otherId });
  });

  it('sends real stock page and filters, then uses the paired log cursor and resets it on date change', async () => {
    const balances = '/erp/stock/balances';
    const logs = '/erp/stock/logs';
    const row = { productId: id, warehouseId: 7, productName: 'Steel', qty: '15.00' };
    request.mockImplementation(async ({ url, params }) => {
      if (url === balances) return page([row], 457);
      if (url === logs)
        return ok({
          rows: [],
          hasMore: !params?.beforeId,
          nextBeforeDate: params?.beforeId ? null : params?.endDate,
          nextBeforeId: params?.beforeId ? null : otherId
        });
      return ok([]);
    });
    const root = mount(Stock);
    await settle();
    expect(root.textContent).toContain('共 457 条');
    button(root, '表格翻页').click();
    await settle();
    expect(calls(balances).at(-1)?.params).toMatchObject({ pageNum: 2, pageSize: 20, type: 'PRODUCT' });
    enter(root.querySelector<HTMLInputElement>('input[placeholder="请输入产品编码、名称或规格"]')!, ' 钢材 ');
    button(root, '搜索').click();
    await settle();
    expect(calls(balances).at(-1)?.params).toMatchObject({ pageNum: 1, keyword: '钢材', type: 'PRODUCT' });
    root.querySelector<HTMLButtonElement>('[data-action="明细"] button')!.click();
    await settle();
    const first = calls(logs)[0].params;
    expect(first).toMatchObject({ productId: id, startDate: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/), pageSize: 50 });
    expect(first).not.toHaveProperty('beforeId');
    button(root, '下一页').click();
    await settle();
    expect(calls(logs).at(-1)?.params).toEqual({ ...first, beforeDate: first?.endDate, beforeId: otherId });
    expect(button(root, '下一页').disabled).toBe(true);
    enter(root.querySelector<HTMLInputElement>('[data-dates]')!, '2026-01-01,2026-12-31', 'change');
    await settle();
    expect(calls(logs).at(-1)?.params).toEqual({ productId: id, startDate: '2026-01-01', endDate: '2026-12-31', pageSize: 50 });
  });

  it('queries product quantities by the visible IDs and keeps selections outside the current page', async () => {
    const product = (productId: string, code: string, status = 1) => ({ id: productId, code, name: code, type: 'MATERIAL', qtyScale: 3, status });
    const selected = product(id, 'STOPPED', 0);
    const p1 = product('1', 'P1');
    const p2 = product(otherId, 'P2');
    request.mockImplementation(async ({ url, params, data }) => {
      if (url === '/erp/master/products/options') return params?.pageNum === 1 ? options([p1], 2) : options([p2]);
      if (url === '/erp/master/products/options/by-ids') return ok([selected]);
      if (url === '/erp/stock/balances/quantities') {
        const { ids } = data as { ids: string[] };
        return ok(ids.map((productId) => ({ productId, qty: '3.125' })));
      }
      return ok([]);
    });
    const change = vi.fn();
    const root = mount(ProductSelect, { modelValue: [id], multiple: true, type: 'MATERIAL', onChangeMultiple: change });
    root.querySelector<HTMLElement>('.product-picker__trigger')!.click();
    await settle();
    expect(calls('/erp/master/products/options/by-ids')[0].data).toEqual({ ids: [id] });
    expect(calls('/erp/master/products/options')[0].params).toMatchObject({ pageNum: 1, type: 'MATERIAL' });
    expect(root.querySelector('[data-col="stock"]')?.textContent).toBe('3.125');
    root.querySelector<HTMLElement>('[data-row="1"]')!.click();
    button(root, '下一页').click();
    await settle();
    root.querySelector<HTMLElement>(`[data-row="${otherId}"]`)!.click();
    button(root, '确 定').click();
    expect(change).toHaveBeenCalledWith([selected, p1, p2]);
    expect(calls('/erp/stock/balances/quantities').map(({ data }) => data)).toEqual([{ ids: ['1'] }, { ids: [otherId] }]);
    expect(calls('/erp/stock/balances')).toHaveLength(0);
  });

  it('discards an older source-order response after a newer keyword search', async () => {
    const first = deferred();
    request.mockImplementation(({ params }) =>
      params?.keyword ? Promise.resolve(page([{ id: otherId, no: 'LATEST', customerId: id }])) : first.response
    );
    const root = mount(SourceOrderSelect, { kind: 'sale' });
    const input = root.querySelector<HTMLInputElement>('input')!;
    input.dispatchEvent(new Event('focus'));
    enter(input, 'LATEST');
    await settle();
    first.resolve(page([{ id, no: 'OLD', customerId: id }]));
    await settle();
    expect(root.textContent).toContain('LATEST');
    expect(root.textContent).not.toContain('OLD');
  });
});
