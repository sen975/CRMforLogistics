import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const endpoints = readFileSync(new URL('../src/api/endpoints.ts', import.meta.url), 'utf8');
const authorizationPanel = readFileSync(
  new URL('../src/components/whatsapp/WhatsAppAuthorizationPanel.tsx', import.meta.url),
  'utf8',
);

test('uses the shared WhatsApp API contract instead of account-scoped template paths', () => {
  assert.match(endpoints, /function whatsappManagementBase\([^)]*\): string[\s\S]*?return ['"]\/v1\/whatsapp['"]/);
  assert.match(endpoints, /export async function fetchSharedTemplates\b/);
  assert.match(endpoints, /export async function createSharedTemplate\b/);
  assert.doesNotMatch(endpoints, /\/v1\/channel-accounts\/\$\{accountId\}\/whatsapp/);
});

test('uses embedded signup completion instead of phone migration verification', () => {
  assert.match(endpoints, /createWhatsAppAuthorizationAttempt\(\): Promise/);
  assert.match(endpoints, /event: 'FINISH'/);
  assert.match(endpoints, /phoneNumberId: string/);
  assert.doesNotMatch(authorizationPanel, /verificationCode|GetMigrationVerifyCode|startMigration/);
  assert.match(authorizationPanel, /WA_EMBEDDED_SIGNUP/);
  assert.match(authorizationPanel, /window\.addEventListener\('message'/);
});
