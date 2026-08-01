import {AiModelUpdate, AiProviderUpdate} from './ai-admin-policy';
import {httpErrorMessage, validModelUpdate, validProviderUpdate} from './ai-admin-validation';

describe('AI admin validation', () => {
  const provider = (): AiProviderUpdate => ({providerName: 'OpenAI',
    providerType: 'openai', baseUrl: 'https://api.openai.com', messagesUrl: null,
    apiVersion: null, enabled: true});

  const model = (): AiModelUpdate => ({providerId: 1,
    modelKey: 'model', providerModelName: 'model', displayName: 'Model', description: '',
    enabled: true, defaultModel: true, sortOrder: 0, maxTokens: 4096,
    contextWindow: 128000, outputReserveTokens: 4096, autoCompactThresholdTokens: 100000,
    emergencyHeadroomTokens: 4096, toolOutputTokenLimit: 32000,
    providerCompactionEnabled: true, temperature: null, thinkingBudgetTokens: null,
    adaptiveThinking: false, outputEffort: null, cacheStrategy: null,
    reasoningModelSupported: true, outputEffortSupported: false, verbositySupported: false,
    temperatureSupported: true, thinkingModes: ['enabled'], defaultThinking: 'enabled',
    reasoningEfforts: [{name: 'high', displayName: 'High', description: '',
      defaultEffort: true, sortOrder: 0}]});

  it('requires a supported provider type and endpoint for enabled providers', () => {
    expect(validProviderUpdate(provider())).toBe(true);
    expect(validProviderUpdate({...provider(), providerType: 'azure-openai'})).toBe(false);
    expect(validProviderUpdate({...provider(), providerType: 'custom'})).toBe(false);
    expect(validProviderUpdate({...provider(), baseUrl: null})).toBe(false);
    expect(validProviderUpdate({...provider(), baseUrl: 'file:///tmp/key'})).toBe(false);
  });

  it('matches context budget, uniqueness, integer, and effort invariants', () => {
    expect(validModelUpdate(model())).toBe(true);
    expect(validModelUpdate({...model(), maxTokens: 1.5})).toBe(false);
    expect(validModelUpdate({...model(), autoCompactThresholdTokens: 128000})).toBe(false);
    expect(validModelUpdate({...model(), toolOutputTokenLimit: 119808})).toBe(true);
    expect(validModelUpdate({...model(), toolOutputTokenLimit: 119809})).toBe(false);
    expect(validModelUpdate({...model(), thinkingModes: ['enabled', 'enabled']})).toBe(false);
    expect(validModelUpdate({...model(), reasoningEfforts: [
      ...model().reasoningEfforts, {...model().reasoningEfforts[0], name: 'HIGH', defaultEffort: false}
    ]})).toBe(false);
    expect(validModelUpdate({...model(), enabled: false, defaultModel: false,
      reasoningEfforts: [{...model().reasoningEfforts[0], defaultEffort: false}]})).toBe(false);
    expect(validModelUpdate({...model(), reasoningEfforts: [
      {...model().reasoningEfforts[0], sortOrder: -1}
    ]})).toBe(false);
    expect(validModelUpdate({...model(), reasoningEfforts: []})).toBe(true);
  });

  it('extracts only safe server error text', () => {
    expect(httpErrorMessage({error: {message: 'Conflict'}}, 'fallback')).toBe('Conflict');
    expect(httpErrorMessage({error: {details: 'secret'}}, 'fallback')).toBe('fallback');
    expect(httpErrorMessage(null, 'fallback')).toBe('fallback');
  });
});
