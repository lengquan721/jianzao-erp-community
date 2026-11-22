<template>
  <t-dialog v-model:visible="visible" :header="title" width="820px" :footer="false" attach="body" :destroy-on-close="true">
    <div class="flex items-center gap-2 mb-2">
      <t-button theme="primary" :disabled="!html || loading" @click="print">打印</t-button>
    </div>
    <div v-loading="loading" style="min-height: 200px">
      <iframe
        v-if="html"
        ref="frame"
        :srcdoc="html"
        sandbox="allow-same-origin allow-modals"
        title="打印预览"
        style="width: 100%; height: 62vh; border: 1px solid var(--td-border-level-1-color)"
      ></iframe>
    </div>
  </t-dialog>
</template>

<script setup lang="ts">
import { getCurrentInstance, onBeforeUnmount, onDeactivated, ref, watch } from 'vue';
import type { ComponentInternalInstance } from 'vue';
import { renderPayment, renderReceipt } from '@/api/erp/print';
import type { ErpId } from '@/api/erp/types';
import { isPrintableHtml } from '@/utils/erp/printPreview';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const visible = ref(false);
const loading = ref(false);
const html = ref('');
const title = ref('');
const frame = ref<HTMLIFrameElement>();
let requestId = 0;
const invalidate = () => {
  ++requestId;
  html.value = '';
  loading.value = false;
};
watch(
  visible,
  (value) => {
    if (!value) invalidate();
  },
  { flush: 'sync' }
);
onBeforeUnmount(invalidate);
onDeactivated(invalidate);

/** 与详情编辑完全独立：打印不改变受控红字的只读与业务动作。 */
const open = async (kind: 'receipt' | 'payment', id: ErpId, no?: string) => {
  const current = ++requestId;
  title.value = `打印${kind === 'receipt' ? '收款单' : '付款单'} - ${no ?? ''}`;
  html.value = '';
  visible.value = true;
  loading.value = true;
  try {
    const result: unknown = await (kind === 'receipt' ? renderReceipt(id) : renderPayment(id));
    if (current !== requestId || !visible.value) return;
    if (!isPrintableHtml(result)) {
      proxy?.$modal.msgWarning('打印预览生成失败，请检查模板');
      return;
    }
    html.value = result;
  } catch {
    if (current === requestId && visible.value) proxy?.$modal.msgWarning('打印预览加载失败，请稍后重试');
  } finally {
    if (current === requestId) loading.value = false;
  }
};

const print = () => {
  if (!html.value || loading.value || !visible.value) return;
  const win = frame.value?.contentWindow;
  if (!win) {
    proxy?.$modal.msgWarning('预览尚未加载完成');
    return;
  }
  try {
    win.focus();
    win.print();
  } catch {
    proxy?.$modal.msgWarning('打印被浏览器拦截，请允许打印');
  }
};

defineExpose({ open });
</script>
