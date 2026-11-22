// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as Vue from 'vue';
import { createMemoryHistory, createRouter, routeLocationKey, routerKey } from 'vue-router';
import Sale from './index.vue';

const api = vi.hoisted(() => ({
  addSale: vi.fn(),
  updateSale: vi.fn(),
  listSale: vi.fn(),
  getSale: vi.fn(),
  getSalePriceHistory: vi.fn(),
  confirmSale: vi.fn(),
  delSale: vi.fn(),
  voidSale: vi.fn(),
  withdrawSale: vi.fn()
}));
vi.mock('@/api/erp/trade', () => api);
vi.mock('@/api/erp/print', () => ({ renderSaleDelivery: vi.fn() }));
vi.mock('@/api/erp/order', () => ({ listSaleOpenItems: vi.fn() }));
vi.mock('@/utils/permission', () => ({ checkPermi: () => true }));
vi.mock('@/components/erp/PartnerSelect.vue', () => ({ default: { render: () => null } }));
vi.mock('@/components/erp/ProductSelect.vue', () => ({ default: { render: () => null } }));
vi.mock('@/components/erp/SourceOrderSelect.vue', () => ({ default: { render: () => null } }));
vi.mock('@/components/erp/ProductFormFields.vue', () => ({ default: { render: () => null } }));
vi.mock('@/components/erp/StatusTag.vue', () => ({ default: { render: () => null } }));
vi.mock('tdesign-icons-vue-next', () => {
  const icon = { render: () => null };
  return {
    BrowseIcon: icon,
    CheckCircleIcon: icon,
    CloseCircleIcon: icon,
    DeleteIcon: icon,
    DownloadIcon: icon,
    Edit1Icon: icon,
    PenFluorescenceIcon: icon,
    PlusIcon: icon,
    PrintIcon: icon,
    RefreshIcon: icon,
    RollbackIcon: icon,
    SearchIcon: icon
  };
});

