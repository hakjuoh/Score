import {AiModelCommand, AiModelProfile, AiModelUpdate, AiNumericConstraint} from './ai-admin-policy';

export type AiModelValidationField =
  'contextWindow' | 'maxTokens' | 'outputReserveTokens' | 'autoCompactThresholdTokens' |
  'emergencyHeadroomTokens' | 'toolOutputTokenLimit' | 'thinkingBudgetTokens' |
  'temperature' | 'thinkingModes' | 'reasoningEfforts' | 'capabilities' |
  'outputEffort' | 'cacheStrategy';

export interface AiModelValidationIssue {
  field: AiModelValidationField;
  message: string;
}

export function isImplicitUnsetEffort(
  effort: Pick<AiModelProfile['reasoningEfforts'][number], 'name' | 'displayName'>): boolean {
  return ['none', 'disabled'].includes(effort.name.toLowerCase())
    || effort.displayName.toLowerCase() === 'none';
}

export function modelCommand(form: AiModelUpdate): AiModelCommand {
  return {providerId: form.providerId,
    modelKey: form.modelKey, enabled: form.enabled, defaultModel: form.defaultModel,
    lightweightModel: form.lightweightModel,
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
    modelOptions: structuredClone(form.modelOptions),
    reasoningEfforts: form.reasoningEfforts.filter(effort => !isImplicitUnsetEffort(effort))
      .map(effort => ({name: effort.name,
      defaultEffort: effort.defaultEffort, sortOrder: effort.sortOrder}))};
}

