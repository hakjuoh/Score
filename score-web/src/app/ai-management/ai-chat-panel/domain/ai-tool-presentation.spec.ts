import {describe, expect, it} from 'vitest';
import {defaultToolStatusContent} from './ai-tool-presentation';

describe('defaultToolStatusContent', () => {
  it.each([
    ['completed', 'search completed.'],
    ['failed', 'search failed.'],
    ['blocked', 'search is awaiting approval.'],
    ['denied', 'search was denied before execution.'],
    ['cancelled', 'search was stopped before execution.']
  ] as const)('presents %s consistently', (status, expected) => {
    expect(defaultToolStatusContent(status, 'search')).toBe(expected);
  });

  it.each([
    ['completed', 'Executed'],
    ['failed', 'Execution failed'],
    ['blocked', 'Awaiting approval'],
    ['denied', 'Denied before execution'],
    ['cancelled', 'Stopped before execution']
  ] as const)('uses a bounded fallback for unnamed %s tools', (status, expected) => {
    expect(defaultToolStatusContent(status, undefined)).toBe(expected);
  });
});
