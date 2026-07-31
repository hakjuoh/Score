import {AiModelCommand, AiModelProfile, AiModelUpdate, AiNumericConstraint} from './ai-admin-policy';

export function modelCommand(form: AiModelUpdate): AiModelCommand {
  return {expectedVersion: form.expectedVersion, providerId: form.providerId,
    modelKey: form.modelKey, enabled: form.enabled, defaultModel: form.defaultModel,
    sortOrder: form.sortOrder, maxTokens: form.maxTokens, contextWindow: form.contextWindow,
    outputReserveTokens: form.outputReserveTokens,
    autoCompactThresholdTokens: form.autoCompactThresholdTokens,
    emergencyHeadroomTokens: form.emergencyHeadroomTokens,
    toolOutputTokenLimit: form.toolOutputTokenLimit,
    providerCompactionEnabled: form.providerCompactionEnabled, temperature: form.temperature,
    thinkingBudgetTokens: form.thinkingBudgetTokens, adaptiveThinking: form.adaptiveThinking,
    outputEffort: form.outputEffort, cacheStrategy: form.cacheStrategy,
    reasoningModelSupported: form.reasoningModelSupported,
    outputEffortSupported: form.outputEffortSupported,
    verbositySupported: form.verbositySupported,
    temperatureSupported: form.temperatureSupported,
    thinkingModes: [...form.thinkingModes], defaultThinking: form.defaultThinking,
    reasoningEfforts: form.reasoningEfforts.map(effort => ({name: effort.name,
      defaultEffort: effort.defaultEffort, sortOrder: effort.sortOrder}))};
}

export function editableModelState(form: AiModelUpdate): object {
  const {expectedVersion: _expectedVersion, ...editable} = modelCommand(form);
  return {...editable, profileIdentity: {providerModelName: form.providerModelName,
    displayName: form.displayName, description: form.description},
  effortMetadata: form.reasoningEfforts.map(effort => ({name: effort.name,
    displayName: effort.displayName, description: effort.description}))};
}

export function applyModelProfile(form: AiModelUpdate, profile: AiModelProfile): AiModelUpdate {
  return {...form, ...profileDefaults(profile)};
}

export function clearModelProfile(form: AiModelUpdate): AiModelUpdate {
  return {...form, modelKey: '', providerModelName: '', displayName: '', description: '',
    maxTokens: null, contextWindow: 0, outputReserveTokens: null,
    autoCompactThresholdTokens: null, emergencyHeadroomTokens: 0,
    toolOutputTokenLimit: 0, providerCompactionEnabled: false, temperature: null,
    thinkingBudgetTokens: null, adaptiveThinking: false, outputEffort: null,
    cacheStrategy: null, reasoningModelSupported: null, outputEffortSupported: null,
    verbositySupported: null, temperatureSupported: null, thinkingModes: [],
    defaultThinking: null, reasoningEfforts: []};
}

export function constrainModelToProfile(form: AiModelUpdate,
                                        profile: AiModelProfile): AiModelUpdate {
  const allowedEfforts = new Set(profile.reasoningEfforts.map(effort => effort.name));
  const definitions = new Map(profile.reasoningEfforts.map(effort => [effort.name, effort]));
  const efforts = form.reasoningEfforts.filter(effort => allowedEfforts.has(effort.name))
    .map((effort, index) => ({...definitions.get(effort.name)!,
      defaultEffort: effort.defaultEffort, sortOrder: index}));
  if (efforts.length > 0 && !efforts.some(effort => effort.defaultEffort)) {
    efforts[0].defaultEffort = true;
  }
  const thinkingModes = form.thinkingModes.filter(mode => profile.thinkingModes.includes(mode));
  const adaptiveThinking = profile.capabilityConstraints.adaptiveThinking.supported
    && form.adaptiveThinking;
  const normalizedModes = adaptiveThinking
    ? thinkingModes.includes('adaptive') ? thinkingModes : [...thinkingModes, 'adaptive']
    : thinkingModes.filter(mode => mode !== 'adaptive');
  return {...form, providerModelName: profile.providerModelName,
    displayName: profile.displayName, description: profile.description,
    adaptiveThinking,
    providerCompactionEnabled: profile.capabilityConstraints.providerCompaction.supported
      && form.providerCompactionEnabled,
    reasoningModelSupported: supportedSetting(
      form.reasoningModelSupported, profile.capabilityConstraints.reasoningOptions),
    outputEffortSupported: supportedSetting(
      form.outputEffortSupported, profile.capabilityConstraints.outputEffort),
    verbositySupported: supportedSetting(form.verbositySupported,
      profile.capabilityConstraints.verbosity),
    temperatureSupported: supportedSetting(
      form.temperatureSupported, profile.capabilityConstraints.temperature),
    temperature: profile.capabilityConstraints.temperature.supported ? form.temperature : null,
    thinkingBudgetTokens: profile.configurationConstraints.thinkingBudgetTokens.maximum !== null
      ? form.thinkingBudgetTokens : null,
    outputEffort: profile.capabilityConstraints.outputEffort.supported && form.outputEffortSupported
      && allowedEfforts.has(form.outputEffort ?? '') ? form.outputEffort : null,
    cacheStrategy: form.cacheStrategy === profile.cacheStrategy ? form.cacheStrategy : null,
    thinkingModes: normalizedModes,
    defaultThinking: normalizedModes.includes(form.defaultThinking ?? '')
      ? form.defaultThinking : normalizedModes[0] ?? null,
    reasoningEfforts: efforts};
}

