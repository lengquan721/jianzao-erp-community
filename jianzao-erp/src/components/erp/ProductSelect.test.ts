// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, reactive } from 'vue';
import type { ComponentPublicInstance, PropType } from 'vue';
import type { ErpId, ErpProductVO } from '@/api/erp/types';
import ProductSelect from './ProductSelect.vue';

type RequestConfig = { url: string; data?: { ids: ErpId[] }; params?: { pageNum?: number } };
const { request } = vi.hoisted(() => ({ request: vi.fn<(config: RequestConfig) => Promise<unknown>>() }));
vi.mock('@/utils/request', () => ({ default: request }));
vi.mock('@/utils/permission', () => ({ checkPermi: () => true }));
vi.mock('@/components/erp/StatusTag.vue', () => ({ default: { render: () => null } }));
vi.mock('tdesign-icons-vue-next', () => {
  const icon = { render: () => null };
  return { CloseCircleFilledIcon: icon, SearchIcon: icon };
});
// 正式 Vite 自动组件导入仍运行；只替换控件模块，真实 watcher/loader/API 分批不 mock。
vi.mock('tdesign-vue-next', async () => {
  const { defineComponent, h } = await import('vue');
  const container = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  });
  const input = defineComponent({
    props: { value: String, placeholder: String, disabled: Boolean },
    setup: (props) => () => h('input', { value: props.value ?? '', placeholder: props.placeholder, disabled: props.disabled, readonly: true })
  });
  const button = defineComponent({
    props: { disabled: Boolean },
    emits: ['click'],
    setup:
      (props, { slots, emit }) =>
      () =>
        h('button', { disabled: props.disabled, onClick: () => emit('click') }, slots.default?.())
  });
  const dialog = defineComponent({
    props: { visible: Boolean },
    setup:
      (props, { slots }) =>
      () =>
        props.visible ? h('section', { 'data-dialog': '' }, [slots.default?.(), slots.footer?.()]) : null
  });
  const table = defineComponent({
    props: { data: Array as PropType<ErpProductVO[]> },
    emits: ['row-click'],
    setup:
      (props, { emit }) =>
      () =>
        h(
          'div',
          { 'data-table': '' },
          props.data?.map((row) => h('button', { 'data-pick': String(row.id), onClick: () => emit('row-click', { row }) }, row.name))
        )
  });
  return { Input: input, Button: button, Dialog: dialog, Table: table, Select: container, CheckTag: container };
});

