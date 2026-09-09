import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const source = fs.readFileSync(path.join(here, '../src/pages/PhoneRepositoryPage.tsx'), 'utf8');

test('phone repository never navigates to a contact route without a contact id', () => {
  assert.match(source, /if \(!record\.contactId\)/);
  assert.match(source, /message\.warning\(/);
  assert.match(source, /encodeURIComponent\(record\.contactId\)/);
});
