/**
 * Combines authenticated and administrator resource metadata into the complete route registry.
 */

import {AiResourceRoute} from '../ai-chat-panel.model';
import {AI_ADMIN_RESOURCE_ROUTES} from './ai-admin-resource-routes';
import {AI_AUTHENTICATED_RESOURCE_ROUTES} from './ai-authenticated-resource-routes';

export const AI_RESOURCE_ROUTES: AiResourceRoute[] = [
  ...AI_AUTHENTICATED_RESOURCE_ROUTES,
  ...AI_ADMIN_RESOURCE_ROUTES
];
