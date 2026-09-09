import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const page = readFileSync(new URL('../src/pages/TemplatesPage.tsx', import.meta.url), 'utf8');
const publicLibrary = readFileSync(new URL('../src/components/templates/PublicTemplateLibrary.tsx', import.meta.url), 'utf8');
const styles = readFileSync(new URL('../src/components/templates/publicTemplateWorkbench.css', import.meta.url), 'utf8');

test('template management keeps both tabs under one titled and aligned workspace', () => {
  assert.match(page, />WhatsApp模板管理</);
  assert.match(page, /className="template-management-tab-content"/);
  assert.match(publicLibrary, /className="template-management-tab-content"/);
  assert.match(styles, /\.shared-template-card\s*\{/);
  assert.match(styles, /\.shared-template-card__preview\s*\{/);
  assert.match(styles, /\.shared-template-card__summary\s*\{/);
  assert.match(styles, /\.shared-template-card__actions\s*\{/);
});
