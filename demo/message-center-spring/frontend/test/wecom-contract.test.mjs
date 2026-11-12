import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const source = (path) => readFileSync(new URL(`../${path}`, import.meta.url), 'utf8');

test('frontend uses only the Spring WeCom login, binding, and viewer contracts', () => {
  const endpoints = source('src/api/endpoints.ts');

  for (const path of [
    '/auth/wecom/attempts',
    '/auth/wecom/exchange',
    '/account/wecom-binding',
    '/v1/wecom/js-sdk-config',
  ]) {
    assert.match(endpoints, new RegExp(path.replaceAll('/', '\\/')));
  }
  assert.match(endpoints, /const weComViewerBase = '\/v1\/wecom\/conversation-view'/);
  assert.match(endpoints, /`\$\{weComViewerBase\}\/bootstrap`/);
  assert.match(endpoints, /`\$\{weComViewerBase\}\/sessions`/);
  assert.doesNotMatch(endpoints, /message-center-demo|viewerAuthToken=/);
});

test('frontend keeps viewer credentials in memory and delegates plaintext to official components', () => {
  const auth = source('src/hooks/useAuth.tsx');
  const types = source('src/api/types.ts');
  const timeline = source('src/components/wecom/WeComTimelineSegment.tsx');
  const bubble = source('src/components/MessageBubble.tsx');
  const sdk = source('src/wecom/wecomSdk.ts');

  assert.match(types, /sourceId\??:\s*string\s*\|\s*null/);
  assert.doesNotMatch(auth, /localStorage\.setItem\([^\n]*wecomViewerAuthToken/i);
  assert.match(timeline, /<ww-open-message/);
  assert.match(timeline, /secret-key=/);
  assert.match(bubble, /message\.channelType === 'wecom'\) return null/);
  assert.match(sdk, /wecom-jssdk-2\.3\.4\.js/);
  assert.match(sdk, /jwxwork-1\.0\.0\.js/);
  assert.match(sdk, /from '@wecom\/jssdk'/);
  assert.doesNotMatch(sdk, /window\.ww \?\? sdk/);
  assert.doesNotMatch(sdk, /window\.ww\?\.createOpenDataFrameFactory/);

  const combined = [auth, types, timeline, bubble, sdk].join('\n').toLowerCase();
  assert.doesNotMatch(combined, /permanent_code|suite[_-]secret\s*[:=]\s*['"][^'"]+['"]/);
});
