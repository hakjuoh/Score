/**
 * Verifies workflow placement when node identifiers repeat or nested workflow types are unknown.
 */

import {AiExecutionComposite} from './ai-execution-composite';
import {AiChatMessage, AiChatSocketEvent} from './ai-chat-panel.model';

describe('AiExecutionComposite unknown workflow fallback', () => {
  it('does not update an earlier turn when node ids repeat', () => {
    const messages: AiChatMessage[] = [];
    const composite = new AiExecutionComposite();

    startUnknown(composite, messages, 'turn-1');
    upsertActivity(composite, messages, 'turn-1', 'First turn running.');
    startUnknown(composite, messages, 'turn-2');
    upsertActivity(composite, messages, 'turn-2', 'Second turn running.');

    expect(messages.filter(message => message.eventType === 'workflow_activity'))
      .toEqual([
        expect.objectContaining({turnId: 'turn-1', content: 'First turn running.'}),
        expect.objectContaining({turnId: 'turn-2', content: 'Second turn running.'})
      ]);
  });

  it('keeps a known nested workflow in the conversation of a plain activity', () => {
    const messages: AiChatMessage[] = [];
    const composite = new AiExecutionComposite();
    const turnId = 'turn-1';
    const root = composite.startWorkflow(workflowEvent(
      turnId, 'root', 'main', 1, 'sequential'), messages);
    expect(root?.anchor).toBeDefined();
    messages.push(root!.anchor!);

    composite.placeAgent({
      requestId: 'request-1', turnId, type: 'system', subtype: 'subagent_started',
      content: 'Owner running.', metadata: {
        nodeId: 'owner', parentNodeId: 'root', agentName: 'Owner'
      }
    }, messages);
    const owner = root!.anchor!.activities?.find(activity => activity.agentId === 'owner');
    expect(owner).toBeDefined();

    const unknown = composite.startWorkflow(workflowEvent(
      turnId, 'future', 'owner', 3, 'speculative'), messages);
    expect(unknown?.container).toBe(owner!.messages);
    unknown!.container.push(unknown!.anchor!);

    const plain = composite.upsertPlainActivity({
      requestId: 'request-1', turnId, type: 'system', subtype: 'subagent_started',
      content: 'Future worker running.', metadata: {
        nodeId: 'future-worker', parentNodeId: 'future', agentName: 'Future worker'
      }
    }, messages);
    expect(plain?.container).toBe(owner!.messages);
    plain!.container.push(plain!.message);

    const nested = composite.startWorkflow(workflowEvent(
      turnId, 'known-nested', 'future-worker', 5, 'sequential'), messages);
    expect(nested).toEqual(expect.objectContaining({
      container: owner!.messages, root: false, presentation: 'box'
    }));
  });
});

function workflowEvent(turnId: string, nodeId: string, parentNodeId: string,
                       depth: number, workflowType: string): AiChatSocketEvent {
  return {
    requestId: 'request-1', turnId, type: 'system', subtype: 'workflow_started',
    content: `${nodeId} started.`, metadata: {
      nodeId, parentNodeId, depth, workflowType
    }
  };
}

function startUnknown(composite: AiExecutionComposite, messages: AiChatMessage[],
                      turnId: string): void {
  const placement = composite.startWorkflow({
    requestId: 'request-1', turnId, type: 'system', subtype: 'workflow_started',
    content: 'Future mode.', metadata: {
      nodeId: 'future', parentNodeId: 'main', depth: 1, workflowType: 'speculative'
    }
  }, messages);
  if (placement?.anchor) messages.push(placement.anchor);
}

function upsertActivity(composite: AiExecutionComposite, messages: AiChatMessage[],
                        turnId: string, content: string): void {
  const event: AiChatSocketEvent = {
    requestId: 'request-1', turnId, type: 'system', subtype: 'subagent_started',
    content, metadata: {
      nodeId: 'worker', parentNodeId: 'future', agentName: 'Future worker'
    }
  };
  const placement = composite.upsertPlainActivity(event, messages);
  if (placement?.created) placement.container.push(placement.message);
}
