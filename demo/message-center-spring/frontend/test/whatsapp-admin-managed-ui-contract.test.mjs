import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const settings = readFileSync(
  new URL('../src/pages/ChannelSettingsPage.tsx', import.meta.url),
  'utf8',
);

test('渠道设置只暴露管理员托管的 WhatsApp 账号入口', () => {
  assert.match(settings, /useAuth/);
  assert.match(settings, /const \{ isAdmin \} = useAuth\(\)/);
  assert.match(settings, /AdminWhatsAppAccountPanel/);
  assert.doesNotMatch(settings, /WhatsAppAuthorizationPanel/);
  assert.doesNotMatch(settings, /WhatsAppPhoneNumberPanel/);
  assert.doesNotMatch(settings, /import\s+\{\s*WhatsAppAccountPanel\s*\}/);
  assert.doesNotMatch(settings, /ADMIN_API_WABA|setWhatsAppOnboardingMode|绑定企业 API 电话/);
});
