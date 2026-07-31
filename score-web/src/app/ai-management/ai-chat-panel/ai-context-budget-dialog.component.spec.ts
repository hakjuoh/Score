/**
 * Verifies that context-budget slices partition the model window without overlap.
 */

import {contextBudgetSlices} from './ai-context-budget-chart.model';

describe('contextBudgetSlices', () => {
  it('partitions the full model window without overlapping reserved capacity', () => {
    const slices = contextBudgetSlices({
      usage: {
        modelName: 'claude-fable-5', currentInputTokens: 12380,
        contextWindow: 200000, safeInputLimit: 175808, remainingTokens: 163428,
        usedPercent: 7, estimated: true, source: 'preflight_estimate'
      },
      outputReserveTokens: 16000,
      emergencyHeadroomTokens: 8192
    });

    expect(Object.fromEntries(slices.map(slice => [slice.label, slice.tokens]))).toEqual({
      'Current input': 12380,
      'Safe input remaining': 163428,
      'Output reserve': 16000,
      'Emergency headroom': 8192
    });
    expect(slices.reduce((total, slice) => total + slice.tokens, 0)).toBe(200000);
  });
});
