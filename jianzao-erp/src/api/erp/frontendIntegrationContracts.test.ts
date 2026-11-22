import { beforeEach, describe, expect, it, vi } from 'vitest';
import { confirmedOpeningByPartyIds, saveFinOpening } from './finance';
import { previewPrintDesign, previewPrintTemplate, renderStatement } from './print';
import type { PrintTemplateForm } from './types';

const request = vi.hoisted(() => vi.fn());
vi.mock('@/utils/request', () => ({ default: request }));
beforeEach(() => request.mockReset().mockResolvedValue({ code: 200, data: [] }));

describe('frontend integration wire contracts', () => {
  it('requests only current page IDs with direction and no numeric conversion or broad fallback', async () => {
    await confirmedOpeningByPartyIds('AR', ['9007199254740993', '9007199254740995', '9007199254740993']);
    expect(request).toHaveBeenCalledExactlyOnceWith({
      url: '/erp/finance/openings/confirmed/by-party-ids',
      method: 'get',
      params: { partyType: 'AR', partyIds: '9007199254740993,9007199254740995' }
    });
  });
  it('rejects empty/oversized ID sets rather than dropping the final IDs or scanning all openings', async () => {
    await expect(confirmedOpeningByPartyIds('AP', [])).rejects.toThrow();
    await expect(
      confirmedOpeningByPartyIds(
        'AP',
        Array.from({ length: 201 }, (_, i) => String(i + 1))
      )
    ).rejects.toThrow();
    expect(request).not.toHaveBeenCalled();
  });
  it('keeps decimal amounts and explicit date on opening save', async () => {
    await saveFinOpening({ partyType: 'AR', partyId: '9007199254740993', amount: '9007199254740993.12', openingDate: '2026-10-31' });
    expect(request).toHaveBeenLastCalledWith(
      expect.objectContaining({
        data: {
          partyType: 'AR',
          partyId: '9007199254740993',
          amount: '9007199254740993.12',
          openingDate: '2026-10-31'
        }
      })
    );
  });
  it('passes range dates without replacing existing month requests or designer schemaJson', async () => {
    await renderStatement({ customerId: '9007199254740993', startDate: '2026-10-01', endDate: '2026-11-20' });
    expect(request).toHaveBeenLastCalledWith(
      expect.objectContaining({
        url: '/erp/print/STATEMENT',
        params: {
          customerId: '9007199254740993',
          startDate: '2026-10-01',
          endDate: '2026-11-20'
        }
      })
    );
    await previewPrintTemplate({ scene: 'STATEMENT_RANGE', content: '<main>源码</main>', paper: '241x93' });
    expect(request).toHaveBeenLastCalledWith(
      expect.objectContaining({ params: expect.objectContaining({ scene: 'STATEMENT_RANGE', paper: '241x93' }), data: '<main>源码</main>' })
    );
    const payload: PrintTemplateForm = { name: '旧设计器', scene: 'STATEMENT_RANGE', schemaJson: '{"paper":"241x93","blocks":[]}' };
    await previewPrintDesign({ scene: payload.scene, schemaJson: payload.schemaJson! });
    expect(request).toHaveBeenLastCalledWith(expect.objectContaining({ data: payload.schemaJson, responseType: 'text', timeout: 120000 }));
  });
});
