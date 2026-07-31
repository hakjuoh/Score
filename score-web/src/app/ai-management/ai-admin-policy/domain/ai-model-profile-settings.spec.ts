import {AiModelProfile, AiModelUpdate} from './ai-admin-policy';
import {applyModelProfile, modelProfileValidationErrors} from './ai-model-profile-settings';

describe('AI model profile settings', () => {
  it('uses constraint and capability defaults instead of legacy duplicate fields', () => {
    const result = applyModelProfile(form(), profile());

    expect(result).toMatchObject({contextWindow: 1000, maxTokens: 100,
      outputReserveTokens: 100, autoCompactThresholdTokens: 700,
      emergencyHeadroomTokens: 10, toolOutputTokenLimit: 100, temperature: 0.5,
      reasoningModelSupported: true, outputEffortSupported: false,
      verbositySupported: true, temperatureSupported: true});
  });

  it.each([
    ['optional max output', {...form(), maxTokens: null}, null],
    ['fractional temperature', {...form(), temperature: 0.75}, null],
    ['temperature above profile maximum', {...form(), temperature: 1.25}, 'Temperature'],
    ['fractional token value', {...form(), maxTokens: 99.5}, 'Max Output Tokens']
  ])('validates %s from the typed profile constraint', (_name, value, errorPrefix) => {
    const errors = modelProfileValidationErrors(value as AiModelUpdate, profile());

    if (errorPrefix) expect(errors.some(error => error.startsWith(errorPrefix))).toBe(true);
    else expect(errors).toEqual([]);
  });

  function profile(): AiModelProfile {
    const optional = (defaultValue: number | null, minimum: number | null,
                      maximum: number | null) => ({defaultValue, minimum, maximum, optional: true});
    const required = (defaultValue: number, minimum: number, maximum: number) =>
      ({defaultValue, minimum, maximum, optional: false});
    return {providerType: 'openai', modelKey: 'model', providerModelName: 'provider-model',
      displayName: 'Model', description: 'Model description',
      // Deliberately different: these compatibility properties must not drive new-form defaults.
      maxTokens: 999, contextWindow: 999, maxOutputTokens: 999, maxContextWindow: 999,
      outputReserveTokens: 999, autoCompactThresholdTokens: 999,
      emergencyHeadroomTokens: 999, toolOutputTokenLimit: 999,
      providerCompactionEnabled: false, temperature: null, thinkingBudgetTokens: null,
      minThinkingBudgetTokens: null, maxThinkingBudgetTokens: null, adaptiveThinking: false,
      outputEffort: null, cacheStrategy: null, reasoningModelSupported: false,
      outputEffortSupported: true, verbositySupported: false, temperatureSupported: false,
      thinkingModes: [], defaultThinking: null, reasoningEfforts: [],
      configurationConstraints: {contextWindow: required(1000, 1, 1000),
        maxOutputTokens: optional(100, 1, 100),
        outputReserveTokens: optional(100, 1, 999),
        autoCompactThresholdTokens: optional(700, 1, 999),
        emergencyHeadroomTokens: required(10, 0, 999),
        toolOutputTokenLimit: required(100, 1, 999),
        thinkingBudgetTokens: optional(null, null, null),
        temperature: optional(0.5, 0, 1)},
      capabilityConstraints: {
        reasoningOptions: {supported: true, defaultEnabled: true},
        outputEffort: {supported: true, defaultEnabled: false},
        verbosity: {supported: true, defaultEnabled: true},
        temperature: {supported: true, defaultEnabled: true},
        adaptiveThinking: {supported: false, defaultEnabled: false},
        providerCompaction: {supported: false, defaultEnabled: false}}
    };
  }

  function form(): AiModelUpdate {
    return {expectedVersion: null, providerId: 1, modelKey: 'model',
      providerModelName: 'provider-model', displayName: 'Model', description: '',
      enabled: true, defaultModel: false, sortOrder: 0, maxTokens: 100,
      contextWindow: 1000, outputReserveTokens: 100, autoCompactThresholdTokens: 700,
      emergencyHeadroomTokens: 10, toolOutputTokenLimit: 100,
      providerCompactionEnabled: false, temperature: 0.5, thinkingBudgetTokens: null,
      adaptiveThinking: false, outputEffort: null, cacheStrategy: null,
      reasoningModelSupported: true, outputEffortSupported: false,
      verbositySupported: true, temperatureSupported: true,
      thinkingModes: [], defaultThinking: null, reasoningEfforts: []};
  }
});
