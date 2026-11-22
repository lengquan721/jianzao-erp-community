import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ErpId, ErpProductVO } from '@/api/erp/types';
import { loadProductForRestore } from './productRestoreLoader';

// 保留真实 loader → productOptionsByIds → fetchIdBatches，仅替换 HTTP 边界。
type RequestConfig = { url: string; method: string; data: { ids: ErpId[] }; headers: Record<string, unknown> };
const { request } = vi.hoisted(() => ({ request: vi.fn<(config: RequestConfig) => Promise<unknown>>() }));
vi.mock('@/utils/request', () => ({ default: request }));

const product = (id: ErpId, name = `商品${id}`): ErpProductVO => ({ id, code: `P${id}`, name, unit: '件', type: 'PRODUCT', status: 0 });
const response = (products: ErpProductVO[]) => ({ code: 200, msg: 'ok', data: products });
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((done, fail) => {
    resolve = done;
    reject = fail;
  });
  return { promise, resolve, reject };
}
beforeEach(() => {
  request.mockReset().mockImplementation(async ({ data }) => response(data.ids.map((id) => product(id)).reverse()));
});

describe('shared product restore microtask loader using the actual byIds API', () => {
  it('merges twenty distinct rows plus duplicates into one POST and maps shuffled rows by ID', async () => {
    const ids = Array.from({ length: 20 }, (_, index) => String(9007199254740993n + BigInt(index)));
    const promises = ids.map(loadProductForRestore);
    expect(loadProductForRestore(ids[3])).toBe(promises[3]);
    expect(loadProductForRestore(ids[19])).toBe(promises[19]);
    expect(request).not.toHaveBeenCalled();
    const rows = await Promise.all(promises);
    expect(rows.map((row) => row?.id)).toEqual(ids);
    expect(request).toHaveBeenCalledExactlyOnceWith({
      url: '/erp/master/products/options/by-ids',
      method: 'post',
      data: { ids },
      headers: { repeatSubmit: false, silentError: 'true', silentHttpError: 'true' }
    });
  });

  it('normalizes duplicate keys without converting the first ID or snowflake IDs to Number', async () => {
    const number = loadProductForRestore(7);
    const text = loadProductForRestore('7');
    const snowflake = loadProductForRestore('9007199254740993');
    expect(number).toBe(text);
    expect((await Promise.all([number, text, snowflake])).map((row) => row?.id)).toEqual([7, 7, '9007199254740993']);
    expect(request.mock.calls[0][0].data.ids).toEqual([7, '9007199254740993']);
  });

  it.each([
    [200, [200]],
    [201, [200, 1]],
    [405, [200, 200, 5]]
  ] as const)('splits %s distinct IDs at the existing 200-ID boundary without truncation', async (count, sizes) => {
    const ids = Array.from({ length: count }, (_, index) => String(9007199254740993n + BigInt(index)));
    const rows = await Promise.all(ids.map(loadProductForRestore));
    expect(request.mock.calls.map(([config]) => config.data.ids.length)).toEqual(sizes);
    expect(request.mock.calls.flatMap(([config]) => config.data.ids)).toEqual(ids);
    expect(rows.map((row) => row?.id)).toEqual(ids);
  });

  it('returns undefined for missing IDs and refetches later instead of caching rows or negative results', async () => {
    request.mockResolvedValueOnce(response([product('found', '旧名称')]));
    const first = await Promise.all([loadProductForRestore('missing'), loadProductForRestore('found')]);
    expect(first[0]).toBeUndefined();
    expect(first[1]?.name).toBe('旧名称');
    request.mockResolvedValueOnce(response([product('missing', '补回'), product('found', '新名称')]));
    const next = await Promise.all([loadProductForRestore('missing'), loadProductForRestore('found')]);
    expect(next.map((row) => row?.name)).toEqual(['补回', '新名称']);
    expect(request).toHaveBeenCalledTimes(2);
  });

  it('rejects every waiting row on failure and permits a new merged retry', async () => {
    const failure = new Error('restore failed');
    request.mockRejectedValueOnce(failure);
    const results = await Promise.allSettled([loadProductForRestore('a'), loadProductForRestore('b'), loadProductForRestore('a')]);
    expect(results).toEqual(Array.from({ length: 3 }, () => ({ status: 'rejected', reason: failure })));
    const retry = await Promise.all([loadProductForRestore('a'), loadProductForRestore('b')]);
    expect(retry.map((row) => row?.id)).toEqual(['a', 'b']);
    expect(request.mock.calls.map(([config]) => config.data.ids)).toEqual([
      ['a', 'b'],
      ['a', 'b']
    ]);
  });

  it('rejects the entire wave if a later 200-ID chunk fails and refetches every ID on retry', async () => {
    const failure = new Error('second chunk failed');
    request.mockImplementationOnce(async ({ data }) => response(data.ids.map((id) => product(id)))).mockRejectedValueOnce(failure);
    const ids = Array.from({ length: 201 }, (_, index) => `retry-${index}`);
    const results = await Promise.allSettled(ids.map(loadProductForRestore));
    expect(results).toEqual(ids.map(() => ({ status: 'rejected', reason: failure })));
    const retried = await Promise.all(ids.map(loadProductForRestore));
    expect(retried.map((row) => row?.id)).toEqual(ids);
    expect(request.mock.calls.map(([config]) => config.data.ids.length)).toEqual([200, 1, 200, 1]);
  });

  it('releases the queue before awaiting HTTP so separate waves cannot share stale in-flight data', async () => {
    const old = deferred<ReturnType<typeof response>>();
    request.mockReturnValueOnce(old.promise).mockResolvedValueOnce(response([product('same', '新批次')]));
    const first = loadProductForRestore('same');
    await Promise.resolve();
    expect(request).toHaveBeenCalledTimes(1);
    const second = loadProductForRestore('same');
    expect(second).not.toBe(first);
    expect((await second)?.name).toBe('新批次');
    old.resolve(response([product('same', '旧批次')]));
    expect((await first)?.name).toBe('旧批次');
    expect(request).toHaveBeenCalledTimes(2);
  });
});
