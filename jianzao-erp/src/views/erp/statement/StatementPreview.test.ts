// @vitest-environment jsdom
import { Button, Card, DatePicker, Empty, Form, FormItem } from 'tdesign-vue-next';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, nextTick } from 'vue';
import Statement from './index.vue';

const renderStatement = vi.hoisted(() =>
  vi.fn<(params: { customerId: string; month?: string; startDate?: string; endDate?: string }) => Promise<string>>()
);
vi.mock('@/api/erp/print', () => ({ renderStatement }));

vi.mock('tdesign-vue-next', async () => {
  const { defineComponent, h } = await import('vue');
  const Slot = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  });
  const Button = defineComponent({
    props: { loading: Boolean, disabled: Boolean },
    setup:
      (props, { slots }) =>
      () =>
        h('button', { 'aria-busy': String(props.loading), disabled: props.disabled }, slots.default?.())
  });
  const DatePicker = defineComponent({
    props: { modelValue: String, mode: String, placeholder: String },
    emits: ['update:modelValue'],
    setup:
      (props, { emit }) =>
      () =>
        h('input', {
          'data-month': props.mode === 'month' ? '' : undefined,
          'data-date': props.placeholder,
          value: props.modelValue,
          onChange: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value)
        })
  });
  const Select = defineComponent({
    props: { modelValue: String, options: Array },
    emits: ['update:modelValue'],
    setup:
      (props, { emit }) =>
      () =>
        h(
          'select',
          {
            'data-mode': '',
            value: props.modelValue,
            onChange: (event: Event) => emit('update:modelValue', (event.target as HTMLSelectElement).value)
          },
          [h('option', { value: 'month' }, '月度'), h('option', { value: 'range' }, '区间')]
        )
  });
  return { Button, DatePicker, Select, Card: Slot, Empty: Slot, Form: Slot, FormItem: Slot };
});

vi.mock('@/components/erp/PartnerSelect.vue', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    default: defineComponent({
      emits: ['update:modelValue', 'change'],
      setup:
        (_, { emit }) =>
        () =>
          h('input', {
            'data-customer': '',
            onChange: (event: Event) => {
              const value = (event.target as HTMLInputElement).value || undefined;
              emit('update:modelValue', value);
              emit('change', value);
            }
          })
    })
  };
});

let unmount: (() => void) | undefined;
const requests: Array<(html: string) => void> = [];
const updated = async () => {
  await Promise.resolve();
  await nextTick();
};

beforeEach(() => {
  requests.length = 0;
  renderStatement.mockReset().mockImplementation(() => new Promise((resolve) => requests.push(resolve)));
});
afterEach(() => {
  unmount?.();
  unmount = undefined;
});

function mount() {
  const host = document.createElement('div');
  const app = createApp(Statement);
  const components = { Button, Card, DatePicker, Empty, Form, FormItem };
  for (const [name, component] of Object.entries(components)) app.component(`T${name}`, component);
  app.directive('loading', {});
  const warn = vi.fn();
  Object.defineProperty(app.config.globalProperties, '$modal', { value: { msgWarning: warn } });
  app.mount(host);
  unmount = () => app.unmount();
  const change = (selector: string, value: string) => {
    const input = host.querySelector<HTMLInputElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('change'));
  };
  const button = (text: string) => Array.from(host.querySelectorAll('button')).find((node) => node.textContent?.trim() === text)!;
  return {
    chooseCustomer: (customer: string) => change('[data-customer]', customer),
    chooseMonth: (month: string) => change('[data-month]', month),
    chooseMode: (mode: string) => change('[data-mode]', mode),
    chooseStart: (date: string) => change('[data-date="请选择开始日"]', date),
    chooseEnd: (date: string) => change('[data-date="请选择结束日"]', date),
    query: () => button('预览').click(),
    busy: () => button('预览').getAttribute('aria-busy'),
    printable: () => !button('打印').disabled && !button('新窗口打开').disabled,
    html: () => host.querySelector('iframe')?.getAttribute('srcdoc'),
    warn
  };
}

