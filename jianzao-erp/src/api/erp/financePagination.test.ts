import { beforeEach, describe, expect, it, vi } from 'vitest';
import { arApDetail, arApDetailPage, arApSummary, arApSummaryPage } from './finance';

const { request } = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('@/utils/request', () => ({ default: request }));

beforeEach(() => {
  request.mockReset();
});

describe('finance read pagination contracts', () => {
  it('keeps all-match summary amounts separate from the group page and real group count', async () => {
    const payload = {
      code: 200,
      msg: 'ok',
      data: {
        partyType: 'AP',
        totalAmount: '9100.25',
        settledAmount: '2200.00',
        unsettledAmount: '6900.25',
        openingAmount: '2000.00',
        total: 47,
        parties: [{ partyId: '9007199254740993', amount: '100.00', settled: '20.00', unsettled: '80.00', opening: '0.00' }]
      }
    };
    request.mockResolvedValue(payload);
    const query = { partyType: 'AP' as const, pageNum: 2, pageSize: 20 };

    const result = await arApSummaryPage(query);

    expect(result).toBe(payload);
    expect(result.data.total).toBe(47);
    expect(result.data.unsettledAmount).toBe('6900.25');
    expect(result.data.parties).toHaveLength(1);
    expect(request).toHaveBeenCalledWith({ url: '/erp/finance/ar-ap/summary/page', method: 'get', params: query });
  });

  it('uses top-level detail rows/total, exact large IDs and page parameters without client filtering', async () => {
    const rows = [
      { id: '9007199254740993', bizDate: '2026-11-20 18:01:02', sourceType: 'SALE', status: 'SETTLED', unsettledAmount: '0.00' },
      { id: '9007199254740994', bizDate: null, sourceType: 'SALE_RETURN', status: 'OPEN', unsettledAmount: '-50.25' }
    ];
    const payload = { code: 200, msg: 'ok', rows, total: 43 };
    request.mockResolvedValue(payload);
    const query = { partyType: 'AR' as const, partyId: '9007199254740995', pageNum: 3, pageSize: 20 };

    const result = await arApDetailPage(query);

    expect(result.rows).toBe(rows);
    expect(result.total).toBe(43);
    expect(request).toHaveBeenCalledWith({ url: '/erp/finance/ar-ap/detail/page', method: 'get', params: query });
  });

  it('keeps legacy detail responses whole beyond one interactive page and omits the new filter by default', async () => {
    const data = Array.from({ length: 201 }, (_, index) => ({ id: String(9007199254740993n + BigInt(index)) }));
    request.mockResolvedValue({ code: 200, msg: 'ok', data });

    const result = await arApDetail('AR', '9007199254740995');

    expect(result.data).toBe(data);
    expect(result.data).toHaveLength(201);
    expect(request).toHaveBeenCalledWith({
      url: '/erp/finance/ar-ap/detail',
      method: 'get',
      params: { partyType: 'AR', partyId: '9007199254740995' }
    });
  });

  it.each(['AR', 'AP'])('requests only allocatable rows when explicitly chosen for %s without paging', async (partyType) => {
    const data = Array.from({ length: 201 }, (_, index) => ({ id: String(index + 1) }));
    request.mockResolvedValue({ code: 200, msg: 'ok', data });

    const result = await arApDetail(partyType, '9007199254740995', true);

    expect(result.data).toBe(data);
    expect(request).toHaveBeenCalledWith({
      url: '/erp/finance/ar-ap/detail',
      method: 'get',
      params: { partyType, partyId: '9007199254740995', allocatableOnly: true }
    });
  });

  it('does not change the old summary envelope or silently swallow oversized legacy-read errors', async () => {
    const payload = { code: 200, msg: 'ok', data: { partyType: 'AR', parties: [{ partyId: '9' }] } };
    request.mockResolvedValueOnce(payload);
    expect(await arApSummary('AR')).toBe(payload);
    expect(request).toHaveBeenLastCalledWith({ url: '/erp/finance/ar-ap/summary', method: 'get', params: { partyType: 'AR' } });
    const error = new Error('旧明细/核销候选接口最多返回 5000 条');
    request.mockRejectedValueOnce(error);
    await expect(arApDetail('AP', '8')).rejects.toBe(error);
  });
});