type Model = ErpId | ErpId[] | null | undefined;
type TestVm = { $: { setupState: { selected?: ErpProductVO } } };
const cleanups: Array<() => void> = [];
const product = (id: ErpId, name = `商品${id}`): ErpProductVO => ({
  id,
  code: `P${id}`,
  name,
  spec: '规格',
  unit: '件',
  type: 'PRODUCT',
  status: 0,
  qtyScale: 3,
  purchasePrice: 999,
  costPrice: 888
});
const ok = (data: unknown) => ({ code: 200, msg: 'ok', data });
const byIdsCalls = () => request.mock.calls.map(([config]) => config).filter((config) => config.url.endsWith('/options/by-ids'));
const flush = async () => {
  await new Promise((resolve) => setTimeout(resolve, 0));
  await nextTick();
};
function deferred() {
  let resolve!: (value: unknown) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise((done, fail) => {
    resolve = done;
    reject = fail;
  });
  return { promise, resolve, reject };
}
function mountRows(ids: Model[], multiple = false) {
  const rows = reactive(ids.map((id, key) => ({ key, id, price: 100.25 + key })));
  const initialPrices = rows.map((row) => row.price);
  const change = vi.fn<(key: number, value: ErpProductVO | undefined) => void>();
  const update = vi.fn<(key: number, value: ErpId | ErpId[] | undefined) => void>();
  const multipleChange = vi.fn<(key: number, products: ErpProductVO[]) => void>();
  const instances = new Map<number, TestVm>();
  const root = document.createElement('div');
  document.body.append(root);
  const app = createApp(() =>
    h(
      'div',
      rows.map((row) =>
        h('section', { key: row.key, 'data-row': row.key }, [
          h(ProductSelect, {
            ref: (instance: ComponentPublicInstance | Element | null) => {
              if (instance) instances.set(row.key, instance as unknown as TestVm);
            },
            modelValue: row.id,
            multiple,
            showStock: false,
            'onUpdate:modelValue': (value: ErpId | ErpId[] | undefined) => {
              update(row.key, value);
              row.id = value;
            },
            onChange: (value: ErpProductVO | undefined) => {
              change(row.key, value);
              // 模拟开单页依赖 change 重算单价；回显期间必须保持此事件完全静默。
              row.price = Number(value?.purchasePrice ?? 0);
            },
            'onChange-multiple': (products: ErpProductVO[]) => multipleChange(row.key, products)
          })
        ])
      )
    )
  );
  app.mount(root);
  let mounted = true;
  const unmount = () => {
    if (!mounted) return;
    mounted = false;
    app.unmount();
    root.remove();
  };
  cleanups.push(unmount);
  return { root, rows, initialPrices, change, update, multipleChange, instances, unmount };
}
function label(root: HTMLElement, row = 0) {
  const input = root.querySelector<HTMLInputElement>(`[data-row="${row}"] .product-picker__trigger input`);
  expect(input).not.toBeNull();
  return input!.value;
}
function silent(view: ReturnType<typeof mountRows>) {
  expect(view.change).not.toHaveBeenCalled();
  expect(view.update).not.toHaveBeenCalled();
  expect(view.multipleChange).not.toHaveBeenCalled();
  expect(view.rows.map((row) => row.price)).toEqual(view.initialPrices);
}
function open(root: HTMLElement) {
  const trigger = root.querySelector<HTMLElement>('.product-picker__trigger');
  expect(trigger).not.toBeNull();
  trigger!.click();
}
function clickButton(root: HTMLElement, text: string) {
  const button = Array.from(root.querySelectorAll<HTMLButtonElement>('[data-dialog] button')).find((item) => item.textContent?.trim() === text);
  expect(button, text).toBeDefined();
  button!.click();
  return button!;
}
function pick(root: HTMLElement, id: ErpId) {
  const button = root.querySelector<HTMLButtonElement>(`[data-pick="${id}"]`);
  expect(button).not.toBeNull();
  button!.click();
}

beforeEach(() => {
  request.mockReset().mockImplementation(async ({ url, data }) => {
    if (url.endsWith('/options/by-ids')) return ok(data!.ids.map((id) => product(id)).reverse());
    if (url === '/erp/master/products/options') return ok({ rows: [], hasMore: false, nextPageNum: null });
    throw new Error(`unexpected request ${url}`);
  });
});
afterEach(() => {
  for (const cleanup of cleanups.splice(0)) cleanup();
});

