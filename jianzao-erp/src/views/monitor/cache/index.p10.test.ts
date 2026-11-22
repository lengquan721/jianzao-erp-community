// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as Vue from 'vue';
import Cache from './index.vue';

const mocks = vi.hoisted(() => ({ getCache: vi.fn(), init: vi.fn() }));
vi.mock('@/api/monitor/cache', () => ({ getCache: mocks.getCache }));
vi.mock('echarts', () => ({ init: mocks.init }));
vi.mock('tdesign-icons-vue-next', () => {
  const icon = { render: () => null };
  return { ChartPieIcon: icon, DashboardIcon: icon, DesktopIcon: icon };
});
// 正式自动导入的 Row/Col/Card 必须在模块边界桩化，避免子控件 resize 混入页面资源断言。
vi.mock('tdesign-vue-next', async () => {
  const { defineComponent, h } = await import('vue');
  const container = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', [slots.header?.(), slots.default?.()])
  });
  return { Row: container, Col: container, Card: container };
});
type TestVm = { $: { setupState: { getList: () => Promise<void> } } };
const response = () => ({ data: { info: { used_memory_human: '12.5M' }, dbSize: 2, commandStats: [{ name: 'get', value: 2 }] } });
const modal = { loading: vi.fn(), closeLoading: vi.fn() };
const errors = vi.fn();
const cleanups: Array<() => void> = [];
const charts: Array<{ setOption: ReturnType<typeof vi.fn>; resize: ReturnType<typeof vi.fn>; dispose: ReturnType<typeof vi.fn> }> = [];
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
function mount() {
  const root = document.createElement('div');
  document.body.append(root);
  const app = Vue.createApp(Cache);
  Object.defineProperty(app.config.globalProperties, '$modal', { value: modal });
  app.config.errorHandler = errors;
  const vm = app.mount(root) as unknown as TestVm;
  let active = true;
  const unmount = () => {
    if (!active) return;
    active = false;
    app.unmount();
    root.remove();
  };
  cleanups.push(unmount);
  return { state: vm.$.setupState, unmount };
}
const resizeListeners = (spy: ReturnType<typeof vi.spyOn>) => spy.mock.calls.filter(([event]) => event === 'resize');

beforeEach(() => {
  vi.clearAllMocks();
  charts.length = 0;
  mocks.getCache.mockReset().mockResolvedValue(response());
  mocks.init.mockReset().mockImplementation(() => {
    const chart = { setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() };
    charts.push(chart);
    return chart;
  });
});
afterEach(() => {
  for (const cleanup of cleanups.splice(0)) cleanup();
  vi.restoreAllMocks();
});

describe('P10 actual cache ECharts resource lifecycle', () => {
  it('resizes live charts and removes the identical listener reference while disposing both on unmount', async () => {
    const add = vi.spyOn(window, 'addEventListener');
    const remove = vi.spyOn(window, 'removeEventListener');
    const { unmount } = mount();
    await flush();
    expect(mocks.init).toHaveBeenCalledTimes(2);
    expect(mocks.init.mock.calls.every(([element, theme]) => element instanceof HTMLElement && theme === 'macarons')).toBe(true);
    expect(charts[0].setOption).toHaveBeenCalledWith(
      expect.objectContaining({ series: [expect.objectContaining({ type: 'pie', data: response().data.commandStats })] })
    );
    expect(charts[1].setOption).toHaveBeenCalledWith(expect.objectContaining({ series: [expect.objectContaining({ type: 'gauge' })] }));
    window.dispatchEvent(new Event('resize'));
    expect(charts.map((chart) => chart.resize.mock.calls.length)).toEqual([1, 1]);
    const handlers = resizeListeners(add);
    expect(handlers).toHaveLength(1);
    unmount();
    expect(resizeListeners(remove)).toEqual([['resize', handlers[0][1]]]);
    expect(charts.map((chart) => chart.dispose.mock.calls.length)).toEqual([1, 1]);
    window.dispatchEvent(new Event('resize'));
    expect(charts.map((chart) => chart.resize.mock.calls.length)).toEqual([1, 1]);
    expect(modal.closeLoading).toHaveBeenCalledTimes(1);
  });

  it('does not create charts or revive listeners after an unmounted pending request resolves', async () => {
    const pending = deferred();
    mocks.getCache.mockReturnValueOnce(pending.promise);
    const add = vi.spyOn(window, 'addEventListener');
    const remove = vi.spyOn(window, 'removeEventListener');
    const { unmount } = mount();
    unmount();
    pending.resolve(response());
    await flush();
    expect(mocks.init).not.toHaveBeenCalled();
    const handlers = resizeListeners(add);
    expect(handlers).toHaveLength(1);
    expect(resizeListeners(remove)).toEqual([['resize', handlers[0][1]]]);
    expect(modal.closeLoading).toHaveBeenCalledTimes(1);
    expect(errors).not.toHaveBeenCalled();
  });

  it('does not reinitialize charts or accumulate resize listeners on another load', async () => {
    const add = vi.spyOn(window, 'addEventListener');
    const { state } = mount();
    await flush();
    await state.getList();
    expect(mocks.init).toHaveBeenCalledTimes(2);
    expect(charts.map((chart) => chart.setOption.mock.calls.length)).toEqual([2, 2]);
    expect(resizeListeners(add)).toHaveLength(1);
    window.dispatchEvent(new Event('resize'));
    expect(charts.map((chart) => chart.resize.mock.calls.length)).toEqual([1, 1]);
  });

  it('releases the loading overlay on request failure and can dispose the empty chart lifecycle', async () => {
    mocks.getCache.mockRejectedValueOnce(new Error('cache failed'));
    const add = vi.spyOn(window, 'addEventListener');
    const remove = vi.spyOn(window, 'removeEventListener');
    const { unmount } = mount();
    await flush();
    expect(modal.closeLoading).toHaveBeenCalledTimes(1);
    expect(mocks.init).not.toHaveBeenCalled();
    expect(errors).toHaveBeenCalledWith(expect.objectContaining({ message: 'cache failed' }), expect.anything(), 'mounted hook');
    window.dispatchEvent(new Event('resize'));
    unmount();
    expect(resizeListeners(remove)).toEqual([['resize', resizeListeners(add)[0][1]]]);
  });

  it('remounts with independent chart ownership and leaves no old resize callback alive', async () => {
    const add = vi.spyOn(window, 'addEventListener');
    const first = mount();
    await flush();
    const oldCharts = charts.slice();
    first.unmount();
    const second = mount();
    await flush();
    window.dispatchEvent(new Event('resize'));
    expect(oldCharts.map((chart) => chart.resize.mock.calls.length)).toEqual([0, 0]);
    expect(charts.slice(2).map((chart) => chart.resize.mock.calls.length)).toEqual([1, 1]);
    const handlers = resizeListeners(add);
    expect(handlers).toHaveLength(2);
    expect(handlers[0][1]).not.toBe(handlers[1][1]);
    second.unmount();
    expect(charts.map((chart) => chart.dispose.mock.calls.length)).toEqual([1, 1, 1, 1]);
  });
});
