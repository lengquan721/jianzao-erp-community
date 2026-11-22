import { DialogPlugin, MessagePlugin, NotifyPlugin, LoadingPlugin, Input } from 'tdesign-vue-next';
import type { DialogInstance, DialogOptions, LoadingInstance, TNode } from 'tdesign-vue-next';

let loadingInstance: LoadingInstance;
const dismissed = new WeakSet<DialogInstance>();

/**
 * 关闭并销毁命令式弹窗实例（幂等）：TDesign 点取消会连带触发 onCancel 与 onClose，
 * 两次 hide/destroy 无害但重复，用 WeakSet 保证只销毁一次；resolve/reject 本身只结算一次
 * @param ins TDesign DialogPlugin 返回的实例
 */
function dismiss(ins: DialogInstance) {
  if (dismissed.has(ins)) return;
  dismissed.add(ins);
  ins.hide();
  ins.destroy();
}

/**
 * 打开确认弹窗并包装成真 Promise：确定 resolve，取消/关闭/ESC/遮罩 reject
 * @param options DialogPlugin 的弹窗配置（不含回调）
 * @param resolveValue 确定时 resolve 的值
 * @returns 用户操作结果的 Promise，取消或关闭时 reject
 */
function openDialog(options: DialogOptions, resolveValue: any): Promise<any> {
  return new Promise((resolve, reject) => {
    const ins = DialogPlugin.confirm({
      attach: 'body',
      header: '系统提示',
      confirmBtn: '确定',
      cancelBtn: '取消',
      ...options,
      onConfirm: () => {
        dismiss(ins);
        resolve(resolveValue);
      },
      onCancel: () => {
        dismiss(ins);
        reject('cancel');
      },
      // esc / 关闭按钮 / 点遮罩均会触发 onClose
      onClose: () => {
        dismiss(ins);
        reject('close');
      }
    });
  });
}

/**
 * 打开仅确定的提示弹窗，确定或关闭时自动销毁实例（EP alert 同款行为，返回 void）
 * @param content 提示文案
 * @param theme TDesign 弹窗主题，映射原 EP 的 type 语义
 */
function openAlert(content: any, theme: 'default' | 'info' | 'warning' | 'danger' | 'success') {
  const ins = DialogPlugin.alert({
    attach: 'body',
    header: '系统提示',
    body: content,
    theme,
    confirmBtn: '确定',
    onConfirm: () => dismiss(ins),
    // esc / 关闭按钮 / 点遮罩同样要销毁实例
    onClose: () => dismiss(ins)
  });
}

export default {
  // 消息提示
  msg(content: any) {
    MessagePlugin.info(content);
  },
  // 错误消息
  msgError(content: any) {
    MessagePlugin.error(content);
  },
  // 成功消息
  msgSuccess(content: any) {
    MessagePlugin.success(content);
  },
  // 警告消息
  msgWarning(content: any) {
    MessagePlugin.warning(content);
  },
  // 弹出提示
  alert(content: any) {
    openAlert(content, 'default');
  },
  // 错误提示
  alertError(content: any) {
    openAlert(content, 'danger');
  },
  // 成功提示
  alertSuccess(content: any) {
    openAlert(content, 'success');
  },
  // 警告提示
  alertWarning(content: any) {
    openAlert(content, 'warning');
  },
  // 通知提示
  notify(content: any) {
    NotifyPlugin.info({ content });
  },
  // 错误通知
  notifyError(content: any) {
    NotifyPlugin.error({ content });
  },
  // 成功通知
  notifySuccess(content: any) {
    NotifyPlugin.success({ content });
  },
  // 警告通知
  notifyWarning(content: any) {
    NotifyPlugin.warning({ content });
  },
  // 确认窗体：确定 resolve，取消/关闭/ESC/遮罩 reject，保证 await 后的危险操作不会在取消时继续执行
  confirm(content: any): Promise<any> {
    return openDialog({ body: content, theme: 'warning' }, 'confirm');
  },
  /**
   * 提交内容：TDesign 无对应 prompt API，用 DialogPlugin 挂一个输入框
   * 确定 resolve 输入值（{ value } 形状），取消/关闭 reject，保持原语义
   * @param content 提示文案
   * @returns 用户输入值的 Promise
   */
  prompt(content: any): Promise<{ value: string }> {
    return new Promise<{ value: string }>((resolve, reject) => {
      let inputValue = '';
      // 用 t-input 的 TNode 承接输入值，确定时回传
      const body: TNode = (h) =>
        h('div', null, [
          h('div', { style: 'margin-bottom: 12px' }, String(content)),
          h(Input, {
            defaultValue: '',
            autofocus: true,
            placeholder: '请输入内容',
            onChange: (v) => {
              inputValue = String(v ?? '');
            }
          })
        ]);
      const ins = DialogPlugin.confirm({
        attach: 'body',
        header: '系统提示',
        body,
        theme: 'warning',
        confirmBtn: '确定',
        cancelBtn: '取消',
        onConfirm: () => {
          dismiss(ins);
          resolve({ value: inputValue });
        },
        onCancel: () => {
          dismiss(ins);
          reject('cancel');
        },
        onClose: () => {
          dismiss(ins);
          reject('close');
        }
      });
    });
  },
  // 打开遮罩层：TDesign LoadingPlugin 实例仅提供 hide()，成对开关
  loading(content: string) {
    loadingInstance = LoadingPlugin({
      fullscreen: true,
      text: content,
      preventScrollThrough: true
    });
  },
  // 关闭遮罩层
  closeLoading() {
    loadingInstance?.hide();
  }
};
