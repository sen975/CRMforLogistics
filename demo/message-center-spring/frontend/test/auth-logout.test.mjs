import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const endpoints = readFileSync(
  new URL('../src/api/endpoints.ts', import.meta.url),
  'utf8',
);
const authHook = readFileSync(
  new URL('../src/hooks/useAuth.tsx', import.meta.url),
  'utf8',
);

test('logout revokes the server session before clearing local authentication', () => {
  assert.match(endpoints, /client\.post\(['"]\/auth\/logout['"]\)/);
  assert.match(authHook, /logout as logoutApi/);
  assert.match(authHook, /await logoutApi\(\)/);
  assert.match(authHook, /finally\s*{/);
  assert.ok(
    authHook.indexOf('await logoutApi()') < authHook.indexOf("localStorage.removeItem('token')"),
  );
});
