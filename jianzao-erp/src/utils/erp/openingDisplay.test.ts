import { describe, expect, it } from 'vitest';
import { openingAmountText } from './openingDisplay';

describe('read-only opening amount presentation', () => {
  it('preserves decimal strings beyond safe integer precision', () => {
    expect(openingAmountText('9007199254740993.12')).toBe('9,007,199,254,740,993.12');
    expect(openingAmountText('123.455')).toBe('123.46');
    expect(openingAmountText('-123.455')).toBe('-123.46');
  });
  it('distinguishes unknown/invalid from a real authorized zero', () => {
    expect(openingAmountText(undefined)).toBe('未知');
    expect(openingAmountText(null)).toBe('未知');
    expect(openingAmountText('not an amount')).toBe('未知');
    expect(openingAmountText('0')).toBe('0.00');
  });
});
