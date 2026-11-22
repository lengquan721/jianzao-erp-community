// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as Vue from 'vue';
import Receipt from './index.vue';

const api = vi.hoisted(() => ({
  arApDetail: vi.fn(),
  getReceipt: vi.fn(),
  listReceipt: vi.fn(),
  addReceipt: vi.fn(),
  updateReceipt: vi.fn(),
  confirmReceipt: vi.fn(),
  delReceipt: vi.fn(),
  voidReceipt: vi.fn(),
  withdrawReceipt: vi.fn()
}));
vi.mock('@/api/erp/finance', () => api);
vi.mock('@/api/erp/account', () => ({ accountOptions: vi.fn(async () => ({ data: [] })) }));
vi.mock('@/utils/permission', () => ({ checkPermi: () => true }));
vi.mock('@/components/erp/PartnerSelect.vue', () => ({ default: { render: () => null } }));
vi.mock('@/components/erp/StatusTag.vue', () => ({ default: { render: () => null } }));
vi.mock('tdesign-icons-vue-next', () => {
  const icon = { render: () => null };
  return {
    BrowseIcon: icon,
    CheckCircleIcon: icon,
    CloseCircleIcon: icon,
    DeleteIcon: icon,
    Edit1Icon: icon,
    PlusIcon: icon,
    PrintIcon: icon,
    RollbackIcon: icon
  };
});

vi.mock('@/components/RightToolbar/index.vue', () => ({ default: { render: () => null } }));
vi.mock('@/components/Pagination/index.vue', () => ({ default: { render: () => null } }));
// 正式 Vite 会把模板控件编译为模块导入；必须 mock 导入模块，而非仅 app.component。
vi.mock('tdesign-vue-next', async () => {
  const { defineComponent, h } = await import('vue');
  const container = defineComponent({
    setup(_, { slots, expose }) {
      expose({ clearValidate: () => undefined });
      return () => h('div', [slots.default?.(), slots.footer?.()]);
    }
  });
  const table = defineComponent({
    props: ['data', 'rowKey', 'loading'],
    setup: (props) => () =>
      h(
        'div',
        { 'data-table': props.rowKey, 'data-loading': String(props.loading) },
        props.data?.map((row: { arApId?: string; sourceNo?: string }) => h('span', row.sourceNo ?? row.arApId))
      )
  });
  return {
    Row: container,
    Col: container,
    Card: container,
    Form: container,
    FormItem: container,
    Select: container,
    Button: container,
    Tooltip: container,
    Dialog: container,
    DatePicker: container,
    InputNumber: container,
    Input: container,
    Tag: container,
    Checkbox: container,
    Table: table
  };
});

// 执行真实 SFC setup / 事件函数；局部类型仅描述测试读取的实例边界，不复制竞态逻辑。
type TestVm = {
  $: {
    setupState: {
      form: { customerId?: string };
      allocRows: { arApId: string; checked: boolean; allocAmount: number; unsettledAmount: number }[];
      detailLoading: boolean;
      dialog: { visible: boolean };
      handlePartyChange: () => Promise<void>;
      handleAdd: () => Promise<void>;
      loadDetail: (id: string, isReadonly: boolean) => Promise<void>;
    };
  };
};
const modal = { msgWarning: vi.fn(), msgSuccess: vi.fn(), confirm: vi.fn() };
let cleanup: (() => void) | undefined;
const flush = async () => {
  await new Promise((resolve) => setTimeout(resolve, 0));
  await Vue.nextTick();
};
const deferred = () => {
  let resolve!: (value: unknown) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise((done, fail) => {
    resolve = done;
    reject = fail;
  });
  return { promise, resolve, reject };
};
const ledger = (id: string) => ({
  data: [
    {
      id,
      sourceNo: id,
      bizDate: '2026-11-20',
      sourceType: 'SALE',
      amount: '100.00',
      settledAmount: '20.00',
      unsettledAmount: '80.00',
      status: 'OPEN'
    }
  ]
});
function mount() {
  const root = document.createElement('div');
  document.body.append(root);
  const app = Vue.createApp(Receipt);
  Object.defineProperty(app.config.globalProperties, '$modal', { value: modal });
  Object.defineProperty(app.config.globalProperties, 'animate', { value: { searchAnimate: { enter: '', leave: '' } } });
  app.directive('hasPermi', {});
  app.directive('loading', {});
  const vm = app.mount(root) as unknown as TestVm;
  cleanup = () => {
    app.unmount();
    root.remove();
  };
  return { root, state: vm.$.setupState };
}
function changeCustomer(state: ReturnType<typeof mount>['state'], id?: string) {
  state.form.customerId = id;
  return state.handlePartyChange();
}

