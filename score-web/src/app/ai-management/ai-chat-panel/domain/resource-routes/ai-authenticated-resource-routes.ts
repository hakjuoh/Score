import {AiResourceRoute} from '../ai-chat-panel.model';
import {AI_BIE_RESOURCE_ROUTES} from './ai-bie-resource-routes';
import {AI_COMPONENT_RESOURCE_ROUTES} from './ai-component-resource-routes';
import {AI_CONTEXT_RESOURCE_ROUTES} from './ai-context-resource-routes';
import {AI_GOVERNANCE_RESOURCE_ROUTES} from './ai-governance-resource-routes';

export const AI_AUTHENTICATED_RESOURCE_ROUTES: AiResourceRoute[] = [
  ...AI_BIE_RESOURCE_ROUTES,
  ...AI_CONTEXT_RESOURCE_ROUTES,
  ...AI_COMPONENT_RESOURCE_ROUTES,
  ...AI_GOVERNANCE_RESOURCE_ROUTES
];
