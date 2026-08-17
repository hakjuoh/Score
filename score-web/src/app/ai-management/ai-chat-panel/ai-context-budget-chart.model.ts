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
  description: string;
}

const CONTEXT_COLORS = {
  input: '#2563eb',
  remaining: '#bfdbfe',
  output: '#8b5cf6',
  headroom: '#f59e0b',
  other: '#d4d4d8'
};

export const CONTEXT_SLICE_DESCRIPTIONS: Record<string, string> = {
  'Current input': 'Tokens consumed by the current conversation input (messages, prompt instructions, system context, attachments, and tool outputs).',
  'Safe input remaining': 'Remaining token capacity available for additional input before reaching the safe input limit.',
  'Output reserve': 'Tokens reserved exclusively for the AI model to generate responses and reasoning without context overflow.',
  'Emergency headroom': 'Safety buffer reserved to absorb tokenizer differences, system metadata, and runtime overhead.',
  'Other reserved': 'Additional reserved capacity configured for the model.'
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
    {
      label: 'Current input',
      tokens: used,
      color: CONTEXT_COLORS.input,
      description: CONTEXT_SLICE_DESCRIPTIONS['Current input']
    },
    {
      label: 'Safe input remaining',
      tokens: safeLimit - used,
      color: CONTEXT_COLORS.remaining,
      description: CONTEXT_SLICE_DESCRIPTIONS['Safe input remaining']
    },
    {
      label: 'Output reserve',
      tokens: outputReserve,
      color: CONTEXT_COLORS.output,
      description: CONTEXT_SLICE_DESCRIPTIONS['Output reserve']
    },
    {
      label: 'Emergency headroom',
      tokens: emergencyHeadroom,
      color: CONTEXT_COLORS.headroom,
      description: CONTEXT_SLICE_DESCRIPTIONS['Emergency headroom']
    },
    {
      label: 'Other reserved',
      tokens: reserved - outputReserve - emergencyHeadroom,
      color: CONTEXT_COLORS.other,
      description: CONTEXT_SLICE_DESCRIPTIONS['Other reserved']
    }
  ];
  return slices.filter(slice => slice.tokens > 0).map(slice => ({
    ...slice,
    percent: slice.tokens * 100 / window
  }));
}
