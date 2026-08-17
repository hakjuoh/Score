/**
 * Verifies that context-budget slices partition the model window without overlap
 * across simple synthetic models and configured frontier models.
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

  describe('simple test model (Window: 1000, Safe: 700, Reserve: 200, Headroom: 100)', () => {
    it('correctly calculates initial 0-token usage state', () => {
      const slices = contextBudgetSlices({
        usage: {
          modelName: 'simple-test-model',
          currentInputTokens: 0,
          contextWindow: 1000,
          safeInputLimit: 700,
          remainingTokens: 700,
          usedPercent: 0,
          estimated: true,
          source: 'configured'
        },
        outputReserveTokens: 200,
        emergencyHeadroomTokens: 100
      });

      expect(Object.fromEntries(slices.map(slice => [slice.label, slice.tokens]))).toEqual({
        'Safe input remaining': 700,
        'Output reserve': 200,
        'Emergency headroom': 100
      });
      expect(slices.reduce((total, slice) => total + slice.tokens, 0)).toBe(1000);
      expect(slices.reduce((total, slice) => total + slice.percent, 0)).toBeCloseTo(100, 5);

      const safeSlice = slices.find(s => s.label === 'Safe input remaining');
      const reserveSlice = slices.find(s => s.label === 'Output reserve');
      const headroomSlice = slices.find(s => s.label === 'Emergency headroom');
      expect(safeSlice?.percent).toBe(70);
      expect(reserveSlice?.percent).toBe(20);
      expect(headroomSlice?.percent).toBe(10);
    });

    it('correctly calculates 50% partial usage (350 tokens)', () => {
      const slices = contextBudgetSlices({
        usage: {
          modelName: 'simple-test-model',
          currentInputTokens: 350,
          contextWindow: 1000,
          safeInputLimit: 700,
          remainingTokens: 350,
          usedPercent: 50,
          estimated: false,
          source: 'provider'
        },
        outputReserveTokens: 200,
        emergencyHeadroomTokens: 100
      });

      expect(Object.fromEntries(slices.map(slice => [slice.label, slice.tokens]))).toEqual({
        'Current input': 350,
        'Safe input remaining': 350,
        'Output reserve': 200,
        'Emergency headroom': 100
      });
      expect(slices.reduce((total, slice) => total + slice.tokens, 0)).toBe(1000);
      expect(slices.reduce((total, slice) => total + slice.percent, 0)).toBeCloseTo(100, 5);

      const inputSlice = slices.find(s => s.label === 'Current input');
      const safeSlice = slices.find(s => s.label === 'Safe input remaining');
      expect(inputSlice?.percent).toBe(35);
      expect(safeSlice?.percent).toBe(35);
    });

    it('correctly handles full safe input capacity (700 tokens)', () => {
      const slices = contextBudgetSlices({
        usage: {
          modelName: 'simple-test-model',
          currentInputTokens: 700,
          contextWindow: 1000,
          safeInputLimit: 700,
          remainingTokens: 0,
          usedPercent: 100,
          estimated: false,
          source: 'provider'
        },
        outputReserveTokens: 200,
        emergencyHeadroomTokens: 100
      });

      expect(Object.fromEntries(slices.map(slice => [slice.label, slice.tokens]))).toEqual({
        'Current input': 700,
        'Output reserve': 200,
        'Emergency headroom': 100
      });
      expect(slices.reduce((total, slice) => total + slice.tokens, 0)).toBe(1000);
      expect(slices.reduce((total, slice) => total + slice.percent, 0)).toBeCloseTo(100, 5);
    });

    it('clamps input usage if tokens exceed safe input limit (800 tokens)', () => {
      const slices = contextBudgetSlices({
        usage: {
          modelName: 'simple-test-model',
          currentInputTokens: 800,
          contextWindow: 1000,
          safeInputLimit: 700,
          remainingTokens: 0,
          usedPercent: 100,
          estimated: false,
          source: 'provider'
        },
        outputReserveTokens: 200,
        emergencyHeadroomTokens: 100
      });

      expect(Object.fromEntries(slices.map(slice => [slice.label, slice.tokens]))).toEqual({
        'Current input': 700,
        'Output reserve': 200,
        'Emergency headroom': 100
      });
      expect(slices.reduce((total, slice) => total + slice.tokens, 0)).toBe(1000);
    });
  });

  describe('GPT-5.6 Sol model (Window: 1,050,000, Safe: 917,904, Reserve: 128,000, Headroom: 4,096)', () => {
    it('correctly calculates initial state for GPT-5.6 Sol', () => {
      const slices = contextBudgetSlices({
        usage: {
          modelName: 'gpt-5_6-sol',
          currentInputTokens: 0,
          contextWindow: 1050000,
          safeInputLimit: 917904,
          remainingTokens: 917904,
          usedPercent: 0,
          estimated: true,
          source: 'configured'
        },
        outputReserveTokens: 128000,
        emergencyHeadroomTokens: 4096
      });

      expect(Object.fromEntries(slices.map(slice => [slice.label, slice.tokens]))).toEqual({
        'Safe input remaining': 917904,
        'Output reserve': 128000,
        'Emergency headroom': 4096
      });
      expect(slices.reduce((total, slice) => total + slice.tokens, 0)).toBe(1050000);
      expect(slices.reduce((total, slice) => total + slice.percent, 0)).toBeCloseTo(100, 5);

      const safeSlice = slices.find(s => s.label === 'Safe input remaining');
      const reserveSlice = slices.find(s => s.label === 'Output reserve');
      const headroomSlice = slices.find(s => s.label === 'Emergency headroom');
      expect(safeSlice?.percent).toBeCloseTo(87.419, 2);
      expect(reserveSlice?.percent).toBeCloseTo(12.190, 2);
      expect(headroomSlice?.percent).toBeCloseTo(0.390, 2);
    });

    it('correctly partitions context with active turn input for GPT-5.6 Sol', () => {
      const slices = contextBudgetSlices({
        usage: {
          modelName: 'gpt-5_6-sol',
          currentInputTokens: 50000,
          contextWindow: 1050000,
          safeInputLimit: 917904,
          remainingTokens: 867904,
          usedPercent: 5.4,
          estimated: false,
          source: 'provider'
        },
        outputReserveTokens: 128000,
        emergencyHeadroomTokens: 4096
      });

      expect(Object.fromEntries(slices.map(slice => [slice.label, slice.tokens]))).toEqual({
        'Current input': 50000,
        'Safe input remaining': 867904,
        'Output reserve': 128000,
        'Emergency headroom': 4096
      });
      expect(slices.reduce((total, slice) => total + slice.tokens, 0)).toBe(1050000);
      expect(slices.reduce((total, slice) => total + slice.percent, 0)).toBeCloseTo(100, 5);
    });
  });

  describe('Claude Opus 5 model (Window: 1,000,000, Safe: 863,808, Reserve: 128,000, Headroom: 8,192)', () => {
    it('correctly calculates initial state for Claude Opus 5', () => {
      const slices = contextBudgetSlices({
        usage: {
          modelName: 'claude-opus-5',
          currentInputTokens: 0,
          contextWindow: 1000000,
          safeInputLimit: 863808,
          remainingTokens: 863808,
          usedPercent: 0,
          estimated: true,
          source: 'configured'
        },
        outputReserveTokens: 128000,
        emergencyHeadroomTokens: 8192
      });

      expect(Object.fromEntries(slices.map(slice => [slice.label, slice.tokens]))).toEqual({
        'Safe input remaining': 863808,
        'Output reserve': 128000,
        'Emergency headroom': 8192
      });
      expect(slices.reduce((total, slice) => total + slice.tokens, 0)).toBe(1000000);
      expect(slices.reduce((total, slice) => total + slice.percent, 0)).toBeCloseTo(100, 5);
    });
  });
});