export function modelProfileValidationErrors(form: AiModelUpdate,
                                             profile: AiModelProfile): string[] {
  const errors: string[] = [];
  const constraints = profile.configurationConstraints;
  addNumberError(errors, 'Context Window', form.contextWindow, constraints.contextWindow, true);
  addNumberError(errors, 'Max Output Tokens', form.maxTokens,
    constraints.maxOutputTokens, true);
  addNumberError(errors, 'Output Reserve Tokens', form.outputReserveTokens,
    constraints.outputReserveTokens, true);
  addNumberError(errors, 'Auto-compact Threshold', form.autoCompactThresholdTokens,
    constraints.autoCompactThresholdTokens, true);
  addNumberError(errors, 'Emergency Headroom', form.emergencyHeadroomTokens,
    constraints.emergencyHeadroomTokens, true);
  addNumberError(errors, 'Tool Output Token Limit', form.toolOutputTokenLimit,
    constraints.toolOutputTokenLimit, true);
  addNumberError(errors, 'Thinking Token Budget', form.thinkingBudgetTokens,
    constraints.thinkingBudgetTokens, true);
  addNumberError(errors, 'Temperature', form.temperature, constraints.temperature, false);

  const effectiveMaxTokens = form.maxTokens ?? constraints.maxOutputTokens.defaultValue;
  const effectiveThinkingBudget = form.thinkingBudgetTokens
    ?? constraints.thinkingBudgetTokens.defaultValue;
  if (effectiveThinkingBudget !== null && effectiveMaxTokens !== null
    && effectiveThinkingBudget >= effectiveMaxTokens) {
    errors.push('Thinking Token Budget must be smaller than Max Output Tokens.');
  }
  const reserve = form.outputReserveTokens
    ?? constraints.outputReserveTokens.defaultValue ?? 0;
  const safeInput = form.contextWindow - reserve - form.emergencyHeadroomTokens;
  const threshold = form.autoCompactThresholdTokens
    ?? constraints.autoCompactThresholdTokens.defaultValue ?? 0;
  if (reserve >= form.contextWindow || form.emergencyHeadroomTokens >= form.contextWindow - reserve
    || safeInput <= 0 || threshold > safeInput || form.toolOutputTokenLimit > safeInput) {
    errors.push('Reserve, headroom, compaction threshold, and tool output limit must fit the Context Window.');
  }

  addUnsupportedCapabilityErrors(errors, form, profile);
  const allowedModes = new Set(profile.thinkingModes);
  if (form.thinkingModes.some(mode => !allowedModes.has(mode))) {
    errors.push('Thinking Modes contain a value that this model does not support.');
  }
  if (form.adaptiveThinking !== form.thinkingModes.includes('adaptive')) {
    errors.push('Adaptive Thinking and the adaptive Thinking Mode must be enabled together.');
  }
  if (constraints.thinkingBudgetTokens.maximum !== null
    && (form.thinkingModes.length === 0 || !form.defaultThinking)) {
    errors.push('Fixed Thinking requires at least one enabled mode and a default mode.');
  }
  if (form.defaultThinking && !form.thinkingModes.includes(form.defaultThinking)) {
    errors.push('Default Thinking Mode must be one of the enabled modes.');
  }
  const allowedEfforts = new Set(profile.reasoningEfforts.map(effort => effort.name));
  if (form.reasoningEfforts.some(effort => !allowedEfforts.has(effort.name))) {
    errors.push('Reasoning Efforts contain a value that this model does not support.');
  }
  if (form.reasoningEfforts.length > 0
    && form.reasoningEfforts.filter(effort => effort.defaultEffort).length !== 1) {
    errors.push('Select exactly one default Reasoning Effort.');
  }
  if (profile.capabilityConstraints.reasoningOptions.supported
    && form.reasoningModelSupported !== true
    && form.reasoningEfforts.length > 0) {
    errors.push('Enable Reasoning Options before selecting Reasoning Efforts.');
  }
  if (form.outputEffort && !(profile.capabilityConstraints.outputEffort.supported
    && form.outputEffortSupported
    && allowedEfforts.has(form.outputEffort))) {
    errors.push('Output Effort is not enabled or supported.');
  }
  if (form.cacheStrategy && form.cacheStrategy !== profile.cacheStrategy) {
    errors.push('Cache Strategy is not supported by this model.');
  }
  return [...new Set(errors)];
}

