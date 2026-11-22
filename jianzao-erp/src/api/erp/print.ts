import request from '@/utils/request';
import { AxiosPromise } from 'axios';
import type { PrintDesignMeta, PrintDesignSchema, PrintTemplateForm, PrintTemplateVO } from './types';

/* ------------------------------ 打印模板 ------------------------------ */

export function listPrintTemplate(query: { scene?: string; keyword?: string; pageNum?: number; pageSize?: number }): AxiosPromise<PrintTemplateVO[]> {
  return request({ url: '/erp/print/templates', method: 'get', params: query });
}

export function getPrintTemplate(id: string | number): AxiosPromise<PrintTemplateVO> {
  return request({ url: `/erp/print/templates/${id}`, method: 'get' });
}

export function addPrintTemplate(data: PrintTemplateForm): AxiosPromise<PrintTemplateVO> {
  return request({ url: '/erp/print/templates', method: 'post', data });
}

export function updatePrintTemplate(data: PrintTemplateForm): AxiosPromise<PrintTemplateVO> {
  return request({ url: `/erp/print/templates/${data.id}`, method: 'put', data });
}

/** 停用模板（软删除保留审计） */
export function disablePrintTemplate(id: string | number): AxiosPromise<void> {
  return request({ url: `/erp/print/templates/${id}`, method: 'delete' });
}

/** 设为场景默认 */
export function setDefaultPrintTemplate(id: string | number): AxiosPromise<void> {
  return request({ url: `/erp/print/templates/${id}/default`, method: 'post' });
}

/**
 * 预览：用示例数据渲染编辑中的模板内容，直接返回 HTML（与渲染接口一致）
 *
 * 后端签名：POST /erp/print/preview?scene=&customerId=&showProfit=
 * body 是模板内容**原始字符串**（不是 JSON），responseType 为 text 透传 HTML。
 */
export function previewPrintTemplate(params: {
  scene: string;
  content: string;
  customerId?: string | number;
  showProfit?: boolean;
  /** 模板纸张：A4/A5/宽x高(mm)/AUTO，后端按此注入 @page */
  paper?: string;
}): AxiosPromise<string> {
  return request({
    url: '/erp/print/preview',
    method: 'post',
    params: {
      scene: params.scene,
      customerId: params.customerId,
      showProfit: params.showProfit ?? false,
      paper: params.paper
    },
    data: params.content,
    headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
    responseType: 'text'
  });
}

/* ------------------------------ 打印渲染 ------------------------------ */

/**
 * 渲染打印 HTML（服务端返回完整 HTML，含内联 CSS）
 * 前端用 iframe 加载后调用 contentWindow.print()
 */
export function renderPrint(url: string, params?: Record<string, string | number | boolean>): AxiosPromise<string> {
  return request({
    url,
    method: 'get',
    params,
    responseType: 'text',
    headers: { 'Content-Type': 'text/plain;charset=UTF-8' }
  });
}

/** 送货单渲染；copies 为打印份数（联单数），默认 1 */
export function renderSaleDelivery(
  saleId: string | number,
  params?: { templateId?: string | number; showProfit?: boolean; copies?: number }
): AxiosPromise<string> {
  return renderPrint(`/erp/print/SALE_DELIVERY/${saleId}`, params);
}

/** 对账单渲染；month 为月度账期，startDate/endDate 为自定义闭区间（同时给出时优先） */
export function renderStatement(params: {
  customerId: string | number;
  month?: string;
  startDate?: string;
  endDate?: string;
  templateId?: string | number;
}): AxiosPromise<string> {
  return renderPrint('/erp/print/STATEMENT', params);
}

/** 收款单渲染 */
export function renderReceipt(receiptId: string | number, params?: { templateId?: string | number }): AxiosPromise<string> {
  return renderPrint(`/erp/print/RECEIPT/${receiptId}`, params);
}

/** 付款单渲染 */
export function renderPayment(paymentId: string | number, params?: { templateId?: string | number }): AxiosPromise<string> {
  return renderPrint(`/erp/print/PAYMENT/${paymentId}`, params);
}

/* ------------------------------ 打印设计器 ------------------------------ */

/** 设计器元数据：变量字典、表格数据源、可选列 */
export function getPrintDesignMeta(scene: string): AxiosPromise<PrintDesignMeta> {
  return request({ url: '/erp/print/design/variables', method: 'get', params: { scene } });
}

/** 预置版式：新建模板时的起点组件树 */
export function getPrintPreset(scene: string): AxiosPromise<PrintDesignSchema> {
  return request({ url: '/erp/print/design/presets', method: 'get', params: { scene } });
}

/**
 * 预览设计器组件树：后端编译成 HTML 再用示例数据渲染
 *
 * body 传组件树 JSON 字符串，服务端编译 + 净化 + 渲染，前端不拼 HTML。
 */
export function previewPrintDesign(params: { scene: string; schemaJson: string; showProfit?: boolean }): AxiosPromise<string> {
  return request({
    url: '/erp/print/preview/design',
    method: 'post',
    params: {
      scene: params.scene,
      showProfit: params.showProfit ?? false
    },
    data: params.schemaJson,
    headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
    responseType: 'text',
    // 组件树里可能内嵌 base64 图片，体积远大于普通接口，默认 50s 容易在弱网下超时
    timeout: 120000
  });
}
