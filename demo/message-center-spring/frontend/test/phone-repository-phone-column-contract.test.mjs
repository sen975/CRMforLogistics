import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const source = readFileSync(
  new URL('../src/pages/PhoneRepositoryPage.tsx', import.meta.url),
  'utf8',
);

test('phone repository keeps phone identifiers on one line with ellipsis', () => {
  assert.match(source, /ellipsis=\{\{ tooltip: phoneValue \}\}/);
  assert.match(source, /whiteSpace:\s*'nowrap'/);
  assert.match(source, /renderPhoneValue\(/);
  assert.match(source, /minWidth:\s*0/);
  assert.match(source, /scroll=\{\{ x:\s*1100 \}\}/);
  assert.match(source, /renderPhoneValue\(name \|\| '—'\)/);
  assert.match(source, /phoneValue\.replace\(\/\^phone:\/\, ''\)/);
});
