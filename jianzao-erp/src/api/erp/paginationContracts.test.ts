import { beforeEach, describe, expect, it, vi } from 'vitest';

const { request } = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('@/utils/request', () => ({ default: request }));

import { productOptions, productOptionsByIds, productCategoryOptions } from './master';
import { listStockBalances, listStockLogs, listStOpening } from './stock';
import { listPurchaseOrder, listSaleOrder } from './order';

describe('paginated ERP API contracts', () => {
  beforeEach(() => {
    request.mockReset();
  });

  it('keeps the stock page rows and real total with server keyword/type/page filters', async () => {
    const page = { code: 200, msg: 'ok', rows: [{ productId: '9007199254740993', warehouseId: 3 }], total: 437 };
    request.mockResolvedValue(page);
    const query = { pageNum: 4, pageSize: 20, keyword: 'steel', type: 'MATERIAL' as const };

    const result = await listStockBalances(query);

    expect(result).toEqual(page);
    expect(result.rows).toHaveLength(1);
    expect(result.total).toBe(437);
    expect(request).toHaveBeenCalledWith(expect.objectContaining({ url: '/erp/stock/balances', params: query }));
  });

  it('passes stock log date window, size and paired compound cursor verbatim', async () => {
    const result = { code: 200, msg: 'ok', data: { rows: [], hasMore: false, nextBeforeDate: null, nextBeforeId: null } };
    request.mockResolvedValue(result);
    const query = {
      productId: '9007199254740993',
      startDate: '2026-01-01',
      endDate: '2026-12-31',
      pageSize: 50,
      beforeDate: '2026-11-10',
      beforeId: '9007199254740994'
    };

    await listStockLogs(query);

    expect(request).toHaveBeenCalledWith(expect.objectContaining({ url: '/erp/stock/logs', params: query }));
  });

  it('passes required page and warehouse filters to opening list and keeps top-level total', async () => {
    const page = { code: 200, msg: 'ok', rows: [{ id: 8, warehouseId: 12 }], total: 61 };
    request.mockResolvedValue(page);
    const query = { pageNum: 3, pageSize: 20, warehouseId: '12' };

    const result = await listStOpening(query);

    expect(result.total).toBe(61);
    expect(request).toHaveBeenCalledWith(expect.objectContaining({ url: '/erp/stock/opening/list', params: query }));
  });

  it('uses remote options filters/page and the server hasMore cursor instead of local slicing', async () => {
    const payload = { rows: [{ id: '1', code: 'P1', name: 'Part' }], hasMore: true, nextPageNum: 2 };
    request.mockResolvedValue({ code: 200, msg: 'ok', data: payload });
    const query = { pageNum: 1, pageSize: 30, keyword: 'part', categoryId: '4', type: 'MATERIAL' as const };

    const result = await productOptions(query);

    expect(result).toEqual(payload);
    expect(request).toHaveBeenCalledWith(expect.objectContaining({ url: '/erp/master/products/options', params: query }));
  });

  it('uses the options page envelope for categories', async () => {
    const payload = { rows: [{ id: '2', name: 'Raw' }], hasMore: false, nextPageNum: null };
    request.mockResolvedValue({ code: 200, msg: 'ok', data: payload });

    expect(await productCategoryOptions({ pageNum: 2, pageSize: 100 })).toEqual(payload);
  });

  it('batches selected IDs at 200 without truncating later IDs or coercing them to Number', async () => {
    request.mockImplementation(async ({ data }: { data: { ids: string[] } }) => ({ code: 200, msg: 'ok', data: data.ids.map((id) => ({ id })) }));
    const ids = Array.from({ length: 201 }, (_, index) => String(9007199254740993n + BigInt(index)));

    const rows = await productOptionsByIds(ids);

    expect(request).toHaveBeenCalledTimes(2);
    expect(request.mock.calls[0][0].data.ids).toHaveLength(200);
    expect(request.mock.calls[1][0].data.ids).toEqual([ids[200]]);
    expect(rows.map((item) => item.id)).toEqual(ids);
  });

  it('paginates sale and purchase source-order filters without replacing server page totals', async () => {
    const page = { code: 200, msg: 'ok', rows: [{ id: '7', executedAmount: '42.00' }], total: 91 };
    request.mockResolvedValue(page);
    const saleQuery = { pageNum: 2, pageSize: 30, keyword: 'SO', status: 'CONFIRMED', customerId: '8', overdue: true, dueDateStart: '2026-01-01' };
    const sale = await listSaleOrder(saleQuery);
    expect(sale.total).toBe(91);
    expect(request).toHaveBeenLastCalledWith(expect.objectContaining({ url: '/erp/sale-orders/list', params: saleQuery }));

    const purchaseQuery = { pageNum: 3, pageSize: 30, keyword: 'PO', status: 'CONFIRMED', supplierId: '9', overdue: true, dueDateEnd: '2026-12-31' };
    const purchase = await listPurchaseOrder(purchaseQuery);
    expect(purchase.total).toBe(91);
    expect(request).toHaveBeenLastCalledWith(expect.objectContaining({ url: '/erp/purchase-orders/list', params: purchaseQuery }));
  });
});
