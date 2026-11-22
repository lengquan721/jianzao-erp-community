// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, nextTick } from 'vue';
import Statement from './index.vue';

const renderStatement = vi.hoisted(() => vi.fn());
vi.mock('@/api/erp/print', () => ({ renderStatement }));
vi.mock('@/components/erp/PartnerSelect.vue', () => ({ default: { render: () => null } }));
vi.mock('tdesign-vue-next', async (importOriginal) => {
  const actual = await importOriginal<typeof import('tdesign-vue-next')>();
  const { defineComponent, h } = await import('vue');
  const slot = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', [slots.default?.()])
  });
  return { ...actual, Form: slot, FormItem: slot, Card: slot, Button: slot, Empty: slot, DatePicker: slot, Select: slot };
});

const flush = async () => {
  await new Promise((resolve) => setTimeout(resolve, 0));
  await nextTick();
};
let cleanup: (() => void) | undefined;
type State = {
  query: { customerId?: string; periodMode: 'month' | 'range'; month: string; startDate: string; endDate: string };
  rangeDays: (startDate: string, endDate: string) => number;
  handleQuery: () => Promise<void>;
  statementHtml: string;
};
const warnings: string[] = [];
function mount() {
  const root = document.createElement('div');
  document.body.append(root);
  const app = createApp(Statement);
  app.directive('loading', {});
  Object.defineProperty(app.config.globalProperties, '$modal', {
    value: { msgWarning: (message: string) => warnings.push(message) }
  });
  const vm = app.mount(root) as unknown as { $: { setupState: State } };
  cleanup = () => {
    app.unmount();
    root.remove();
  };
  return vm.$.setupState;
}
afterEach(() => {
  cleanup?.();
  cleanup = undefined;
  warnings.length = 0;
  renderStatement.mockReset();
});

describe('对账单页面的日期窗口与本地月默认值', () => {
  it('默认使用本地当月，区间默认从当月一日到今天，而不是 UTC 月份', () => {
    const state = mount();
    const now = new Date();
    const pad = (n: number) => String(n).padStart(2, '0');
    const localMonth = `${now.getFullYear()}-${pad(now.getMonth() + 1)}`;
    expect(state.query.periodMode).toBe('month');
    expect(state.query.month).toBe(localMonth);
    expect(state.query.startDate).toBe(`${localMonth}-01`);
    expect(state.query.endDate).toBe(`${localMonth}-${pad(now.getDate())}`);
  });

  it('366 天闭区间放行，367 天在前端预检即拒绝且不发起请求', async () => {
    const state = mount();
    state.query.customerId = '9';
    state.query.periodMode = 'range';
    state.query.startDate = '2026-01-01';
    state.query.endDate = '2027-01-01';
    expect(state.rangeDays('2026-01-01', '2027-01-01')).toBe(366);
    renderStatement.mockResolvedValue('<div class="statement"></div>');
    await state.handleQuery();
    await flush();
    expect(renderStatement).toHaveBeenCalledTimes(1);
    expect(renderStatement).toHaveBeenCalledWith({ customerId: '9', startDate: '2026-01-01', endDate: '2027-01-01' });

    renderStatement.mockClear();
    state.query.endDate = '2027-01-02';
    expect(state.rangeDays('2026-01-01', '2027-01-02')).toBe(367);
    await state.handleQuery();
    await flush();
    expect(renderStatement).not.toHaveBeenCalled();
    expect(warnings).toContain('对账区间不能超过 366 天');
  });

  it('开始日晚于结束日时拒绝，且不发起请求', async () => {
    const state = mount();
    state.query.customerId = '9';
    state.query.periodMode = 'range';
    state.query.startDate = '2026-05-02';
    state.query.endDate = '2026-05-01';
    await state.handleQuery();
    await flush();
    expect(renderStatement).not.toHaveBeenCalled();
    expect(warnings).toContain('请选择有效的起止日期，开始日不得晚于结束日');
  });
});