export function editableModelState(form: AiModelUpdate): object {
  return {...modelCommand(form), profileIdentity: {providerModelName: form.providerModelName,
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
    defaultThinking: null, reasoningEfforts: [], modelOptions: {}};
}

export function constrainModelToProfile(form: AiModelUpdate,
                                        profile: AiModelProfile): AiModelUpdate {
  const explicitEfforts = profile.reasoningEfforts.filter(effort => !isImplicitUnsetEffort(effort));
  const allowedEfforts = new Set(explicitEfforts.map(effort => effort.name));
  const definitions = new Map(explicitEfforts.map(effort => [effort.name, effort]));
  const efforts = form.reasoningEfforts.filter(effort => allowedEfforts.has(effort.name))
    .map((effort, index) => ({...definitions.get(effort.name)!,
      defaultEffort: effort.defaultEffort, sortOrder: index}));
  if (efforts.length > 0 && !efforts.some(effort => effort.defaultEffort)) {
    efforts[0].defaultEffort = true;
  }
  const thinkingModes = form.thinkingModes.filter(mode => profile.thinkingModes.includes(mode));
  const providerEnforcedThinking = profile.thinkingModes.length > 0
    && !profile.thinkingModes.includes('disabled');
  const adaptiveThinking = profile.capabilityConstraints.adaptiveThinking.supported
    && (providerEnforcedThinking || form.adaptiveThinking);
  let normalizedModes = providerEnforcedThinking
    ? [...profile.thinkingModes]
    : adaptiveThinking
      ? thinkingModes.includes('adaptive') ? thinkingModes : [...thinkingModes, 'adaptive']
      : thinkingModes.filter(mode => mode !== 'adaptive');
  // Older catalog rows can predate persisted thinking modes. A fixed-thinking profile must
  // recover its profile-owned defaults instead of surfacing an error the user cannot repair.
  if (profile.configurationConstraints.thinkingBudgetTokens.maximum !== null
    && normalizedModes.length === 0) {
    normalizedModes = [...profile.thinkingModes];
  }
  const allowedOptionKeys = new Set((profile.options ?? []).map(option => option.key));
  const modelOptions = Object.fromEntries(Object.entries(form.modelOptions ?? {})
    .filter(([key]) => allowedOptionKeys.has(key)));
  const reserve = form.outputReserveTokens
    ?? profile.configurationConstraints.outputReserveTokens.defaultValue ?? 0;
  const safeInput = form.contextWindow - reserve - form.emergencyHeadroomTokens;
  const managedToolLimit = Math.max(1, Math.min(
    profile.configurationConstraints.toolOutputTokenLimit.defaultValue ?? 0, safeInput));
  return {...form, providerModelName: profile.providerModelName,
    displayName: profile.displayName, description: profile.description,
    toolOutputTokenLimit: managedToolLimit,
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
      ? form.defaultThinking : normalizedModes.includes(profile.defaultThinking ?? '')
        ? profile.defaultThinking : normalizedModes[0] ?? null,
    reasoningEfforts: efforts, modelOptions};
}

export function modelProfileValidationErrors(form: AiModelUpdate,
                                             profile: AiModelProfile): string[] {
  return modelProfileValidationIssues(form, profile).map(issue => issue.message);
}

export function modelProfileValidationIssues(form: AiModelUpdate,
                                              profile: AiModelProfile): AiModelValidationIssue[] {
  const errors: AiModelValidationIssue[] = [];
  const constraints = profile.configurationConstraints;
  addNumberError(errors, 'contextWindow', 'Context Window', form.contextWindow,
    constraints.contextWindow, true);
  addNumberError(errors, 'maxTokens', 'Max Output Tokens', form.maxTokens,
    constraints.maxOutputTokens, true);
  addNumberError(errors, 'outputReserveTokens', 'Output Reserve Tokens', form.outputReserveTokens,
    constraints.outputReserveTokens, true);
  addNumberError(errors, 'autoCompactThresholdTokens', 'Auto-compact Threshold',
    form.autoCompactThresholdTokens,
    constraints.autoCompactThresholdTokens, true);
  addNumberError(errors, 'emergencyHeadroomTokens', 'Emergency Headroom',
    form.emergencyHeadroomTokens,
    constraints.emergencyHeadroomTokens, true);
  addNumberError(errors, 'toolOutputTokenLimit', 'Tool Output Token Limit',
    form.toolOutputTokenLimit, constraints.toolOutputTokenLimit, true);
  addNumberError(errors, 'thinkingBudgetTokens', 'Thinking Token Budget',
    form.thinkingBudgetTokens,
    constraints.thinkingBudgetTokens, true);
  addNumberError(errors, 'temperature', 'Temperature', form.temperature,
    constraints.temperature, false);

  const effectiveMaxTokens = form.maxTokens ?? constraints.maxOutputTokens.defaultValue;
  const effectiveThinkingBudget = form.thinkingBudgetTokens
    ?? constraints.thinkingBudgetTokens.defaultValue;
  if (effectiveMaxTokens !== null && effectiveMaxTokens >= form.contextWindow) {
    addIssue(errors, 'maxTokens',
      'Max Output Tokens must be smaller than the Context Window.');
  }
  if (effectiveThinkingBudget !== null && effectiveMaxTokens !== null
    && effectiveThinkingBudget >= effectiveMaxTokens) {
    addIssue(errors, 'thinkingBudgetTokens',
      'Thinking Token Budget must be smaller than Max Output Tokens.');
  }
  const reserve = form.outputReserveTokens
    ?? constraints.outputReserveTokens.defaultValue ?? 0;
  const safeInput = form.contextWindow - reserve - form.emergencyHeadroomTokens;
  const threshold = form.autoCompactThresholdTokens
    ?? constraints.autoCompactThresholdTokens.defaultValue ?? 0;
  if (reserve >= form.contextWindow) addIssue(errors, 'outputReserveTokens',
    'Output Reserve Tokens must be smaller than the Context Window.');
  if (form.emergencyHeadroomTokens >= form.contextWindow - reserve || safeInput <= 0) {
    addIssue(errors, 'emergencyHeadroomTokens',
      'Emergency Headroom must leave usable space in the Context Window.');
  }
  if (threshold >= form.contextWindow || threshold > safeInput) {
    addIssue(errors, 'autoCompactThresholdTokens',
    'Auto-compact Threshold must fit within the usable Context Window.');
  }
  if (form.toolOutputTokenLimit > safeInput) addIssue(errors, 'contextWindow',
    'Context Window must leave enough usable space for managed tool output.');

  addUnsupportedCapabilityErrors(errors, form, profile);
  const allowedModes = new Set(profile.thinkingModes);
  if (form.thinkingModes.some(mode => !allowedModes.has(mode))) {
    addIssue(errors, 'thinkingModes',
      'Thinking Modes contain a value that this model does not support.');
  }
  if (form.adaptiveThinking !== form.thinkingModes.includes('adaptive')) {
    addIssue(errors, 'thinkingModes',
      'Adaptive Thinking and the adaptive Thinking Mode must be enabled together.');
  }
  if (!allowedModes.has('disabled')
    && profile.thinkingModes.some(mode => !form.thinkingModes.includes(mode))) {
    addIssue(errors, 'thinkingModes', 'Provider-enforced Thinking Modes cannot be disabled.');
  }
  if (constraints.thinkingBudgetTokens.maximum !== null
    && (form.thinkingModes.length === 0 || !form.defaultThinking)) {
    addIssue(errors, 'thinkingModes',
      'Fixed Thinking requires at least one enabled mode and a default mode.');
  }
  if (form.defaultThinking && !form.thinkingModes.includes(form.defaultThinking)) {
    addIssue(errors, 'thinkingModes',
      'Default Thinking Mode must be one of the enabled modes.');
  }
  const allowedEfforts = new Set(profile.reasoningEfforts
    .filter(effort => !isImplicitUnsetEffort(effort)).map(effort => effort.name));
  if (form.reasoningEfforts.some(effort => !allowedEfforts.has(effort.name))) {
    addIssue(errors, 'reasoningEfforts',
      'Reasoning Efforts contain a value that this model does not support.');
  }
  if (form.reasoningEfforts.length > 0
    && form.reasoningEfforts.filter(effort => effort.defaultEffort).length !== 1) {
    addIssue(errors, 'reasoningEfforts', 'Select exactly one default Reasoning Effort.');
  }
  if (profile.capabilityConstraints.reasoningOptions.supported
    && form.reasoningModelSupported !== true
    && form.reasoningEfforts.length > 0) {
    addIssue(errors, 'reasoningEfforts',
      'Enable Reasoning Options before selecting Reasoning Efforts.');
  }
  if (form.outputEffort && !(profile.capabilityConstraints.outputEffort.supported
    && form.outputEffortSupported
    && allowedEfforts.has(form.outputEffort))) {
    addIssue(errors, 'outputEffort', 'Output Effort is not enabled or supported.');
  }
  if (form.cacheStrategy && form.cacheStrategy !== profile.cacheStrategy) {
    addIssue(errors, 'cacheStrategy', 'Cache Strategy is not supported by this model.');
  }
  return errors.filter((issue, index) => errors.findIndex(candidate =>
    candidate.field === issue.field && candidate.message === issue.message) === index);
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
    reasoningEfforts: profile.reasoningEfforts.filter(effort => !isImplicitUnsetEffort(effort))
      .map(effort => ({...effort}))};
}

function supportedSetting(configured: boolean | null,
                          capability: {supported: boolean; defaultEnabled: boolean}): boolean | null {
  if (!capability.supported) return false;
  return configured ?? capability.defaultEnabled;
}

function addNumberError(errors: AiModelValidationIssue[], field: AiModelValidationField,
                        label: string, value: number | null,
                        constraint: AiNumericConstraint, integer: boolean): void {
  if (value === null) {
    if (!constraint.optional) addIssue(errors, field, `${label} is required.`);
    return;
  }
  if (!Number.isFinite(value) || integer && !Number.isSafeInteger(value)
    || constraint.minimum === null || constraint.maximum === null
    || value < constraint.minimum || value > constraint.maximum) {
    addIssue(errors, field,
      `${label} must be between ${constraint.minimum ?? '—'} and ${constraint.maximum ?? '—'}.`);
  }
}

function addUnsupportedCapabilityErrors(errors: AiModelValidationIssue[], form: AiModelUpdate,
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
    .forEach(([, , label]) => addIssue(errors, 'capabilities',
      `${label} is not supported by this model.`));
}

function addIssue(errors: AiModelValidationIssue[], field: AiModelValidationField,
                  message: string): void {
  errors.push({field, message});
}
