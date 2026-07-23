import assert from 'node:assert/strict';
import test from 'node:test';

import { extractParamEnumValues, normalizeParamSchema } from './parameter-schema.ts';

test('extractParamEnumValues reads standard and vendor enum shapes', () => {
  assert.deepEqual(extractParamEnumValues({ enum: ['ACC', 'ASCCP'] }), ['ACC', 'ASCCP']);
  assert.deepEqual(extractParamEnumValues({ items: { enum: ['ACC', 'ASCCP'] } }), ['ACC', 'ASCCP']);
  assert.deepEqual(extractParamEnumValues({ 'x-item-enum': ['WIP', 'Draft'] }), ['WIP', 'Draft']);
});

test('extractParamEnumValues keeps standard enum precedence', () => {
  assert.deepEqual(
    extractParamEnumValues({
      enum: ['standard'],
      items: { enum: ['items'] },
      'x-item-enum': ['extension'],
    }),
    ['standard'],
  );
});

test('normalizeParamSchema preserves vendor extensions while resolving nullable types', () => {
  const schema = normalizeParamSchema({
    anyOf: [{ type: 'string' }, { type: 'null' }],
    'x-item-enum': ['WIP', 'Draft'],
    'x-comma-separated': true,
  });

  assert.equal(schema.type, 'string');
  assert.deepEqual(extractParamEnumValues(schema), ['WIP', 'Draft']);
  assert.equal(schema['x-comma-separated'], true);
});
