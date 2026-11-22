// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, nextTick } from 'vue';
import Tenant from './index.vue';
import type { TenantForm } from '@/api/system/tenant/types';

const request = vi.hoisted(() => vi.fn());
vi.mock('@/utils/request', () => ({ default: request }));
vi.mock('@/store/modules/user', () => ({ useUserStore: () => ({ userId: '1' }) }));
vi.mock('@/utils/permission', () => ({ checkPermi: () => true }));
vi.mock('@/components/RightToolbar/index.vue', () => ({ default: { render: () => null } }));
vi.mock('@/components/Pagination/index.vue', () => ({ default: { render: () => null } }));
// 租户页面与日期选择器保持真实，只替换与日期无关的表格/表单/对话框外壳。
vi.mock('tdesign-vue-next', async (importOriginal) => {
  const actual = await importOriginal<typeof import('tdesign-vue-next')>();
  const { defineComponent, h } = await import('vue');
  const slot = defineComponent({
    setup(_, { slots, expose }) {
      expose({ validate: async () => true, clearValidate: () => undefined });
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
  return {
    ...actual,
    Dialog: dialog,
    Card: slot,
    Form: slot,
    FormItem: slot,
    Button: slot,
    Input: slot,
    Select: slot,
    Textarea: slot,
    Table: slot,
    Tooltip: slot,
    Tag: slot,
    Switch: slot,
    DatePicker: actual.DatePicker
  };
});
const originalDate = '2026-11-01 10:20:30';
const flush = async () => {
  await new Promise((resolve) => setTimeout(resolve, 0));
  await nextTick();
};
let cleanup: (() => void) | undefined;
function mount() {
  const root = document.createElement('div');
  document.body.append(root);
  const app = createApp(Tenant);
  app.directive('hasPermi', {});
  app.directive('loading', {});
  Object.defineProperty(app.config.globalProperties, 'animate', { value: { searchAnimate: { enter: '', leave: '' } } });
  Object.defineProperty(app.config.globalProperties, '$modal', { value: { msgSuccess: vi.fn() } });
  const vm = app.mount(root) as unknown as {
    $: {
      setupState: {
        form: TenantForm;
        handleUpdate: (row: { id: string }) => Promise<void>;
        handleAdd: () => void;
        submitForm: () => Promise<void>;
        handleExpireTimeChange: (value: string) => void;
        handleExpireTimeClear: () => void;
        tenantPayload: () => TenantForm;
      };
    };
  };
  cleanup = () => {
    app.unmount();
    root.remove();
  };
  return { root, state: vm.$.setupState };
}
beforeEach(() => {
  request.mockReset().mockImplementation(async ({ url, method }) => {
    if (url === '/system/tenant/9007199254740993' && method === 'get')
      return { data: { id: '9007199254740993', tenantId: '000001', companyName: '测试', expireTime: originalDate } };
    return { rows: [], total: 0, data: [] };
  });
});
afterEach(() => {
  cleanup?.();
  cleanup = undefined;
});

describe('tenant expiry field-presence intent from actual page', () => {
  it('omits expiry when untouched, keeps long ID, and never uses pick as a committed change', async () => {
    const { root, state } = mount();
    await state.handleUpdate({ id: '9007199254740993' });
    await flush();
    expect(root.querySelector('.t-date-picker')).not.toBeNull();
    expect(state.form.expireTime).toBe(originalDate);
    expect(state.tenantPayload()).not.toHaveProperty('expireTime');
    await state.submitForm();
    const write = request.mock.calls.find(([config]) => config.method === 'put')?.[0];
    expect(write?.data?.id).toBe('9007199254740993');
    expect(write?.data).not.toHaveProperty('expireTime');
  });

  it('a real picker day pick cancelled without panel confirm does not clear or change expiry', async () => {
    const { root, state } = mount();
    await state.handleUpdate({ id: '9007199254740993' });
    await flush();
    root.querySelector<HTMLInputElement>('.t-date-picker input')!.click();
    await flush();
    const day = document.body.querySelector<HTMLElement>('.t-date-picker__cell:not(.t-date-picker__cell--disabled)');
    expect(day).not.toBeNull();
    day!.click();
    await flush();
    document.body.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    await flush();
    expect(state.form.expireTime).toBe(originalDate);
    expect(state.tenantPayload()).not.toHaveProperty('expireTime');
  });

  it('sends explicit null only for clear, then a formatted string for a committed new date', async () => {
    const { state } = mount();
    await state.handleUpdate({ id: '9007199254740993' });
    state.handleExpireTimeChange(''); // 取消/未提交的空值不是清空意图。
    expect(state.tenantPayload()).not.toHaveProperty('expireTime');
    state.handleExpireTimeClear();
    expect(state.tenantPayload().expireTime).toBeNull();
    state.handleExpireTimeChange('2027-02-03 04:05:06');
    await state.submitForm();
    expect(request).toHaveBeenCalledWith(
      expect.objectContaining({ method: 'put', data: expect.objectContaining({ expireTime: '2027-02-03 04:05:06' }) })
    );
  });

  it('new unselected expiry is omitted, not silently converted into clear', async () => {
    const { state } = mount();
    state.handleAdd();
    expect(state.tenantPayload()).not.toHaveProperty('expireTime');
    state.handleExpireTimeClear();
    expect(state.tenantPayload().expireTime).toBeNull();
  });
});
