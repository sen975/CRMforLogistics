import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const endpoints = readFileSync(new URL('../src/api/endpoints.ts', import.meta.url), 'utf8');

test('uses the shared WhatsApp API contract instead of account-scoped template paths', () => {
  assert.match(endpoints, /const whatsappManagementBase = ['"]\/v1\/whatsapp['"]/);
  assert.match(endpoints, /export async function fetchSharedTemplates\b/);
  assert.match(endpoints, /export async function createSharedTemplate\b/);
  assert.doesNotMatch(endpoints, /\/v1\/channel-accounts\/\$\{accountId\}\/whatsapp/);
});