describe('跨月对账预览只使用当前过滤条件下的最新响应', () => {
  it('较早客户响应晚于最新响应时，仍只显示最新客户', async () => {
    const page = mount();
    page.chooseCustomer('1');
    page.chooseCustomer('2');
    expect(renderStatement.mock.calls.map(([params]) => params.customerId)).toEqual(['1', '2']);
    requests[1]('<h1>客户二</h1>');
    await updated();
    expect(page.printable()).toBe(true);
    requests[0]('<h1>客户一</h1>');
    await updated();
    expect(page.html()).toBe('<h1>客户二</h1>');
  });

  it('较早响应先完成时，不恢复旧预览或结束当前加载', async () => {
    const page = mount();
    page.chooseCustomer('1');
    page.chooseCustomer('2');
    requests[0]('<h1>旧账单</h1>');
    await updated();
    expect(page.busy()).toBe('true');
    expect(page.printable()).toBe(false);
    expect(page.html()).toBeUndefined();
    requests[1]('<h1>新账单</h1>');
    await updated();
    expect(page.busy()).toBe('false');
    expect(page.printable()).toBe(true);
  });

  it('改账期清除已完成预览；月份切回也不会复活旧请求', async () => {
    const page = mount();
    page.chooseMonth('2026-04');
    page.chooseCustomer('1');
    requests[0]('<h1>四月账单</h1>');
    await updated();
    expect(page.printable()).toBe(true);
    page.chooseMonth('2026-05');
    await updated();
    expect(page.printable()).toBe(false);
    page.query();
    expect(renderStatement).toHaveBeenLastCalledWith({ customerId: '1', month: '2026-05' });
    page.chooseMonth('2026-04');
    page.chooseMonth('2026-05');
    requests[1]('<h1>已过期的五月账单</h1>');
    await updated();
    expect(page.printable()).toBe(false);
    expect(page.html()).toBeUndefined();
  });

  it('区间模式只提交闭区间，任一起止日或模式变化均使晚到预览失效', async () => {
    const page = mount();
    page.chooseMode('range');
    await updated();
    page.chooseStart('2026-10-01');
    page.chooseEnd('2026-11-20');
    page.chooseCustomer('9007199254740993');
    expect(renderStatement).toHaveBeenLastCalledWith({ customerId: '9007199254740993', startDate: '2026-10-01', endDate: '2026-11-20' });
    page.chooseEnd('2026-11-21');
    requests[0]('<h1>旧区间</h1>');
    await updated();
    expect(page.printable()).toBe(false);
    page.query();
    requests[1]('<h1>新区间</h1>');
    await updated();
    expect(page.html()).toBe('<h1>新区间</h1>');
    page.chooseMode('month');
    await updated();
    expect(page.printable()).toBe(false);
  });

  it('区间缺日期或倒置时不请求，且JSON/纯文本响应不能打印', async () => {
    const page = mount();
    page.chooseMode('range');
    await updated();
    // 页面默认带本地当月窗口，这里显式清空以覆盖“缺日期”分支。
    page.chooseStart('');
    page.chooseEnd('');
    page.chooseCustomer('1');
    expect(renderStatement).not.toHaveBeenCalled();
    page.chooseStart('2026-11-21');
    page.chooseEnd('2026-11-20');
    page.query();
    expect(renderStatement).not.toHaveBeenCalled();
    page.chooseStart('2026-11-01');
    page.query();
    requests[0]('{"code":500,"msg":"bad"}');
    await updated();
    expect(page.printable()).toBe(false);
    page.query();
    requests[1]('not html');
    await updated();
    expect(page.html()).toBeUndefined();
    expect(page.printable()).toBe(false);
  });

  it('367 天区间在前端预检即拒绝，不发起请求', async () => {
    const page = mount();
    page.chooseMode('range');
    await updated();
    page.chooseCustomer('1');
    renderStatement.mockClear();
    page.chooseStart('2026-01-01');
    page.chooseEnd('2027-01-02');
    page.query();
    expect(renderStatement).not.toHaveBeenCalled();
    expect(page.warn).toHaveBeenCalledWith('对账区间不能超过 366 天');
  });

  it('卸载后响应与错误不能恢复预览', async () => {
    const page = mount();
    page.chooseCustomer('1');
    unmount?.();
    unmount = undefined;
    requests[0]('<h1>卸载后</h1>');
    await updated();
    expect(page.html()).toBeUndefined();
    expect(page.warn).not.toHaveBeenCalled();
  });

  it('缺账期不发送请求；清空客户时晚到的账单不恢复', async () => {
    const page = mount();
    page.chooseMonth('');
    page.chooseCustomer('1');
    expect(page.warn).toHaveBeenCalledWith('请选择账期');
    expect(renderStatement).not.toHaveBeenCalled();
    page.chooseMonth('2026-05');
    page.query();
    page.chooseCustomer('');
    requests[0]('<h1>已清空客户</h1>');
    await updated();
    expect(page.printable()).toBe(false);
    expect(page.html()).toBeUndefined();
  });
});
