import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

test('企业微信 viewer 请求统一使用 v1 后端路由', async () => {
  const source = await readFile(new URL('../src/api/endpoints.ts', import.meta.url), 'utf8');
  assert.match(source, /const weComViewerBase = '\/v1\/wecom\/conversation-view';/);
  assert.match(source, /'\/v1\/wecom\/js-sdk-config'/);
  assert.doesNotMatch(source, /const weComViewerBase = '\/wecom\/conversation-view';/);
});