function profileDefaults(profile: AiModelProfile) {
  const values = profile.configurationConstraints;
  const capabilities = profile.capabilityConstraints;
  return {modelKey: profile.modelKey, providerModelName: profile.providerModelName,
    displayName: profile.displayName, description: profile.description,
    maxTokens: values.maxOutputTokens.defaultValue,
    contextWindow: values.contextWindow.defaultValue ?? 0,
    outputReserveTokens: values.outputReserveTokens.defaultValue,
    autoCompactThresholdTokens: values.autoCompactThresholdTokens.defaultValue,
    emergencyHeadroomTokens: values.emergencyHeadroomTokens.defaultValue ?? 0,
    toolOutputTokenLimit: values.toolOutputTokenLimit.defaultValue ?? 0,
    providerCompactionEnabled: capabilities.providerCompaction.defaultEnabled,
    temperature: values.temperature.defaultValue,
    thinkingBudgetTokens: values.thinkingBudgetTokens.defaultValue,
    adaptiveThinking: capabilities.adaptiveThinking.defaultEnabled,
    outputEffort: profile.outputEffort,
    cacheStrategy: profile.cacheStrategy,
    reasoningModelSupported: capabilities.reasoningOptions.defaultEnabled,
    outputEffortSupported: capabilities.outputEffort.defaultEnabled,
    verbositySupported: capabilities.verbosity.defaultEnabled,
    temperatureSupported: capabilities.temperature.defaultEnabled,
    thinkingModes: [...profile.thinkingModes], defaultThinking: profile.defaultThinking,
    reasoningEfforts: profile.reasoningEfforts.map(effort => ({...effort}))};
}

function supportedSetting(configured: boolean | null,
                          capability: {supported: boolean; defaultEnabled: boolean}): boolean | null {
  if (!capability.supported) return false;
  return configured ?? capability.defaultEnabled;
}

function addNumberError(errors: string[], label: string, value: number | null,
                        constraint: AiNumericConstraint, integer: boolean): void {
  if (value === null) {
    if (!constraint.optional) errors.push(`${label} is required.`);
    return;
  }
  if (!Number.isFinite(value) || integer && !Number.isSafeInteger(value)
    || constraint.minimum === null || constraint.maximum === null
    || value < constraint.minimum || value > constraint.maximum) {
    errors.push(`${label} must be between ${constraint.minimum ?? '—'} and ${constraint.maximum ?? '—'}.`);
  }
}

function addUnsupportedCapabilityErrors(errors: string[], form: AiModelUpdate,
                                        profile: AiModelProfile): void {
  const capabilities: Array<[boolean, boolean | null, string]> = [
    [form.adaptiveThinking, profile.capabilityConstraints.adaptiveThinking.supported,
      'Adaptive Thinking'],
    [form.providerCompactionEnabled, profile.capabilityConstraints.providerCompaction.supported,
      'Provider Compaction'],
    [form.reasoningModelSupported === true,
      profile.capabilityConstraints.reasoningOptions.supported, 'Reasoning Options'],
    [form.outputEffortSupported === true,
      profile.capabilityConstraints.outputEffort.supported, 'Output Effort'],
    [form.verbositySupported === true, profile.capabilityConstraints.verbosity.supported,
      'Verbosity'],
    [form.temperatureSupported === true, profile.capabilityConstraints.temperature.supported,
      'Temperature']
  ];
  capabilities.filter(([enabled, supported]) => enabled && supported !== true)
    .forEach(([, , label]) => errors.push(`${label} is not supported by this model.`));
}
