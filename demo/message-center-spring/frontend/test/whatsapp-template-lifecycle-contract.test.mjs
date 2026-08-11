import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const types = readFileSync(new URL('../src/api/types.ts', import.meta.url), 'utf8');
const endpoints = readFileSync(new URL('../src/api/endpoints.ts', import.meta.url), 'utf8');
const client = readFileSync(new URL('../src/api/client.ts', import.meta.url), 'utf8');
const authHook = readFileSync(new URL('../src/hooks/useAuth.tsx', import.meta.url), 'utf8');
const layout = readFileSync(new URL('../src/components/AppLayout.tsx', import.meta.url), 'utf8');

function functionSource(name) {
  return endpoints.match(new RegExp(`export async function ${name}\\b[\\s\\S]*?(?=\\nexport async function|$)`))?.[0] ?? '';
}

test('declares the WhatsApp template management and authentication contracts', () => {
  for (const contract of [
    'TemplateAdmin',
    'TemplateOperation',
    'TemplateComponent',
    'TemplateMediaAsset',
    'TemplateListPage',
    'TemplateCommand',
  ]) {
    assert.match(types, new RegExp(`export interface ${contract}\\b`));
  }
  assert.match(types, /export interface LoginResponse\s*{[\s\S]*?roles:\s*string\[\]/);
});

test('persists and clears every local authentication key including roles', () => {
  assert.match(authHook, /localStorage\.getItem\('roles'\)/);
  assert.match(authHook, /localStorage\.setItem\('roles',\s*JSON\.stringify\(res\.roles\)\)/);
  assert.match(authHook, /localStorage\.removeItem\('token'\)/);
  assert.match(authHook, /localStorage\.removeItem\('username'\)/);
  assert.match(authHook, /localStorage\.removeItem\('roles'\)/);
  assert.match(authHook, /roles:\s*string\[\]/);
  assert.match(authHook, /isAdmin:\s*boolean/);
  assert.match(authHook, /roles\.includes\('ADMIN'\)/);
  assert.match(client, /localStorage\.removeItem\('token'\)/);
  assert.match(client, /localStorage\.removeItem\('username'\)/);
  assert.match(client, /localStorage\.removeItem\('roles'\)/);
});

test('shows template management and channel settings navigation only to administrators', () => {
  assert.match(layout, /const \{ username, logout, isAdmin \} = useAuth\(\)/);
  assert.match(
    layout,
    /\{isAdmin && \(\s*<>[\s\S]*?navigate\('\/templates'\)[\s\S]*?navigate\('\/settings\/channels'\)[\s\S]*?<\/>\s*\)\}/,
  );
});

test('maps every management API to the approved channel-account scope', () => {
  assert.match(endpoints, /`\/v1\/channel-accounts\/\$\{accountId\}\/whatsapp`/);

  assert.match(functionSource('fetchAdminTemplates'), /client\.get<TemplateListPage>\(`\$\{whatsappManagementBase\(accountId\)\}\/templates`, \{ params \}\)/);
  assert.match(functionSource('fetchAdminTemplate'), /client\.get<TemplateAdmin>\(`\$\{whatsappManagementBase\(accountId\)\}\/templates\/\$\{templateCode\}`, \{[\s\S]*?params: \{ language \}/);
  assert.match(functionSource('createAdminTemplate'), /client\.post<TemplateOperation>\(`\$\{whatsappManagementBase\(accountId\)\}\/templates`, command\)/);
  assert.match(functionSource('updateAdminTemplate'), /client\.put<TemplateOperation>\(`\$\{whatsappManagementBase\(accountId\)\}\/templates\/\$\{templateCode\}`, command, \{[\s\S]*?params: \{ language \}/);
  assert.match(functionSource('setAdminTemplateSendPermission'), /client\.put<TemplateOperation>\([\s\S]*?\/templates\/\$\{templateCode\}\/send-permission`[\s\S]*?\{ allowSend, clientRequestId \}[\s\S]*?\{ params: \{ language \} \}/);
  assert.match(functionSource('deleteAdminTemplate'), /client\.delete<TemplateOperation>\(`\$\{whatsappManagementBase\(accountId\)\}\/templates\/\$\{templateCode\}`, \{[\s\S]*?params: \{ language, clientRequestId \}/);
  assert.match(functionSource('syncAdminTemplates'), /client\.post<TemplateSyncResult>\(`\$\{whatsappManagementBase\(accountId\)\}\/templates\/sync`\)/);
  assert.match(functionSource('uploadTemplateMedia'), /client\.post<TemplateMediaAsset>\(`\$\{whatsappManagementBase\(accountId\)\}\/template-media`, formData\)/);
  assert.match(functionSource('fetchTemplateOperations'), /client\.get<TemplateOperation\[\]>\([\s\S]*?\/templates\/\$\{templateCode\}\/operations`[\s\S]*?\{ params: \{ language \} \}/);
});

test('uploads template media as FormData without overriding the browser multipart boundary', () => {
  const upload = functionSource('uploadTemplateMedia');
  assert.match(types, /export type TemplateMediaFormat = 'IMAGE' \| 'VIDEO' \| 'DOCUMENT'/);
  assert.match(types, /export interface TemplateMediaAsset\s*{[\s\S]*?format:\s*TemplateMediaFormat/);
  assert.match(upload, /format:\s*TemplateMediaFormat/);
  assert.match(upload, /new FormData\(\)/);
  assert.match(upload, /formData\.append\('format',\s*format\)/);
  assert.match(upload, /formData\.append\('file',\s*file\)/);
  assert.doesNotMatch(upload, /Content-Type|multipart\/form-data/);
});

test('admin response types preserve nullable provider category while commands stay constrained', () => {
  assert.match(types, /export type TemplateCategory = 'UTILITY' \| 'MARKETING'/);
  assert.match(types, /export interface TemplateAdmin\s*{[\s\S]*?category:\s*string \| null/);
  assert.match(types, /export interface TemplateCommand\s*{[\s\S]*?category:\s*TemplateCategory/);
  assert.match(types, /export interface TemplateListFilters\s*{[\s\S]*?category\?:\s*string/);
});
