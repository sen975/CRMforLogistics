import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';

const root = path.resolve(import.meta.dirname, '..');
const read = (relativePath) => fs.readFileSync(path.join(root, relativePath), 'utf8');

test('渠道设置使用管理员分配面板而非 WhatsApp 自助注册入口', () => {
  const page = read('src/pages/ChannelSettingsPage.tsx');

  assert.match(page, /AdminWhatsAppAccountPanel/);
  assert.doesNotMatch(page, /WhatsAppAuthorizationPanel/);
  assert.doesNotMatch(page, /WhatsAppPhoneNumberPanel/);
  assert.doesNotMatch(page, /import\s+\{\s*WhatsAppAccountPanel\s*\}/);
  assert.doesNotMatch(page, /createWhatsAppAuthorizationAttempt|addWhatsAppPhoneNumber/);
});

test('管理员面板只使用管理员账号分配合同并且号码保持脱敏', () => {
  const panel = read('src/components/whatsapp/AdminWhatsAppAccountPanel.tsx');
  const endpoints = read('src/api/endpoints.ts');

  assert.match(panel, /syncAdminWhatsAppAccounts/);
  assert.match(panel, /assignAdminWhatsAppAccount/);
  assert.match(panel, /reclaimAdminWhatsAppAccount/);
  assert.match(panel, /transferAdminWhatsAppAccount/);
  assert.match(panel, /fetchAdminUsers/);
  assert.match(panel, /maskedPhone/);
  assert.match(endpoints, /\/admin\/whatsapp\/accounts/);
});
