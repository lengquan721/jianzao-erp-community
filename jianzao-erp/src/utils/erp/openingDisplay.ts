import type { ErpAmount } from '@/api/erp/types';

/** 只读期初：未知不当作0，十进制字符串不经过Number，以分为单位精确格式化。 */
export function openingAmountText(value: ErpAmount | null | undefined): string {
  if (value == null) return '未知';
  const match = /^([+-]?)(\d+)(?:\.(\d+))?$/.exec(String(value));
  if (!match) return '未知';
  const fraction = (match[3] ?? '').padEnd(3, '0');
  let cents = BigInt(match[2]) * 100n + BigInt(fraction.slice(0, 2));
  if (fraction[2] >= '5') cents += 1n;
  const integer = String(cents / 100n).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  const sign = match[1] === '-' && cents !== 0n ? '-' : '';
  return `${sign}${integer}.${String(cents % 100n).padStart(2, '0')}`;
}
