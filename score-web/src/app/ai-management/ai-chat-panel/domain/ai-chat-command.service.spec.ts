/**
 * Verifies the AI Chat Command service contract, failure handling, and edge cases.
 */

import {AiChatCommandService} from './ai-chat-command.service';

describe('AiChatCommandService', () => {
  const service = new AiChatCommandService();

  it('classifies local commands separately from backend prompt commands', () => {
    expect(service.decide('/clear')).toEqual({kind: 'local', command: 'clear'});
    expect(service.decide('/cancel')).toEqual({kind: 'local', command: 'cancel'});
    expect(service.decide('/stop')).toEqual({kind: 'none'});
    expect(service.decide('/debug')).toEqual({kind: 'local', command: 'debug'});
    expect(service.decide('/debug on')).toEqual({kind: 'none'});
    expect(service.decide('/debug off')).toEqual({kind: 'none'});
    expect(service.decide('/mcp')).toEqual({kind: 'local', command: 'mcp'});
    expect(service.decide('/model')).toEqual({kind: 'local', command: 'model'});
    expect(service.decide('/runtime')).toEqual({kind: 'none'});
    expect(service.decide('/permissions')).toEqual({kind: 'local', command: 'permissions'});
    expect(service.decide('/agents')).toEqual({kind: 'none'});
    expect(service.decide('/compact')).toEqual({kind: 'backend'});
    expect(service.decide('/compact preserve import IDs')).toEqual({kind: 'backend'});
    expect(service.isKnownCommand('/compact preserve import IDs')).toBe(true);
    expect(service.decide('/compactly')).toEqual({kind: 'none'});
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
