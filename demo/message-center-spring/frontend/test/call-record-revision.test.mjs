import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import ts from 'typescript';

const source = readFileSync(
  new URL('../src/utils/callRecordRevision.ts', import.meta.url),
  'utf8',
);
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;
const loadedModule = { exports: {} };
new Function('module', 'exports', compiled)(loadedModule, loadedModule.exports);
const { decideTranscriptSave } = loadedModule.exports;

test('uses the latest record version when the transcript revision is unchanged', () => {
  assert.deepEqual(
    decideTranscriptSave('revision-1', { currentRevisionId: 'revision-1', version: 7 }),
    { kind: 'save', expectedVersion: 7 },
  );
});

test('rejects automatic save when another transcript revision was created', () => {
  assert.deepEqual(
    decideTranscriptSave('revision-1', { currentRevisionId: 'revision-2', version: 8 }),
    { kind: 'conflict' },
  );
});

test('treats two null revision ids as the same transcript baseline', () => {
  assert.deepEqual(
    decideTranscriptSave(null, { currentRevisionId: null, version: 6 }),
    { kind: 'save', expectedVersion: 6 },
  );
});
