<template>
  <div class="p-2">
    <t-card :bordered="false" class="erp-filter-card">
      <t-form :data="query" class="erp-filter-row" label-align="right" :label-width="'auto'">
        <t-form-item label="客户" name="customerId">
          <PartnerSelect v-model="query.customerId" kind="customer" placeholder="请选择客户" class="query-w-md" @change="handleQuery" />
        </t-form-item>
        <t-form-item label="模式" name="periodMode">
          <t-select v-model="query.periodMode" :options="periodOptions" class="query-w-sm" />
        </t-form-item>
        <t-form-item v-if="query.periodMode === 'month'" label="账期" name="month">
          <t-date-picker v-model="query.month" mode="month" value-type="YYYY-MM" placeholder="请选择月份" class="query-w-sm" />
        </t-form-item>
        <template v-else>
          <t-form-item label="开始日" name="startDate">
            <t-date-picker v-model="query.startDate" value-type="YYYY-MM-DD" placeholder="请选择开始日" class="query-w-sm" />
          </t-form-item>
          <t-form-item label="结束日" name="endDate">
            <t-date-picker v-model="query.endDate" value-type="YYYY-MM-DD" placeholder="请选择结束日" class="query-w-sm" />
          </t-form-item>
        </template>
        <div class="erp-filter-actions">
          <t-button theme="primary" :loading="loading" @click="handleQuery">
            <template #icon><SearchIcon /></template>
            预览
          </t-button>
          <t-button theme="default" variant="outline" :disabled="!statementHtml" @click="handlePrint">
            <template #icon><PrintIcon /></template>
            打印
          </t-button>
          <t-button theme="default" variant="outline" :disabled="!statementHtml" @click="handleOpen">
            <template #icon><DownloadIcon /></template>
            新窗口打开
          </t-button>
        </div>
      </t-form>
    </t-card>

    <div v-loading="loading" class="erp-surface erp-panel">
      <div class="erp-panel__head">
        <span class="erp-panel-title">对账单预览</span>
        <span class="erp-text-secondary">账期 {{ periodLabel || '-' }} · 服务端渲染</span>
      </div>

      <t-empty v-if="!statementHtml" description="请选择客户与账期后点击预览" :image-style="{ fontSize: '100px' }" />
      <iframe
        v-else
        ref="iframeRef"
        class="statement-frame"
        :srcdoc="statementHtml"
        sandbox="allow-same-origin allow-modals"
        title="对账单预览"
      ></iframe>
    </div>
  </div>
</template>

<script setup name="ErpStatement" lang="ts">
import { renderStatement } from '@/api/erp/print';
import type { ErpId } from '@/api/erp/types';
import PartnerSelect from '@/components/erp/PartnerSelect.vue';
import { DownloadIcon, PrintIcon, SearchIcon } from 'tdesign-icons-vue-next';
import { computed, getCurrentInstance, onBeforeUnmount, onDeactivated, reactive, ref, watch } from 'vue';
import { isPrintableHtml } from '@/utils/erp/printPreview';
import type { ComponentInternalInstance } from 'vue';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const loading = ref(false);
const statementHtml = ref('');
const iframeRef = ref<HTMLIFrameElement>();

const periodOptions = [
  { label: '月度', value: 'month' },
  { label: '区间', value: 'range' }
];
/** 本地日期 yyyy-MM-dd：不用 toISOString，避免 UTC 偏移导致跨天/跨月 */
const formatLocalDate = (date: Date) => {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
};
const todayText = formatLocalDate(new Date());
const query = reactive<{
  customerId?: ErpId;
  periodMode: 'month' | 'range';
  month: string;
  startDate: string;
  endDate: string;
}>({
  customerId: undefined,
  periodMode: 'month',
  month: todayText.slice(0, 7),
  startDate: `${todayText.slice(0, 7)}-01`,
  endDate: todayText
});
/** 区间天数（含首尾），与后端 MAX_RANGE_DAYS=366 对齐 */
const rangeDays = (startDate: string, endDate: string) => Math.round((Date.parse(endDate) - Date.parse(startDate)) / 86400000) + 1;
const periodLabel = computed(() => (query.periodMode === 'month' ? query.month : `${query.startDate || '-'} 至 ${query.endDate || '-'}`));

let previewRequest = 0;
const invalidatePreview = () => {
  ++previewRequest;
  statementHtml.value = '';
  loading.value = false;
};
watch([() => query.customerId, () => query.periodMode, () => query.month, () => query.startDate, () => query.endDate], invalidatePreview, {
  flush: 'sync'
});
onBeforeUnmount(invalidatePreview);
onDeactivated(invalidatePreview);

const handleQuery = async () => {
  const { customerId, periodMode, month, startDate, endDate } = query;
  if (!customerId || (periodMode === 'month' && !month)) {
    proxy?.$modal.msgWarning(customerId ? '请选择账期' : '请选择客户');
    return;
  }
  if (periodMode === 'range' && (!startDate || !endDate || startDate > endDate)) {
    proxy?.$modal.msgWarning('请选择有效的起止日期，开始日不得晚于结束日');
    return;
  }
  if (periodMode === 'range' && rangeDays(startDate, endDate) > 366) {
    proxy?.$modal.msgWarning('对账区间不能超过 366 天');
    return;
  }
  const request = ++previewRequest;
  loading.value = true;
  statementHtml.value = '';
  try {
    const result: unknown = await renderStatement(periodMode === 'range' ? { customerId, startDate, endDate } : { customerId, month });
    if (request !== previewRequest) return;
    if (!isPrintableHtml(result)) {
      proxy?.$modal.msgWarning('对账单预览生成失败，请检查模板');
      return;
    }
    statementHtml.value = result;
  } catch {
    if (request === previewRequest) proxy?.$modal.msgWarning('对账单加载失败，请稍后重试');
  } finally {
    if (request === previewRequest) loading.value = false;
  }
};

/**
 * 打印走 iframe 内容窗口，浏览器拦截弹窗时提示用户改用「新窗口打开」
 */
const handlePrint = () => {
  const win = iframeRef.value?.contentWindow;
  if (!win || !statementHtml.value) {
    proxy?.$modal.msgWarning('请先预览对账单');
    return;
  }
  try {
    win.focus();
    win.print();
  } catch {
    proxy?.$modal.msgWarning('打印被浏览器拦截，请使用「新窗口打开」后打印');
  }
};

const handleOpen = () => {
  if (!statementHtml.value) return;
  const win = window.open('', '_blank');
  if (!win) {
    proxy?.$modal.msgWarning('新窗口被浏览器拦截，请允许弹出窗口');
    return;
  }
  // 新窗口也以无脚本iframe展示，不能绕开主预览的sandbox。
  win.opener = null;
  const frame = win.document.createElement('iframe');
  frame.title = '对账单预览';
  frame.setAttribute('sandbox', 'allow-same-origin allow-modals');
  frame.style.cssText = 'width:100%;height:98vh;border:0';
  frame.srcdoc = statementHtml.value;
  win.document.body.replaceChildren(frame);
};
</script>

<style scoped lang="scss">
.statement-frame {
  width: 100%;
  height: 70vh;
  border: 1px solid var(--td-border-level-1-color);
  border-radius: 4px;
  background: #fff;
}
</style>
