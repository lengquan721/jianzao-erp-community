import type { ErpAmount } from '@/api/erp/types';

/** 净执行金额由订单接口聚合；缺字段时不冒充 0。 */
export function readExecutedAmount({ executedAmount }: { executedAmount?: ErpAmount | null }): number | null {
  if (executedAmount == null || executedAmount === '') return null;
  const amount = Number(executedAmount);
  return Number.isFinite(amount) ? amount : null;
}

/** 仅受销售退货控制的红字收款为 1；不得在普通收款页修改。 */
export function isControlledRefund(negative?: number | null): boolean {
  return negative === 1;
}

/** 收款接口的退款原值为正数；展示时取负值，不修改原始单据及核销值。 */
export function signedReceiptAmount(amount: ErpAmount | null | undefined, negative?: number | null): number {
  const value = Number(amount ?? 0);
  return isControlledRefund(negative) ? -Math.abs(value) : value;
}
