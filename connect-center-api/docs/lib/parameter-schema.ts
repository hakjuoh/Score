export type ParamSchemaLike = {
  type?: string;
  enum?: unknown[];
  items?: { enum?: unknown[] };
  anyOf?: Array<{ type?: string; enum?: unknown[]; items?: { enum?: unknown[] } }>;
  'x-item-enum'?: unknown[];
  [key: string]: unknown;
};

export function normalizeParamSchema(raw: unknown): ParamSchemaLike {
  const schema = (raw as ParamSchemaLike | undefined) ?? {};
  if (Array.isArray(schema.anyOf) && schema.anyOf.length > 0) {
    const preferred = schema.anyOf.find((item) => item?.type && item.type !== 'null') ?? schema.anyOf[0];
    return {
      ...schema,
      type: schema.type ?? preferred?.type,
      enum: schema.enum ?? preferred?.enum,
      items: schema.items ?? preferred?.items,
    };
  }
  return schema;
}

export function extractParamEnumValues(schema: ParamSchemaLike): string[] | undefined {
  const values =
    [schema.enum, schema.items?.enum, schema['x-item-enum']].find(
      (candidate): candidate is unknown[] => Array.isArray(candidate) && candidate.length > 0,
    ) ?? undefined;
  return values?.map((value) => String(value));
}
