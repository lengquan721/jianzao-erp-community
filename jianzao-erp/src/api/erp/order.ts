import request from '@/utils/request';
import type { AxiosPromise } from 'axios';
import type {
  ErpId,
  ErpPageResult,
  ErpResult,
  ErpPurchaseOrderExecVO,
  ErpPurchaseOrderForm,
  ErpPurchaseOrderItemVO,
  ErpPurchaseOrderQuery,
  ErpPurchaseOrderReceiptForm,
  ErpPurchaseOrderVO,
  ErpSaleOrderDeliveryForm,
  ErpSaleOrderExecVO,
  ErpSaleOrderForm,
  ErpSaleOrderItemVO,
  ErpSaleOrderQuery,
  ErpSaleOrderVO
} from './types';

/**
 * 订单层接口（DOC/31 §9.1）：路径用 /erp/purchase-orders、/erp/sale-orders（连字符 + 复数），
 * 与执行单层 /erp/purchase/orders、/erp/sales/orders（见 trade.ts）严格区分，互不复用。
 */

/* ------------------------------ 采购订单 ------------------------------ */

export function listPurchaseOrder(query: ErpPurchaseOrderQuery): Promise<ErpPageResult<ErpPurchaseOrderVO>> {
  return request<unknown, ErpPageResult<ErpPurchaseOrderVO>>({ url: '/erp/purchase-orders/list', method: 'get', params: query });
}

export function getPurchaseOrder(id: ErpId): Promise<ErpResult<ErpPurchaseOrderVO>> {
  return request<unknown, ErpResult<ErpPurchaseOrderVO>>({ url: `/erp/purchase-orders/${id}`, method: 'get' });
}

export function addPurchaseOrder(data: ErpPurchaseOrderForm): AxiosPromise<ErpPurchaseOrderVO> {
  return request({ url: '/erp/purchase-orders', method: 'post', data });
}

export function updatePurchaseOrder(data: ErpPurchaseOrderForm): AxiosPromise<ErpPurchaseOrderVO> {
  return request({ url: `/erp/purchase-orders/${data.id}`, method: 'put', data });
}

/** 删除草稿采购订单（逻辑删除头与行，幂等：第二次静默成功） */
export function delPurchaseOrder(id: string | number): AxiosPromise<void> {
  return request({ url: `/erp/purchase-orders/${id}`, method: 'delete' });
}

export function confirmPurchaseOrder(id: string | number): AxiosPromise<ErpPurchaseOrderVO> {
  return request({ url: `/erp/purchase-orders/${id}/confirm`, method: 'post' });
}

/** 撤回订单：要求无执行量且无未处理执行单 */
export function withdrawPurchaseOrder(id: string | number): AxiosPromise<void> {
  return request({ url: `/erp/purchase-orders/${id}/withdraw`, method: 'post' });
}

export function voidPurchaseOrder(id: string | number): AxiosPromise<void> {
  return request({ url: `/erp/purchase-orders/${id}/void`, method: 'post' });
}

/** 整单关闭剩余：reason 必填，后端缺失返回 40902 */
export function closePurchaseOrder(id: string | number, reason: string): AxiosPromise<void> {
  return request({ url: `/erp/purchase-orders/${id}/close`, method: 'post', data: { reason } });
}

/** 行级关闭剩余：closed_qty 冻结为 qty−executed，reason 必填 */
export function closePurchaseOrderItem(id: string | number, itemId: string | number, reason: string): AxiosPromise<void> {
  return request({ url: `/erp/purchase-orders/${id}/items/${itemId}/close`, method: 'post', data: { reason } });
}

/** 行级重开（唯一重开入口，无整单重开）：前端「全部重开」循环调用本接口 */
export function reopenPurchaseOrderItem(id: string | number, itemId: string | number, reason?: string): AxiosPromise<void> {
  return request({ url: `/erp/purchase-orders/${id}/items/${itemId}/reopen`, method: 'post', data: { reason } });
}

/** 订单→采购入库单部分下推：生成 DRAFT 入库单并返回其ID；不传 items 默认带全部未交行 */
export function createPurchaseReceipt(id: string | number, data?: ErpPurchaseOrderReceiptForm): AxiosPromise<ErpId> {
  return request({ url: `/erp/purchase-orders/${id}/receipts`, method: 'post', data });
}

