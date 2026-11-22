// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, nextTick } from 'vue';
import Finance from './index.vue';

type Config = { url: string; params?: Record<string, unknown> };
const { request } = vi.hoisted(() => ({ request: vi.fn<(config: Config) => Promise<unknown>>() }));
vi.mock('@/utils/request', () => ({ default: request }));
vi.mock('tdesign-icons-vue-next', () => ({ ArrowLeftIcon: { render: () => null } }));

// 正式 Vite 会把控件自动转为静态导入；在模块边界桩化，不依赖 app.component 覆盖。
// 页面、API 封装、状态展示保持真实，控件只负责渲染和发出原事件。
vi.mock('tdesign-vue-next', async () => {
  const { defineComponent, h } = await import('vue');
  const container = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  });
  const table = defineComponent({
    props: { data: Array<Record<string, unknown>>, columns: Array<{ colKey: string }>, rowKey: String, empty: String },
    emits: ['row-click'],
    setup:
      (props, { slots, emit }) =>
      () =>
        h('table', { 'data-table': props.rowKey }, [
          h(
            'tbody',
            props.data?.length
              ? props.data.map((row) =>
                  h(
                    'tr',
                    {
                      'data-row': String(row[props.rowKey!]),
                      onClick: () => emit('row-click', { row })
                    },
                    props.columns?.map((col) => h('td', { 'data-col': col.colKey }, slots[col.colKey]?.({ row }) ?? String(row[col.colKey] ?? '')))
                  )
                )
              : [h('tr', h('td', props.empty))]
          )
        ])
  });
  const tabs = defineComponent({
    emits: ['update:modelValue', 'change'],
    setup:
      (_, { emit }) =>
      () =>
        h(
          'div',
          ['AR', 'AP'].map((value) =>
            h(
              'button',
              {
                'data-party': value,
                onClick: () => {
                  emit('update:modelValue', value);
                  emit('change', value);
                }
              },
              value
            )
          )
        )
  });
  return { Row: container, Col: container, TabPanel: container, Button: container, Tag: container, Table: table, Tabs: tabs };
});
vi.mock('@/components/Pagination/index.vue', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    default: defineComponent({
      props: { page: Number, limit: Number, total: Number },
      emits: ['update:page', 'update:limit', 'pagination'],
      setup:
        (props, { emit }) =>
        () =>
          h('nav', { 'data-total': props.total, 'data-page': props.page }, [
            h(
              'button',
              {
                'data-next': '',
                onClick: () => {
                  emit('update:page', (props.page ?? 1) + 1);
                  emit('pagination');
                }
              },
              '下一页'
            ),
            h(
              'button',
              {
                'data-size': '',
                onClick: () => {
                  emit('update:limit', 50);
                  emit('update:page', 1);
                  emit('pagination');
                }
              },
              '每页50'
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
let cleanup: (() => void) | undefined;
const flush = async () => {
  await new Promise((resolve) => setTimeout(resolve, 0));
  await nextTick();
};
const ok = (data: unknown) => ({ code: 200, msg: 'ok', data });
const group = (id = '9007199254740993', name = '当前页单位') => ({
  partyId: id,
  partyName: name,
  amount: '100.00',
  settled: '20.00',
  unsettled: '80.00',
  opening: '0.00'
});
const summary = (parties = [group()], total = 47) =>
  ok({
    partyType: 'AR',
    totalAmount: '9100.25',
    settledAmount: '2200.00',
    unsettledAmount: '6900.25',
    openingAmount: '2000.00',
    total,
    parties
  });
const detail = (id = 'ledger-1', total = 43) => ({
  code: 200,
  msg: 'ok',
  total,
  rows: [
    {
      id,
      sourceId: '99',
      bizDate: '2026-11-20 18:01:02',
      sourceType: 'SALE',
      sourceNo: 'XS-99',
      amount: '300.00',
      settledAmount: '300.00',
      unsettledAmount: '0.00',
      status: 'SETTLED',
      statusText: '已结清'
    },
    {
      id: `${id}-return`,
      bizDate: null,
      sourceType: 'SALE_RETURN',
      sourceNo: 'XT-100',
      amount: '-50.25',
      settledAmount: '0.00',
      unsettledAmount: '-50.25',
      status: 'OPEN',
      statusText: '未结清'
    }
  ]
});
const deferred = () => {
  let resolve!: (value: unknown) => void;
  const promise = new Promise<unknown>((done) => {
    resolve = done;
  });
  return { promise, resolve };
};
function mount() {
  const root = document.createElement('div');
  document.body.append(root);
  const app = createApp(Finance);
  app.directive('loading', {});
  app.mount(root);
  cleanup = () => {
    app.unmount();
    root.remove();
  };
  return root;
}
function click(root: HTMLElement, selector: string) {
  const element = root.querySelector<HTMLElement>(selector);
  expect(element, selector).not.toBeNull();
  element!.click();
}
const values = (root: HTMLElement) => Array.from(root.querySelectorAll('.stat-value'), (element) => element.textContent);

beforeEach(() => {
  request.mockReset();
});
afterEach(() => {
  cleanup?.();
  cleanup = undefined;
});

describe('actual finance summary/detail page', () => {
  it('uses server group count and all-match totals across pages and page sizes, not page sums', async () => {
    request.mockImplementation(async ({ params }) => summary([group(String(params?.pageNum), `单位第${params?.pageNum}页`)]));
    const root = mount();
    await flush();
    expect(request).toHaveBeenLastCalledWith(
      expect.objectContaining({
        url: '/erp/finance/ar-ap/summary/page',
        params: { partyType: 'AR', pageNum: 1, pageSize: 20 }
      })
    );
    expect(values(root)).toEqual(['9,100.25', '2,200.00', '6,900.25', '2,000.00']);
    expect(root.querySelector('nav')?.getAttribute('data-total')).toBe('47');
    click(root, 'nav [data-next]');
    await flush();
    expect(request).toHaveBeenLastCalledWith(expect.objectContaining({ params: { partyType: 'AR', pageNum: 2, pageSize: 20 } }));
    expect(root.textContent).toContain('单位第2页');
    expect(root.textContent).not.toContain('单位第1页');
    expect(values(root)[2]).toBe('6,900.25');
    click(root, 'nav [data-size]');
    await flush();
    expect(request).toHaveBeenLastCalledWith(expect.objectContaining({ params: { partyType: 'AR', pageNum: 1, pageSize: 50 } }));
  });

  it('paginates detail separately while retaining settled/negative rows, business date, source number and status', async () => {
    request.mockImplementation(async ({ url }) => (url.endsWith('/summary/page') ? summary() : detail()));
    const root = mount();
    await flush();
    click(root, '[data-table="partyId"] tr');
    await flush();
    expect(request).toHaveBeenLastCalledWith(
      expect.objectContaining({
        url: '/erp/finance/ar-ap/detail/page',
        params: { partyType: 'AR', partyId: '9007199254740993', pageNum: 1, pageSize: 20 }
      })
    );
    expect(root.querySelector('[data-table="id"]')?.textContent).toContain('2026-11-20');
    expect(root.querySelector('[data-table="id"]')?.textContent).toContain('XS-99');
    expect(root.querySelector('[data-table="id"]')?.textContent).toContain('已结清');
    expect(root.querySelector('[data-table="id"]')?.textContent).toContain('-50.25');
    expect(root.querySelectorAll('nav')[1].getAttribute('data-total')).toBe('43');
    click(root, 'nav:nth-of-type(1) [data-next]'); // 第一块汇总导航独立于明细导航。
    await flush();
    expect(request).toHaveBeenLastCalledWith(expect.objectContaining({ url: '/erp/finance/ar-ap/summary/page' }));
    expect(root.querySelector('[data-table="id"]')).toBeNull();
    click(root, '[data-table="partyId"] tr');
    await flush();
    (root.querySelectorAll('nav')[1].querySelector('[data-next]') as HTMLElement).click();
    await flush();
    expect(request).toHaveBeenLastCalledWith(
      expect.objectContaining({
        url: '/erp/finance/ar-ap/detail/page',
        params: { partyType: 'AR', partyId: '9007199254740993', pageNum: 2, pageSize: 20 }
      })
    );
    expect(values(root)[2]).toBe('6,900.25');
  });

  it('resets both page cursors on AR/AP change and ignores delayed old summary/detail responses', async () => {
    const oldSummary = deferred();
    const oldDetail = deferred();
    let arCalls = 0;
    request.mockImplementation(async ({ url, params }) => {
      if (url.endsWith('/detail/page')) return oldDetail.promise;
      if (params?.partyType === 'AP') return summary([group('supplier-8', '当前供应商')]);
      return ++arCalls === 1 ? summary() : oldSummary.promise;
    });
    const root = mount();
    await flush();
    click(root, '[data-table="partyId"] tr');
    click(root, 'nav [data-next]');
    await flush();
    click(root, '[data-party="AP"]');
    await flush();
    oldSummary.resolve(summary([group('stale-party', '过期客户')]));
    oldDetail.resolve(detail('stale-ledger'));
    await flush();
    expect(root.textContent).toContain('当前供应商');
    expect(root.textContent).not.toContain('过期客户');
    expect(root.querySelector('[data-table="id"]')).toBeNull();
    expect(root.querySelector('nav')?.getAttribute('data-page')).toBe('1');
    click(root, '[data-table="partyId"] tr');
    await flush();
    expect(request).toHaveBeenLastCalledWith(
      expect.objectContaining({
        params: { partyType: 'AP', partyId: 'supplier-8', pageNum: 1, pageSize: 20 }
      })
    );
  });

  it('resets the detail page for another party and rejects late detail from the previous party', async () => {
    const oldDetail = deferred();
    request.mockImplementation(async ({ url, params }) => {
      if (url.endsWith('/summary/page')) return summary([group('customer-1', '客户一'), group('customer-2', '客户二')]);
      return params?.partyId === 'customer-1' ? oldDetail.promise : detail('current-ledger');
    });
    const root = mount();
    await flush();
    click(root, '[data-row="customer-2"]');
    await flush();
    (root.querySelectorAll('nav')[1].querySelector('[data-next]') as HTMLElement).click();
    await flush();
    click(root, '[data-row="customer-1"]');
    await nextTick();
    expect(request).toHaveBeenLastCalledWith(
      expect.objectContaining({
        params: { partyType: 'AR', partyId: 'customer-1', pageNum: 1, pageSize: 20 }
      })
    );
    click(root, '[data-row="customer-2"]');
    await flush();
    oldDetail.resolve(detail('stale-ledger'));
    await flush();
    expect(root.querySelector('[data-row="current-ledger"]')).not.toBeNull();
    expect(root.querySelector('[data-row="stale-ledger"]')).toBeNull();
    expect(root.querySelectorAll('nav')[1].getAttribute('data-page')).toBe('1');
  });

  it('keeps global totals on an empty out-of-range group page', async () => {
    request.mockResolvedValue(summary([], 47));
    const root = mount();
    await flush();
    expect(root.textContent).toContain('暂无往来数据');
    expect(root.querySelector('nav')?.getAttribute('data-total')).toBe('47');
    expect(values(root)[2]).toBe('6,900.25');
  });

  it('shows detail failure without replacing global statistics and can retry by selecting the party', async () => {
    let calls = 0;
    request.mockImplementation(async ({ url }) => {
      if (url.endsWith('/summary/page')) return summary();
      if (++calls === 1) throw new Error('synthetic detail failure');
      return detail();
    });
    const root = mount();
    await flush();
    click(root, '[data-table="partyId"] tr');
    await flush();
    expect(root.textContent).toContain('明细加载失败，请重试');
    expect(values(root)[2]).toBe('6,900.25');
    click(root, '[data-table="partyId"] tr');
    await flush();
    expect(root.textContent).not.toContain('明细加载失败');
    expect(root.querySelector('[data-row="ledger-1"]')).not.toBeNull();
  });

  it('shows summary failure rather than fake zero totals and permits retry', async () => {
    request.mockRejectedValueOnce(new Error('synthetic failure')).mockResolvedValue(summary());
    const root = mount();
    await flush();
    expect(root.textContent).toContain('汇总加载失败，请重试');
    expect(values(root)).toEqual(['-', '-', '-', '-']);
    click(root, '[data-refresh]');
    await flush();
    expect(values(root)[2]).toBe('6,900.25');
    expect(root.textContent).not.toContain('汇总加载失败');
  });
});