describe('actual ProductSelect row restore batching under the formal Vite config', () => {
  it('merges twenty distinct mounted row watchers into one HTTP batch without repricing', async () => {
    const ids = Array.from({ length: 20 }, (_, index) => String(9007199254740993n + BigInt(index)));
    const view = mountRows(ids);
    await flush();
    expect(byIdsCalls()).toHaveLength(1);
    expect(byIdsCalls()[0].data?.ids).toEqual(ids);
    ids.forEach((id, index) => expect(label(view.root, index)).toBe(`P${id} 商品${id} / 规格`));
    expect(view.rows.map((row) => row.id)).toEqual(ids);
    silent(view);
  });

  it('merges twenty rows with duplicate numeric/text IDs and maps shuffled responses to every row', async () => {
    const ids = Array.from({ length: 20 }, (_, index) => [7, '7', '9007199254740993', 'third'][index % 4]);
    const view = mountRows(ids);
    await flush();
    expect(byIdsCalls().map((config) => config.data?.ids)).toEqual([[7, '9007199254740993', 'third']]);
    ids.forEach((id, index) => expect(label(view.root, index)).toBe(`P${id} 商品${id} / 规格`));
    expect(view.rows.map((row) => row.id)).toEqual(ids);
    silent(view);
  });

  it('uses real 200+5 HTTP batches for 205 mounted rows, retaining the final five IDs', async () => {
    const ids = Array.from({ length: 205 }, (_, index) => String(9007199254740993n + BigInt(index)));
    const view = mountRows(ids);
    await flush();
    expect(byIdsCalls().map((config) => config.data?.ids.length)).toEqual([200, 5]);
    expect(byIdsCalls().flatMap((config) => config.data!.ids)).toEqual(ids);
    ids.forEach((id, index) => expect(label(view.root, index)).toBe(`P${id} 商品${id} / 规格`));
    silent(view);
  });

  it('keeps the latest generation when an A to B to A restore completes out of order', async () => {
    const firstA = deferred();
    const b = deferred();
    const currentA = deferred();
    request.mockReturnValueOnce(firstA.promise).mockReturnValueOnce(b.promise).mockReturnValueOnce(currentA.promise);
    const view = mountRows(['a']);
    await flush();
    view.rows[0].id = 'b';
    await flush();
    view.rows[0].id = 'a';
    await flush();
    currentA.resolve(ok([product('a', '当前A')]));
    await flush();
    b.resolve(ok([product('b', '过期B')]));
    firstA.resolve(ok([product('a', '过期A')]));
    await flush();
    expect(byIdsCalls().map((config) => config.data?.ids)).toEqual([['a'], ['b'], ['a']]);
    expect(label(view.root)).toBe('Pa 当前A / 规格');
    expect(view.instances.get(0)!.$.setupState.selected?.name).toBe('当前A');
    silent(view);
  });

  it.each([null, undefined, ''])('clears to %s without reviving a pending restore or emitting changes', async (empty) => {
    const pending = deferred();
    request.mockReturnValueOnce(pending.promise);
    const view = mountRows(['a']);
    await flush();
    view.rows[0].id = empty;
    await nextTick();
    pending.resolve(ok([product('a')]));
    await flush();
    expect(label(view.root)).toBe('');
    expect(view.rows[0].id).toBe(empty);
    expect(byIdsCalls()).toHaveLength(1);
    silent(view);
  });

  it('does not request or emit for initially null, undefined or blank IDs', async () => {
    const view = mountRows([null, undefined, '']);
    await flush();
    expect(request).not.toHaveBeenCalled();
    expect([0, 1, 2].map((index) => label(view.root, index))).toEqual(['', '', '']);
    silent(view);
  });

  it.each(['missing', 'failed'])('retains the ID and price after a %s restore, then permits a fresh retry', async (mode) => {
    if (mode === 'failed') request.mockRejectedValueOnce(new Error('restore failed'));
    else request.mockResolvedValueOnce(ok([]));
    const view = mountRows(['keep-id']);
    await flush();
    expect(label(view.root)).toBe('');
    expect(view.rows[0].id).toBe('keep-id');
    silent(view);
    view.rows[0].id = null;
    await nextTick();
    view.rows[0].id = 'keep-id';
    await flush();
    expect(byIdsCalls().map((config) => config.data?.ids)).toEqual([['keep-id'], ['keep-id']]);
    expect(label(view.root)).toBe('Pkeep-id 商品keep-id / 规格');
    silent(view);
  });

  it('does not write to an unmounted subscriber while its duplicate row still receives the shared result', async () => {
    const pending = deferred();
    request.mockReturnValueOnce(pending.promise);
    const view = mountRows(['shared', 'shared']);
    await flush();
    const removed = view.instances.get(0)!;
    view.rows.splice(0, 1);
    await nextTick();
    pending.resolve(ok([product('shared')]));
    await flush();
    expect(byIdsCalls().map((config) => config.data?.ids)).toEqual([['shared']]);
    expect(removed.$.setupState.selected).toBeUndefined();
    expect(label(view.root, 1)).toBe('Pshared 商品shared / 规格');
    expect(view.change).not.toHaveBeenCalled();
    expect(view.update).not.toHaveBeenCalled();
    expect(view.rows[0].price).toBe(view.initialPrices[1]);
  });

  it('honors cleanup even if unmounted before the shared microtask starts HTTP', async () => {
    const pending = deferred();
    request.mockReturnValueOnce(pending.promise);
    const view = mountRows(['gone']);
    const removed = view.instances.get(0)!;
    view.unmount();
    await flush();
    pending.resolve(ok([product('gone')]));
    await flush();
    expect(removed.$.setupState.selected).toBeUndefined();
    expect(byIdsCalls()).toHaveLength(1);
    silent(view);
  });

  it('refetches the same ID on a later mount instead of reusing a previous result', async () => {
    request.mockResolvedValueOnce(ok([product('same', '旧批次')]));
    const first = mountRows(['same']);
    await flush();
    expect(label(first.root)).toBe('Psame 旧批次 / 规格');
    first.unmount();
    request.mockResolvedValueOnce(ok([product('same', '新批次')]));
    const second = mountRows(['same']);
    await flush();
    expect(label(second.root)).toBe('Psame 新批次 / 规格');
    expect(byIdsCalls()).toHaveLength(2);
    silent(first);
    silent(second);
  });

  it('preserves multiple-mode byIds restore and cross-page selection without single change events', async () => {
    const savedIds = ['9007199254740993', 7];
    const saved = savedIds.map((id) => product(id));
    const firstPage = product('page-1');
    const secondPage = product('page-2');
    request.mockImplementation(async ({ url, data, params }) => {
      if (url.endsWith('/options/by-ids')) return ok(data!.ids.map((id) => saved.find((item) => item.id === id)!));
      return ok({ rows: [params?.pageNum === 2 ? secondPage : firstPage], hasMore: params?.pageNum === 1, nextPageNum: 2 });
    });
    const view = mountRows([savedIds], true);
    await flush();
    expect(request).not.toHaveBeenCalled();
    expect(label(view.root)).toBe('已选 2 项');
    open(view.root);
    await flush();
    expect(byIdsCalls().map((config) => config.data?.ids)).toEqual([savedIds]);
    pick(view.root, firstPage.id);
    await nextTick();
    clickButton(view.root, '下一页');
    await flush();
    pick(view.root, secondPage.id);
    await nextTick();
    clickButton(view.root, '确 定');
    await nextTick();
    expect(view.rows[0].id).toEqual([...savedIds, firstPage.id, secondPage.id]);
    expect(view.multipleChange).toHaveBeenCalledExactlyOnceWith(0, [...saved, firstPage, secondPage]);
    expect(view.update).toHaveBeenCalledExactlyOnceWith(0, [...savedIds, firstPage.id, secondPage.id]);
    expect(view.change).not.toHaveBeenCalled();
    expect(view.rows[0].price).toBe(view.initialPrices[0]);
    expect(byIdsCalls()).toHaveLength(1);
    expect(label(view.root)).toBe('已选 4 项');
  });

  it('keeps multiple IDs when restore fails, blocks confirmation and restores on reopening', async () => {
    let attempts = 0;
    request.mockImplementation(async ({ url }) => {
      if (!url.endsWith('/options/by-ids')) return ok({ rows: [], hasMore: false });
      if (++attempts === 1) throw new Error('multiple restore failed');
      return ok([product('saved')]);
    });
    const view = mountRows([['saved']], true);
    open(view.root);
    await flush();
    expect(clickButton(view.root, '确 定').disabled).toBe(true);
    expect(view.rows[0].id).toEqual(['saved']);
    silent(view);
    clickButton(view.root, '取 消');
    await nextTick();
    open(view.root);
    await flush();
    expect(clickButton(view.root, '确 定').disabled).toBe(false);
    await nextTick();
    expect(byIdsCalls()).toHaveLength(2);
    expect(view.rows[0].id).toEqual(['saved']);
    expect(view.multipleChange).toHaveBeenCalledExactlyOnceWith(0, [product('saved')]);
    expect(view.change).not.toHaveBeenCalled();
    expect(view.rows[0].price).toBe(view.initialPrices[0]);
  });

  it('still emits and reprices only on an explicit user confirmation, not on restore or row highlight', async () => {
    const chosen = product('chosen');
    request.mockImplementation(async ({ url }) => (url.endsWith('/options/by-ids') ? ok([product('old')]) : ok({ rows: [chosen], hasMore: false })));
    const view = mountRows(['old']);
    await flush();
    open(view.root);
    await flush();
    pick(view.root, chosen.id);
    await nextTick();
    expect(view.rows[0].id).toBe('old');
    silent(view);
    clickButton(view.root, '确 定');
    await nextTick();
    expect(view.update).toHaveBeenCalledExactlyOnceWith(0, 'chosen');
    expect(view.change).toHaveBeenCalledExactlyOnceWith(0, chosen);
    expect(view.rows[0].price).toBe(999);
    expect(label(view.root)).toBe('Pchosen 商品chosen / 规格');
  });
});
