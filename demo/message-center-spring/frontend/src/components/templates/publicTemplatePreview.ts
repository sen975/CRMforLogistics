import type { PublicTemplateVariable } from '../../api/types';

export type PreviewMode = 'parameter' | 'example';

export type PreviewSegment =
  | { kind: 'text'; value: string }
  | { kind: 'variable'; code: string; value: string; resolved: boolean };

const TOKEN_PATTERN = /\$\(([A-Za-z][A-Za-z0-9_]*)\)/g;

export function previewSegments(
  text: string,
  variables: PublicTemplateVariable[],
  mode: PreviewMode,
): PreviewSegment[] {
  const byCode = new Map(variables.map((variable) => [variable.code, variable]));
  const result: PreviewSegment[] = [];
  let offset = 0;

  for (const match of text.matchAll(TOKEN_PATTERN)) {
    const index = match.index ?? 0;
    if (index > offset) result.push({ kind: 'text', value: text.slice(offset, index) });

    const code = match[1];
    const variable = byCode.get(code);
    const example = variable?.example?.trim();
    result.push(variable ? {
      kind: 'variable',
      code,
      value: mode === 'example' && example ? example : code,
      resolved: true,
    } : {
      kind: 'variable',
      code,
      value: match[0],
      resolved: false,
    });
    offset = index + match[0].length;
  }

  if (offset < text.length) result.push({ kind: 'text', value: text.slice(offset) });
  return result;
}
