// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, nextTick } from 'vue';
import FinancePrintDialog from './FinancePrintDialog.vue';

const api = vi.hoisted(() => ({ renderReceipt: vi.fn(), renderPayment: vi.fn() }));
vi.mock('@/api/erp/print', () => api);
vi.mock('tdesign-vue-next', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    Dialog: defineComponent({
      props: { visible: Boolean },
      emits: ['update:visible'],
      setup:
        (props, { slots, emit }) =>
        () =>
          props.visible
            ? h('section', [h('button', { 'data-close': '', onClick: () => emit('update:visible', false) }, '关闭'), slots.default?.()])
            : null
    }),
    Button: defineComponent({
      props: { disabled: Boolean },
      setup:
        (props, { slots }) =>
        () =>
          h('button', { disabled: props.disabled, 'data-print': '' }, slots.default?.())
    })
  };
});
const deferred = () => {
  let resolve!: (value: unknown) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<unknown>((done, fail) => {
    resolve = done;
    reject = fail;
  });
  return { promise, resolve, reject };
};
const flush = async () => {
  await Promise.resolve();
  await nextTick();
};
const warning = vi.fn();
let cleanup: (() => void) | undefined;
function mount() {
  const root = document.createElement('div');
  document.body.append(root);
  const app = createApp(FinancePrintDialog);
  app.directive('loading', {});
  Object.defineProperty(app.config.globalProperties, '$modal', { value: { msgWarning: warning } });
  const vm = app.mount(root) as unknown as { open: (kind: 'receipt' | 'payment', id: string) => Promise<void> };
  const unmount = () => {
    app.unmount();
    root.remove();
    cleanup = undefined;
  };
  cleanup = unmount;
  return { root, open: vm.open, unmount, html: () => root.querySelector('iframe')?.getAttribute('srcdoc') };
}
beforeEach(() => {
  vi.clearAllMocks();
});
afterEach(() => {
  cleanup?.();
});

describe('FinancePrintDialog actual component ownership and response boundary', () => {
  it('keeps only the latest receipt/payment and preserves long IDs without conversion', async () => {
    const old = deferred();
    const current = deferred();
    api.renderReceipt.mockReturnValueOnce(old.promise);
    api.renderPayment.mockReturnValueOnce(current.promise);
    const page = mount();
    const a = page.open('receipt', '9007199254740993');
    const b = page.open('payment', '9007199254740995');
    current.resolve('<h1>当前付款</h1>');
    await b;
    old.resolve('<h1>过期收款</h1>');
    await a;
    await flush();
    expect(page.html()).toBe('<h1>当前付款</h1>');
    expect(api.renderReceipt).toHaveBeenCalledWith('9007199254740993');
    expect(api.renderPayment).toHaveBeenCalledWith('9007199254740995');
    expect(page.root.querySelector('iframe')?.getAttribute('sandbox')).toBe('allow-same-origin allow-modals');
  });

  it('invalidates on close even if reopened for the same ID, including stale errors/finally', async () => {
    const old = deferred();
    const current = deferred();
    api.renderReceipt.mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise);
    const page = mount();
    const a = page.open('receipt', '9');
    await nextTick();
    page.root.querySelector<HTMLButtonElement>('[data-close]')!.click();
    const b = page.open('receipt', '9');
    old.reject(new Error('stale failure'));
    await a;
    await flush();
    expect(page.html()).toBeUndefined();
    expect(page.root.querySelector<HTMLButtonElement>('[data-print]')!.disabled).toBe(true);
    expect(warning).not.toHaveBeenCalled();
    current.resolve('<main>当前账单</main>');
    await b;
    await flush();
    expect(page.html()).toBe('<main>当前账单</main>');
  });

  it.each(['{"code":500,"msg":"失败"}', ' [ {"msg":"失败"} ]', 'not html', '', { code: 200, data: '<h1>错误包装</h1>' }])(
    'never leaves old printable content for invalid text response %j',
    async (result) => {
      api.renderReceipt.mockResolvedValueOnce('<h1>旧预览</h1>').mockResolvedValueOnce(result);
      const page = mount();
      await page.open('receipt', '1');
      await flush();
      expect(page.html()).toBe('<h1>旧预览</h1>');
      await page.open('receipt', '2');
      await flush();
      expect(page.html()).toBeUndefined();
      expect(page.root.querySelector<HTMLButtonElement>('[data-print]')!.disabled).toBe(true);
      expect(warning).toHaveBeenCalled();
    }
  );

  it('does not update or warn after unmount', async () => {
    const pending = deferred();
    api.renderReceipt.mockReturnValueOnce(pending.promise);
    const page = mount();
    const result = page.open('receipt', '1');
    page.unmount();
    pending.reject(new Error('late failure'));
    await result;
    expect(warning).not.toHaveBeenCalled();
    expect(page.html()).toBeUndefined();
  });
});
