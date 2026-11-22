// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, nextTick } from 'vue';
import Payment from './index.vue';

const api = vi.hoisted(() => ({
  arApDetail: vi.fn(),
  getPayment: vi.fn(),
  listPayment: vi.fn(),
  addPayment: vi.fn(),
  updatePayment: vi.fn(),
  confirmPayment: vi.fn(),
  withdrawPayment: vi.fn(),
  voidPayment: vi.fn(),
  delPayment: vi.fn()
}));
vi.mock('@/api/erp/finance', () => api);
vi.mock('@/api/erp/account', () => ({ accountOptions: async () => ({ data: [{ id: '9007199254740993', name: '仅名称选项' }] }) }));
vi.mock('@/utils/permission', () => ({ checkPermi: () => true }));
vi.mock('@/components/erp/PartnerSelect.vue', () => ({ default: { render: () => null } }));
vi.mock('@/components/erp/StatusTag.vue', () => ({ default: { render: () => null } }));
vi.mock('@/components/RightToolbar/index.vue', () => ({ default: { render: () => null } }));
vi.mock('@/components/Pagination/index.vue', () => ({ default: { render: () => null } }));
vi.mock('tdesign-vue-next', async () => {
  const { defineComponent, h } = await import('vue');
  const slot = defineComponent({
    setup(_, { slots, expose }) {
      expose({ validate: async () => true, clearValidate: () => undefined });
      return () => h('div', [slots.default?.(), slots.footer?.()]);
    }
  });
  return Object.fromEntries(
    [
      'Row',
      'Col',
      'Card',
      'Form',
      'FormItem',
      'Select',
      'Button',
      'Tooltip',
      'Dialog',
      'DatePicker',
      'InputNumber',
      'Input',
      'Tag',
      'Checkbox',
      'Table'
    ].map((name) => [name, slot])
  );
});
const deferred = () => {
  let resolve!: (value: unknown) => void;
  const promise = new Promise<unknown>((done) => {
    resolve = done;
  });
  return { promise, resolve };
};
const ledger = (id: string) => ({ data: [{ id, sourceNo: id, amount: '10', settledAmount: '0', unsettledAmount: '10', status: 'OPEN' }] });
let cleanup: (() => void) | undefined;
function mount() {
  const root = document.createElement('div');
  const app = createApp(Payment);
  app.directive('hasPermi', {});
  app.directive('loading', {});
  Object.defineProperty(app.config.globalProperties, 'animate', { value: { searchAnimate: { enter: '', leave: '' } } });
  Object.defineProperty(app.config.globalProperties, '$modal', { value: { msgWarning: vi.fn() } });
  const vm = app.mount(root) as unknown as {
    $: {
      setupState: {
        form: { supplierId?: string };
        dialog: { visible: boolean };
        readonly: boolean;
        allocRows: Array<{ arApId: string; sourceNo?: string; sourceType?: string }>;
        detailLoading: boolean;
        handleAdd: () => Promise<void>;
        handlePartyChange: () => Promise<void>;
        loadDetail: (id: string, readonly: boolean) => Promise<void>;
        submitForm: (confirm: boolean) => Promise<void>;
      };
    };
  };
  cleanup = () => app.unmount();
  return vm.$.setupState;
}
beforeEach(() => {
  vi.clearAllMocks();
  api.listPayment.mockResolvedValue({ rows: [], total: 0 });
});
afterEach(() => {
  cleanup?.();
  cleanup = undefined;
});

describe('actual payment detail/ledger ownership', () => {
  it('handles supplier A-B-A without reviving the first A or releasing current loading', async () => {
    const first = deferred();
    const latest = deferred();
    api.arApDetail.mockReturnValueOnce(first.promise).mockResolvedValueOnce(ledger('B')).mockReturnValueOnce(latest.promise);
    const state = mount();
    state.form.supplierId = 'A';
    const a = state.handlePartyChange();
    state.form.supplierId = 'B';
    await state.handlePartyChange();
    state.form.supplierId = 'A';
    const current = state.handlePartyChange();
    first.resolve(ledger('A-old'));
    await a;
    expect(state.allocRows).toEqual([]);
    expect(state.detailLoading).toBe(true);
    latest.resolve(ledger('A-current'));
    await current;
    expect(state.allocRows[0].arApId).toBe('A-current');
  });

  it('invalidates a pending detail on close/reopen and keeps long IDs on the boundary', async () => {
    const old = deferred();
    api.getPayment.mockReturnValueOnce(old.promise);
    const state = mount();
    await state.handleAdd();
    const pending = state.loadDetail('9007199254740993', false);
    state.dialog.visible = false;
    await state.handleAdd();
    old.resolve({ data: { id: '9007199254740993', supplierId: 'old', allocations: [] } });
    await pending;
    expect(api.getPayment).toHaveBeenCalledWith('9007199254740993');
    expect(api.arApDetail).not.toHaveBeenCalled();
    expect(state.form.supplierId).toBeUndefined();
    expect(state.detailLoading).toBe(false);
  });

  it('keeps source metadata on settled allocations and refuses writes from readonly details', async () => {
    api.getPayment.mockResolvedValue({
      data: {
        id: '1',
        supplierId: '9007199254740993',
        amount: '10',
        allocations: [{ arApId: '9007199254740995', amount: '10', sourceNo: '期初应付', sourceType: 'OPENING' }]
      }
    });
    api.arApDetail.mockResolvedValue({ data: [] });
    const state = mount();
    await state.loadDetail('1', true);
    await nextTick();
    expect(state.allocRows[0]).toMatchObject({ arApId: '9007199254740995', sourceNo: '期初应付', sourceType: 'OPENING' });
    await state.submitForm(true);
    expect(api.updatePayment).not.toHaveBeenCalled();
    expect(api.addPayment).not.toHaveBeenCalled();
  });
});
