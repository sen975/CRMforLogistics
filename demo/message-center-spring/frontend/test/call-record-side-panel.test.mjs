import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import ts from 'typescript';

const frontendRoot = new URL('../', import.meta.url);

function parse(relativePath) {
  const url = new URL(relativePath, frontendRoot);
  return ts.createSourceFile(
    url.pathname,
    readFileSync(url, 'utf8'),
    ts.ScriptTarget.Latest,
    true,
    ts.ScriptKind.TSX,
  );
}

function inspect(sourceFile) {
  const jsxTags = new Set();
  const calls = new Set();
  const routeLiterals = [];

  const visit = (node) => {
    if (ts.isCallExpression(node) && ts.isIdentifier(node.expression)) {
      calls.add(node.expression.text);
    }
    if (ts.isJsxOpeningElement(node) || ts.isJsxSelfClosingElement(node)) {
      jsxTags.add(node.tagName.getText(sourceFile));
    }
    if ((ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node))
        && node.text.includes('call-record')) {
      routeLiterals.push(node.text);
    }
    ts.forEachChild(node, visit);
  };

  visit(sourceFile);
  return { jsxTags, calls, routeLiterals };
}

test('call record details use the shared layout side panel without a page route', () => {
  const threadPage = inspect(parse('src/pages/ThreadPage.tsx'));
  const phoneRepository = inspect(parse('src/pages/PhoneRepositoryPage.tsx'));
  const appLayout = inspect(parse('src/components/AppLayout.tsx'));
  const router = inspect(parse('src/router.tsx'));

  assert.equal(threadPage.calls.has('selectCallRecord'), true);
  assert.equal(phoneRepository.calls.has('selectCallRecord'), true);
  assert.equal(appLayout.jsxTags.has('Sider'), true);
  assert.equal(appLayout.jsxTags.has('CallRecordDetail'), true);
  assert.deepEqual(router.routeLiterals, []);
});

test('call record transcript save refreshes the record before optimistic update', () => {
  const callRecordDetailSource = readFileSync(
    new URL('src/components/CallRecordDetail.tsx', frontendRoot),
    'utf8',
  );

  assert.match(callRecordDetailSource, /await fetchCallRecord\(callRecordId\)/);
  assert.match(callRecordDetailSource, /decideTranscriptSave\(/);
  assert.ok(
    callRecordDetailSource.indexOf('await fetchCallRecord(callRecordId)')
      < callRecordDetailSource.indexOf('await reviseTranscript(callRecordId'),
  );
  assert.match(callRecordDetailSource, /TRANSCRIPT_VERSION_CONFLICT/);
  assert.match(callRecordDetailSource, /loading=\{savingTranscript\}/);
  assert.match(callRecordDetailSource, /disabled=\{savingTranscript\}/);
});

test('call record audio waits for an authorized audio session before mounting', () => {
  const callRecordDetailSource = readFileSync(
    new URL('src/components/CallRecordDetail.tsx', frontendRoot),
    'utf8',
  );

  assert.match(callRecordDetailSource, /audioSessionCallRecordId/);
  assert.match(callRecordDetailSource, /createAudioSession\(callRecordId\)\s*\.then/);
  assert.match(callRecordDetailSource, /audioSessionCallRecordId === callRecordId \? \(/);
});

test('thread refreshes immediately after call upload and polls active transcription states', () => {
  const threadPageSource = readFileSync(
    new URL('src/pages/ThreadPage.tsx', frontendRoot),
    'utf8',
  );
  const sendFormSource = readFileSync(
    new URL('src/components/SendForm.tsx', frontendRoot),
    'utf8',
  );

  assert.match(threadPageSource, /onCallRecordCreated=\{refreshCallRecords\}/);
  assert.match(threadPageSource, /hasActiveCallRecord\(callRecords\)/);
  assert.match(threadPageSource, /CALL_RECORD_POLL_INTERVAL_MS/);
  assert.match(sendFormSource, /onSuccess=\{onCallRecordCreated\}/);
});
