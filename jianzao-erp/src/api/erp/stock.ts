import request from '@/utils/request';
import type { AxiosPromise } from 'axios';
import type {
  ErpId,
  ErpPageResult,
  ErpResult,
  ErpStockAlertVO,
  ErpStockLogPageVO,
  ErpStockLogQuery,
  ErpStockQuantityVO,
  ErpStockQuery,
  ErpStockVO,
  StOpeningForm,
  StOpeningQuery,
  StOpeningVO
} from './types';
import { fetchIdBatches } from './idBatches';
import type { ErpImportResult } from './master';

export type { ErpImportResult } from './master';

// 这三个列表在表格内呈现失败状态，不让已过期的请求额外弹出错误。
const listHeaders = { silentError: 'true', silentHttpError: 'true' };

/** 库存余额（成品/原料），响应的 rows / total 位于顶层。 */
export function listStockBalances(query: ErpStockQuery): Promise<ErpPageResult<ErpStockVO>> {
  return request<unknown, ErpPageResult<ErpStockVO>>({ url: '/erp/stock/balances', method: 'get', params: query, headers: listHeaders });
}

/** 库存上下限预警明细（DOC/25 §3.2，不分页） */
export function listStockAlerts(query: { type?: string; productId?: ErpId }): AxiosPromise<ErpStockAlertVO[]> {
  return request({ url: '/erp/stock/alerts', method: 'get', params: query });
}

/** 全仓数量：只查调用方所需的商品 ID，不扫描余额页。 */
export function listStockQuantities(ids: readonly ErpId[]): Promise<ErpStockQuantityVO[]> {
  return fetchIdBatches<ErpStockQuantityVO>('/erp/stock/balances/quantities', ids);
}

/** 按商品和业务日期窗查一页，下一页使用后端返回的日期 + ID。 */
export function listStockLogs(query: ErpStockLogQuery): Promise<ErpResult<ErpStockLogPageVO>> {
  return request<unknown, ErpResult<ErpStockLogPageVO>>({ url: '/erp/stock/logs', method: 'get', params: query, headers: listHeaders });
}

/** 查询单个产品的库存余额 */
export function getStockBalance(productId: string | number): AxiosPromise<ErpStockVO> {
  return request({ url: `/erp/stock/balances/${productId}`, method: 'get' });
}

/* ------------------------------ 库存期初 ------------------------------ */

export function listStOpening(query: StOpeningQuery): Promise<ErpPageResult<StOpeningVO>> {
  return request<unknown, ErpPageResult<StOpeningVO>>({ url: '/erp/stock/opening/list', method: 'get', params: query, headers: listHeaders });
}

/** 期初库存导出（按仓库筛选，后端不分页） */
export function exportStOpening(query: { warehouseId?: ErpId }): AxiosPromise<Blob> {
  return request({ url: '/erp/stock/opening/export', method: 'post', params: query, responseType: 'blob' });
}

/** 期初库存导入（multipart，updateSupport 由调用方填入 FormData） */
export function importStOpening(data: FormData): AxiosPromise<ErpImportResult> {
  return request({ url: '/erp/stock/opening/importData', method: 'post', data });
}

/** 下载期初库存导入模板 */
export function stOpeningImportTemplate(): AxiosPromise<Blob> {
  return request({ url: '/erp/stock/opening/importTemplate', method: 'post', responseType: 'blob' });
}

/** 期初录入（upsert，product + warehouse 唯一） */
export function saveStOpening(data: StOpeningForm): AxiosPromise<StOpeningVO> {
  return request({ url: '/erp/stock/opening', method: 'post', data });
}

/** 期初确认锁定（写 OPENING 流水） */
export function confirmStOpening(id: string | number): AxiosPromise<void> {
  return request({ url: `/erp/stock/opening/${id}/confirm`, method: 'post' });
}

/** 期初反确认（reverse 回退） */
export function unconfirmStOpening(id: string | number): AxiosPromise<void> {
  return request({ url: `/erp/stock/opening/${id}/unconfirm`, method: 'post' });
}

/** 删除草稿库存期初（逻辑删除，单号不回收重用） */
export function delStOpening(id: string | number): AxiosPromise<void> {
  return request({ url: `/erp/stock/opening/${id}`, method: 'delete' });
}
