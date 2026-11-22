import request from '@/utils/request';
import type { ErpId, ErpResult } from './types';

/** options/by-ids 与全仓数量查询均最多接收 200 个 ID；保持原 ID 类型，不丢雪花 ID 精度。 */
export async function fetchIdBatches<T>(url: string, ids: readonly ErpId[]): Promise<T[]> {
  const unique = [...new Map(ids.map((id) => [String(id), id])).values()];
  const rows: T[] = [];
  for (let offset = 0; offset < unique.length; offset += 200) {
    const res = await request<unknown, ErpResult<T[]>>({
      url,
      method: 'post',
      data: { ids: unique.slice(offset, offset + 200) },
      // 只读 POST，允许刷新同一批已选项；调用方按缺失值降级。
      headers: { repeatSubmit: false, silentError: 'true', silentHttpError: 'true' }
    });
    rows.push(...res.data);
  }
  return rows;
}
