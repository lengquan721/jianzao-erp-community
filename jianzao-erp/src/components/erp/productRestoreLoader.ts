import { productOptionsByIds } from '@/api/erp/master';
import type { ErpId, ErpProductVO } from '@/api/erp/types';

type RestoreEntry = {
  id: ErpId;
  promise: Promise<ErpProductVO | undefined>;
  resolve: (product: ErpProductVO | undefined) => void;
  reject: (error: unknown) => void;
};

/** 只合并同一微任务窗口内各行的回显；不保留结果、缺失值或在途请求缓存。 */
let pending = new Map<string, RestoreEntry>();

async function flush() {
  const batch = pending;
  pending = new Map();
  try {
    // 复用 byIds 的去重与每批 200 上限，不再由每个行 watcher 单独请求。
    const products = await productOptionsByIds([...batch.values()].map((entry) => entry.id));
    const byId = new Map(products.map((product) => [String(product.id), product]));
    for (const [key, entry] of batch) entry.resolve(byId.get(key));
  } catch (error) {
    for (const entry of batch.values()) entry.reject(error);
  }
}

export function loadProductForRestore(id: ErpId): Promise<ErpProductVO | undefined> {
  const key = String(id);
  const existing = pending.get(key);
  if (existing) return existing.promise;

  let resolve!: RestoreEntry['resolve'];
  let reject!: RestoreEntry['reject'];
  const promise = new Promise<ErpProductVO | undefined>((done, fail) => {
    resolve = done;
    reject = fail;
  });
  pending.set(key, { id, promise, resolve, reject });
  if (pending.size === 1) queueMicrotask(() => void flush());
  return promise;
}
