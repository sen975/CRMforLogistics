import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import ts from 'typescript';

const source = readFileSync(
  new URL('../src/utils/callRecordTranscript.ts', import.meta.url),
  'utf8',
);
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;
const loadedModule = { exports: {} };
new Function('module', 'exports', compiled)(loadedModule, loadedModule.exports);
const { canRetryTranscription, splitTranscriptDisplayLines } = loadedModule.exports;

test('transcript display lines split Chinese and English punctuation without rewriting text', () => {
  const sourceText = '你好。今天可以吗？Yes, it works. Next line!';
  const lines = splitTranscriptDisplayLines(sourceText);

  assert.deepEqual(lines, ['你好。', '今天可以吗？', 'Yes, it works.', ' Next line!']);
  assert.equal(lines.join(''), sourceText);
});

test('transcript display lines keep decimal points and text without punctuation', () => {
  const decimalText = '价格是12.5元。';
  const plainText = 'No punctuation here';

  assert.deepEqual(splitTranscriptDisplayLines(decimalText), ['价格是12.5元。']);
  assert.equal(splitTranscriptDisplayLines(decimalText).join(''), decimalText);
  assert.deepEqual(splitTranscriptDisplayLines(plainText), [plainText]);
  assert.equal(splitTranscriptDisplayLines(plainText).join(''), plainText);
});

test('retry is available for failed and completed transcripts without timestamps only', () => {
  const failedRecord = { transcription: { state: 'failed' } };
  const completedWithoutSegments = {
    transcription: { state: 'completed', result: { segments: [] } },
  };
  const completedWithSegments = {
    transcription: {
      state: 'completed',
      result: { segments: [{ startSeconds: 0, endSeconds: 1, text: '你好。' }] },
    },
  };

  assert.equal(canRetryTranscription(failedRecord), true);
  assert.equal(canRetryTranscription(completedWithoutSegments), true);
  assert.equal(canRetryTranscription(completedWithSegments), false);
});