vi.mock('@/components/RightToolbar/index.vue', () => ({ default: { render: () => null } }));
vi.mock('@/components/Pagination/index.vue', () => ({ default: { render: () => null } }));
// 正式 auto-import 从模块读取函数，stubGlobal 不会替换静态导入；仅隔离无关历史价格计时器。
vi.mock('@vueuse/core', () => ({ useDebounceFn: () => vi.fn() }));
vi.mock('tdesign-vue-next', async () => {
  const { defineComponent, h } = await import('vue');
  const container = defineComponent({
    setup(_, { slots, expose }) {
      expose({ clearValidate: () => undefined });
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
  const drawer = defineComponent({
    props: { visible: Boolean },
    setup:
      (props, { slots }) =>
      () =>
        props.visible ? h('section', { 'data-sale-drawer': '' }, [slots.header?.(), slots.default?.(), slots.footer?.()]) : null
  });
  const button = defineComponent({
    props: { loading: Boolean, disabled: Boolean },
    emits: ['click'],
    setup:
      (props, { slots, emit }) =>
      () =>
        h(
          'button',
          {
            disabled: props.disabled,
            'data-loading': String(props.loading),
            onClick: () => emit('click')
          },
          slots.default?.()
        )
  });
  return {
    Row: container,
    Col: container,
    Form: container,
    FormItem: container,
    Select: container,
    Tooltip: container,
    DatePicker: container,
    InputNumber: container,
    Input: container,
    Tag: container,
    Table: container,
    Checkbox: container,
    Alert: container,
    Button: button,
    Drawer: drawer,
    Dialog: dialog
  };
});

// 真实销售页的 submitForm / buildPayload / validateRows；局部实例类型不补写生产 API。
type TestVm = {
  $: {
    setupState: {
      form: { id?: string; customerId?: string; bizDate: string; freightAmount: number };
      rows: { productId?: string; qty: number; price: number; priceTouched?: boolean }[];
      dialog: { visible: boolean };
      saving: boolean;
      readonly: boolean;
      handleAdd: () => Promise<void>;
      submitForm: (confirm: boolean) => Promise<void>;
      handleConfirm: (row: { id: string; no: string }) => Promise<void>;
    };
  };
};
const modal = { msgWarning: vi.fn(), msgSuccess: vi.fn(), confirm: vi.fn() };
let cleanup: (() => void) | undefined;
const deferred = () => {
  let resolve!: (value: unknown) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise((done, fail) => {
    resolve = done;
    reject = fail;
  });
  return { promise, resolve, reject };
};
function mount() {
  const root = document.createElement('div');
  document.body.append(root);
  const app = Vue.createApp(Sale);
  Object.defineProperty(app.config.globalProperties, '$modal', { value: modal });
  Object.defineProperty(app.config.globalProperties, 'animate', { value: { searchAnimate: { enter: '', leave: '' } } });
  // useRoute/useRouter 保持正式 auto-import 接线，以真实 router 的注入 key 提供空查询路由。
  const router = createRouter({ history: createMemoryHistory(), routes: [] });
  app.provide(routerKey, router);
  app.provide(routeLocationKey, router.currentRoute.value);
  app.directive('hasPermi', {});
  app.directive('loading', {});
  const vm = app.mount(root) as unknown as TestVm;
  cleanup = () => {
    app.unmount();
    root.remove();
  };
  return { root, state: vm.$.setupState };
}
async function validForm() {
  const view = mount();
  await view.state.handleAdd();
  Object.assign(view.state.form, { customerId: 'customer-1', bizDate: '2026-11-20', freightAmount: 5 });
  Object.assign(view.state.rows[0], { productId: 'product-1', qty: 2, price: 15, priceTouched: true });
  await Vue.nextTick();
  return view;
}
function saveButtons(root: HTMLElement) {
  return Array.from(root.querySelectorAll<HTMLButtonElement>('[data-sale-drawer] button')).filter((item) =>
    ['保存为待出库', '保存并确认出库'].includes(item.textContent?.trim() ?? '')
  );
}

beforeEach(() => {
  for (const mock of Object.values(api)) mock.mockReset();
  for (const mock of Object.values(modal)) mock.mockReset();
  api.listSale.mockResolvedValue({ rows: [], total: 0 });
  api.addSale.mockResolvedValue({ data: {} });
  api.updateSale.mockResolvedValue({ data: {} });
});
afterEach(() => {
  cleanup?.();
  cleanup = undefined;
});

describe('P10 actual sale shared save lock', () => {
  it.each([false, true])('blocks repeat/cross-mode create while confirm=%s is pending and binds both buttons', async (confirm) => {
    const pending = deferred();
    api.addSale.mockReturnValue(pending.promise);
    const { state, root } = await validForm();
    const first = state.submitForm(confirm);
    const repeat = state.submitForm(confirm);
    const crossMode = state.submitForm(!confirm);
    expect(api.addSale).toHaveBeenCalledTimes(1);
    expect(api.addSale).toHaveBeenCalledWith(
      expect.objectContaining({
        customerId: 'customer-1',
        bizDate: '2026-11-20',
        freightAmount: 5,
        confirm,
        items: [expect.objectContaining({ productId: 'product-1', qty: 2, price: 15 })]
      })
    );
    await Vue.nextTick();
    expect(saveButtons(root)).toHaveLength(2);
    expect(saveButtons(root).every((item) => item.disabled && item.dataset.loading === 'true')).toBe(true);
    pending.resolve({ data: { costWarnings: ['无成本产品'] } });
    await Promise.all([first, repeat, crossMode]);
    expect(state.saving).toBe(false);
    expect(state.dialog.visible).toBe(false);
    expect(api.listSale).toHaveBeenCalledTimes(2);
    expect(modal.confirm).not.toHaveBeenCalled(); // submitForm 原本没有二次确认，不增加业务交互。
    if (confirm) expect(modal.msgWarning).toHaveBeenCalledWith(expect.stringContaining('本次毛利将按 100% 计'));
    else expect(modal.msgWarning).not.toHaveBeenCalled();
  });

  it('uses the same lock for update without switching to the create API', async () => {
    const pending = deferred();
    api.updateSale.mockReturnValue(pending.promise);
    const { state } = await validForm();
    state.form.id = 'sale-42';
    const first = state.submitForm(false);
    const duplicate = state.submitForm(true);
    expect(api.updateSale).toHaveBeenCalledTimes(1);
    expect(api.updateSale).toHaveBeenCalledWith(expect.objectContaining({ id: 'sale-42', confirm: false }));
    expect(api.addSale).not.toHaveBeenCalled();
    pending.resolve({ data: {} });
    await Promise.all([first, duplicate]);
    expect(state.saving).toBe(false);
  });

  it.each([false, true])('releases the lock and leaves the form open after confirm=%s fails, allowing retry', async (confirm) => {
    api.addSale.mockRejectedValueOnce(new Error('save failed'));
    const { state, root } = await validForm();
    await expect(state.submitForm(confirm)).rejects.toThrow('save failed');
    await Vue.nextTick();
    expect(state.saving).toBe(false);
    expect(state.dialog.visible).toBe(true);
    expect(saveButtons(root)).toHaveLength(2);
    expect(saveButtons(root).every((item) => !item.disabled && item.dataset.loading === 'false')).toBe(true);
    expect(modal.msgSuccess).not.toHaveBeenCalled();
    await state.submitForm(confirm);
    expect(api.addSale).toHaveBeenCalledTimes(2);
    expect(state.saving).toBe(false);
  });

  it('holds the lock through list refresh and releases it even if refresh fails', async () => {
    const refresh = deferred();
    const { state } = await validForm();
    api.listSale.mockReturnValueOnce(refresh.promise);
    const first = state.submitForm(false);
    await Vue.nextTick();
    expect(api.listSale).toHaveBeenCalledTimes(2);
    const duplicate = state.submitForm(true);
    expect(api.addSale).toHaveBeenCalledTimes(1);
    expect(state.saving).toBe(true);
    const failure = expect(first).rejects.toThrow('refresh failed');
    refresh.reject(new Error('refresh failed'));
    await Promise.all([failure, duplicate]);
    expect(state.saving).toBe(false);
    expect(state.dialog.visible).toBe(false);
  });

  it('does not retain loading after validation fails and allows corrected input', async () => {
    const { state } = await validForm();
    state.form.customerId = undefined;
    await state.submitForm(true);
    expect(api.addSale).not.toHaveBeenCalled();
    expect(modal.msgWarning).toHaveBeenCalledWith('请选择客户');
    expect(state.saving).toBe(false);
    state.form.customerId = 'customer-1';
    await state.submitForm(false);
    expect(api.addSale).toHaveBeenCalledTimes(1);
    expect(state.saving).toBe(false);
  });

  it('does not save in readonly mode even if the handler is invoked directly', async () => {
    const { state } = await validForm();
    state.readonly = true;
    await state.submitForm(true);
    expect(api.addSale).not.toHaveBeenCalled();
    expect(api.updateSale).not.toHaveBeenCalled();
  });

  it('preserves existing row-confirm cancellation and leaves subsequent saving available', async () => {
    modal.confirm.mockRejectedValueOnce(new Error('cancel'));
    const { state } = await validForm();
    await state.handleConfirm({ id: 'sale-42', no: 'XS-42' });
    expect(api.confirmSale).not.toHaveBeenCalled();
    expect(state.saving).toBe(false);
    await state.submitForm(false);
    expect(api.addSale).toHaveBeenCalledTimes(1);
    expect(state.saving).toBe(false);
  });
});
