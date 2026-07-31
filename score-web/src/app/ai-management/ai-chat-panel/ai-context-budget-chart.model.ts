/**
 * Defines context-budget chart data, slice geometry, and display contracts.
 */

import {AiContextUsage} from './domain/ai-chat-panel.model';

export interface AiContextBudgetData {
  usage: AiContextUsage;
  outputReserveTokens: number;
  emergencyHeadroomTokens: number;
}

export interface AiContextBudgetSlice {
  label: string;
  tokens: number;
  percent: number;
  color: string;
}

const CONTEXT_COLORS = {
  input: '#2563eb',
  remaining: '#bfdbfe',
  output: '#8b5cf6',
  headroom: '#f59e0b',
  other: '#d4d4d8'
};

export function contextBudgetSlices(data: AiContextBudgetData): AiContextBudgetSlice[] {
  const window = Math.max(1, Math.trunc(data.usage.contextWindow));
  const safeLimit = Math.min(window, Math.max(0, Math.trunc(data.usage.safeInputLimit)));
  const used = Math.min(safeLimit, Math.max(0, Math.trunc(data.usage.currentInputTokens)));
  const reserved = window - safeLimit;
  const outputReserve = Math.min(reserved, Math.max(0, Math.trunc(data.outputReserveTokens)));
  const emergencyHeadroom = Math.min(reserved - outputReserve,
    Math.max(0, Math.trunc(data.emergencyHeadroomTokens)));
  const slices = [
    {label: 'Current input', tokens: used, color: CONTEXT_COLORS.input},
    {label: 'Safe input remaining', tokens: safeLimit - used, color: CONTEXT_COLORS.remaining},
    {label: 'Output reserve', tokens: outputReserve, color: CONTEXT_COLORS.output},
    {label: 'Emergency headroom', tokens: emergencyHeadroom, color: CONTEXT_COLORS.headroom},
    {label: 'Other reserved', tokens: reserved - outputReserve - emergencyHeadroom, color: CONTEXT_COLORS.other}
  ];
  return slices.filter(slice => slice.tokens > 0).map(slice => ({
    ...slice,
    percent: slice.tokens * 100 / window
  }));
}
