import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const types = readFileSync(new URL('../src/api/types.ts', import.meta.url), 'utf8');
const endpoints = readFileSync(new URL('../src/api/endpoints.ts', import.meta.url), 'utf8');
const client = readFileSync(new URL('../src/api/client.ts', import.meta.url), 'utf8');
const authHook = readFileSync(new URL('../src/hooks/useAuth.tsx', import.meta.url), 'utf8');
const layout = readFileSync(new URL('../src/components/AppLayout.tsx', import.meta.url), 'utf8');
const publicTemplateLibrary = readFileSync(
  new URL('../src/components/templates/PublicTemplateLibrary.tsx', import.meta.url),
  'utf8',
);
const publicTemplateDetailModal = readFileSync(
  new URL('../src/components/templates/PublicTemplateDetailModal.tsx', import.meta.url),
  'utf8',
);
const templateEditorDrawer = readFileSync(
  new URL('../src/components/templates/TemplateEditorDrawer.tsx', import.meta.url),
  'utf8',
);
const publicTemplateWorkbenchStyles = readFileSync(
  new URL('../src/components/templates/publicTemplateWorkbench.css', import.meta.url),
  'utf8',
);

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
    'PublicTemplatePage',
    'PublicTemplateListPage',
  ]) {
    assert.match(types, new RegExp(`export interface ${contract}\\b`));
  }
  assert.match(types, /export interface LoginResponse\s*{[\s\S]*?roles:\s*string\[\]/);
});

test('keeps public template text and buttons on their owning page', () => {
  assert.match(types, /export interface PublicTemplateContent\s*{[\s\S]*?pages:\s*PublicTemplatePage\[\]/);
  assert.match(types, /export interface PublicTemplatePage\s*{[\s\S]*?buttons:\s*PublicTemplateButton\[\]/);
  const publicTemplateContent = types.match(/export interface PublicTemplateContent\s*{([\s\S]*?)\n}/)?.[1] ?? '';
  assert.doesNotMatch(publicTemplateContent, /\bbuttons\s*:/);
});

test('owns public-template editor layout in responsive CSS', () => {
  const editorWorkbench = templateEditorDrawer.match(/<div className="template-editor-workbench"[\s\S]*?<\/div>/)?.[0] ?? '';
  assert.match(editorWorkbench, /className="template-editor-workbench"/);
  assert.doesNotMatch(editorWorkbench, /\bstyle=\{\{/);
  assert.match(
    publicTemplateWorkbenchStyles,
    /\.template-editor-workbench\s*\{[\s\S]*?grid-template-columns:\s*minmax\(0,\s*1fr\)\s+minmax\(260px,\s*0\.7fr\)[\s\S]*?\}/,
  );
  assert.match(
    publicTemplateWorkbenchStyles,
    /\.template-editor-workbench__form,\s*\.template-editor-workbench__source\s*\{[\s\S]*?min-width:\s*0/,
  );
  const mobileStyles = publicTemplateWorkbenchStyles.match(/@media\s*\(max-width:\s*720px\)\s*\{([\s\S]*)/)?.[1] ?? '';
  assert.match(mobileStyles, /\.template-editor-workbench[\s\S]*?grid-template-columns:\s*minmax\(0,\s*1fr\)/);
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
  assert.match(layout, /navigate\('\/templates'\)/);
  assert.match(layout, /navigate\('\/settings\/channels'\)/);
});

test('maps every management API to the shared WhatsApp scope', () => {
  assert.match(endpoints, /const sharedWhatsAppBase = ['"]\/v1\/whatsapp['"]/);
  assert.doesNotMatch(endpoints, /\/v1\/channel-accounts\/\$\{accountId\}\/whatsapp/);
  assert.match(functionSource('fetchSharedTemplates'), /client\.get<SharedTemplateListPage>\(`\$\{sharedWhatsAppBase\}\/templates`/);
  assert.match(functionSource('fetchSharedTemplate'), /client\.get<SharedTemplate>\(\s*`\$\{sharedWhatsAppBase\}\/templates/);
  assert.match(functionSource('createSharedTemplate'), /client\.post<TemplateOperation>\(\s*`\$\{sharedWhatsAppBase\}\/templates\/applications`, command, \{ params: \{ scopeId \} \}/);
  assert.match(functionSource('createTemplateChangeRequest'), /\/templates\/\$\{encodeURIComponent\(templateId\)\}\/change-requests`/);
  assert.match(functionSource('syncSharedTemplates'), /client\.post<TemplateSyncResult>\(`\$\{sharedWhatsAppBase\}\/templates\/sync`, undefined, \{ params: \{ scopeId \} \}\)/);
  assert.match(functionSource('fetchPublicTemplates'), /client\.get<PublicTemplateListPage>\(`\$\{sharedWhatsAppBase\}\/public-templates`/);
  assert.doesNotMatch(endpoints, /copyPublicTemplate|\/public-templates\/.*\/copy/);
  assert.doesNotMatch(publicTemplateLibrary, /copyPublicTemplate|复制此模板|复制并送审/);
  assert.doesNotMatch(
    publicTemplateDetailModal,
    /复制此模板|复制并送审|CopyTemplate|\bcopyName\b|\bcopyVariables\b|\bcopyPending\b|\bcopyError\b|\bonCopy\b/,
  );
  assert.doesNotMatch(types, /PublicTemplateCopyCommand/);
  const templateOperation = types.match(/export interface TemplateOperation\s*{([\s\S]*?)\n}/)?.[1] ?? '';
  assert.doesNotMatch(templateOperation, /'COPY'/);
  assert.match(templateOperation, /'RETIRED'/);
});

test('uploads template media as FormData without overriding the browser multipart boundary', () => {
  const upload = functionSource('uploadTemplateMedia');
  assert.match(types, /export type TemplateMediaFormat = 'IMAGE' \| 'VIDEO' \| 'DOCUMENT'/);
  assert.match(types, /export interface TemplateMediaAsset\s*{[\s\S]*?format:\s*TemplateMediaFormat/);
  assert.match(upload, /TemplateMediaFormat/);
  assert.match(upload, /new FormData\(\)/);
  assert.match(upload, /formData\.append\('format',\s*format\)/);
  assert.match(upload, /formData\.append\('file',\s*file\)/);
  assert.match(upload, /formData\.append\('clientRequestId',\s*clientRequestId\)/);
  assert.match(upload, /clientRequestId/);
  assert.doesNotMatch(upload, /Content-Type|multipart\/form-data/);
});

test('admin response types preserve nullable provider category while commands stay constrained', () => {
  assert.match(types, /export type TemplateCategory = 'UTILITY' \| 'MARKETING'/);
  assert.match(types, /export interface TemplateAdmin\s*{[\s\S]*?category:\s*string \| null/);
  assert.match(types, /export interface TemplateCommand\s*{[\s\S]*?category:\s*TemplateCategory/);
  assert.match(types, /export interface TemplateListFilters\s*{[\s\S]*?category\?:\s*string/);
});