beforeEach(() => {
  vi.clearAllMocks();
  api.arApDetail.mockReset();
  api.getReceipt.mockReset();
  api.listReceipt.mockResolvedValue({ rows: [], total: 0 });
});
afterEach(() => {
  cleanup?.();
  cleanup = undefined;
});

describe('P10 actual receipt ledger ownership', () => {
  it('keeps the latest customer when the older successful response arrives last', async () => {
    const old = deferred();
    const current = deferred();
    api.arApDetail.mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise);
    const { root, state } = mount();
    const a = changeCustomer(state, 'customer-A');
    const b = changeCustomer(state, 'customer-B');
    current.resolve(ledger('B-ledger'));
    await b;
    expect(state.allocRows.map((row: { arApId: string }) => row.arApId)).toEqual(['B-ledger']);
    old.resolve(ledger('A-stale'));
    await a;
    await Vue.nextTick();
    expect(root.querySelector('[data-table="arApId"]')?.textContent).toBe('B-ledger');
    expect(state.detailLoading).toBe(false);
    expect(api.arApDetail.mock.calls).toEqual([
      ['AR', 'customer-A', true],
      ['AR', 'customer-B', true]
    ]);
  });

  it('rejects the first A response after switching A to B and back to A', async () => {
    const firstA = deferred();
    api.arApDetail.mockReturnValueOnce(firstA.promise).mockResolvedValueOnce(ledger('B-ledger')).mockResolvedValueOnce(ledger('A-current'));
    const { state } = mount();
    const old = changeCustomer(state, 'customer-A');
    await changeCustomer(state, 'customer-B');
    await changeCustomer(state, 'customer-A');
    firstA.resolve(ledger('A-stale'));
    await old;
    expect(state.allocRows[0]?.arApId).toBe('A-current');
    expect(state.detailLoading).toBe(false);
  });

  it('does not let the previous request finally release the current loading state', async () => {
    const old = deferred();
    const current = deferred();
    api.arApDetail.mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise);
    const { state } = mount();
    const a = changeCustomer(state, 'customer-A');
    const b = changeCustomer(state, 'customer-B');
    old.resolve(ledger('A-stale'));
    await a;
    expect(state.allocRows).toEqual([]);
    expect(state.detailLoading).toBe(true);
    current.resolve(ledger('B-ledger'));
    await b;
    expect(state.detailLoading).toBe(false);
  });

  it('does not clear the latest rows when an older request fails', async () => {
    const old = deferred();
    api.arApDetail.mockReturnValueOnce(old.promise).mockResolvedValueOnce(ledger('B-ledger'));
    const { state } = mount();
    const a = changeCustomer(state, 'customer-A');
    await changeCustomer(state, 'customer-B');
    old.reject(new Error('old ledger failed'));
    await a;
    expect(state.allocRows[0]?.arApId).toBe('B-ledger');
    expect(state.detailLoading).toBe(false);
  });

  it('invalidates in-flight requests on clear without making an empty-customer API call', async () => {
    const old = deferred();
    api.arApDetail.mockReturnValueOnce(old.promise);
    const { state } = mount();
    const pending = changeCustomer(state, 'customer-A');
    await changeCustomer(state, undefined);
    expect(state.detailLoading).toBe(false);
    expect(state.allocRows).toEqual([]);
    old.resolve(ledger('A-stale'));
    await pending;
    expect(state.allocRows).toEqual([]);
    expect(api.arApDetail).toHaveBeenCalledTimes(1);
  });

  it('invalidates pending ledger data when reopening a new receipt', async () => {
    const old = deferred();
    api.arApDetail.mockReturnValueOnce(old.promise);
    const { state } = mount();
    const pending = changeCustomer(state, 'customer-A');
    await state.handleAdd();
    old.resolve(ledger('A-stale'));
    await pending;
    expect(state.form.customerId).toBeUndefined();
    expect(state.allocRows).toEqual([]);
    expect(state.detailLoading).toBe(false);
  });

  it.each(['customer-B', undefined])('does not revive saved allocations after detail customer changes to %s', async (customerId) => {
    const old = deferred();
    api.getReceipt.mockResolvedValue({ data: { id: 'receipt-1', customerId: 'customer-A', allocations: [{ arApId: 'A-saved', amount: 30 }] } });
    api.arApDetail.mockReturnValueOnce(old.promise).mockResolvedValueOnce(ledger('B-ledger'));
    const { state } = mount();
    const detail = state.loadDetail('receipt-1', false);
    await flush();
    await changeCustomer(state, customerId);
    old.resolve(ledger('A-stale'));
    await detail;
    expect(state.allocRows.map((row: { arApId: string }) => row.arApId)).toEqual(customerId ? ['B-ledger'] : []);
    expect(state.detailLoading).toBe(false);
  });

  it('does not let stale detail finally stop the new customer loading indicator', async () => {
    const old = deferred();
    const current = deferred();
    api.getReceipt.mockResolvedValue({ data: { id: 'receipt-1', customerId: 'customer-A', allocations: [{ arApId: 'A-saved', amount: 30 }] } });
    api.arApDetail.mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise);
    const { state } = mount();
    const detail = state.loadDetail('receipt-1', false);
    await flush();
    const b = changeCustomer(state, 'customer-B');
    old.resolve(ledger('A-stale'));
    await detail;
    expect(state.allocRows).toEqual([]);
    expect(state.detailLoading).toBe(true);
    current.resolve(ledger('B-ledger'));
    await b;
    expect(state.allocRows[0]?.arApId).toBe('B-ledger');
    expect(state.detailLoading).toBe(false);
  });

  it('invalidates a pending receipt detail on reset, before it can start a ledger request', async () => {
    const old = deferred();
    api.getReceipt.mockReturnValueOnce(old.promise);
    api.arApDetail.mockResolvedValue(ledger('A-stale'));
    const { state } = mount();
    const detail = state.loadDetail('receipt-old', false);
    await state.handleAdd();
    old.resolve({ data: { id: 'receipt-old', customerId: 'customer-A', allocations: [] } });
    await detail;
    expect(api.arApDetail).not.toHaveBeenCalled();
    expect(state.form.customerId).toBeUndefined();
    expect(state.allocRows).toEqual([]);
  });

  it('does not revive a detail or ledger after closing the dialog', async () => {
    const pending = deferred();
    api.getReceipt.mockReturnValueOnce(pending.promise);
    const { state } = mount();
    await state.handleAdd();
    const detail = state.loadDetail('receipt-1', false);
    state.dialog.visible = false;
    pending.resolve({ data: { id: 'receipt-1', customerId: 'old-customer', allocations: [] } });
    await detail;
    expect(api.arApDetail).not.toHaveBeenCalled();
    expect(state.form.customerId).toBeUndefined();
    expect(state.detailLoading).toBe(false);
  });

  it('uses saved allocation source metadata even when the settled ledger is no longer allocatable', async () => {
    api.getReceipt.mockResolvedValue({
      data: {
        id: 'receipt-1',
        customerId: 'customer-A',
        allocations: [{ arApId: '9007199254740993', amount: '12.34', sourceType: 'OPENING', sourceNo: '期初余额' }]
      }
    });
    api.arApDetail.mockResolvedValue({ data: [] });
    const { root, state } = mount();
    await state.loadDetail('receipt-1', true);
    await Vue.nextTick();
    expect(root.querySelector('[data-table="arApId"]')?.textContent).toBe('期初余额');
  });

  it('preserves filtering and saved allocation fallback when the current ledger fails', async () => {
    api.arApDetail.mockResolvedValueOnce({
      data: [
        ...ledger('open').data,
        { ...ledger('void').data[0], status: 'VOID' },
        { ...ledger('settled').data[0], unsettledAmount: 0 },
        { ...ledger('negative').data[0], unsettledAmount: -1 }
      ]
    });
    const { state } = mount();
    await changeCustomer(state, 'customer-A');
    expect(state.allocRows).toEqual([expect.objectContaining({ arApId: 'open', unsettledAmount: 80, checked: false, allocAmount: 0 })]);
    api.getReceipt.mockResolvedValue({ data: { id: 'receipt-1', customerId: 'customer-A', allocations: [{ arApId: 'saved', amount: '30.00' }] } });
    api.arApDetail.mockRejectedValueOnce(new Error('current ledger failed'));
    await state.loadDetail('receipt-1', false);
    expect(state.allocRows).toEqual([expect.objectContaining({ arApId: 'saved', checked: true, allocAmount: 30 })]);
    expect(state.detailLoading).toBe(false);
  });
});
