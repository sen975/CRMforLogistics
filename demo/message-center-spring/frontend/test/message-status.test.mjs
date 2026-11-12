import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const source = readFileSync(
  new URL('../src/components/MessageBubble.tsx', import.meta.url),
  'utf8',
);
const detailPanelSource = readFileSync(
  new URL('../src/components/ContactDetailPanel.tsx', import.meta.url),
  'utf8',
);

test('outbound message bubble keeps provider delivery status visible', () => {
  assert.match(source, /message\.status/);
  assert.match(source, /submission_unknown/);
  assert.match(source, /发送失败/);
  assert.match(source, /已读/);
});

test('chatapp template uses resolved body text instead of legacy html card', () => {
  const emailOnlyHtml = /message\.(?:kind|channelType) === 'email'[\s\S]*message\.bodyHtml/;
  const detailEmailOnlyHtml = /messageDetail\.(?:kind|channelType) === 'email'[\s\S]*messageDetail\.bodyHtml/;

  assert.match(source, emailOnlyHtml);
  assert.match(detailPanelSource, detailEmailOnlyHtml);
});
