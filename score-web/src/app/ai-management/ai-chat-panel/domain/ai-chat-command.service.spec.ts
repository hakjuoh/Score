/**
 * Verifies the AI Chat Command service contract, failure handling, and edge cases.
 */

import {AiChatCommandService} from './ai-chat-command.service';

describe('AiChatCommandService', () => {
  const service = new AiChatCommandService();

  it('resolves known command invocations from the shared catalog', () => {
    expect(service.resolveCommand('/clear')).toBe('/clear');
    expect(service.resolveCommand('/cancel')).toBe('/cancel');
    expect(service.resolveCommand('/stop')).toBeUndefined();
    expect(service.resolveCommand('/debug')).toBe('/debug');
    expect(service.resolveCommand('/debug on')).toBeUndefined();
    expect(service.resolveCommand('/debug off')).toBeUndefined();
    expect(service.resolveCommand('/mcp')).toBe('/mcp');
    expect(service.resolveCommand('/model')).toBe('/model');
    expect(service.resolveCommand('/runtime')).toBeUndefined();
    expect(service.resolveCommand('/permissions')).toBe('/permissions');
    expect(service.resolveCommand('/agents')).toBeUndefined();
    expect(service.resolveCommand('/compact')).toBe('/compact');
    expect(service.resolveCommand('/compact preserve import IDs')).toBe('/compact');
    expect(service.isKnownCommand('/compact preserve import IDs')).toBe(true);
    expect(service.resolveCommand('/compactly')).toBeUndefined();
    expect(service.isKnownCommand('/compactly')).toBe(false);
  });

  it('returns command suggestions only for slash prompts', () => {
    expect(service.suggestions('hello')).toEqual([]);
    expect(service.suggestions('/co').map(command => command.name)).toEqual(['/compact']);
  });

  it('shows model normally and cancel only while a request is active', () => {
    expect(service.suggestions('/').map(command => command.name)).toContain('/model');
    expect(service.suggestions('/').map(command => command.name)).not.toContain('/runtime');
    expect(service.suggestions('/').map(command => command.name)).toContain('/permissions');
    expect(service.suggestions('/').map(command => command.name)).toContain('/mcp');
    expect(service.suggestions('/').map(command => command.name)).not.toContain('/agents');
    expect(service.suggestions('/').map(command => command.name)).not.toContain('/cancel');
    expect(service.suggestions('/', true).map(command => command.name)).toEqual(['/cancel']);
  });
});