/** 订单执行记录：入库与退货按时间倒序，金额已带符号。 */
export function listPurchaseOrderExecutions(id: ErpId): Promise<ErpResult<ErpPurchaseOrderExecVO[]>> {
  return request<unknown, ErpResult<ErpPurchaseOrderExecVO[]>>({ url: `/erp/purchase-orders/${id}/executions`, method: 'get' });
}

/** 未交行选择器：orderId 必填，只返回该单 CONFIRMED 且 remain>0 的行（不接受 supplierId） */
export function listPurchaseOpenItems(orderId: string | number): AxiosPromise<ErpPurchaseOrderItemVO[]> {
  return request({ url: '/erp/purchase-orders/open-items', method: 'get', params: { orderId } });
}

/* ------------------------------ 销售订单 ------------------------------ */

export function listSaleOrder(query: ErpSaleOrderQuery): Promise<ErpPageResult<ErpSaleOrderVO>> {
  return request<unknown, ErpPageResult<ErpSaleOrderVO>>({ url: '/erp/sale-orders/list', method: 'get', params: query });
}

export function getSaleOrder(id: ErpId): Promise<ErpResult<ErpSaleOrderVO>> {
  return request<unknown, ErpResult<ErpSaleOrderVO>>({ url: `/erp/sale-orders/${id}`, method: 'get' });
}

export function addSaleOrder(data: ErpSaleOrderForm): AxiosPromise<ErpSaleOrderVO> {
  return request({ url: '/erp/sale-orders', method: 'post', data });
}

export function updateSaleOrder(data: ErpSaleOrderForm): AxiosPromise<ErpSaleOrderVO> {
  return request({ url: `/erp/sale-orders/${data.id}`, method: 'put', data });
}

/** 删除草稿销售订单（逻辑删除头与行，幂等：第二次静默成功） */
export function delSaleOrder(id: string | number): AxiosPromise<void> {
  return request({ url: `/erp/sale-orders/${id}`, method: 'delete' });
}

export function confirmSaleOrder(id: string | number): AxiosPromise<ErpSaleOrderVO> {
  return request({ url: `/erp/sale-orders/${id}/confirm`, method: 'post' });
}

/** 撤回销售订单：要求无执行量且无非作废关联执行单 */
export function withdrawSaleOrder(id: string | number): AxiosPromise<void> {
  return request({ url: `/erp/sale-orders/${id}/withdraw`, method: 'post' });
}

export function voidSaleOrder(id: string | number): AxiosPromise<void> {
  return request({ url: `/erp/sale-orders/${id}/void`, method: 'post' });
}

/** 整单关闭剩余：reason 必填，后端缺失返回 40902 */
export function closeSaleOrder(id: string | number, reason: string): AxiosPromise<void> {
  return request({ url: `/erp/sale-orders/${id}/close`, method: 'post', data: { reason } });
}

/** 行级关闭剩余：closed_qty 冻结为 qty−executed，reason 必填 */
export function closeSaleOrderItem(id: string | number, itemId: string | number, reason: string): AxiosPromise<void> {
  return request({ url: `/erp/sale-orders/${id}/items/${itemId}/close`, method: 'post', data: { reason } });
}

/** 行级重开（唯一重开入口）：前端「全部重开」循环调用本接口 */
export function reopenSaleOrderItem(id: string | number, itemId: string | number, reason?: string): AxiosPromise<void> {
  return request({ url: `/erp/sale-orders/${id}/items/${itemId}/reopen`, method: 'post', data: { reason } });
}

/** 订单→销售出库单部分下推：生成 DRAFT 出库单并返回其ID；不传 items 默认带全部未交行 */
export function createSaleDelivery(id: string | number, data?: ErpSaleOrderDeliveryForm): AxiosPromise<ErpId> {
  return request({ url: `/erp/sale-orders/${id}/deliveries`, method: 'post', data });
}

/** 订单执行记录：出库与退货按时间倒序，金额已带符号。 */
export function listSaleOrderExecutions(id: ErpId): Promise<ErpResult<ErpSaleOrderExecVO[]>> {
  return request<unknown, ErpResult<ErpSaleOrderExecVO[]>>({ url: `/erp/sale-orders/${id}/executions`, method: 'get' });
}

/** 未交行选择器：orderId 必填，只返回该单 CONFIRMED 且 remain>0 的行 */
export function listSaleOpenItems(orderId: string | number): AxiosPromise<ErpSaleOrderItemVO[]> {
  return request({ url: '/erp/sale-orders/open-items', method: 'get', params: { orderId } });
}
